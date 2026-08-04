/*
 * rahasher_jni.c — JNI binding for rcheevos ROM hashing, shared build.
 *
 * Mirrors the Android binding but is compiled for the host platform and bound to
 * com.pegasus.bridge.hasher.NativeRomHasher instead of the Android-only class.
 * The rcheevos sources are unmodified: the same ROM yields the same hash from
 * the arm64 and x86_64 builds, which is what makes the port safe.
 *
 * Returns "MD5|CONSOLE_ID", or NULL when the file yields no hash.
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include "rc_hash.h"

static void quiet_message(const char* message, const rc_hash_iterator_t* iter) {
    (void)message;
    (void)iter;
}

JNIEXPORT jstring JNICALL
Java_com_pegasus_bridge_hasher_NativeRomHasher_hashFile(JNIEnv *env, jobject self, jstring jpath) {
    (void)self;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return NULL;

    rc_hash_iterator_t iterator;
    char hash[33];
    char result[64]; /* 32 hex + '|' + up to 10 digits + NUL */
    jstring jresult = NULL;

    memset(&iterator, 0, sizeof(iterator));
    iterator.callbacks.verbose_message = quiet_message;
    iterator.callbacks.error_message   = quiet_message;

    rc_hash_initialize_iterator(&iterator, path, NULL, 0);

    if (rc_hash_iterate(hash, &iterator)) {
        int console_id = (int)iterator.consoles[iterator.index - 1];
        snprintf(result, sizeof(result), "%s|%d", hash, console_id);
        jresult = (*env)->NewStringUTF(env, result);
    }

    rc_hash_destroy_iterator(&iterator);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    return jresult;
}
