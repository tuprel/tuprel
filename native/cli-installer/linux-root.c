#define _GNU_SOURCE
#include "linux-root.h"

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int trusted_directory(int fd, uid_t uid, int private_root) {
    struct stat info;
    if (fstat(fd, &info) != 0 || !S_ISDIR(info.st_mode)) return 0;
    if (private_root) return info.st_uid == uid && (info.st_mode & 07777) == 0700;
    return (info.st_uid == 0 || info.st_uid == uid) && !(info.st_mode & 0022);
}

static int supported_mount(int fd) {
    struct statx info;
    if (statx(fd, "", AT_EMPTY_PATH, STATX_MNT_ID, &info) != 0
            || !(info.stx_mask & STATX_MNT_ID)) return 0;
    FILE *mounts = fopen("/proc/self/mountinfo", "re");
    if (!mounts) return 0;
    char line[16384];
    int supported = 0;
    while (fgets(line, sizeof line, mounts)) {
        uint64_t id;
        if (!strchr(line, '\n')) break;
        if (sscanf(line, "%" SCNu64 " ", &id) != 1 || id != info.stx_mnt_id) continue;
        int root_start = 0;
        const char *type = strstr(line, " - ");
        sscanf(line, "%*s %*s %*s %n", &root_start);
        supported = root_start > 0 && strncmp(line + root_start, "/ ", 2) == 0
            && type && (strncmp(type + 3, "ext4 ", 5) == 0 || strncmp(type + 3, "xfs ", 4) == 0);
        break;
    }
    fclose(mounts);
    return supported;
}

static int valid_component(const char *component) {
    return *component && strcmp(component, ".") != 0 && strcmp(component, "..") != 0;
}

int installer_open_root(const char *path) {
    uid_t uid = geteuid();
    if (uid == 0 || getuid() != uid || path[0] != '/' || strlen(path) >= PATH_MAX) {
        errno = EACCES;
        return -1;
    }
    const int flags = O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    int fd = open("/", flags);
    if (fd < 0) return -1;
    if (!trusted_directory(fd, uid, 0) || !supported_mount(fd)) goto refused;

    char components[PATH_MAX];
    strcpy(components, path + 1);
    char *remaining = components;
    char *component;
    while ((component = strsep(&remaining, "/")) != NULL) {
        if (!valid_component(component)) goto refused;
        int child = openat(fd, component, flags);
        if (child < 0) goto refused;
        close(fd);
        fd = child;
        if (!trusted_directory(fd, uid, remaining == NULL) || !supported_mount(fd)) goto refused;
    }
    return fd;

refused:
    close(fd);
    errno = EACCES;
    return -1;
}

int installer_create_file(int root, const char *name) {
    uid_t uid = geteuid();
    if (uid == 0 || getuid() != uid || !valid_component(name) || strchr(name, '/')
            || !trusted_directory(root, uid, 1) || !supported_mount(root)) {
        errno = EACCES;
        return -1;
    }
    const struct open_how options = {
        .flags = O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
        .mode = 0600,
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_XDEV
    };
    return (int)syscall(SYS_openat2, root, name, &options, sizeof options);
}
