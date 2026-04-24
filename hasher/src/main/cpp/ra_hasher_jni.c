/*
 * ra_hasher_jni.c — JNI bridge for rcheevos ROM hashing
 *
 * Ported from ra_hasher_tool.c (Termux standalone tool).
 * Exposes a single JNI function: hashFile(path) → "HASH|CONSOLE_ID" or null.
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include "rc_hash.h"
#include "rc_consoles.h"

#define TAG "RAHasher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void jni_verbose_message(const char* message, const rc_hash_iterator_t* iter) {
    (void)iter;
    LOGI("[VERBOSE] %s", message);
}

static void jni_error_message(const char* message, const rc_hash_iterator_t* iter) {
    (void)iter;
    LOGE("[ERROR] %s", message);
}

JNIEXPORT jstring JNICALL
Java_com_ra_romhasher_NativeHasher_hashFile(JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return NULL;

    rc_hash_iterator_t iterator;
    char hash[33];
    char result[64]; /* "HASH|CONSOLE_ID" — 32 + 1 + up to 10 digits + null */
    jstring jresult = NULL;

    memset(&iterator, 0, sizeof(iterator));
    iterator.callbacks.verbose_message = jni_verbose_message;
    iterator.callbacks.error_message   = jni_error_message;

    rc_hash_initialize_iterator(&iterator, path, NULL, 0);

    if (rc_hash_iterate(hash, &iterator)) {
        int console_id = (int)iterator.consoles[iterator.index - 1];
        snprintf(result, sizeof(result), "%s|%d", hash, console_id);
        jresult = (*env)->NewStringUTF(env, result);
    } else {
        LOGI("[SKIP] No hash for: %s", path);
    }

    rc_hash_destroy_iterator(&iterator);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    return jresult;
}
