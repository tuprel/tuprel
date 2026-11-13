#include "linux-root.h"

#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    int root = installer_open_root(argv[1]);
    if (root < 0) return 2;
    puts("READY");
    if (fflush(stdout) != 0) {
        close(root);
        return 2;
    }
    char name[256];
    if (fgets(name, sizeof name, stdin) == NULL) {
        close(root);
        return 2;
    }
    size_t length = strlen(name);
    if (length == 0 || name[length - 1] != '\n') {
        close(root);
        return 2;
    }
    name[length - 1] = '\0';
    if (strcmp(name, "check-identity") == 0) {
        int valid = installer_root_valid(root, argv[1]);
        close(root);
        return valid ? 0 : 2;
    }
    int file = installer_create_file(root, name);
    close(root);
    if (file < 0) return 2;
    const char marker[] = "installer-root-proof\n";
    ssize_t written = write(file, marker, sizeof marker - 1);
    int closed = close(file);
    return written == sizeof marker - 1 && closed == 0 ? 0 : 2;
}
