#define _GNU_SOURCE
#include "protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/openat2.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int directory(int parent, const char *path) {
    struct open_how options = {
        .flags = O_PATH | O_DIRECTORY | O_CLOEXEC,
        .resolve = RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS
    };
    return (int)syscall(SYS_openat2, parent, path, &options, sizeof options);
}

static int identity(int fd, struct statx *id) {
    if (fd < 0 || statx(fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS | STATX_MNT_ID, id) != 0
            || (id->stx_mask & (STATX_TYPE | STATX_MODE | STATX_UID | STATX_INO | STATX_MNT_ID))
                != (STATX_TYPE | STATX_MODE | STATX_UID | STATX_INO | STATX_MNT_ID)) { return 0; }
    return S_ISDIR(id->stx_mode) && id->stx_uid == geteuid() && !(id->stx_mode & 0022);
}

static int same(const struct statx *left, const struct statx *right) {
    return left->stx_mnt_id == right->stx_mnt_id && left->stx_ino == right->stx_ino;
}

static int still_named(int parent, const char *path, const struct statx *expected) {
    struct statx actual;
    int fd = directory(parent, path);
    int matches = identity(fd, &actual) && same(&actual, expected);
    if (fd >= 0) { close(fd); }
    return matches;
}

static const char *filesystem(uint64_t mount_id) {
    FILE *mounts = fopen("/proc/self/mountinfo", "re");
    if (!mounts) { return NULL; }
    char line[16384];
    const char *result = NULL;
    while (fgets(line, sizeof line, mounts)) {
        uint64_t id;
        if (!strchr(line, '\n')) { break; }
        if (sscanf(line, "%" SCNu64 " ", &id) != 1 || id != mount_id) { continue; }
        char *type = strstr(line, " - ");
        if (type && strncmp(type + 3, "ext4 ", 5) == 0) { result = "ext4"; }
        if (type && strncmp(type + 3, "xfs ", 4) == 0) { result = "xfs"; }
        break;
    }
    fclose(mounts);
    return result;
}

static int absolute_root(const char *path) {
    if (*path != '/' || strlen(path) > 4095) { return 0; }
    const char *component = path + 1;
    do {
        size_t length = strcspn(component, "/");
        if (length == 0 || (length == 1 && component[0] == '.')
                || (length == 2 && component[0] == '.' && component[1] == '.')) { return 0; }
        component += length;
    } while (*component++ == '/');
    return 1;
}

static enum result rename_once(const char *path, const char *stage, const char *name) {
    int root = directory(AT_FDCWD, path);
    if (root < 0) { return REFUSED; }
    int catalog = directory(root, "migrations");
    int source = directory(root, stage);
    struct statx root_id, catalog_id, source_id, old_name;
    enum result result = REFUSED;
    if (!identity(root, &root_id) || !identity(catalog, &catalog_id) || !identity(source, &source_id)
            || (source_id.stx_mode & 0077) || root_id.stx_mnt_id != catalog_id.stx_mnt_id
            || root_id.stx_mnt_id != source_id.stx_mnt_id) { goto done; }
    const char *type = filesystem(root_id.stx_mnt_id);
    if (!type || printf("READY %s %" PRIx64 " %" PRIx64 " %" PRIx64 " %" PRIx64 "\n",
            type, (uint64_t)root_id.stx_mnt_id, (uint64_t)root_id.stx_ino,
            (uint64_t)catalog_id.stx_ino, (uint64_t)source_id.stx_ino) < 0 || !proceed()) { goto done; }
    if (!still_named(AT_FDCWD, path, &root_id) || !still_named(root, "migrations", &catalog_id)
            || !still_named(root, stage, &source_id)) { goto done; }

    if (syscall(SYS_renameat2, root, stage, catalog, name, RENAME_NOREPLACE) != 0) {
        result = errno == EEXIST || errno == ENOTEMPTY ? COLLISION : UNKNOWN;
        goto done;
    }
    result = still_named(AT_FDCWD, path, &root_id) && still_named(root, "migrations", &catalog_id)
        && still_named(catalog, name, &source_id)
        && statx(root, stage, AT_SYMLINK_NOFOLLOW, STATX_TYPE, &old_name) != 0 && errno == ENOENT ? MOVED : UNKNOWN;
done:
    if (source >= 0) { close(source); }
    if (catalog >= 0) { close(catalog); }
    close(root);
    return result;
}

int main(int argc, char **argv) {
    if (argc != 5 || strcmp(argv[1], "1") != 0 || !absolute_root(argv[2])
            || !stage_name(argv[3], strlen(argv[3])) || !destination_name(argv[4], strlen(argv[4]))) {
        return finish(REFUSED);
    }
    return finish(rename_once(argv[2], argv[3], argv[4]));
}
