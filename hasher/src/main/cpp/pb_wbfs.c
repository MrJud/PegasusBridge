/*
 * pb_wbfs.c — reads a .wbfs container as Dolphin reads it. See pb_wbfs.h.
 *
 * The container, every number big-endian (Dolphin's WbfsBlob, and
 * file-formats.h of Wiimms ISO Tools):
 *
 *   0x000  4  the letters WBFS
 *   0x004  4  how many sectors the container has
 *   0x008  1  log2 of a sector's size
 *   0x009  1  log2 of a block's size
 *   0x00C  1  not 0 when the first slot holds a disc; no other slot is read
 *   S + 0x100     the block table, S being a sector's size: 16 bits for each
 *                 block of the disc, the container block that holds it, or 0
 *                 for a block the container does not hold
 *
 * A container too large for a file system is split into Game.wbfs, Game.wbf1
 * and so on to Game.wbf9, which are one run of bytes end to end.
 *
 * Nothing the container says is used before it has been checked, and every
 * check has a reason of its own. The file is opened, sought and read through
 * the reader rcheevos had put on the iterator, so a path outside ASCII and a
 * file past 4 GiB are handled here exactly as they are for an .iso.
 */

#include "pb_wbfs.h"

#include <inttypes.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The first file and the nine that may follow it. */
#define WBFS_PARTS 10

/*
 * What a Wii disc is said to hold, whatever is on it: two layers of 143432
 * sectors of 0x8000 bytes. It is Dolphin's size for the disc in any .wbfs,
 * and the block table has an entry for each block of it.
 */
#define WBFS_DISC ((uint64_t)286864 * 0x8000)

struct pb_wbfs {
    struct rc_hash_filereader raw; /* the reader for plain files, as rcheevos set it */
    void* part[WBFS_PARTS];
    uint64_t part_size[WBFS_PARTS];
    unsigned parts;
    uint64_t container_size; /* of all the parts together */
    unsigned shift;          /* log2 of a block's size */
    uint64_t block_size;
    uint32_t blocks;         /* entries in the table */
    uint16_t* table;
    uint64_t position;       /* where in the disc rcheevos is */
    uint64_t bytes_read;
    uint64_t bytes_unheld;
    /*
     * One byte that holds nothing. It is here for where it puts the member
     * after it: one byte past a boundary every other member is aligned to,
     * which is where no memory handed out by malloc begins. disc_open
     * refuses a pointer by that.
     */
    char off_the_boundary;
    /*
     * The path the container was opened by, in a copy the container owns.
     * It is the last member, with the room it needs after the rest, and it is
     * how rcheevos' callback for opening a file finds the container: see
     * disc_open. What counts there is its address. The text is the path's
     * because rcheevos reads it: it looks for the file's name in it once it
     * has opened the file, for a message that goes to a callback no caller
     * of ours sets, and no test tells this text from another.
     */
    char path[];
};

/* Held by the compiler and not by the order the members happen to be in. */
_Static_assert(offsetof(struct pb_wbfs, path) % _Alignof(struct pb_wbfs) != 0,
               "the path of a container must not begin where an allocation can");

static void wbfs_close(struct pb_wbfs* wbfs) {
    unsigned part;

    if (!wbfs)
        return;
    for (part = 0; part < wbfs->parts; part++)
        wbfs->raw.close(wbfs->part[part]);
    free(wbfs->table);
    free(wbfs);
}

/* The size of an open file, or a negative number where the system would not say. */
static int64_t raw_size(const struct pb_wbfs* wbfs, void* file) {
    wbfs->raw.seek(file, 0, SEEK_END);
    return wbfs->raw.tell(file);
}

/* Always 0, so that a refusal is one line where it is made. */
static int refuse(char why[PB_WBFS_REASON], const char* format, ...) {
    va_list args;

    va_start(args, format);
    vsnprintf(why, PB_WBFS_REASON, format, args);
    va_end(args);
    return 0;
}

/*
 * Opens the parts that follow the first file until their sizes add up to what
 * the header states. Dolphin names them by putting a digit in place of the
 * path's last byte, and so does this. Dolphin opens every part there is; here
 * one is opened only while the sizes are short, so that a stray .wbf1 beside a
 * container that is whole does not spoil it.
 *
 * A part that is not there is said in rcheevos' own words for a file that
 * could not be opened: it may be one still being copied, and the caller tries
 * such a file again.
 */
static int wbfs_open_parts(struct pb_wbfs* wbfs, const char* path, uint64_t stated,
                           char why[PB_WBFS_REASON]) {
    size_t length = strlen(path);
    char* name;
    int ok = 1;

    if (wbfs->container_size >= stated)
        return 1;
    if (length == 0)
        return refuse(why, "Could not open file");

    name = (char*)malloc(length + 1);
    if (!name)
        return refuse(why, "No memory for the name of a WBFS part");
    memcpy(name, path, length + 1);

    while (ok && wbfs->container_size < stated) {
        void* file;
        int64_t size;

        if (wbfs->parts == WBFS_PARTS) {
            ok = refuse(why, "WBFS: the header says %" PRIu64 " bytes and ten parts hold %" PRIu64,
                        stated, wbfs->container_size);
            break;
        }

        name[length - 1] = (char)('0' + wbfs->parts);
        file = wbfs->raw.open(name);
        if (!file) {
            ok = refuse(why, "Could not open part .wbf%u: the header says %" PRIu64
                             " bytes and %u part(s) hold %" PRIu64,
                        wbfs->parts, stated, wbfs->parts, wbfs->container_size);
            break;
        }
        /* Counted among the parts at once, so that every way out closes it. */
        wbfs->part[wbfs->parts] = file;
        wbfs->part_size[wbfs->parts] = 0;
        wbfs->parts++;

        size = raw_size(wbfs, file);
        if (size < 0)
            ok = refuse(why, "WBFS: the size of part .wbf%u could not be read", wbfs->parts - 1);
        /* Held against what is left to reach the stated size before it is
         * added, so the sum cannot pass it, let alone wrap. */
        else if ((uint64_t)size > stated - wbfs->container_size)
            ok = refuse(why, "WBFS: the parts hold more than the header says");
        else {
            wbfs->part_size[wbfs->parts - 1] = (uint64_t)size;
            wbfs->container_size += (uint64_t)size;
        }
    }

    free(name);
    return ok;
}

/*
 * Reads the block table from the first file, which is where Dolphin reads it
 * from, and holds every entry to the container. Two bytes are made into each
 * entry; the bytes read are never looked at as numbers themselves, so neither
 * the machine's byte order nor its alignment comes into it.
 */
static int wbfs_read_table(struct pb_wbfs* wbfs, uint64_t sector_size, char why[PB_WBFS_REASON]) {
    const uint64_t at = sector_size + 0x100;
    const uint64_t length = (uint64_t)wbfs->blocks * 2;
    unsigned char bytes[4096];
    uint32_t entry = 0;

    if (at + length > wbfs->part_size[0])
        return refuse(why, "WBFS: the block table runs past the end of the file");

    wbfs->table = (uint16_t*)malloc((size_t)wbfs->blocks * sizeof(uint16_t));
    if (!wbfs->table)
        return refuse(why, "No memory for the WBFS block table");

    wbfs->raw.seek(wbfs->part[0], (int64_t)at, SEEK_SET);
    while (entry < wbfs->blocks) {
        size_t wanted = sizeof(bytes) / 2;
        size_t index;

        if (wanted > wbfs->blocks - entry)
            wanted = wbfs->blocks - entry;
        if (wbfs->raw.read(wbfs->part[0], bytes, wanted * 2) != wanted * 2)
            return refuse(why, "WBFS: the block table could not be read whole");
        for (index = 0; index < wanted; index++)
            wbfs->table[entry++] = (uint16_t)((bytes[index * 2] << 8) | bytes[index * 2 + 1]);
    }

    /* A block has to begin inside the container, and need not end there: a
     * last block the file cuts short is a read that comes back short, as it
     * is for Dolphin, and rcheevos refuses the disc for it if it needs the
     * bytes. */
    for (entry = 0; entry < wbfs->blocks; entry++) {
        const uint16_t where = wbfs->table[entry];
        if (where != 0 && ((uint64_t)where << wbfs->shift) >= wbfs->container_size)
            return refuse(why, "WBFS: disc block %" PRIu32 " is said to be at container block %u, past the end",
                          entry, (unsigned)where);
    }
    if (wbfs->table[0] == 0)
        return refuse(why, "WBFS: the first block of the disc is not in the container");
    return 1;
}

/* The checks, in the order they are made. 1, or 0 with the reason. */
static int wbfs_check(struct pb_wbfs* wbfs, const char* path, char why[PB_WBFS_REASON]) {
    unsigned char header[16];
    unsigned sector_shift, block_shift;
    uint32_t sectors;
    uint64_t stated;
    int64_t size;
    void* file;

    /* rcheevos' own words, which the caller knows for a failure to try again. */
    file = wbfs->raw.open(path);
    if (!file)
        return refuse(why, "Could not open file");
    wbfs->part[0] = file;
    wbfs->parts = 1;

    size = raw_size(wbfs, file);
    if (size <= 0)
        return refuse(why, "WBFS: the file is empty");
    wbfs->part_size[0] = (uint64_t)size;
    wbfs->container_size = (uint64_t)size;

    wbfs->raw.seek(file, 0, SEEK_SET);
    if (wbfs->raw.read(file, header, sizeof(header)) != sizeof(header))
        return refuse(why, "WBFS: the file ends inside its header");
    if (memcmp(header, "WBFS", 4) != 0)
        return refuse(why, "WBFS: the file does not begin with the letters WBFS");

    /* Both are held to a range before anything is shifted by them. */
    sector_shift = header[8];
    if (sector_shift < 9 || sector_shift > 16)
        return refuse(why, "WBFS: a sector of 2^%u bytes is not one of 2^9 to 2^16", sector_shift);
    /* 15 is the least Dolphin takes. 30 keeps the largest entry of the table,
     * shifted, far inside 64 bits. */
    block_shift = header[9];
    if (block_shift < 15 || block_shift > 30)
        return refuse(why, "WBFS: a block of 2^%u bytes is not one of 2^15 to 2^30", block_shift);

    sectors = ((uint32_t)header[4] << 24) | ((uint32_t)header[5] << 16)
            | ((uint32_t)header[6] << 8) | header[7];
    if (sectors == 0)
        return refuse(why, "WBFS: the header counts no sectors");
    stated = (uint64_t)sectors << sector_shift; /* 2^48 at most */

    if (header[12] == 0)
        return refuse(why, "WBFS: the container holds no disc");

    /* As Dolphin holds it: the parts add up to what the header states, to
     * the byte. */
    if (wbfs->container_size > stated)
        return refuse(why, "WBFS: the file is %" PRIu64 " bytes and its header says %" PRIu64,
                      wbfs->container_size, stated);
    if (!wbfs_open_parts(wbfs, path, stated, why))
        return 0;

    wbfs->shift = block_shift;
    wbfs->block_size = (uint64_t)1 << block_shift;
    wbfs->blocks = (uint32_t)((WBFS_DISC + wbfs->block_size - 1) >> block_shift); /* 286864 at most */
    return wbfs_read_table(wbfs, (uint64_t)1 << sector_shift, why);
}

/* The container, checked and ready to be read, or NULL with the reason. */
static struct pb_wbfs* wbfs_open(const struct rc_hash_filereader* raw, const char* path,
                                 char why[PB_WBFS_REASON]) {
    const size_t length = strlen(path);
    struct pb_wbfs* wbfs = (struct pb_wbfs*)calloc(1, offsetof(struct pb_wbfs, path) + length + 1);

    if (!wbfs) {
        refuse(why, "No memory for the WBFS container");
        return NULL;
    }
    wbfs->raw = *raw;
    memcpy(wbfs->path, path, length + 1);
    if (!wbfs_check(wbfs, path, why)) {
        wbfs_close(wbfs);
        return NULL;
    }
    return wbfs;
}

/*
 * Reads bytes of the disc from where the position is, as Dolphin's Read and
 * SeekToCluster do, and returns how many it read.
 *
 * A block the container does not hold has 0 in the table, and Dolphin makes
 * no case of it: the bytes are read from container block 0, which is the
 * container's own header and table, at the same place inside the block. That
 * is not what the disc had there, and it is what Dolphin hashes, so it is
 * what is read here. Such bytes are counted.
 *
 * A read that comes back short is never filled up: rcheevos refuses a Wii
 * disc that does not give it every byte it asked for.
 */
static size_t wbfs_read(struct pb_wbfs* wbfs, unsigned char* buffer, size_t wanted) {
    size_t done = 0;

    while (done < wanted && wbfs->position < WBFS_DISC) {
        const uint64_t block = wbfs->position >> wbfs->shift;
        const uint64_t inside = wbfs->position & (wbfs->block_size - 1);
        uint64_t address, part_start = 0, take;
        unsigned part = 0;
        size_t got;

        /* Always so, since the table covers the whole disc; and never taken on trust. */
        if (block >= wbfs->blocks)
            break;
        address = ((uint64_t)wbfs->table[block] << wbfs->shift) + inside;
        if (address >= wbfs->container_size)
            break;

        /* The part the address is in. A block may lie across two parts:
         * files are split at a size that is no whole number of blocks. */
        while (part < wbfs->parts && address - part_start >= wbfs->part_size[part]) {
            part_start += wbfs->part_size[part];
            part++;
        }
        if (part == wbfs->parts)
            break;

        take = wanted - done;
        if (take > WBFS_DISC - wbfs->position)
            take = WBFS_DISC - wbfs->position;
        if (take > wbfs->block_size - inside)
            take = wbfs->block_size - inside;
        if (take > wbfs->part_size[part] - (address - part_start))
            take = wbfs->part_size[part] - (address - part_start);

        wbfs->raw.seek(wbfs->part[part], (int64_t)(address - part_start), SEEK_SET);
        got = wbfs->raw.read(wbfs->part[part], buffer + done, (size_t)take);
        if (got > take) /* no reader gives more than it was asked for */
            got = (size_t)take;

        done += got;
        wbfs->position += got;
        wbfs->bytes_read += got;
        if (wbfs->table[block] == 0)
            wbfs->bytes_unheld += got;
        if (got < take)
            break;
    }
    return done;
}

/* ---- what rcheevos is given in place of its reader for plain files ---- */

/*
 * rcheevos' callback for opening a file is given the path and nothing else,
 * so the container that is being hashed has to reach it through the path. For
 * the one call that hashes it, pb_wbfs_hash has the iterator's path point at
 * the copy the container carries, and the pointer rcheevos hands this
 * function is then that member's address: the container begins a known
 * number of bytes before it. Nothing is kept outside the container, so two
 * files hashed at once know nothing of each other, and nothing is asked of
 * the system: a variable that is each thread's own did the same, and where
 * the compiler has no such variables of its own it makes them out of its
 * threading library, which brought assert() into the DLL.
 *
 * That holds only while rcheevos hands on the very pointer it found on the
 * iterator, and not a copy of the text. It does: rc_hash_wii calls
 * rc_file_open(iterator, iterator->path), and rc_file_open passes its
 * argument to this callback as it is. And only that call can come here.
 * These callbacks are on the iterator from just before rc_hash_generate to
 * just after it, for the Wii's console and no other, and with no buffer on
 * the iterator rcheevos goes from there straight to rc_hash_wii, which opens
 * one file, once; a Wii disc is no playlist and names no second file.
 *
 * What this can tell of a pointer that is not the member is little, and it
 * has to be told before anything is read through it: to look at the
 * container the bytes before the pointer have to be read, and if they are
 * not a container's that read is already the fault; and to know the pointer
 * without reading there, it would have to be kept somewhere two threads
 * share, which is what this is rid of. One thing needs no read. The
 * container is where calloc put it, on a boundary of its type, and its path
 * begins off such a boundary (the assertion under the struct). Memory that
 * malloc, calloc or strdup handed out begins on one, so a copy of the path
 * that rcheevos or anyone made that way would give a container that begins
 * off it, and is refused: rcheevos then says the file could not be opened,
 * and nothing was read. A pointer into the middle of some other text can
 * still fall where the member would, and is taken for it.
 *
 * So the rest is held from outside: vendored_check.py holds rcheevos to the
 * tree these lines were read in, and rahash_cli --handed, which
 * native_repro_test.py runs before any row, has rcheevos hash a Wii disc, a
 * Wii package and a file that does not open, and sees which pointer its open
 * is given and how many times.
 */
static void* RC_CCONV disc_open(const char* path_utf8) {
    if (!path_utf8)
        return NULL;
    if (((uintptr_t)path_utf8 - offsetof(struct pb_wbfs, path)) % _Alignof(struct pb_wbfs) != 0)
        return NULL;
    return (char*)path_utf8 - offsetof(struct pb_wbfs, path);
}

/* The position is kept inside the disc, and worked out without a sum that
 * could pass what a signed number holds. */
static void RC_CCONV disc_seek(void* handle, int64_t offset, int origin) {
    struct pb_wbfs* wbfs = (struct pb_wbfs*)handle;
    uint64_t from;

    switch (origin) {
        case SEEK_SET: from = 0; break;
        case SEEK_CUR: from = wbfs->position; break;
        case SEEK_END: from = WBFS_DISC; break;
        default: return;
    }

    if (offset < 0) {
        const uint64_t back = (uint64_t)(-(offset + 1)) + 1;
        wbfs->position = back > from ? 0 : from - back;
    } else {
        wbfs->position = (uint64_t)offset > WBFS_DISC - from ? WBFS_DISC : from + (uint64_t)offset;
    }
}

static int64_t RC_CCONV disc_tell(void* handle) {
    return (int64_t)((struct pb_wbfs*)handle)->position;
}

static size_t RC_CCONV disc_read(void* handle, void* buffer, size_t requested_bytes) {
    return wbfs_read((struct pb_wbfs*)handle, (unsigned char*)buffer, requested_bytes);
}

/* Nothing: pb_wbfs_hash opened the container and closes it, once, whether
 * rcheevos remembers to close what it opened or not. */
static void RC_CCONV disc_close(void* handle) {
    (void)handle;
}

/* ---- what the header declares ---- */

int pb_wbfs_names(const char* path) {
    static const char extension[] = ".wbfs";
    const size_t length = path ? strlen(path) : 0;
    size_t index;

    /* Before the last five bytes are looked at, there have to be five. */
    if (length < sizeof(extension) - 1)
        return 0;
    path += length - (sizeof(extension) - 1);
    for (index = 0; index < sizeof(extension) - 1; index++) {
        char letter = path[index];
        if (letter >= 'A' && letter <= 'Z')
            letter = (char)(letter - 'A' + 'a');
        if (letter != extension[index])
            return 0;
    }
    return 1;
}

int pb_wbfs_hash(char hash[33], uint32_t console, rc_hash_iterator_t* iterator,
                 struct pb_wbfs_stats* stats, char why[PB_WBFS_REASON]) {
    struct pb_wbfs* wbfs;
    const char* path;
    int found;

    hash[0] = '\0';
    why[0] = '\0';
    stats->bytes_read = 0;
    stats->bytes_unheld = 0;

    if (console != 0 && console != RC_CONSOLE_WII)
        return refuse(why, "A .wbfs holds a Wii disc, and console %" PRIu32 " was asked for", console);

    /* An iterator made for no path has no file to open, in rcheevos' words. */
    path = iterator->path;
    if (!path)
        return refuse(why, "Could not open file");

    wbfs = wbfs_open(&iterator->callbacks.filereader, path, why);
    if (!wbfs)
        return 0;

    iterator->callbacks.filereader.open = disc_open;
    iterator->callbacks.filereader.seek = disc_seek;
    iterator->callbacks.filereader.tell = disc_tell;
    iterator->callbacks.filereader.read = disc_read;
    iterator->callbacks.filereader.close = disc_close;

    /* The same text at the address disc_open knows the container by. There
     * is one way out of this function from here on, and the path rcheevos
     * allocated is back on the iterator before it. */
    iterator->path = wbfs->path;
    found = rc_hash_generate(hash, RC_CONSOLE_WII, iterator);
    iterator->path = path;

    /* The iterator is handed back as it came: whatever is done with it next
     * finds the reader for plain files, and not one whose container is gone,
     * and rc_hash_destroy_iterator frees the path it allocated, and not the
     * middle of a container that has been freed. */
    iterator->callbacks.filereader = wbfs->raw;

    stats->bytes_read = wbfs->bytes_read;
    stats->bytes_unheld = wbfs->bytes_unheld;
    wbfs_close(wbfs);
    return found;
}

void pb_wbfs_answer(char answer[PB_WBFS_ANSWER], const char hash[33],
                    const struct pb_wbfs_stats* stats) {
    snprintf(answer, PB_WBFS_ANSWER, "%s|%d|%" PRIu64 "|%" PRIu64,
             hash, RC_CONSOLE_WII, stats->bytes_read, stats->bytes_unheld);
}
