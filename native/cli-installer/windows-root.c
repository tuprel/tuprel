#define _WIN32_WINNT 0x0A00
#include "windows-root.h"

#include <aclapi.h>
#include <sddl.h>
#include <winternl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define PATH_CAPACITY 4096
#define MAX_ANCESTORS 256
#define NO_REPARSE 0x1000L
#define CREATE_NEW_OBJECT 2
#define OPEN_OBJECT 1

#ifdef INSTALLER_ROOT_PROOF
#define PROBE(...) fprintf(stderr, __VA_ARGS__)
#else
#define PROBE(...) ((void)0)
#endif

struct installer_root {
    HANDLE chain[MAX_ANCESTORS];
    FILE_ID_INFO identities[MAX_ANCESTORS];
    size_t count;
    PSID user;
    PSID servicing;
    ULONGLONG volume;
    wchar_t path[PATH_CAPACITY];
};

static PSID process_user(void) {
    HANDLE token;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    DWORD bytes = 0;
    GetTokenInformation(token, TokenUser, NULL, 0, &bytes);
    TOKEN_USER *info = bytes ? malloc(bytes) : NULL;
    PSID sid = NULL;
    if (info && GetTokenInformation(token, TokenUser, info, bytes, &bytes)
            && IsValidSid(info->User.Sid) && !IsWellKnownSid(info->User.Sid, WinLocalSystemSid)) {
        DWORD size = GetLengthSid(info->User.Sid);
        sid = LocalAlloc(LMEM_FIXED, size);
        if (sid && !CopySid(size, sid, info->User.Sid)) {
            LocalFree(sid);
            sid = NULL;
        }
    }
    free(info);
    CloseHandle(token);
    return sid;
}

static int trusted_sid(const installer_root *root, PSID sid) {
    return IsValidSid(sid) && (EqualSid(sid, root->user) || EqualSid(sid, root->servicing)
        || IsWellKnownSid(sid, WinLocalSystemSid) || IsWellKnownSid(sid, WinBuiltinAdministratorsSid));
}

static int trusted_acl(const installer_root *root, HANDLE handle, int private_object) {
    PSID owner = NULL;
    PACL acl = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    DWORD status = GetSecurityInfo(handle, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &owner, NULL, &acl, NULL, &descriptor);
    int valid = status == ERROR_SUCCESS && owner && acl && IsValidAcl(acl)
        && (private_object ? EqualSid(owner, root->user) : trusted_sid(root, owner));
    DWORD unsafe = FILE_WRITE_DATA | FILE_WRITE_EA | FILE_DELETE_CHILD | FILE_WRITE_ATTRIBUTES
        | DELETE | WRITE_DAC | WRITE_OWNER | GENERIC_ALL | GENERIC_WRITE | MAXIMUM_ALLOWED;
    if (private_object) unsafe |= FILE_APPEND_DATA;
    for (DWORD i = 0; valid && i < acl->AceCount; i++) {
        void *entry = NULL;
        if (!GetAce(acl, i, &entry)) { valid = 0; break; }
        const ACE_HEADER *header = entry;
        if (!private_object && (header->AceFlags & INHERIT_ONLY_ACE)) continue;
        if (header->AceType == ACCESS_DENIED_ACE_TYPE) continue;
        if (header->AceType != ACCESS_ALLOWED_ACE_TYPE
                || header->AceSize < sizeof(ACCESS_ALLOWED_ACE)) { valid = 0; break; }
        const ACCESS_ALLOWED_ACE *allow = entry;
        if ((allow->Mask & unsafe) && !trusted_sid(root, (PSID)&allow->SidStart)) {
            PROBE("Unsafe ACL mask: %08lx\n", allow->Mask);
            valid = 0;
        }
    }
    if (!valid) PROBE("ACL refused at depth %zu (security status %lu)\n", root->count, status);
    if (descriptor) LocalFree(descriptor);
    return valid;
}

static int component(const wchar_t *name) {
    size_t length = wcslen(name);
    if (length == 0 || length > 255 || name[length - 1] == L'.' || name[length - 1] == L' ') return 0;
    for (size_t i = 0; i < length; i++) {
        if (name[i] < 32 || wcschr(L"\\/:\"<>|?*", name[i])) return 0;
    }
    return 1;
}

static HANDLE open_relative(HANDLE parent, const wchar_t *name, int create, int file, ACCESS_MASK additional) {
    USHORT bytes = (USHORT)(wcslen(name) * sizeof(wchar_t));
    UNICODE_STRING object_name = {bytes, bytes, (PWSTR)name};
    OBJECT_ATTRIBUTES attributes = {sizeof attributes, parent, &object_name, NO_REPARSE, NULL, NULL};
    IO_STATUS_BLOCK result;
    HANDLE handle = INVALID_HANDLE_VALUE;
    ACCESS_MASK access = additional | FILE_READ_ATTRIBUTES | READ_CONTROL | SYNCHRONIZE
        | (create ? FILE_WRITE_DATA : file ? 0 : FILE_LIST_DIRECTORY | FILE_TRAVERSE);
    NTSTATUS status = NtCreateFile(&handle, access, &attributes, &result, NULL, FILE_ATTRIBUTE_NORMAL,
            create ? 0 : FILE_SHARE_READ | FILE_SHARE_WRITE, create ? CREATE_NEW_OBJECT : OPEN_OBJECT,
            FILE_SYNCHRONOUS_IO_NONALERT | (file ? FILE_NON_DIRECTORY_FILE : FILE_DIRECTORY_FILE), NULL, 0);
    if (status < 0) PROBE("Relative open refused: %08lx\n", (ULONG)status);
    return status >= 0 ? handle : INVALID_HANDLE_VALUE;
}

static int directory(const installer_root *root, HANDLE handle, int private_object, FILE_ID_INFO *id) {
    FILE_ATTRIBUTE_TAG_INFO attributes;
    return GetFileInformationByHandleEx(handle, FileAttributeTagInfo, &attributes, sizeof attributes)
        && (attributes.FileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
            == FILE_ATTRIBUTE_DIRECTORY
        && GetFileInformationByHandleEx(handle, FileIdInfo, id, sizeof *id)
        && id->VolumeSerialNumber == root->volume && trusted_acl(root, handle, private_object);
}

static int canonical(HANDLE handle, const wchar_t *path) {
    wchar_t actual[PATH_CAPACITY];
    DWORD length = GetFinalPathNameByHandleW(handle, actual, PATH_CAPACITY, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    return length && length < PATH_CAPACITY && wcscmp(actual, path) == 0;
}

void installer_close_root(installer_root *root) {
    if (!root) return;
    while (root->count) CloseHandle(root->chain[--root->count]);
    LocalFree(root->user);
    LocalFree(root->servicing);
    free(root);
}

installer_root *installer_open_root(const wchar_t *path) {
    size_t length = wcslen(path);
    if (length < 4 || length >= PATH_CAPACITY - 4 || path[0] < L'A' || path[0] > L'Z'
            || path[1] != L':' || path[2] != L'\\') return NULL;
    wchar_t drive[] = {path[0], L':', L'\\', 0};
    if (GetDriveTypeW(drive) != DRIVE_FIXED) return NULL;
    installer_root *root = calloc(1, sizeof *root);
    if (!root) return NULL;
    root->user = process_user();
    if (!root->user || !ConvertStringSidToSidW(
            L"S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464", &root->servicing)) goto refused;
    wchar_t full[PATH_CAPACITY];
    if (swprintf(full, PATH_CAPACITY, L"\\\\?\\%ls", drive) < 0) goto refused;
    HANDLE handle = CreateFileW(full, READ_CONTROL | FILE_READ_ATTRIBUTES | FILE_LIST_DIRECTORY | FILE_TRAVERSE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    if (handle == INVALID_HANDLE_VALUE) goto refused;
    root->chain[root->count++] = handle;
    FILE_ID_INFO id;
    wchar_t filesystem[16];
    if (!GetVolumeInformationByHandleW(handle, NULL, 0, NULL, NULL, NULL, filesystem, 16)
            || wcscmp(filesystem, L"NTFS") != 0 || !canonical(handle, full)
            || !GetFileInformationByHandleEx(handle, FileIdInfo, &id, sizeof id)) goto refused;
    root->volume = id.VolumeSerialNumber;
    if (!directory(root, handle, 0, &root->identities[0])) goto refused;
    if (swprintf(full, PATH_CAPACITY, L"\\\\?\\%ls", path) < 0) goto refused;

    const wchar_t *start = path + 3;
    do {
        size_t count = wcscspn(start, L"\\");
        wchar_t name[256];
        if (count >= 256 || root->count >= MAX_ANCESTORS) goto refused;
        wmemcpy(name, start, count);
        name[count] = L'\0';
        if (!component(name)) goto refused;
        handle = open_relative(handle, name, 0, 0, 0);
        if (handle == INVALID_HANDLE_VALUE) goto refused;
        root->chain[root->count++] = handle;
        int last = start[count] == L'\0';
        if (!directory(root, handle, last, &root->identities[root->count - 1])) goto refused;
        if (last) {
            if (!canonical(handle, full)) goto refused;
            if (wcscpy_s(root->path, PATH_CAPACITY, full) != 0) goto refused;
            return root;
        }
        start += count + 1;
    } while (1);
refused:
    PROBE("Root refused at depth %zu (Win32 status %lu)\n", root->count, GetLastError());
    installer_close_root(root);
    return NULL;
}

int installer_root_valid(const installer_root *root) {
    for (size_t i = 0; i < root->count; i++) {
        FILE_ID_INFO current;
        if (!directory(root, root->chain[i], i + 1 == root->count, &current)
                || memcmp(&current.FileId, &root->identities[i].FileId, sizeof current.FileId) != 0) {
            return 0;
        }
    }
    return canonical(root->chain[root->count - 1], root->path);
}

HANDLE installer_create_file(const installer_root *root, const wchar_t *name) {
    if (!component(name) || !installer_root_valid(root)) return INVALID_HANDLE_VALUE;
    return open_relative(root->chain[root->count - 1], name, 1, 1, 0);
}

int installer_root_identity(const installer_root *root, FILE_ID_INFO *id) {
    if (!installer_root_valid(root)) return 0;
    *id = root->identities[root->count - 1];
    return 1;
}

static int rename_entry(const installer_root *root, const wchar_t *source, const wchar_t *destination, int replace) {
    if (!component(source) || !component(destination) || !installer_root_valid(root)) return 0;
    HANDLE parent = root->chain[root->count - 1];
    HANDLE handle = open_relative(parent, source, 0, replace, DELETE);
    if (handle == INVALID_HANDLE_VALUE) return 0;
    int renamed = 0;
    FILE_ID_INFO id;
    wchar_t target[PATH_CAPACITY];
    DWORD prefix = GetFinalPathNameByHandleW(parent, target, PATH_CAPACITY, FILE_NAME_NORMALIZED | VOLUME_NAME_GUID);
    BY_HANDLE_FILE_INFORMATION file;
    int valid = replace ? (GetFileInformationByHandle(handle, &file)
            && !(file.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
            && file.nNumberOfLinks == 1 && trusted_acl(root, handle, 0)) : directory(root, handle, 1, &id);
    if (valid && prefix && prefix < PATH_CAPACITY
            && swprintf(target + prefix, PATH_CAPACITY - prefix, L"\\%ls", destination) >= 0
            && installer_root_valid(root)) {
        size_t length = wcslen(target) * sizeof *target;
        size_t capacity = FIELD_OFFSET(FILE_RENAME_INFO, FileName) + length + sizeof *target;
        FILE_RENAME_INFO *rename = calloc(1, capacity);
        if (rename) {
            rename->ReplaceIfExists = replace ? TRUE : FALSE;
            rename->FileNameLength = (DWORD)length;
            memcpy(rename->FileName, target, length);
            renamed = SetFileInformationByHandle(handle, FileRenameInfo, rename, (DWORD)capacity) != 0;
            free(rename);
        }
    }
    CloseHandle(handle);
    return renamed;
}

int installer_rename_new(const installer_root *root, const wchar_t *source, const wchar_t *destination) {
    return rename_entry(root, source, destination, 0);
}

int installer_replace_file(const installer_root *root, const wchar_t *source, const wchar_t *destination) {
    return rename_entry(root, source, destination, 1);
}
