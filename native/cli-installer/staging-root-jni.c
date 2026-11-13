#include <jni.h>
#include <stdint.h>
#ifdef _WIN32
#include "windows-root.h"
#include <stdio.h>
#include <wchar.h>
#else
#include "linux-root.h"
#include <errno.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

JNIEXPORT jlong JNICALL Java_StagingRoot_open(JNIEnv *env, jclass type, jstring path) {
    (void)type;
#ifdef _WIN32
    jsize length = (*env)->GetStringLength(env, path);
    if (length < 1 || length >= 4096) return 0;
    wchar_t buffer[4096];
    (*env)->GetStringRegion(env, path, 0, length, (jchar *)buffer);
    if ((*env)->ExceptionCheck(env)) return 0;
    buffer[length] = 0;
    return (jlong)(intptr_t)installer_open_root(buffer);
#else
    const char *value = (*env)->GetStringUTFChars(env, path, NULL);
    if (!value) return 0;
    int fd = installer_open_root(value);
    (*env)->ReleaseStringUTFChars(env, path, value);
    return fd < 0 ? 0 : (jlong)fd + 1;
#endif
}

JNIEXPORT jboolean JNICALL Java_StagingRoot_matches(JNIEnv *env, jclass type, jlong handle, jstring path) {
    (void)type;
#ifdef _WIN32
    (void)env;
    (void)path;
    return installer_root_valid((installer_root *)(intptr_t)handle) ? JNI_TRUE : JNI_FALSE;
#else
    const char *value = (*env)->GetStringUTFChars(env, path, NULL);
    if (!value) return JNI_FALSE;
    int valid = installer_root_valid((int)(handle - 1), value);
    (*env)->ReleaseStringUTFChars(env, path, value);
    return valid ? JNI_TRUE : JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL Java_StagingRoot_release(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
#ifdef _WIN32
    installer_close_root((installer_root *)(intptr_t)handle);
#else
    close((int)(handle - 1));
#endif
}

JNIEXPORT jstring JNICALL Java_StagingRoot_windowsIdentity(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
#ifdef _WIN32
    FILE_ID_INFO id;
    if (!installer_root_identity((installer_root *)(intptr_t)handle, &id)) return NULL;
    char identity[17 + 2 * sizeof id.FileId.Identifier];
    if (snprintf(identity, sizeof identity, "%016llx", id.VolumeSerialNumber) != 16) return NULL;
    for (size_t i = 0; i < sizeof id.FileId.Identifier; i++) {
        if (snprintf(identity + 16 + 2 * i, 3, "%02x", (unsigned int)id.FileId.Identifier[i]) != 2) return NULL;
    }
    return (*env)->NewStringUTF(env, identity);
#else
    (void)env;
    (void)handle;
    return NULL;
#endif
}

JNIEXPORT jboolean JNICALL Java_StagingRoot_writeNew(JNIEnv *env, jclass type, jlong handle,
        jstring name, jbyteArray bytes, jboolean executable) {
    (void)type;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!data) return JNI_FALSE;
    int ok = 0;
#ifdef _WIN32
    (void)executable;
    jsize chars = (*env)->GetStringLength(env, name);
    wchar_t filename[256];
    if (chars > 0 && chars < 256) {
        (*env)->GetStringRegion(env, name, 0, chars, (jchar *)filename);
        filename[chars] = 0;
        if (!(*env)->ExceptionCheck(env)) {
            HANDLE file = installer_create_file((installer_root *)(intptr_t)handle, filename);
            if (file != INVALID_HANDLE_VALUE) {
                DWORD written = 0;
                ok = WriteFile(file, data, (DWORD)length, &written, NULL) && written == (DWORD)length;
                if (!CloseHandle(file)) ok = 0;
            }
        }
    }
#else
    const char *filename = (*env)->GetStringUTFChars(env, name, NULL);
    if (filename) {
        int file = installer_create_file((int)(handle - 1), filename);
        (*env)->ReleaseStringUTFChars(env, name, filename);
        if (file >= 0) {
            jsize offset = 0;
            while (offset < length) {
                ssize_t count = write(file, data + offset, (size_t)(length - offset));
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) break;
                offset += (jsize)count;
            }
            ok = offset == length && (!executable || fchmod(file, 0700) == 0);
            if (close(file) != 0) ok = 0;
        }
    }
#endif
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_StagingRoot_rename(JNIEnv *env, jclass type, jlong handle,
        jstring source, jstring destination, jboolean replace) {
    (void)type;
    int ok = 0;
#ifdef _WIN32
    wchar_t from[256], to[256];
    jsize from_length = (*env)->GetStringLength(env, source);
    jsize to_length = (*env)->GetStringLength(env, destination);
    if (from_length < 1 || from_length >= 256 || to_length < 1 || to_length >= 256) return JNI_FALSE;
    (*env)->GetStringRegion(env, source, 0, from_length, (jchar *)from);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    (*env)->GetStringRegion(env, destination, 0, to_length, (jchar *)to);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    from[from_length] = 0;
    to[to_length] = 0;
    installer_root *root = (installer_root *)(intptr_t)handle;
    ok = replace ? installer_replace_file(root, from, to) : installer_rename_new(root, from, to);
#else
    const char *from = (*env)->GetStringUTFChars(env, source, NULL);
    if (!from) return JNI_FALSE;
    const char *to = (*env)->GetStringUTFChars(env, destination, NULL);
    if (to) {
        int root = (int)(handle - 1);
        ok = replace ? installer_replace_file(root, from, to) : installer_rename_new(root, from, to);
        (*env)->ReleaseStringUTFChars(env, destination, to);
    }
    (*env)->ReleaseStringUTFChars(env, source, from);
#endif
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_StagingRoot_trustedFile(JNIEnv *env, jclass type, jlong handle, jstring name) {
    (void)type;
    int ok = 0;
#ifdef _WIN32
    wchar_t buffer[256];
    jsize length = (*env)->GetStringLength(env, name);
    if (length < 1 || length >= 256) return JNI_FALSE;
    (*env)->GetStringRegion(env, name, 0, length, (jchar *)buffer);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    buffer[length] = 0;
    ok = installer_trusted_file((installer_root *)(intptr_t)handle, buffer);
#else
    const char *filename = (*env)->GetStringUTFChars(env, name, NULL);
    if (filename) {
        ok = installer_trusted_file((int)(handle - 1), filename);
        (*env)->ReleaseStringUTFChars(env, name, filename);
    }
#endif
    return ok ? JNI_TRUE : JNI_FALSE;
}
