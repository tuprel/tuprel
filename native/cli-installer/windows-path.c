#define _WIN32_WINNT 0x0A00
#include "windows-root.h"

#include <ktmw32.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

#define CAPACITY 32767
static const wchar_t marker[] = L"TuprelStagingPathOwner";

static int read_value(HKEY key, const wchar_t *name, wchar_t *value, DWORD *kind) {
    DWORD bytes = CAPACITY * sizeof *value;
    LSTATUS status = RegQueryValueExW(key, name, NULL, kind, (BYTE *)value, &bytes);
    if (status == ERROR_FILE_NOT_FOUND) { *kind = REG_NONE; *value = 0; return 1; }
    return status == ERROR_SUCCESS && (*kind == REG_SZ || *kind == REG_EXPAND_SZ)
        && bytes >= sizeof *value && bytes % sizeof *value == 0
        && value[bytes / sizeof *value - 1] == 0 && wcslen(value) + 1 == bytes / sizeof *value;
}

static int set_value(HKEY key, const wchar_t *name, const wchar_t *value, DWORD kind) {
    if (kind == REG_NONE) return RegDeleteValueW(key, name) == ERROR_SUCCESS;
    DWORD bytes = (DWORD)((wcslen(value) + 1) * sizeof *value);
    return RegSetValueExW(key, name, 0, kind, (const BYTE *)value, bytes) == ERROR_SUCCESS;
}

static int contains_entry(const wchar_t *path, const wchar_t *bin) {
    size_t bin_length = wcslen(bin);
    for (const wchar_t *entry = path; *entry;) {
        size_t length = wcscspn(entry, L";");
        if (length == bin_length && _wcsnicmp(entry, bin, length) == 0) return 1;
        entry += length;
        if (*entry) entry++;
    }
    return 0;
}

/* Reject entries that are ambiguous or already resolve a Tuprel executable. */
static int clear_path(const wchar_t *value, const wchar_t *bin) {
    if (!*value) return 1;
    wchar_t *expanded = calloc(CAPACITY, sizeof *expanded);
    if (!expanded) return 0;
    DWORD size = ExpandEnvironmentStringsW(value, expanded, CAPACITY);
    int valid = size && size <= CAPACITY;
    wchar_t *next = NULL;
    for (wchar_t *dir = valid ? wcstok_s(expanded, L";", &next) : NULL; dir; dir = wcstok_s(NULL, L";", &next)) {
        wchar_t file[4096];
        size_t length = wcslen(dir);
        if (length < 3 || length >= 4000 || dir[1] != L':' || dir[2] != L'\\'
                || wcspbrk(dir, L"%\"\r\n") || _wcsicmp(dir, bin) == 0) { valid = 0; break; }
        const wchar_t *names[] = {L"tuprel.exe", L"tuprel.com", L"tuprel.cmd", L"tuprel.bat", L"tuprel.ps1"};
        for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) {
            if (swprintf(file, sizeof file / sizeof file[0], L"%ls\\%ls", dir, names[i]) < 0) { valid = 0; break; }
            if (GetFileAttributesW(file) != INVALID_FILE_ATTRIBUTES) { valid = 0; break; }
            DWORD error = GetLastError();
            if (error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) { valid = 0; break; }
        }
        if (!valid) break;
    }
    free(expanded);
    return valid && value[0] != L';' && !wcsstr(value, L";;") && value[wcslen(value) - 1] != L';';
}

static int update(HKEY key, const wchar_t *bin, int add) {
    wchar_t *path = calloc(CAPACITY, sizeof *path);
    wchar_t *owner = calloc(CAPACITY, sizeof *owner);
    int ok = 0;
    DWORD kind, owner_kind;
    if (!path || !owner || !read_value(key, L"Path", path, &kind) || !read_value(key, marker, owner, &owner_kind)) goto done;
    size_t length = wcslen(path), bin_length = wcslen(bin);
    if (owner_kind != REG_NONE) {
        if (owner_kind != REG_SZ || owner[1] != L'|' || wcscmp(owner + 2, bin) != 0 || kind == REG_NONE
                || length < bin_length || wcscmp(path + length - bin_length, bin) != 0) goto done;
        size_t prefix = length - bin_length;
        if (prefix && path[prefix - 1] != L';') goto done;
        path[prefix ? prefix - 1 : 0] = 0;
        if (contains_entry(path, bin)) goto done;
        if (add) { ok = clear_path(path, bin); goto done; }
        DWORD restore = kind;
        if (!*path && owner[0] == L'N') restore = REG_NONE;
        if (owner[0] != L'N' && owner[0] != L'P') goto done;
        ok = set_value(key, L"Path", path, restore) && set_value(key, marker, L"", REG_NONE);
    } else if (add && length + bin_length + 2 <= CAPACITY && clear_path(path, bin)) {
        if (swprintf(owner, CAPACITY, L"%lc|%ls", kind == REG_NONE ? L'N' : L'P', bin) < 0) goto done;
        if (length) path[length++] = L';';
        wcscpy_s(path + length, CAPACITY - length, bin);
        ok = set_value(key, L"Path", path, kind == REG_NONE ? REG_EXPAND_SZ : kind)
            && set_value(key, marker, owner, REG_SZ);
    } else if (!add) {
        ok = 1;
    }
done:
    free(owner);
    free(path);
    return ok;
}

int wmain(int argc, wchar_t **argv) {
    int expected = 3;
#ifdef INSTALLER_PATH_FIXTURE
    expected = 4;
#endif
    if (argc != expected || (wcscmp(argv[1], L"add") && wcscmp(argv[1], L"remove"))
            || wcspbrk(argv[2], L";%\"\r\n") || wcslen(argv[2]) >= 4000) return 2;
    int add = wcscmp(argv[1], L"add") == 0;
    installer_root *root = installer_open_root(argv[2]);
    if (!root) return 1;
#ifdef INSTALLER_PATH_FIXTURE
    int ok = installer_trusted_file(root, L"tuprel.ps1");
#else
    int ok = installer_trusted_file(root, L"tuprel.cmd");
#endif
    HKEY machine = NULL;
    if (ok && add) {
        wchar_t *path = calloc(CAPACITY, sizeof *path);
        DWORD kind;
        ok = path && RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
                0, KEY_QUERY_VALUE, &machine) == ERROR_SUCCESS;
        if (ok) {
            ok = read_value(machine, L"Path", path, &kind) && clear_path(path, argv[2]);
            RegCloseKey(machine);
        }
        free(path);
    }
    const wchar_t *subkey = L"Environment";
#ifdef INSTALLER_PATH_FIXTURE
    subkey = L"Software\\TuprelStagingPathFixture";
#endif
    HANDLE tx = ok ? CreateTransaction(NULL, NULL, 0, 0, 0, 0, NULL) : INVALID_HANDLE_VALUE;
    ok = 0;
    if (tx != INVALID_HANDLE_VALUE) {
        HKEY key;
        if (RegOpenKeyTransactedW(HKEY_CURRENT_USER, subkey, 0, KEY_QUERY_VALUE | KEY_SET_VALUE, &key, tx, NULL)
                == ERROR_SUCCESS) {
            ok = update(key, argv[2], add) && installer_root_valid(root);
            RegCloseKey(key);
#ifdef INSTALLER_PATH_FIXTURE
            ok = ok && wcscmp(argv[3], L"commit") == 0;
#endif
            if (ok) ok = CommitTransaction(tx);
        }
        CloseHandle(tx);
    }
    installer_close_root(root);
#ifndef INSTALLER_PATH_FIXTURE
    if (ok) {
        DWORD_PTR result;
        LRESULT sent = SendMessageTimeoutW(HWND_BROADCAST, WM_SETTINGCHANGE, 0, (LPARAM)L"Environment",
                SMTO_ABORTIFHUNG, 1000, &result);
        puts(sent ? "WM_SETTINGCHANGE broadcast returned success"
                  : "WM_SETTINGCHANGE broadcast unconfirmed");
    }
#endif
    if (!ok) fputs("Tuprel user PATH change refused or unconfirmed. Inspect the user PATH before retrying.\n", stderr);
    return ok ? 0 : 1;
}
