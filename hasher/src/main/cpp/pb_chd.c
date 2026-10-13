/*
 * pb_chd.c — reads a disc out of a .chd for rcheevos. See pb_chd.h.
 *
 * A .chd of version 5, every number big-endian (MAME's chd.h):
 *
 *     0   8  the letters MComprHD
 *     8   4  the header's length, 124
 *    12   4  the version, 5
 *    16  16  up to four compressors, each by four letters, or 0
 *    32   8  how many bytes the disc is, unpacked
 *    40   8  where the map is
 *    48   8  where the first piece of metadata is
 *    56   4  a hunk's size
 *    60   4  a unit's size: 2448 for a frame of a CD, 2048 for a DVD's sector
 *   104  20  the SHA-1 of a parent image, or zeros
 *
 * The disc is cut into hunks of a whole number of units, and the map says
 * for each hunk where it lies and how it is packed. A CD's tracks are
 * described in pieces of metadata, a line of text for each, and its frames
 * follow each other with every track filled up to a multiple of four.
 *
 * libchdr unpacks the hunks, and is given the file only after everything
 * above has been read here and found to be what this reader takes: it is
 * somebody else's code fed whatever a file says, and the less of a file it
 * is left to judge the better. What it has decoded of the map is gone over
 * once more before any hunk is asked for. The file is opened, sought and
 * read through the reader rcheevos has put on the iterator, by libchdr as
 * well, so a path outside ASCII and a file past 4 GiB are handled here
 * exactly as they are for an .iso.
 *
 * Where a track begins is worked out as RetroAchievements' own hasher does
 * it (RALibretro, src/HashCHD.cpp) and as chdman, which writes the format,
 * lays the frames out (MAME, src/lib/util/cdrom.cpp). The two differ for a
 * track that follows a pregap, and such a track is refused: see place(). The
 * largest track alone, which one console asks for, is read where that hasher
 * reads it and nowhere else: see choose().
 *
 * Nothing here is kept between two calls, and nothing is written that two
 * threads share.
 */

#include "pb_chd.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libchdr/include/libchdr/chd.h"

#define CHD_HEADER 124

/* The largest hunk chdman writes. */
#define CHD_MAX_HUNK_BYTES (1024u * 1024u)

/*
 * libchdr keeps twelve bytes of map for every hunk, for as long as the file
 * is open, so the hunks an image may have are what bounds the memory one
 * open file costs: this many are 64 MiB of map. A DVD of two layers in hunks
 * of one sector, the smallest there are, has about 4.2 million.
 */
#define CHD_MAX_HUNKS 5592405u

#define CD_UNIT 2448
#define DVD_UNIT 2048

/* A CD has no more tracks, and no .chd of one more lines of each kind. */
#define CHD_MAX_TRACKS 99

/* A walk of the metadata reads every piece before the one it is after, so a
 * chain made to be long costs the square of its length in reads. */
#define CHD_MAX_METADATA 512

/* A word of a track's line, with the NUL that ends it. */
#define CHD_WORD 32

#define TAG(a, b, c, d) (((uint32_t)(a) << 24) | ((uint32_t)(b) << 16) | ((uint32_t)(c) << 8) | (uint32_t)(d))

/* The kinds of line that describe a track, in the order they are looked to. */
enum { LINE_CHT2, LINE_CHTR, LINE_CHGD, LINE_KINDS };

struct chd_track {
    uint32_t number;
    char type[CHD_WORD];
    uint64_t frames;
    uint32_t pregap;
    char pregap_type[CHD_WORD];
};

/* The file as libchdr is given it: rcheevos' reader, and what it opened. */
struct chd_source {
    struct rc_hash_filereader reader;
    void* file;
};

/* What a version 5 header says, once it has been found sound. */
struct chd_shape {
    uint32_t hunk_bytes;
    uint32_t unit_bytes;
    uint64_t logical_bytes;
    uint64_t hunks;
    int packed;
};

struct pb_chd {
    /* The iterator the track was opened for, to say through that a hunk
     * could not be read: rcheevos hands a read the track and nothing else.
     * Every function of rcheevos that opens a track closes it before it
     * returns, and the iterator of a playlist's first file is destroyed
     * after that file was hashed, so the iterator outlives the track. */
    const rc_hash_iterator_t* iterator;
    chd_file* chd;
    uint8_t* hunk;       /* one hunk, unpacked */
    uint32_t held;       /* which one, when `holds` */
    int holds;
    int damaged;         /* a hunk could not be read: nothing more is answered */
    uint32_t unit_bytes;
    uint32_t units_per_hunk;
    uint64_t units;
    uint32_t first_sector;
    uint64_t first_frame;
    uint64_t frames;
    uint32_t header;     /* what of a frame comes before the sector's data */
    uint32_t data;       /* how much of a frame is the sector's data */
};

/*
 * One line to the iterator's callback for errors, where the caller keeps its
 * reasons. None of these lines has a semicolon in it, which is what the
 * caller puts between two reasons, and none but the first below says "Could
 * not open": the caller tries a file again that could not be opened.
 */
static void say(const rc_hash_iterator_t* iterator, const char* format, ...) {
    char text[192];
    va_list args;

    if (!iterator->callbacks.error_message)
        return;
    va_start(args, format);
    vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    iterator->callbacks.error_message(text, iterator);
}

static uint32_t be32(const uint8_t* at) {
    return ((uint32_t)at[0] << 24) | ((uint32_t)at[1] << 16) | ((uint32_t)at[2] << 8) | at[3];
}

static uint64_t be48(const uint8_t* at) {
    return ((uint64_t)at[0] << 40) | ((uint64_t)at[1] << 32) | be32(at + 2);
}

static uint64_t be64(const uint8_t* at) {
    return ((uint64_t)be32(at) << 32) | be32(at + 4);
}

/* Whether the path ends in .chd, in either case. */
static int names_chd(const char* path) {
    static const char extension[] = ".chd";
    const size_t length = path ? strlen(path) : 0;
    size_t index;

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

/* ------------------------------------------------------------ the header */

/* The compressors a disc image is packed with. The one left out is the
 * laserdisc's, which unpacks pictures and sound and no sector of a disc. */
static int disc_compressor(uint32_t tag) {
    static const uint32_t known[] = {
        TAG('z', 'l', 'i', 'b'), TAG('l', 'z', 'm', 'a'), TAG('h', 'u', 'f', 'f'),
        TAG('f', 'l', 'a', 'c'), TAG('z', 's', 't', 'd'), TAG('c', 'd', 'z', 'l'),
        TAG('c', 'd', 'l', 'z'), TAG('c', 'd', 'f', 'l'), TAG('c', 'd', 'z', 's'),
    };
    size_t index;

    for (index = 0; index < sizeof(known) / sizeof(known[0]); index++)
        if (known[index] == tag)
            return 1;
    return tag == 0;
}

/* A compressor's four letters, or its number when they are not letters:
 * what a file says is not put into a reason as it stands. */
static void compressor_name(uint32_t tag, char name[16]) {
    int index;

    for (index = 0; index < 4; index++) {
        const char letter = (char)(tag >> (24 - 8 * index));
        if (!((letter >= 'a' && letter <= 'z') || (letter >= '0' && letter <= '9'))) {
            snprintf(name, 16, "0x%08lx", (unsigned long)tag);
            return;
        }
        name[index] = letter;
    }
    name[4] = '\0';
}

/*
 * Reads the header and the place of the map, and says whether this is an
 * image the reader takes. Everything libchdr would work out from the header
 * is held to a bound here first: it allocates the packed map at whatever
 * size the file states, and checks little of a version 5 header itself.
 */
static int sound_header(const struct rc_hash_filereader* reader, void* file,
                        const rc_hash_iterator_t* iterator, struct chd_shape* shape) {
    static const uint8_t no_parent[20] = { 0 };
    uint8_t header[CHD_HEADER];
    uint8_t map[16];
    uint64_t size, map_at, metadata_at;
    int64_t end;
    int index;

    reader->seek(file, 0, SEEK_END);
    end = reader->tell(file);
    if (end < CHD_HEADER) {
        say(iterator, "CHD: the file is shorter than a header");
        return 0;
    }
    size = (uint64_t)end;

    reader->seek(file, 0, SEEK_SET);
    if (reader->read(file, header, sizeof(header)) != sizeof(header)) {
        say(iterator, "CHD: the header could not be read");
        return 0;
    }
    if (memcmp(header, "MComprHD", 8) != 0) {
        say(iterator, "CHD: the file does not begin as a CHD does");
        return 0;
    }
    if (be32(header + 12) != 5) {
        say(iterator, "CHD: version %lu, and only version 5 is read", (unsigned long)be32(header + 12));
        return 0;
    }
    if (be32(header + 8) != CHD_HEADER) {
        say(iterator, "CHD: a header of %lu bytes, where version 5 has %d",
            (unsigned long)be32(header + 8), CHD_HEADER);
        return 0;
    }
    for (index = 0; index < 4; index++) {
        const uint32_t tag = be32(header + 16 + 4 * index);
        if (!disc_compressor(tag)) {
            char name[16];
            compressor_name(tag, name);
            say(iterator, "CHD: compressor %s is not one a disc image uses", name);
            return 0;
        }
    }
    if (memcmp(header + 104, no_parent, sizeof(no_parent)) != 0) {
        say(iterator, "CHD: it is a difference against a parent CHD, and parents are not read");
        return 0;
    }

    shape->packed = be32(header + 16) != 0;
    shape->logical_bytes = be64(header + 32);
    shape->hunk_bytes = be32(header + 56);
    shape->unit_bytes = be32(header + 60);
    if (shape->unit_bytes != CD_UNIT && shape->unit_bytes != DVD_UNIT) {
        say(iterator, "CHD: units of %lu bytes, where a disc has %d or %d",
            (unsigned long)shape->unit_bytes, CD_UNIT, DVD_UNIT);
        return 0;
    }
    if (shape->hunk_bytes < shape->unit_bytes || shape->hunk_bytes > CHD_MAX_HUNK_BYTES
            || shape->hunk_bytes % shape->unit_bytes != 0) {
        say(iterator, "CHD: hunks of %lu bytes, which is not a whole number of units up to 1 MiB",
            (unsigned long)shape->hunk_bytes);
        return 0;
    }
    if (shape->logical_bytes == 0 || shape->logical_bytes % shape->unit_bytes != 0) {
        say(iterator, "CHD: a disc of %llu bytes, which is not a whole number of units",
            (unsigned long long)shape->logical_bytes);
        return 0;
    }
    /* A quotient and one more for a remainder: the sum libchdr rounds up
     * with wraps for a size near the top of 64 bits. */
    shape->hunks = shape->logical_bytes / shape->hunk_bytes
                 + (shape->logical_bytes % shape->hunk_bytes ? 1 : 0);
    if (shape->hunks > CHD_MAX_HUNKS) {
        say(iterator, "CHD: %llu hunks, and no more than %lu are read",
            (unsigned long long)shape->hunks, (unsigned long)CHD_MAX_HUNKS);
        return 0;
    }

    /* The sums below cannot wrap: the size is that of a file, and the map's
     * place is held under it before anything is added to it. */
    map_at = be64(header + 40);
    if (map_at < CHD_HEADER || map_at >= size) {
        say(iterator, "CHD: its map runs past the end of the file");
        return 0;
    }
    if (!shape->packed) {
        /* Four bytes a hunk, and something after them: libchdr wants the
         * map to end before the file does. */
        if (4 * shape->hunks >= size - map_at) {
            say(iterator, "CHD: its map runs past the end of the file");
            return 0;
        }
    } else {
        uint64_t map_bytes;

        if (size - map_at < sizeof(map)) {
            say(iterator, "CHD: its map runs past the end of the file");
            return 0;
        }
        reader->seek(file, (int64_t)map_at, SEEK_SET);
        if (reader->read(file, map, sizeof(map)) != sizeof(map)) {
            say(iterator, "CHD: its map could not be read");
            return 0;
        }
        /* A packed map has at most seven bytes for a hunk, and a few for the
         * table its codes are read by. */
        map_bytes = be32(map);
        if (map_bytes > 12 * shape->hunks + 256 || map_bytes > size - map_at - sizeof(map)) {
            say(iterator, "CHD: its map runs past the end of the file");
            return 0;
        }
    }

    metadata_at = be64(header + 48);
    if (metadata_at >= size) {
        say(iterator, "CHD: its metadata begins past the end of the file");
        return 0;
    }
    return 1;
}

/* ------------------------------------------------- the file, for libchdr */

static uint64_t source_size(void* opaque) {
    struct chd_source* source = (struct chd_source*)opaque;
    int64_t end;

    source->reader.seek(source->file, 0, SEEK_END);
    end = source->reader.tell(source->file);
    return end < 0 ? (uint64_t)-1 : (uint64_t)end;
}

static size_t source_read(void* buffer, size_t size, size_t count, void* opaque) {
    struct chd_source* source = (struct chd_source*)opaque;

    if (size == 0 || count > (size_t)-1 / size)
        return 0;
    return source->reader.read(source->file, buffer, size * count) / size;
}

static int source_seek(void* opaque, int64_t offset, int origin) {
    struct chd_source* source = (struct chd_source*)opaque;

    source->reader.seek(source->file, offset, origin);
    return 0;
}

static int source_close(void* opaque) {
    struct chd_source* source = (struct chd_source*)opaque;

    source->reader.close(source->file);
    free(source);
    return 0;
}

static const core_file_callbacks SOURCE = { source_size, source_read, source_close, source_seek };

/* ---------------------------------------------------------------- the map */

/*
 * Goes over the map libchdr has decoded, twelve bytes a hunk, before any
 * hunk is asked for. A hunk may be a copy of an earlier one, and libchdr
 * follows such a copy by calling itself: it holds the hunk named to be an
 * earlier one, and not to be something other than a copy in its turn. A map
 * of a few hundred bytes can so describe a chain millions of hunks long, and
 * reading its last hunk ends the process for want of stack. Here a copy has
 * to be of a hunk that holds data.
 */
static int sound_map(const chd_header* header, const struct chd_shape* shape,
                     const rc_hash_iterator_t* iterator) {
    uint64_t hunk;

    if (!shape->packed)
        return 1;
    if (!header->rawmap) {
        say(iterator, "CHD: its map was not decoded");
        return 0;
    }
    for (hunk = 0; hunk < shape->hunks; hunk++) {
        const uint8_t* entry = header->rawmap + 12 * hunk;
        const unsigned kind = entry[0];

        if (kind <= 3) {
            if (header->compression[kind] == 0) {
                say(iterator, "CHD: its map packs hunk %llu with a compressor the header does not name",
                    (unsigned long long)hunk);
                return 0;
            }
        } else if (kind == 5) {
            const uint64_t copied = be48(entry + 4);
            if (copied >= hunk || header->rawmap[12 * copied] == 5) {
                say(iterator, "CHD: its map makes hunk %llu a copy of one that is not an earlier hunk of data",
                    (unsigned long long)hunk);
                return 0;
            }
        } else if (kind == 6) {
            say(iterator, "CHD: its map takes hunk %llu from a parent CHD, and parents are not read",
                (unsigned long long)hunk);
            return 0;
        } else if (kind != 4) {
            say(iterator, "CHD: its map gives hunk %llu a kind no map has", (unsigned long long)hunk);
            return 0;
        }
    }
    return 1;
}

/* ------------------------------------------------------------- the tracks */

/* Takes `expected` off the front of the text, or says it is not there. */
static int literal(const char** text, const char* expected) {
    const size_t length = strlen(expected);

    if (strncmp(*text, expected, length) != 0)
        return 0;
    *text += length;
    return 1;
}

/* A number of one to nine digits, which is one that fits. */
static int number(const char** text, uint32_t* value) {
    const char* at = *text;
    uint32_t sum = 0;
    int digits = 0;

    while (*at >= '0' && *at <= '9' && digits < 10) {
        sum = sum * 10 + (uint32_t)(*at - '0');
        at++;
        digits++;
    }
    if (digits < 1 || digits > 9)
        return 0;
    *text = at;
    *value = sum;
    return 1;
}

/* A word of 1 to 31 characters, up to the space or the end after it. */
static int word(const char** text, char out[CHD_WORD]) {
    const char* at = *text;
    size_t length = 0;

    while (*at > ' ' && *at < 0x7f && length < CHD_WORD) {
        at++;
        length++;
    }
    if (length < 1 || length >= CHD_WORD || (*at != ' ' && *at != '\0'))
        return 0;
    memcpy(out, *text, length);
    out[length] = '\0';
    *text = at;
    return 1;
}

/*
 * One track's line, in the three forms chdman writes:
 *
 *   CHTR  TRACK:%d TYPE:%s SUBTYPE:%s FRAMES:%d
 *   CHT2  the same, then  PREGAP:%d PGTYPE:%s PGSUB:%s POSTGAP:%d
 *   CHGD  TRACK:%d TYPE:%s SUBTYPE:%s FRAMES:%d PAD:%d PREGAP:%d PGTYPE:%s
 *         PGSUB:%s POSTGAP:%d
 *
 * The fields are read in that order and each has to be there, with a bound
 * on every one. What follows the last is not looked at: the other readers
 * of this format take a line with sscanf, which stops where its pattern
 * does, and a field chdman comes to add at the end is not to turn every new
 * image into one that is refused here and hashed everywhere else. A field
 * the form does not have is 0.
 */
static int track_line(int kind, const char* text, struct chd_track* track) {
    char unused[CHD_WORD];
    uint32_t frames, other;

    memset(track, 0, sizeof(*track));
    if (!literal(&text, "TRACK:") || !number(&text, &track->number)
            || !literal(&text, " TYPE:") || !word(&text, track->type)
            || !literal(&text, " SUBTYPE:") || !word(&text, unused)
            || !literal(&text, " FRAMES:") || !number(&text, &frames))
        return 0;
    track->frames = frames;
    if (kind == LINE_CHGD && (!literal(&text, " PAD:") || !number(&text, &other)))
        return 0;
    if (kind != LINE_CHTR
            && (!literal(&text, " PREGAP:") || !number(&text, &track->pregap)
                || !literal(&text, " PGTYPE:") || !word(&text, track->pregap_type)
                || !literal(&text, " PGSUB:") || !word(&text, unused)
                || !literal(&text, " POSTGAP:") || !number(&text, &other)))
        return 0;
    if (*text != ' ' && *text != '\0')
        return 0;
    return track->number >= 1 && track->number <= CHD_MAX_TRACKS;
}

/*
 * Reads what the image says of its tracks into `tracks`, which has room for
 * CHD_MAX_TRACKS, and returns how many there are; 0, having said why, when
 * the image is refused.
 *
 * The track at each place is the line of kind CHT2 at that place when there
 * is one, else the CHTR one, else the CHGD one, which is the order
 * RetroAchievements' hasher asks for them in. A disc with no such line and
 * the mark of a DVD is one track of plain sectors, the whole image. An image
 * with only the older kind of line for a GD-ROM (CHGT) is refused with the
 * images that describe no disc, as it is by that hasher.
 *
 * libchdr hands back a piece of metadata by its place in the chain and
 * walks the chain from its start each time, without ending the text, so the
 * pieces are bounded in number and each is ended here.
 */
static size_t read_tracks(chd_file* chd, const struct chd_shape* shape,
                          const rc_hash_iterator_t* iterator, struct chd_track* tracks) {
    static const uint32_t tags[LINE_KINDS] = {
        TAG('C', 'H', 'T', '2'), TAG('C', 'H', 'T', 'R'), TAG('C', 'H', 'G', 'D'),
    };
    struct chd_track* lines;
    size_t counts[LINE_KINDS] = { 0 };
    size_t count = 0;
    uint64_t frames = 0;
    uint32_t piece;
    int dvd = 0;
    int kind;

    lines = (struct chd_track*)malloc(LINE_KINDS * CHD_MAX_TRACKS * sizeof(*lines));
    if (!lines) {
        say(iterator, "CHD: no memory for the tracks");
        return 0;
    }

    for (piece = 0; ; piece++) {
        char text[256];
        uint32_t length = 0, tag = 0;
        const chd_error error =
            chd_get_metadata(chd, CHDMETATAG_WILDCARD, piece, text, sizeof(text) - 1, &length, &tag, NULL);

        if (error == CHDERR_METADATA_NOT_FOUND)
            break;
        if (error != CHDERR_NONE) {
            say(iterator, "CHD: its metadata could not be read: %s", chd_error_string(error));
            goto refused;
        }
        if (piece >= CHD_MAX_METADATA) {
            say(iterator, "CHD: more than %d pieces of metadata", CHD_MAX_METADATA);
            goto refused;
        }
        text[length < sizeof(text) - 1 ? length : sizeof(text) - 1] = '\0';

        if (tag == TAG('D', 'V', 'D', ' '))
            dvd = 1;
        for (kind = 0; kind < LINE_KINDS; kind++) {
            if (tag != tags[kind])
                continue;
            if (counts[kind] >= CHD_MAX_TRACKS) {
                say(iterator, "CHD: more than %d tracks", CHD_MAX_TRACKS);
                goto refused;
            }
            if (!track_line(kind, text, &lines[kind * CHD_MAX_TRACKS + counts[kind]])) {
                say(iterator, "CHD: track metadata %lu is not in the form chdman writes",
                    (unsigned long)(counts[kind] + 1));
                goto refused;
            }
            counts[kind]++;
        }
    }

    for (count = 0; count < CHD_MAX_TRACKS; count++) {
        for (kind = 0; kind < LINE_KINDS; kind++)
            if (count < counts[kind])
                break;
        if (kind == LINE_KINDS)
            break;
        tracks[count] = lines[kind * CHD_MAX_TRACKS + count];
        frames += tracks[count].frames;
    }

    if (count > 0) {
        if (shape->unit_bytes != CD_UNIT) {
            say(iterator, "CHD: the tracks of a CD in units of %lu bytes, where a CD has %d",
                (unsigned long)shape->unit_bytes, CD_UNIT);
            goto refused;
        }
        if (frames > shape->logical_bytes / shape->unit_bytes) {
            say(iterator, "CHD: its tracks have more frames than the image holds");
            goto refused;
        }
    } else if (dvd) {
        if (shape->unit_bytes != DVD_UNIT) {
            say(iterator, "CHD: a DVD in units of %lu bytes, where a DVD has %d",
                (unsigned long)shape->unit_bytes, DVD_UNIT);
            goto refused;
        }
        memset(&tracks[0], 0, sizeof(tracks[0]));
        tracks[0].number = 1;
        strcpy(tracks[0].type, "MODE1");
        tracks[0].frames = shape->logical_bytes / shape->unit_bytes;
        count = 1;
    } else {
        say(iterator, "CHD: its metadata describes neither a CD nor a DVD");
    }

    free(lines);
    return count;

refused:
    free(lines);
    return 0;
}

/* How many frames chdman adds to a track of this many to make it a multiple of four. */
static uint64_t padding(uint64_t frames) {
    return ((frames + 3) & ~(uint64_t)3) - frames;
}

/*
 * Where the track at `index` begins: the number its first sector has on the
 * disc, and the frame of the image that sector is in. Returns 0 when the
 * frame is not one both readings below give.
 *
 * RetroAchievements' hasher counts, for every earlier track, its pregap, its
 * frames and its padding, and then the track's own pregap, whatever kind of
 * pregap it is. chdman lays out, for every earlier track, its frames and its
 * padding, in which a pregap that is in the image is counted already and one
 * that is not takes no room; and a track's own pregap lies ahead of its
 * first sector only when it is in the image, which a PGTYPE that begins
 * with V says.
 *
 * The two agree for a first track with no pregap, and for a track with a
 * pregap of its own that is in the image when no earlier track has any.
 * Where they differ, RetroAchievements' hasher reads some other frame than
 * the one the disc has there, and the hash it sends is either that of a
 * wrong place or none. Which of the two RetroAchievements lists for such a
 * disc is not known, so no hash is given that only one of them would give.
 */
static int place(const struct chd_track* tracks, size_t index, uint32_t* first_sector, uint64_t* first_frame) {
    uint64_t sector = 0, theirs = 0, written = 0;
    size_t earlier;

    for (earlier = 0; earlier < index; earlier++) {
        const uint64_t stored = tracks[earlier].frames + padding(tracks[earlier].frames);
        sector += tracks[earlier].frames;
        theirs += tracks[earlier].pregap + stored;
        written += stored;
    }
    theirs += tracks[index].pregap;
    if (tracks[index].pregap_type[0] == 'V')
        written += tracks[index].pregap;

    *first_sector = (uint32_t)sector;
    *first_frame = theirs;
    return theirs == written;
}

/*
 * Makes sure the hunk is the one in the buffer. One that cannot be read is
 * said once, in the words pb_chd_is_damage knows, and the track answers
 * nothing from then on: rcheevos takes a short read for the end of what it
 * is hashing and would answer the hash of what came before.
 */
static int hold(struct pb_chd* track, uint32_t hunk) {
    chd_error error;

    if (track->holds && track->held == hunk)
        return 1;
    track->holds = 0;
    error = chd_read(track->chd, hunk, track->hunk);
    if (error != CHDERR_NONE) {
        track->damaged = 1;
        say(track->iterator, "CHD: hunk %lu could not be read: %s", (unsigned long)hunk, chd_error_string(error));
        return 0;
    }
    track->held = hunk;
    track->holds = 1;
    return 1;
}

/*
 * A read of more than one sector's data goes on into the frames that follow,
 * to the end of the image, as RetroAchievements' hasher does; only the Jaguar
 * CD's hash asks for one. A sector outside the track is not read, where
 * that hasher lets the one just past the track through: it is outside the
 * track on any reading.
 */
static size_t pb_chd_read_sector(void* handle, uint32_t sector, void* buffer, size_t requested) {
    struct pb_chd* track = (struct pb_chd*)handle;
    uint8_t* out = (uint8_t*)buffer;
    size_t done = 0;
    uint64_t frame;

    if (!track || track->damaged || sector < track->first_sector)
        return 0;
    if (sector - track->first_sector >= track->frames)
        return 0;
    frame = track->first_frame + (sector - track->first_sector);

    while (requested > 0 && frame < track->units) {
        const size_t offset = (size_t)(frame % track->units_per_hunk) * track->unit_bytes + track->header;
        const size_t take = requested < track->data ? requested : track->data;

        if (!hold(track, (uint32_t)(frame / track->units_per_hunk)))
            break;
        memcpy(out + done, track->hunk + offset, take);
        done += take;
        requested -= take;
        frame++;
    }
    return done;
}

static uint32_t pb_chd_first_track_sector(void* handle) {
    const struct pb_chd* track = (const struct pb_chd*)handle;

    return track ? track->first_sector : 0;
}

/* libchdr closes the file with the image, through the callback it was given. */
static void pb_chd_close_track(void* handle) {
    struct pb_chd* track = (struct pb_chd*)handle;

    if (!track)
        return;
    chd_close(track->chd);
    free(track->hunk);
    free(track);
}

/*
 * What of a frame is the sector's data, by the track's kind. For the kinds
 * that do not say it, sector 16 is looked at, where a disc with files has
 * its volume descriptor: the letters CD001 lie one byte into a sector's
 * data, so where they are found says what comes before the data. The cases
 * and their order are those of RetroAchievements' hasher.
 */
static int sector_kind(struct pb_chd* track, const struct chd_track* line) {
    static const uint8_t sync[12] = { 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0 };
    uint8_t start[32];

    track->header = 0;
    if (strcmp(line->type, "MODE1_RAW") == 0) {
        track->header = 16;
        track->data = 2048;
        return 1;
    }
    if (strcmp(line->type, "MODE1") == 0) {
        track->data = 2048;
        return 1;
    }
    if (strcmp(line->type, "AUDIO") == 0) {
        track->data = 2352;
        return 1;
    }

    track->data = strcmp(line->type, "MODE2_RAW") == 0 ? 2336 : 2352;
    if (pb_chd_read_sector(track, track->first_sector + 16, start, sizeof(start)) != sizeof(start)) {
        if (!track->damaged)
            say(track->iterator, "CHD: track %lu has no sector 16 to tell the kind of its sectors by",
                (unsigned long)line->number);
        return 0;
    }
    if (memcmp(start + 25, "CD001", 5) == 0) {
        track->header = 24;
        track->data = (start[18] & 0x20) ? 2324 : 2048;
    } else if (memcmp(start + 17, "CD001", 5) == 0) {
        track->header = 16;
        track->data = (start[15] & 3) == 1 ? 2048 : 2336;
    } else if (memcmp(start + 1, "CD001", 5) == 0) {
        track->header = 0;
        track->data = 2048;
    } else if (memcmp(start, sync, sizeof(sync)) == 0) {
        track->header = 16;
        track->data = (start[15] & 3) == 1 ? 2048 : 2336;
    } else {
        track->header = 0;
        track->data = 2048;
    }
    return 1;
}

/*
 * Which track is asked for, as RetroAchievements' hasher chooses: the one of
 * that number; or, going through them in order, the first that is not audio
 * when the first with data is asked for; or the last. Returns the place among
 * `tracks` of the track whose kind and length are used, or -1 having said why
 * not, and puts in `where` the place of the track that is read where it lies.
 * The two are one track for every request but the largest.
 *
 * For the largest, that hasher takes the kind and the length of the largest
 * track that is not audio, and reads them at the first sector and the first
 * frame of the last track: it goes through the lines keeping where each
 * lies, and then reads the largest one's line again over the last one's
 * without its place. A track is the largest when it has more frames than any
 * before it, counted from none, so with no track of data it is the first
 * line. RAHasher 1.8.4 was run on discs made for it, and that is the read it
 * makes. Only the PC-FX's hash asks for the largest track, and when the
 * console's name is not found there rcheevos opens track 2 by its number,
 * which is where a disc with audio around its data has it. So the request is
 * answered with that same read, whatever it lands on: it is that hasher's
 * read and no track of the disc, and nothing is asked of where it lies. A
 * disc whose data is its first track of several gets no hash by it, and one
 * with two tracks of the console's the hash of the last.
 */
static int choose(const struct chd_track* tracks, size_t count, uint32_t wanted,
                  const rc_hash_iterator_t* iterator, size_t* where) {
    size_t index, largest = 0;
    uint64_t most = 0;

    if (wanted == RC_HASH_CDTRACK_FIRST_OF_SECOND_SESSION)
        wanted = 2;
    for (index = 0; index < count; index++) {
        *where = index;
        if (tracks[index].number == wanted)
            return (int)index;
        if (strcmp(tracks[index].type, "AUDIO") == 0)
            continue;
        if (wanted == RC_HASH_CDTRACK_FIRST_DATA)
            return (int)index;
        if (tracks[index].frames > most) {
            most = tracks[index].frames;
            largest = index;
        }
    }
    *where = count - 1;
    if (wanted == RC_HASH_CDTRACK_LAST)
        return (int)count - 1;
    if (wanted == RC_HASH_CDTRACK_LARGEST)
        return (int)largest;
    say(iterator, "CHD: it has no track %ld", (long)(int32_t)wanted);
    return -1;
}

/*
 * The track of an image libchdr has open, or NULL having said why not. The
 * image is closed here when it is refused, and belongs to the track when it
 * is not.
 */
static struct pb_chd* open_track(chd_file* chd, const struct chd_shape* shape, uint32_t wanted,
                                 const rc_hash_iterator_t* iterator) {
    const chd_header* header = chd_get_header(chd);
    struct chd_track* tracks = NULL;
    struct pb_chd* track = NULL;
    size_t count, where = 0;
    int index, agreed;

    /* What libchdr read of the header is what was checked: the file is read
     * twice, and one still being copied may not be the same both times. */
    if (!header || header->version != 5 || header->hunkbytes != shape->hunk_bytes
            || header->unitbytes != shape->unit_bytes || header->logicalbytes != shape->logical_bytes
            || header->hunkcount != shape->hunks || (header->compression[0] != 0) != shape->packed) {
        say(iterator, "CHD: the header was not the same when it was read again");
        goto refused;
    }
    if (!sound_map(header, shape, iterator))
        goto refused;

    tracks = (struct chd_track*)malloc(CHD_MAX_TRACKS * sizeof(*tracks));
    track = (struct pb_chd*)calloc(1, sizeof(*track));
    if (!tracks || !track) {
        say(iterator, "CHD: no memory for the tracks");
        goto refused;
    }
    count = read_tracks(chd, shape, iterator, tracks);
    if (count == 0)
        goto refused;
    index = choose(tracks, count, wanted, iterator, &where);
    if (index < 0)
        goto refused;

    track->iterator = iterator;
    track->chd = chd;
    track->unit_bytes = shape->unit_bytes;
    track->units_per_hunk = shape->hunk_bytes / shape->unit_bytes;
    track->units = shape->logical_bytes / shape->unit_bytes;
    track->frames = tracks[index].frames;
    /* The largest track is read where RetroAchievements' hasher reads it,
     * which is that hasher's frame of the last track whether the image has
     * the track there or not. A frame past the image is read as nothing. */
    agreed = place(tracks, where, &track->first_sector, &track->first_frame);
    if (!agreed && wanted != RC_HASH_CDTRACK_LARGEST) {
        say(iterator, "CHD: track %lu lies after a pregap that RetroAchievements' hasher and chdman "
                      "place differently, and is not hashed", (unsigned long)tracks[index].number);
        goto refused;
    }
    track->hunk = (uint8_t*)malloc(shape->hunk_bytes);
    if (!track->hunk) {
        say(iterator, "CHD: no memory for a hunk");
        goto refused;
    }
    if (!sector_kind(track, &tracks[index]))
        goto refused;
    /* Before anything is copied out of a frame by these two. */
    if (track->header + track->data > track->unit_bytes) {
        say(iterator, "CHD: track %lu has sectors of a kind that does not fit a unit of %lu bytes",
            (unsigned long)tracks[index].number, (unsigned long)track->unit_bytes);
        goto refused;
    }

    free(tracks);
    return track;

refused:
    free(tracks);
    if (track)
        free(track->hunk);
    free(track);
    chd_close(chd);
    return NULL;
}

/*
 * rcheevos' callback for opening a track. A path that is not a .chd goes to
 * rcheevos' own reader, whose other three functions are still on the
 * iterator. For a .chd the three of this file are put there: rcheevos asks
 * the iterator for them with every read, and the iterator it hands over as
 * one not to be changed is the caller's own, or the one rcheevos made for a
 * playlist's first file, and is written to nowhere else while a track is
 * open. RetroArch puts its reader of .chd in the same way.
 */
static void* pb_chd_open_track(const char* path, uint32_t wanted, const rc_hash_iterator_t* iterator) {
    const struct rc_hash_filereader* reader = &iterator->callbacks.filereader;
    rc_hash_iterator_t* changed = (rc_hash_iterator_t*)iterator;
    struct chd_source* source;
    struct chd_shape shape;
    struct pb_chd* track;
    chd_file* chd = NULL;
    chd_error error;
    void* file;

    if (!names_chd(path)) {
        struct rc_hash_cdreader standard;
        rc_hash_get_default_cdreader(&standard);
        return standard.open_track_iterator(path, wanted, iterator);
    }

    if (!reader->open || !reader->seek || !reader->tell || !reader->read || !reader->close) {
        say(iterator, "CHD: the iterator has no reader of files");
        return NULL;
    }
    file = reader->open(path);
    if (!file) {
        /* rcheevos' words for it, which the caller knows a file by that may
         * be there the next time it is asked for. */
        say(iterator, "Could not open file");
        return NULL;
    }
    source = (struct chd_source*)malloc(sizeof(*source));
    if (!source || !sound_header(reader, file, iterator, &shape)) {
        if (!source)
            say(iterator, "CHD: no memory for the file");
        free(source);
        reader->close(file);
        return NULL;
    }
    source->reader = *reader;
    source->file = file;

    /* From here the file is libchdr's, which closes it when the open fails
     * as it does when the image is closed. */
    error = chd_open_core_file_callbacks(&SOURCE, source, CHD_OPEN_READ, NULL, &chd);
    if (error != CHDERR_NONE) {
        say(iterator, "CHD: libchdr does not take the file: %s", chd_error_string(error));
        return NULL;
    }

    track = open_track(chd, &shape, wanted, iterator);
    if (!track)
        return NULL;
    changed->callbacks.cdreader.read_sector = pb_chd_read_sector;
    changed->callbacks.cdreader.close_track = pb_chd_close_track;
    changed->callbacks.cdreader.first_track_sector = pb_chd_first_track_sector;
    return track;
}

void pb_chd_install(rc_hash_iterator_t* iterator) {
    struct rc_hash_cdreader standard;

    rc_hash_get_default_cdreader(&standard);
    if (iterator->callbacks.cdreader.open_track_iterator == standard.open_track_iterator)
        iterator->callbacks.cdreader.open_track_iterator = pb_chd_open_track;
}

int pb_chd_is_damage(const char* message) {
    static const char begins[] = "CHD: hunk ";

    return message && strncmp(message, begins, sizeof(begins) - 1) == 0;
}
