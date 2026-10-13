/*
 * pb_wbfs.h — a Wii disc kept in a .wbfs container, read for rcheevos.
 *
 * rcheevos hashes a Wii disc from an image that holds every byte of it, and
 * has no name for the extension wbfs. A .wbfs holds the same disc cut into
 * blocks, with a table that says where each one is and leaves out the blocks
 * that were not copied. pb_wbfs.c reads one as Dolphin does and hands rcheevos
 * the disc, so that the hash is the one Dolphin sends for the same file.
 */

#ifndef PB_WBFS_H
#define PB_WBFS_H

#include <stdint.h>
#include "rc_hash.h"

/*
 * Counted up whenever the reader comes to answer otherwise for some file.
 * The library says it in its version, and a scan keeps the verdict of a
 * .wbfs under it.
 */
#define PB_WBFS_READER 1

/* The room a reason is given, with the NUL that ends it. */
#define PB_WBFS_REASON 256

/*
 * What rcheevos read of the disc for one hash, and how much of that came from
 * blocks the container does not hold. A copy with such blocks is not the disc
 * RetroAchievements knows, and this is how the caller can tell.
 */
struct pb_wbfs_stats {
    uint64_t bytes_read;
    uint64_t bytes_unheld;
};

/*
 * The room an answer is given, with the NUL that ends it: 32 hex digits, the
 * console, two numbers of twenty digits at most and the three bars between.
 */
#define PB_WBFS_ANSWER 96

/* Whether the path ends in .wbfs, in either case. */
int pb_wbfs_names(const char* path);

/*
 * Hashes the Wii disc in the container the iterator was made for, with
 * rc_hash_initialize_iterator(iterator, path, NULL, 0) and nothing changed
 * since but its userdata and its callback for errors.
 *
 * A .wbfs holds a Wii disc and nothing else, so console 0 is taken to ask
 * for that, and any other console but the Wii's is refused here.
 *
 * Returns 1 with the hash, and 0 without one: with the reason in `why` when
 * the container or the console was at fault, and with `why` empty when
 * rcheevos refused the disc inside, which it has then said to the iterator's
 * callback for errors. `stats` is filled either way.
 */
int pb_wbfs_hash(char hash[33], uint32_t console, rc_hash_iterator_t* iterator,
                 struct pb_wbfs_stats* stats, char why[PB_WBFS_REASON]);

/*
 * What is answered for a container that gave a hash, from what pb_wbfs_hash
 * handed back: "<md5>|19|<bytes read>|<bytes not held>". Written here and not
 * by each caller, so that the library and the program the sanitized rows run
 * cannot come to say it in two ways.
 */
void pb_wbfs_answer(char answer[PB_WBFS_ANSWER], const char hash[33],
                    const struct pb_wbfs_stats* stats);

#endif /* PB_WBFS_H */
