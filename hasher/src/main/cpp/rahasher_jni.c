/*
 * rahasher_jni.c — the one way into rcheevos from a JVM, on Android and on the
 * desktop alike.
 *
 * There were two of these files, one to a platform, each exporting hashFile
 * under the name of that platform's own class. They said the same thing twice
 * and had drifted all the same, and both asked rcheevos to guess the console
 * from the extension, which was all either could ask. This one is compiled by
 * shared/native/build.sh and by CMakeLists.txt beside it, from the sources
 * rahasher.sources lists, and is bound to one class both platforms compile,
 * com.pegasus.bridge.hasher.RcheevosNative.
 *
 *   hashForConsole(path, console, errorOut)   "<md5>|<console>", or null
 *   version()                                 "12.5.0+pb10"
 *
 * Nothing is logged from here and nothing is kept between calls: what
 * rcheevos said of a file it gave no hash for goes back to the caller, in the
 * caller's own buffer, and the caller decides who is told. So a call on one
 * thread knows nothing of a call on another, and the library needs nothing of
 * the system but its C library.
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rc_hash.h"
#include "rc_version.h"
#include "pb_patchlevel.h"

/* Every error of one call, in the order rcheevos gave them. */
struct reasons {
    char text[1024];
    size_t length;
    int full; /* a message was cut short or left out, and nothing goes after it */
};

/*
 * How much of the first `length` bytes of text is whole characters: all of
 * it, or all but a last character whose other bytes are not there. The text
 * is UTF-8 wherever a file's name is, and it is cut by the byte in three
 * places: where rcheevos puts a message together, in the room there is for
 * the messages of one call, and in the caller's buffer. Half of a character
 * is bytes no decoder takes, and a reason most often ends with a name.
 */
static size_t whole_characters(const char* text, size_t length) {
    size_t following = 0; /* bytes at the end that go on a character begun before them */
    unsigned char first;
    size_t wanted;

    while (following < length && following < 3
           && ((unsigned char)text[length - 1 - following] & 0xC0) == 0x80)
        ++following;
    if (following == length)
        return length;

    first = (unsigned char)text[length - 1 - following];
    wanted = first >= 0xF0 ? 3 : first >= 0xE0 ? 2 : first >= 0xC0 ? 1 : 0;
    return wanted > following ? length - 1 - following : length;
}

/*
 * rcheevos reports an error to a function, with the iterator it was working
 * on. That is not always the one made below: a playlist is hashed through an
 * iterator of its first file, which is handed the same userdata. Text that
 * does not fit is dropped, since the first errors say the most, and once
 * some has been, so is every message after it: the text ends where it was
 * cut, and not with a word of the next thing said.
 */
static void keep_reason(const char* message, const rc_hash_iterator_t* iterator) {
    struct reasons* reasons = (struct reasons*)iterator->userdata;
    size_t room, length, whole;

    if (!reasons || !message || reasons->full)
        return;

    room = sizeof(reasons->text) - 1 - reasons->length;
    if (reasons->length) {
        if (room <= 2) {
            reasons->full = 1;
            return;
        }
        memcpy(reasons->text + reasons->length, "; ", 2);
        reasons->length += 2;
        room -= 2;
    }

    length = strlen(message);
    if (length > room) {
        length = room;
        reasons->full = 1;
    }
    memcpy(reasons->text + reasons->length, message, length);
    /* Whether it was cut here or not: rcheevos cuts a long message itself. */
    whole = whole_characters(reasons->text, reasons->length + length);
    if (whole < reasons->length + length)
        reasons->full = 1;
    reasons->length = whole;
    reasons->text[reasons->length] = '\0';
}

/*
 * Writes text into the caller's buffer, as much of it as fits before the NUL
 * that ends it. A buffer of no bytes, or none at all, is a caller that did not
 * want to know. Text cut short is cut between two characters and not inside
 * one.
 */
static void say(JNIEnv* env, jbyteArray error_out, const char* text) {
    const jbyte end = 0;
    size_t length;
    jsize room;

    if (!error_out)
        return;
    room = (*env)->GetArrayLength(env, error_out);
    if (room < 1)
        return;

    length = strlen(text);
    if (length > (size_t)room - 1)
        length = whole_characters(text, (size_t)room - 1);

    (*env)->SetByteArrayRegion(env, error_out, 0, (jsize)length, (const jbyte*)text);
    (*env)->SetByteArrayRegion(env, error_out, (jsize)length, 1, &end);
}

/* A call turned away before rcheevos was asked anything. */
static jstring refuse(JNIEnv* env, jbyteArray error_out, const char* why) {
    say(env, error_out, why);
    return NULL;
}

/*
 * The path comes as the bytes of its UTF-8 and not as a string. What JNI
 * gives for a string is its own variant of UTF-8, in which a character
 * outside the first 65536 is six bytes where the file system has four, and
 * rcheevos would be opening a file that is not there.
 *
 * Console 0 asks every console rcheevos takes the extension for, in its order
 * and up to the first that answers, which is all the two old files could do.
 * A console above 0 is hashed as that console and no other: the caller knows
 * what collection the file is in, and the extension does not.
 */
JNIEXPORT jstring JNICALL
Java_com_pegasus_bridge_hasher_RcheevosNative_hashForConsole(
        JNIEnv* env, jclass clazz, jbyteArray path_utf8, jint console_id, jbyteArray error_out) {
    rc_hash_iterator_t iterator;
    struct reasons reasons;
    char hash[33];
    char answer[64]; /* 32 hex digits, '|', a console of three digits at most, NUL */
    char* path;
    jsize length;
    int found;

    (void)clazz;

    if (!path_utf8)
        return refuse(env, error_out, "No path was given");
    length = (*env)->GetArrayLength(env, path_utf8);
    if (length < 1)
        return refuse(env, error_out, "The path is empty");
    /* rcheevos numbers its consoles in one byte. A number outside it is the caller's
     * mistake, and is said to be here; rcheevos would call it a console it does not
     * support, which reads as a verdict on the file. */
    if (console_id < 0 || console_id > 255) {
        snprintf(answer, sizeof(answer), "Console %ld is not one of 0 to 255", (long)console_id);
        return refuse(env, error_out, answer);
    }

    path = (char*)malloc((size_t)length + 1);
    if (!path)
        return refuse(env, error_out, "No memory for the path");
    (*env)->GetByteArrayRegion(env, path_utf8, 0, length, (jbyte*)path);
    path[length] = '\0';
    /* C would take the path to end there, and hash some other file than the one named. */
    if (memchr(path, '\0', (size_t)length)) {
        free(path);
        return refuse(env, error_out, "The path has a NUL in it");
    }
    /*
     * rcheevos takes '/' and '\' alike for what parts a folder from a file,
     * on every system, so a path that ends in one has no file's name to it.
     * An arcade set is hashed by that name with its extension taken off, and
     * up to 12.3.0 rcheevos took one more off a name of no letters than
     * there are: what it then hashed was the 64 MiB that follow the path in
     * memory, and the process ended where its own memory does. Since 12.5.0
     * it answers the MD5 of no name there, and for any other console what
     * it makes of the folder the path leads to. Neither is the hash of a
     * game. Nothing that walks a folder makes such a path, and no file is
     * found under one either.
     */
    if (path[length - 1] == '/' || path[length - 1] == '\\') {
        free(path);
        return refuse(env, error_out, "The path names no file");
    }

    reasons.text[0] = '\0';
    reasons.length = 0;
    reasons.full = 0;

    /*
     * The callback goes in after rc_hash_initialize_iterator and not before:
     * that function clears the whole iterator, and a callback set ahead of it
     * is gone by the time anything is hashed. Both old files set theirs
     * ahead, so neither was ever told a thing.
     *
     * The callback for errors, and that one alone. With one for rcheevos'
     * running commentary set as well, rcheevos does more than talk: it
     * writes into the buffer it has read a Dreamcast disc's header to, and
     * puts into words six bytes of a Wii disc it has not read yet. Nothing
     * here has a use for the commentary, and the files made to hurt rcheevos
     * (shared/tests/native_repro_test.py) are run without it.
     */
    rc_hash_initialize_iterator(&iterator, path, NULL, 0);
    free(path);
    /* The iterator keeps a copy of its own, and goes on to read it unasked. */
    if (!iterator.path)
        return refuse(env, error_out, "No memory for the path");
    iterator.userdata = &reasons;
    iterator.callbacks.error_message = keep_reason;

    if (console_id > 0) {
        found = rc_hash_generate(hash, (uint32_t)console_id, &iterator);
    } else {
        found = rc_hash_iterate(hash, &iterator);
        if (found)
            console_id = iterator.consoles[iterator.index - 1];
    }

    rc_hash_destroy_iterator(&iterator);

    if (!found) {
        /* An error on the way to a hash is not a failure; these were. And
         * rcheevos gives some files up without a word, a playlist with no
         * line in it among them: the caller is told that much, so that null
         * never comes back with nothing to read beside it. */
        say(env, error_out, reasons.length ? reasons.text : "rcheevos gave no hash and no reason");
        return NULL;
    }

    snprintf(answer, sizeof(answer), "%s|%ld", hash, (long)console_id);
    return (*env)->NewStringUTF(env, answer);
}

/*
 * Which rcheevos this library is: the upstream release, then how many local
 * patches are on it. Two builds that differ in either do not answer alike,
 * so the caller holds this against the version it was written for, and it is
 * what a scan's answers are kept under.
 *
 * Put together from the three numbers. The string rcheevos makes of them
 * itself leaves out a patch number of 0, and would read "12.5".
 */
JNIEXPORT jstring JNICALL
Java_com_pegasus_bridge_hasher_RcheevosNative_version(JNIEnv* env, jclass clazz) {
    char text[64];

    (void)clazz;

    snprintf(text, sizeof(text), "%d.%d.%d+pb%d",
             RCHEEVOS_VERSION_MAJOR, RCHEEVOS_VERSION_MINOR, RCHEEVOS_VERSION_PATCH,
             PB_RCHEEVOS_PATCHLEVEL);
    return (*env)->NewStringUTF(env, text);
}
