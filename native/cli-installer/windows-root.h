#ifndef TUPREL_WINDOWS_INSTALL_ROOT_H
#define TUPREL_WINDOWS_INSTALL_ROOT_H

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

typedef struct installer_root installer_root;

installer_root *installer_open_root(const wchar_t *path);
HANDLE installer_create_file(const installer_root *root, const wchar_t *name);
void installer_close_root(installer_root *root);

#endif
