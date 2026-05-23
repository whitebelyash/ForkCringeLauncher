/*
 * Derived from PojavLauncher native bridge code.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdarg.h>
#include <dlfcn.h>

#include <environ/environ.h>
#include <android/log.h>
#include <utils.h>

#define EXEC_HOOK_TAG "JavaLauncherExecHook"
#define ARRAY_SIZE(a) ((int)(sizeof(a) / sizeof((a)[0])))

static jint (*orig_ProcessImpl_forkAndExec)(JNIEnv *env, jobject process, jint mode,
                                            jbyteArray helperpath, jbyteArray prog, jbyteArray argBlock, jint argc,
                                            jbyteArray envBlock, jint envc, jbyteArray dir, jintArray std_fds,
                                            jboolean redirectErrorStream);

static void log_info(const char *fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, EXEC_HOOK_TAG, "%s", buffer);
    printf("%s: %s\n", EXEC_HOOK_TAG, buffer);
}

static void log_warn(const char *fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_WARN, EXEC_HOOK_TAG, "%s", buffer);
    printf("%s WARN: %s\n", EXEC_HOOK_TAG, buffer);
}

static char *byte_array_to_c_string(JNIEnv *env, jbyteArray array) {
    if (array == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, array);
    if (length <= 0) return NULL;

    char *out = (char *)calloc((size_t)length + 1, 1);
    if (out == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)out);
    out[length] = '\0';
    return out;
}

static jbyteArray string_to_bytes(JNIEnv *env, const char *string) {
    if (string == NULL) return NULL;
    jsize length = (jsize)strlen(string) + 1;
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)string);
    return result;
}

static const char *safe_basename(const char *path) {
    if (path == NULL || path[0] == '\0') return "";
    const char *slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static bool starts_with(const char *value, const char *prefix) {
    return value != NULL && prefix != NULL && strncmp(value, prefix, strlen(prefix)) == 0;
}

static bool is_ffmpeg_name(const char *base) {
    return strcmp(base, "ffmpeg") == 0 || strcmp(base, "libffmpeg.so") == 0;
}

static bool is_ffprobe_name(const char *base) {
    return strcmp(base, "ffprobe") == 0 || strcmp(base, "libffprobe.so") == 0;
}

static char *dirname_dup(const char *path) {
    if (path == NULL) return NULL;
    const char *slash = strrchr(path, '/');
    if (slash == NULL) return strdup(".");
    if (slash == path) return strdup("/");

    size_t len = (size_t)(slash - path);
    char *out = (char *)malloc(len + 1);
    if (out == NULL) return NULL;
    memcpy(out, path, len);
    out[len] = '\0';
    return out;
}

static char *dup_env_value_from_block(JNIEnv *env, jbyteArray envBlock, const char *key) {
    if (envBlock == NULL || key == NULL) return NULL;

    jsize length = (*env)->GetArrayLength(env, envBlock);
    if (length <= 0) return NULL;

    char *data = (char *)malloc((size_t)length + 1);
    if (data == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, envBlock, 0, length, (jbyte *)data);
    data[length] = '\0';

    size_t key_len = strlen(key);
    size_t i = 0;
    while (i < (size_t)length) {
        const char *entry = data + i;
        size_t entry_len = strnlen(entry, (size_t)length - i);
        if (entry_len == 0) {
            i++;
            continue;
        }
        if (entry_len > key_len && strncmp(entry, key, key_len) == 0 && entry[key_len] == '=') {
            char *out = strdup(entry + key_len + 1);
            free(data);
            return out;
        }
        i += entry_len + 1;
    }

    free(data);
    return NULL;
}

static char *resolve_env_path(JNIEnv *env, jbyteArray envBlock, const char **keys, int key_count) {
    for (int i = 0; i < key_count; i++) {
        char *from_block = dup_env_value_from_block(env, envBlock, keys[i]);
        if (from_block != NULL && from_block[0] != '\0') return from_block;
        free(from_block);

        const char *from_process = getenv(keys[i]);
        if (from_process != NULL && from_process[0] != '\0') return strdup(from_process);
    }
    return NULL;
}

static const char *first_non_empty(const char *a, const char *b) {
    if (a != NULL && a[0] != '\0') return a;
    if (b != NULL && b[0] != '\0') return b;
    return NULL;
}

static char *prepend_path_value(const char *prefix, const char *old_value) {
    if (prefix == NULL || prefix[0] == '\0') return old_value != NULL ? strdup(old_value) : strdup("");
    if (old_value == NULL || old_value[0] == '\0') return strdup(prefix);

    size_t prefix_len = strlen(prefix);
    const char *cursor = old_value;
    while (cursor != NULL && cursor[0] != '\0') {
        const char *colon = strchr(cursor, ':');
        size_t part_len = colon == NULL ? strlen(cursor) : (size_t)(colon - cursor);
        if (part_len == prefix_len && strncmp(cursor, prefix, prefix_len) == 0) {
            return strdup(old_value);
        }
        cursor = colon == NULL ? NULL : colon + 1;
    }

    size_t len = strlen(prefix) + 1 + strlen(old_value) + 1;
    char *out = (char *)malloc(len);
    if (out == NULL) return NULL;
    snprintf(out, len, "%s:%s", prefix, old_value);
    return out;
}

static jbyteArray merge_ffmpeg_env(JNIEnv *env, jbyteArray oldBlock, jint oldEnvc, const char *binDir, jint *newEnvc) {
    char *old_ld = dup_env_value_from_block(env, oldBlock, "LD_LIBRARY_PATH");
    char *old_path = dup_env_value_from_block(env, oldBlock, "PATH");
    const char *process_ld = getenv("LD_LIBRARY_PATH");
    const char *process_path = getenv("PATH");

    char *merged_ld_value = prepend_path_value(binDir, first_non_empty(old_ld, process_ld));
    char *path_value = first_non_empty(old_path, process_path) != NULL
                       ? strdup(first_non_empty(old_path, process_path))
                       : strdup("");

    free(old_ld);
    free(old_path);

    if (merged_ld_value == NULL || path_value == NULL) {
        free(merged_ld_value);
        free(path_value);
        return oldBlock;
    }

    size_t ld_entry_len = strlen("LD_LIBRARY_PATH=") + strlen(merged_ld_value) + 1;
    size_t path_entry_len = strlen("PATH=") + strlen(path_value) + 1;

    jsize old_len = oldBlock != NULL ? (*env)->GetArrayLength(env, oldBlock) : 0;
    char *old_data = NULL;
    if (old_len > 0) {
        old_data = (char *)malloc((size_t)old_len + 1);
        if (old_data == NULL) {
            free(merged_ld_value);
            free(path_value);
            return oldBlock;
        }
        (*env)->GetByteArrayRegion(env, oldBlock, 0, old_len, (jbyte *)old_data);
        old_data[old_len] = '\0';
    }

    size_t kept_len = 0;
    jint kept_count = 0;
    size_t i = 0;
    while (old_data != NULL && i < (size_t)old_len) {
        const char *entry = old_data + i;
        size_t entry_len = strnlen(entry, (size_t)old_len - i);
        if (entry_len == 0) {
            i++;
            continue;
        }
        if (!starts_with(entry, "LD_LIBRARY_PATH=") && !starts_with(entry, "PATH=")) {
            kept_len += entry_len + 1;
            kept_count++;
        }
        i += entry_len + 1;
    }

    size_t new_len = kept_len + ld_entry_len + path_entry_len;
    char *new_data = (char *)calloc(new_len, 1);
    if (new_data == NULL) {
        free(old_data);
        free(merged_ld_value);
        free(path_value);
        return oldBlock;
    }

    size_t offset = 0;
    i = 0;
    while (old_data != NULL && i < (size_t)old_len) {
        const char *entry = old_data + i;
        size_t entry_len = strnlen(entry, (size_t)old_len - i);
        if (entry_len == 0) {
            i++;
            continue;
        }
        if (!starts_with(entry, "LD_LIBRARY_PATH=") && !starts_with(entry, "PATH=")) {
            memcpy(new_data + offset, entry, entry_len + 1);
            offset += entry_len + 1;
        }
        i += entry_len + 1;
    }

    int wrote = snprintf(new_data + offset, new_len - offset, "LD_LIBRARY_PATH=%s", merged_ld_value);
    offset += (size_t)wrote + 1;
    wrote = snprintf(new_data + offset, new_len - offset, "PATH=%s", path_value);
    (void)wrote;

    jbyteArray out = (*env)->NewByteArray(env, (jsize)new_len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)new_len, (const jbyte *)new_data);
        *newEnvc = kept_count + 2;
    } else {
        out = oldBlock;
        *newEnvc = oldEnvc;
    }

    free(old_data);
    free(new_data);
    free(merged_ld_value);
    free(path_value);
    return out;
}

static bool redirect_tool_direct(JNIEnv *env, const char *toolName, const char *targetPath,
                                 jbyteArray *prog, jbyteArray *envBlock, jint *envc) {
    if (targetPath == NULL || targetPath[0] == '\0') {
        log_warn("%s requested, but no target path was set", toolName);
        return false;
    }

    char *bin_dir = dirname_dup(targetPath);
    if (bin_dir == NULL) {
        log_warn("%s requested, unable to resolve directory from %s", toolName, targetPath);
        return false;
    }

    *envBlock = merge_ffmpeg_env(env, *envBlock, *envc, bin_dir, envc);
    *prog = string_to_bytes(env, targetPath);
    log_info("%s requested, direct target=%s", toolName, targetPath);
    free(bin_dir);
    return true;
}

static jint hooked_ProcessImpl_forkAndExec(JNIEnv *env, jobject process, jint mode,
                                           jbyteArray helperpath, jbyteArray prog, jbyteArray argBlock, jint argc,
                                           jbyteArray envBlock, jint envc, jbyteArray dir, jintArray std_fds,
                                           jboolean redirectErrorStream) {

    char *program = byte_array_to_c_string(env, prog);
    const char *base = safe_basename(program);

    if (strcmp(base, "xdg-open") == 0) {
        free(program);
        Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(env, NULL, CLIPBOARD_OPEN, argBlock);
        return 0;
    }

    if (is_ffmpeg_name(base)) {
        const char *keys[] = {
                "POJAV_FFMPEG_PATH",
                "JAVALAUNCHER_FFMPEG_PATH",
                "FFMPEG_PATH"
        };
        char *target = resolve_env_path(env, envBlock, keys, ARRAY_SIZE(keys));
        redirect_tool_direct(env, "ffmpeg", target, &prog, &envBlock, &envc);
        free(target);
    } else if (is_ffprobe_name(base)) {
        const char *keys[] = {
                "POJAV_FFPROBE_PATH",
                "JAVALAUNCHER_FFPROBE_PATH",
                "FFPROBE_PATH"
        };
        char *target = resolve_env_path(env, envBlock, keys, ARRAY_SIZE(keys));
        redirect_tool_direct(env, "ffprobe", target, &prog, &envBlock, &envc);
        free(target);
    }

    free(program);
    return orig_ProcessImpl_forkAndExec(env, process, mode, helperpath, prog, argBlock,
                                        argc, envBlock, envc, dir, std_fds, redirectErrorStream);
}

static bool register_fork_and_exec(JNIEnv *env, const char *className) {
    jclass cls = (*env)->FindClass(env, className);
    if (cls == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return false;
    }

    JNINativeMethod methods[] = {
            {"forkAndExec", "(I[B[B[BI[BI[B[IZ)I", (void *)&hooked_ProcessImpl_forkAndExec}
    };

    jint result = (*env)->RegisterNatives(env, cls, methods, 1);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (result == 0) {
        log_info("Registered forkAndExec for %s", className);
        return true;
    }

    log_warn("RegisterNatives failed for %s result=%d", className, result);
    return false;
}

void hookExec(void) {
    JNIEnv *env = pojav_environ->runtimeJNIEnvPtr_JRE;
    if (env == NULL) {
        log_warn("runtime JNI env is null, cannot hook forkAndExec");
        return;
    }

    orig_ProcessImpl_forkAndExec = dlsym(RTLD_DEFAULT, "Java_java_lang_ProcessImpl_forkAndExec");
    if (orig_ProcessImpl_forkAndExec == NULL) {
        orig_ProcessImpl_forkAndExec = dlsym(RTLD_DEFAULT, "Java_java_lang_UNIXProcess_forkAndExec");
    }

    if (orig_ProcessImpl_forkAndExec == NULL) {
        log_warn("Unable to find original forkAndExec symbol");
        return;
    }

    bool process_impl = register_fork_and_exec(env, "java/lang/ProcessImpl");
    bool unix_process = register_fork_and_exec(env, "java/lang/UNIXProcess");
    log_info("forkAndExec registration complete ProcessImpl=%d UNIXProcess=%d",
             process_impl ? 1 : 0,
             unix_process ? 1 : 0);
}
