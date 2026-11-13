#ifndef TUPREL_LINUX_INSTALL_ROOT_H
#define TUPREL_LINUX_INSTALL_ROOT_H

/* The caller owns the returned descriptor. Failure returns -1. */
int installer_open_root(const char *path);
int installer_root_valid(int fd, const char *path);
int installer_create_file(int root, const char *name);
int installer_rename_new(int root, const char *source, const char *destination);
int installer_replace_file(int root, const char *source, const char *destination);
int installer_trusted_file(int root, const char *name);

#endif
