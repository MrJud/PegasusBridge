/*
 * rahash_cli.c — the hasher with no JVM around it: one file, one answer.
 *
 *     rahash_cli <console> <path>
 *     rahash_cli --fault=address|undefined
 *     rahash_cli --handed [path]
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
 * A path that ends in .chd is a disc rcheevos hashes through the reader of
 * tracks in pb_chd.c, which is put on the iterator here as the library puts
 * it. A hunk of one that cannot be read makes the run one with no hash,
 * whatever rcheevos made of what it could read.
 *
 * A path that ends in .wbfs is handed to the reader in pb_wbfs.c, which the
 * library calls for such a path too: what console may be asked for, how the
 * container is read and what is counted are all in that file, and here the
 * answer is only printed. It has two numbers more than any other, the bytes
 * rcheevos read of the disc and how many of them were of blocks the container
 * does not hold.
 *
 * It ends in one of four ways, and a test may count on them:
 *   0  one line on stdout, "<md5>|<console>", or for a .wbfs
 *      "<md5>|19|<bytes read>|<bytes not held>"
 *   1  no hash; what rcheevos said of it is on stderr, on one line
 *   2  it was not started with a console from 0 to 255 and a path
 *   3  rcheevos left a file open that it had opened: how many is on stderr,
 *      after what it would have said otherwise
 * Anything else is rcheevos or the reader, not this file: a signal, or a
 * sanitizer's report.
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
 *
 * --handed is not about a file. It asks rcheevos for the hash of a Wii disc
 * with a callback for opening files that keeps the pointer it is given each
 * time, and ends with 0 when that was the iterator's own path and nothing
 * else, once, and with 1 and a line on stderr when it was anything else.
 * With no path the callback opens nothing, and rcheevos stops there. With a
 * path it opens the file as rcheevos would have, and rcheevos goes on through
 * all it does with a disc or a package of the Wii: a second file opened on
 * the way, or the same one again by another pointer, is seen only so. The
 * line on stdout then ends with whether there was a hash, so that a test
 * knows rcheevos went all the way. The reader of .wbfs knows its container
 * by that pointer and can refuse few of the pointers that are not it
 * (disc_open in pb_wbfs.c), so an rcheevos that came to hand its callback a
 * copy of the path, or to open a second file, is to be met here and not
 * there.
 */

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rc_hash.h"
#include "pb_wbfs.h"
#include "pb_chd.h"

/* Every error of one run, in the order rcheevos gave them. */
struct reasons {
    char text[2048];
    size_t length;
    int damaged; /* a hunk of a .chd could not be read: a hash would be of a part */
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
    /* Ahead of the room there is for text, as in the library. */
    if (pb_chd_is_damage(message))
        reasons->damaged = 1;

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
                    "       rahash_cli --fault=address|undefined\n"
                    "       rahash_cli --handed [path]\n");
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

/* What rcheevos handed the callback of --handed, and how many times: the
 * pointer of the first time, and whether any time had another. */
static const char* handed_path;
static int handed_times;
static int handed_others;
/* What rcheevos would have opened the file with, or NULL to open nothing. */
static rc_hash_filereader_open_file_handler handed_open;

static void* RC_CCONV keep_handed(const char* path_utf8) {
    if (handed_times++ == 0)
        handed_path = path_utf8;
    else if (path_utf8 != handed_path)
        handed_others++;
    return handed_open ? handed_open(path_utf8) : NULL;
}

static int handed(const char* path) {
    rc_hash_iterator_t iterator;
    char hash[33];
    int same, found;

    rc_hash_initialize_iterator(&iterator, path ? path : "a disc that is never opened.wbfs", NULL, 0);
    handed_open = path ? iterator.callbacks.filereader.open : NULL;
    iterator.callbacks.filereader.open = keep_handed;
    found = rc_hash_generate(hash, RC_CONSOLE_WII, &iterator);
    /* Compared while the iterator still has its path, and before it is freed. */
    same = iterator.path != NULL && handed_path == iterator.path && handed_others == 0;
    rc_hash_destroy_iterator(&iterator);

    if (handed_times != 1) {
        fprintf(stderr, "rcheevos opened %d file(s) to hash one for the Wii, where the reader of .wbfs counts on one\n",
                handed_times);
        return 1;
    }
    if (!same) {
        fprintf(stderr, "rcheevos handed its callback for opening a file a pointer that is not the iterator's path\n");
        return 1;
    }
    if (!path)
        puts("the iterator's own path, once");
    else
        printf("the iterator's own path, once, %s\n", found ? "and a hash" : "and no hash");
    return 0;
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
    struct pb_wbfs_stats stats;
    char why[PB_WBFS_REASON];
    char hash[33];
    char answer[PB_WBFS_ANSWER]; /* which has room for the two fields of any other file as well */
    char* end;
    long console;
    int found;
    int before, after;

    if (argc == 2 && strncmp(argv[1], "--fault=", 8) == 0)
        return fault(argv[1] + 8);
    if ((argc == 2 || argc == 3) && strcmp(argv[1], "--handed") == 0)
        return handed(argc == 3 ? argv[2] : NULL);

    if (argc != 3)
        return usage();

    errno = 0;
    console = strtol(argv[1], &end, 10);
    if (errno || end == argv[1] || *end != '\0' || console < 0 || console > 255)
        return usage();

    reasons.text[0] = '\0';
    reasons.length = 0;
    reasons.damaged = 0;
    hash[0] = '\0'; /* the answer is put together before it is known whether there is one */

    /*
     * The callback goes in after rc_hash_initialize_iterator and not before:
     * that function clears the whole iterator, and a callback set ahead of it
     * is gone by the time anything is hashed.
     */
    before = open_descriptors();
    rc_hash_initialize_iterator(&iterator, argv[2], NULL, 0);
    iterator.userdata = &reasons;
    iterator.callbacks.error_message = keep_reason;
    pb_chd_install(&iterator); /* the reader of .chd, as the library puts it */

    if (pb_wbfs_names(argv[2])) {
        found = pb_wbfs_hash(hash, (uint32_t)console, &iterator, &stats, why);
        /* What the reader refused the container for goes where rcheevos'
         * own reasons go; when rcheevos refused the disc, it is there already. */
        if (!found && why[0])
            keep_reason(why, &iterator);
        pb_wbfs_answer(answer, hash, &stats);
    } else if (console > 0) {
        found = rc_hash_generate(hash, (uint32_t)console, &iterator);
        snprintf(answer, sizeof(answer), "%s|%ld", hash, console);
    } else {
        found = rc_hash_iterate(hash, &iterator);
        if (found)
            console = iterator.consoles[iterator.index - 1];
        snprintf(answer, sizeof(answer), "%s|%ld", hash, console);
    }

    rc_hash_destroy_iterator(&iterator);
    after = open_descriptors();
    /* A hunk that could not be read: no hash, as the library answers. */
    if (reasons.damaged)
        found = 0;

    if (before >= 0 && after > before) {
        if (found)
            fprintf(stderr, "%s; ", answer);
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

    printf("%s\n", answer);
    return 0;
}
