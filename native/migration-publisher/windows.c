#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "protocol.h"
#include <fcntl.h>
#include <inttypes.h>
#include <io.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define PATH_CAPACITY 4096
#define MAX_ANCESTORS 256

static int ascii_name(const wchar_t *input, char *output, size_t capacity) {
    size_t length = wcslen(input);
    if (length >= capacity) { return 0; }
    for (size_t i = 0; i < length; i++) {
        if (input[i] > 127) { return 0; }
        output[i] = (char)input[i];
    }
    output[length] = 0;
    return 1;
}

static int absolute_root(const wchar_t *path) {
    size_t length = wcslen(path);
    if (length < 4 || length >= PATH_CAPACITY - 100 || path[0] < L'A' || path[0] > L'Z'
            || path[1] != L':' || path[2] != L'\\') { return 0; }
    size_t component = 3;
    for (size_t i = 3; i <= length; i++) {
        wchar_t c = path[i];
        if (c == L'\\' || c == 0) {
            if (i == component || path[i - 1] == L'.' || path[i - 1] == L' ') { return 0; }
            component = i + 1;
        } else if (c < 32 || wcschr(L"/:\"<>|?*", c)) { return 0; }
    }
    return 1;
}

static int exact_path(HANDLE handle, const wchar_t *expected) {
    wchar_t actual[PATH_CAPACITY];
    DWORD length = GetFinalPathNameByHandleW(handle, actual, PATH_CAPACITY, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    return length > 0 && length < PATH_CAPACITY && wcscmp(actual, expected) == 0;
}

static HANDLE checked_directory(const wchar_t *path, DWORD access) {
    HANDLE handle = CreateFileW(path, access | FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    if (handle == INVALID_HANDLE_VALUE) { return handle; }
    FILE_ATTRIBUTE_TAG_INFO attributes;
    if (!GetFileInformationByHandleEx(handle, FileAttributeTagInfo, &attributes, sizeof attributes)
            || (attributes.FileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
                != FILE_ATTRIBUTE_DIRECTORY || !exact_path(handle, path)) {
        CloseHandle(handle);
        return INVALID_HANDLE_VALUE;
    }
    return handle;
}

static int hold_ancestors(wchar_t *path, HANDLE *handles, size_t *count) {
    /* Each ancestor is retained without delete sharing while descendants are opened. */
    size_t length = wcslen(path);
    wchar_t first = path[7];
    path[7] = 0;
    HANDLE drive = checked_directory(path, 0);
    path[7] = first;
    if (drive == INVALID_HANDLE_VALUE) { return 0; }
    handles[(*count)++] = drive;
    for (size_t i = 7; i <= length; i++) {
        if (path[i] != L'\\' && i != length) { continue; }
        if (*count >= MAX_ANCESTORS) { return 0; }
        wchar_t next = path[i];
        path[i] = 0;
        HANDLE directory = checked_directory(path, 0);
        path[i] = next;
        if (directory == INVALID_HANDLE_VALUE) { return 0; }
        handles[(*count)++] = directory;
    }
    return 1;
}

static void print_id(const FILE_ID_INFO *identity) {
    for (size_t i = 0; i < sizeof identity->FileId.Identifier; i++) {
        printf("%02x", (unsigned int)identity->FileId.Identifier[i]);
    }
}

static enum result rename_once(const wchar_t *path, const wchar_t *stage, const wchar_t *name) {
    wchar_t drive[] = {path[0], L':', L'\\', 0};
    if (GetDriveTypeW(drive) != DRIVE_FIXED) { return REFUSED; }
    wchar_t root[PATH_CAPACITY], catalog_path[PATH_CAPACITY], source_path[PATH_CAPACITY], final_path[PATH_CAPACITY];
    if (swprintf(root, PATH_CAPACITY, L"\\\\?\\%ls", path) < 0
            || swprintf(catalog_path, PATH_CAPACITY, L"%ls\\migrations", root) < 0
            || swprintf(source_path, PATH_CAPACITY, L"%ls\\%ls", root, stage) < 0
            || swprintf(final_path, PATH_CAPACITY, L"%ls\\%ls", catalog_path, name) < 0) { return REFUSED; }
    HANDLE handles[MAX_ANCESTORS];
    size_t count = 0;
    HANDLE catalog = INVALID_HANDLE_VALUE, source = INVALID_HANDLE_VALUE;
    FILE_RENAME_INFO *rename = NULL;
    enum result result = REFUSED;
    if (!hold_ancestors(root, handles, &count)) { goto done; }
    HANDLE parent = handles[count - 1];
    catalog = checked_directory(catalog_path, FILE_ADD_SUBDIRECTORY | FILE_TRAVERSE);
    source = checked_directory(source_path, DELETE);
    FILE_ID_INFO root_id, catalog_id, source_id;
    wchar_t filesystem[16];
    if (catalog == INVALID_HANDLE_VALUE || source == INVALID_HANDLE_VALUE
            || !GetVolumeInformationByHandleW(parent, NULL, 0, NULL, NULL, NULL, filesystem, 16)
            || wcscmp(filesystem, L"NTFS") != 0
            || !GetFileInformationByHandleEx(parent, FileIdInfo, &root_id, sizeof root_id)
            || !GetFileInformationByHandleEx(catalog, FileIdInfo, &catalog_id, sizeof catalog_id)
            || !GetFileInformationByHandleEx(source, FileIdInfo, &source_id, sizeof source_id)
            || root_id.VolumeSerialNumber != catalog_id.VolumeSerialNumber
            || root_id.VolumeSerialNumber != source_id.VolumeSerialNumber) { goto done; }

    size_t name_bytes = wcslen(name) * sizeof *name;
    size_t rename_size = offsetof(FILE_RENAME_INFO, FileName) + name_bytes + sizeof *name;
    rename = calloc(1, rename_size);
    if (!rename) { goto done; }
    rename->ReplaceIfExists = FALSE;
    rename->RootDirectory = catalog;
    rename->FileNameLength = (DWORD)name_bytes;
    memcpy(rename->FileName, name, name_bytes);

    printf("READY ntfs %" PRIx64 " ", (uint64_t)root_id.VolumeSerialNumber);
    print_id(&root_id); putchar(' ');
    print_id(&catalog_id); putchar(' ');
    print_id(&source_id); putchar('\n');
    if (ferror(stdout) || !proceed() || !exact_path(parent, root)
            || !exact_path(catalog, catalog_path) || !exact_path(source, source_path)) { goto done; }
    if (GetFileAttributesW(final_path) != INVALID_FILE_ATTRIBUTES) { result = COLLISION; goto done; }
    if (!SetFileInformationByHandle(source, FileRenameInfo, rename, (DWORD)rename_size)) {
        DWORD error = GetLastError();
        result = error == ERROR_ALREADY_EXISTS || error == ERROR_FILE_EXISTS || error == ERROR_DIR_NOT_EMPTY
            ? COLLISION : UNKNOWN;
        goto done;
    }
    result = exact_path(source, final_path) && exact_path(parent, root) && exact_path(catalog, catalog_path) ? MOVED : UNKNOWN;
done:
    free(rename);
    if (source != INVALID_HANDLE_VALUE) { CloseHandle(source); }
    if (catalog != INVALID_HANDLE_VALUE) { CloseHandle(catalog); }
    while (count) { CloseHandle(handles[--count]); }
    return result;
}

int wmain(int argc, wchar_t **argv) {
    char stage[64], name[81];
    if (_setmode(_fileno(stdin), _O_BINARY) == -1 || _setmode(_fileno(stdout), _O_BINARY) == -1) { return REFUSED; }
    if (argc != 5 || wcscmp(argv[1], L"1") != 0 || !absolute_root(argv[2])
            || !ascii_name(argv[3], stage, sizeof stage) || !stage_name(stage, strlen(stage))
            || !ascii_name(argv[4], name, sizeof name) || !destination_name(name, strlen(name))) {
        return finish(REFUSED);
    }
    return finish(rename_once(argv[2], argv[3], argv[4]));
}
