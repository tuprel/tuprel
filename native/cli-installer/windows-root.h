#ifndef TUPREL_WINDOWS_INSTALL_ROOT_H
#define TUPREL_WINDOWS_INSTALL_ROOT_H

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

typedef struct installer_root installer_root;

installer_root *installer_open_root(const wchar_t *path);
int installer_root_valid(const installer_root *root);
int installer_root_identity(const installer_root *root, FILE_ID_INFO *id);
HANDLE installer_create_file(const installer_root *root, const wchar_t *name);
int installer_rename_new(const installer_root *root, const wchar_t *source, const wchar_t *destination);
void installer_close_root(installer_root *root);

#endif
