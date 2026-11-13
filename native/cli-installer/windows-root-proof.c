#include "windows-root.h"

#include <stdio.h>
#include <wchar.h>

int wmain(int argc, wchar_t **argv) {
    if (argc != 3) return 2;
    installer_root *root = installer_open_root(argv[1]);
    if (!root) return 2;
    puts("READY");
    if (fflush(stdout) != 0 || getchar() != '\n') {
        installer_close_root(root);
        return 2;
    }
    HANDLE file = installer_create_file(root, argv[2]);
    installer_close_root(root);
    if (file == INVALID_HANDLE_VALUE) return 2;
    const char marker[] = "installer-root-proof\n";
    DWORD written = 0;
    BOOL ok = WriteFile(file, marker, sizeof marker - 1, &written, NULL);
    BOOL closed = CloseHandle(file);
    return ok && written == sizeof marker - 1 && closed ? 0 : 2;
}
