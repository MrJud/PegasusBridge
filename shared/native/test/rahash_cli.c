/*
 * rahash_cli.c — the hasher with no JVM around it: one file, one answer.
 *
 *     rahash_cli <console> <path>
 *     rahash_cli --fault=address|undefined
 *
 * A file that is cut short or made to mislead can end rcheevos with a signal
 * or leave it reading for ever, and inside a JVM either one takes the daemon,
 * or the worker that runs the tests, with it. This program is the same
 * sources (rahasher.sources) with a main() in place of the JNI file, so that
 * a test can hand it such a file from outside, under a timeout, and see how
 * it ended. build.sh --tools=<dir> builds it twice, as the library is built
 * and with the address and undefined-behaviour sanitizers.
 *
 * Console 0 asks every console rcheevos takes the extension for, in its order
 * and up to the first that answers. A console above 0 is hashed as that
 * console and no other. The library's hashForConsole takes its console the
 * same way, and like this program sets the callback for errors and no other.
 *
 * It ends in one of four ways, and a test may count on them:
 *   0  one line on stdout, "<md5>|<console>"
 *   1  no hash; what rcheevos said of it is on stderr, on one line
 *   2  it was not started with a console from 0 to 255 and a path
 *   3  rcheevos left a file open that it had opened: how many is on stderr,
 *      after what it would have said otherwise
 * Anything else is rcheevos, not this file: a signal, or a sanitizer's report.
 *
 * The third is counted, since nothing else shows it. A file left open is not
 * memory, and neither sanitizer says a word of it; in this program it is gone
 * a moment later with the process, and in the daemon or the app it is one of
 * the thousand or so descriptors a process may hold, lost for good with each
 * file that goes that way. The descriptors open are counted before rcheevos
 * is asked and after it has answered, in the folder the system lists them in.
 * Where there is no such folder nothing is counted and nothing is said.
 *
 * --fault hashes nothing. It does one thing a sanitizer exists to report, a
 * read one byte past a block or a sum too large for an int, and ends with 0.
 * A test that runs files on the sanitized build and finds no report has shown
 * something only if that build is a sanitized one, and the way to know is to
 * give it a fault and see the report: a build without the sanitizer ends with
 * 0 here, as it would have for every file.
 */

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rc_hash.h"

/* Every error of one run, in the order rcheevos gave them. */
struct reasons {
    char text[2048];
    size_t length;
};

/*
 * rcheevos reports an error to a function, with the iterator it was working
 * on. That is not always the one main() made: a playlist is hashed through an
 * iterator of its first file, which is handed the same userdata. Text that
 * does not fit is dropped, since the first errors say the most.
 */
static void keep_reason(const char* message, const rc_hash_iterator_t* iterator) {
    struct reasons* reasons = (struct reasons*)iterator->userdata;
    size_t room, length;

    if (!reasons || !message)
        return;

    room = sizeof(reasons->text) - 1 - reasons->length;
    if (reasons->length && room > 2) {
        memcpy(reasons->text + reasons->length, "; ", 2);
        reasons->length += 2;
        room -= 2;
    }

    length = strlen(message);
    if (length > room)
        length = room;
    memcpy(reasons->text + reasons->length, message, length);
    reasons->length += length;
    reasons->text[reasons->length] = '\0';
}

static int usage(void) {
    fprintf(stderr, "usage: rahash_cli <console, 0 to 255> <path>\n"
                    "       rahash_cli --fault=address|undefined\n");
    return 2;
}

/*
 * The two faults. Each goes through a volatile so that the compiler can
 * neither see it at compile time nor take it out, and neither harms a build
 * with no sanitizer: the byte after a block of 8 is still the allocator's own
 * memory, and the sum wraps. The block is read through a pointer the compiler
 * has lost sight of as well, or the undefined-behaviour sanitizer, which
 * checks a read against what the compiler knows of the block's size, would
 * report it ahead of the address one and say nothing of whether that is there.
 */
static int fault(const char* kind) {
    if (strcmp(kind, "address") == 0) {
        char* volatile block = (char*)calloc(1, 8);
        volatile size_t past = 8;
        volatile char read;
        char* unseen = block;
        if (!unseen)
            return 2;
        read = unseen[past];
        (void)read;
        free(unseen);
        return 0;
    }

    if (strcmp(kind, "undefined") == 0) {
        volatile int most = INT_MAX;
        volatile int sum = most + 1;
        (void)sum;
        return 0;
    }

    return usage();
}

/*
 * How many descriptors this process has open, or -1 where the system has no
 * folder that lists them. The folder is itself open while it is read, each
 * time, so the one more is in both counts and in no difference of them.
 */
static int open_descriptors(void) {
    static const char* const places[] = { "/proc/self/fd", "/dev/fd" };
    size_t place;

    for (place = 0; place < sizeof(places) / sizeof(places[0]); place++) {
        DIR* folder = opendir(places[place]);
        struct dirent* entry;
        int count = 0;

        if (!folder)
            continue;
        while ((entry = readdir(folder)) != NULL)
            if (entry->d_name[0] != '.')
                count++;
        closedir(folder);
        return count;
    }
    return -1;
}

int main(int argc, char** argv) {
    rc_hash_iterator_t iterator;
    struct reasons reasons;
    char hash[33];
    char* end;
    long console;
    int found;
    int before, after;

    if (argc == 2 && strncmp(argv[1], "--fault=", 8) == 0)
        return fault(argv[1] + 8);

    if (argc != 3)
        return usage();

    errno = 0;
    console = strtol(argv[1], &end, 10);
    if (errno || end == argv[1] || *end != '\0' || console < 0 || console > 255)
        return usage();

    reasons.text[0] = '\0';
    reasons.length = 0;

    /*
     * The callback goes in after rc_hash_initialize_iterator and not before:
     * that function clears the whole iterator, and a callback set ahead of it
     * is gone by the time anything is hashed.
     */
    before = open_descriptors();
    rc_hash_initialize_iterator(&iterator, argv[2], NULL, 0);
    iterator.userdata = &reasons;
    iterator.callbacks.error_message = keep_reason;

    if (console > 0) {
        found = rc_hash_generate(hash, (uint32_t)console, &iterator);
    } else {
        found = rc_hash_iterate(hash, &iterator);
        if (found)
            console = iterator.consoles[iterator.index - 1];
    }

    rc_hash_destroy_iterator(&iterator);
    after = open_descriptors();

    if (before >= 0 && after > before) {
        if (found)
            fprintf(stderr, "%s|%ld; ", hash, console);
        else
            fprintf(stderr, "%s; ", reasons.text);
        fprintf(stderr, "%d file(s) left open\n", after - before);
        return 3;
    }

    if (!found) {
        /* An error on the way to a hash is not a failure; these were. */
        fprintf(stderr, "%s\n", reasons.text);
        return 1;
    }

    printf("%s|%ld\n", hash, console);
    return 0;
}
