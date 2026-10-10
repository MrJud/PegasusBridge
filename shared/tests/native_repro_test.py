#!/usr/bin/env python3
"""Runs rcheevos on files it must hash, and on files made to hurt it, outside
any JVM and under a timeout.

    ./tests/native_repro_test.py [tools-dir]

tools-dir is what `native/build.sh <out> --tools=<tools-dir>` wrote: rahash_cli,
the hasher's sources with a main() in place of the JNI file, compiled as the
library is, and rahash_cli_sanitized, the same with the address and
undefined-behaviour sanitizers. With no argument both are built first, into a
temporary directory, which needs JAVA_HOME as build.sh does.

Why a program and not a test of the library: a disc image whose tables say
more than the file holds ended rcheevos with a signal, and one kind left it
reading for ever. Those are patched (rcheevos-patches/PATCHES.md, beside the
sources), and the next file that does either is not known yet. In a Gradle
test worker the first takes the worker with it and the second holds the build
until its job is killed. Here each file is one process, killed after ten
seconds, and what it did is a line of the output.

Every file is made here, from the few bytes that matter and filler; none is a
game. Two kinds of row:

  must pass   The answer is known before the program runs: the MD5 this script
              works out from the bytes by the rule for that console, or no hash
              and a reason. Run on both builds, and given five seconds,
              where each takes a few thousandths of one: a file that is
              refused is to be refused at once. Where another line of rcheevos
              would refuse the file too, the row also says what the reason
              has to say. On the sanitized build a report fails the row
              whatever else it printed. A sanitizer left to itself prints its
              report and exits 1, which is what a refusal looks like, and the
              undefined-behaviour one prints and carries on; so both are told
              to exit 97, and 97 or a report on stderr is a failure.

  known gap   A file the vendored rcheevos is known to crash on, hang on or
              hash when it should refuse. Run on the plain build only, and the
              row holds as long as the program does anything but refuse
              cleanly: a signal, a timeout or a hash, whichever, and the line
              says which. Which signal is not pinned, since that is the
              allocator's and the kernel's business. The day a row here ends
              with exit 1 and a reason the gap is closed, the row fails, and
              whoever closed it moves it to the rows that must pass. There is
              one at present, and it is about a path and not a file: one that
              ends in a separator, hashed as an arcade set. The library's JNI
              file turns such a path away before rcheevos is asked, which
              this program, being rcheevos with nothing in front of it, does
              not. The kind is also for the next defect that is found before
              its patch is written, and for an upgrade of rcheevos that
              brings one.

Before any row, the sanitized build is given two faults of its own making
(rahash_cli --fault) and has to report each. "No report" from a program built
without a sanitizer is true of every file there is, and nothing else in a run
tells the two builds apart: every row that must pass ends the same on both.
If either report is missing no row is run, and the status is 2.
"""

import hashlib
import os
import re
import resource
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent

TIMEOUT_SECONDS = 10

# What a row that must pass is given. The ten seconds above are for the known
# gaps, where still running at the end is one of the ways a gap shows.
MUST_PASS_SECONDS = 5

# The exit status both sanitizers are told to end with, so that a report cannot
# pass for rahash_cli's own "no hash", which is 1.
SANITIZER_EXIT = 97
SANITIZER_ENV = {
    "ASAN_OPTIONS": f"exitcode={SANITIZER_EXIT}:abort_on_error=0",
    "UBSAN_OPTIONS": f"halt_on_error=1:exitcode={SANITIZER_EXIT}",
}
SANITIZER_WORDS = ("AddressSanitizer", "LeakSanitizer", "runtime error:")

# What rahash_cli --fault=<kind> does on purpose, and the word of the report it
# must draw: each is seen by one of the two sanitizers and not by the other.
FAULTS = (
    ("address", "AddressSanitizer", "a read one byte past the end of a block"),
    ("undefined", "runtime error:", "a sum too large for an int"),
)

HASH_LINE = re.compile(r"[0-9a-f]{32}\|[0-9]+")

# What a row that must pass expects when there is no hash to expect.
REFUSED = "refused"


def refused(saying):
    """No hash, and a reason with these words in it. For a file that another
    line would refuse too if the one the row is for were gone: what is said
    is then all that tells the two apart."""
    return (REFUSED, saying)


def md5(data):
    return hashlib.md5(data).hexdigest()


def noise(size, seed):
    """Filler that is the same on every run and looks like no header."""
    return hashlib.shake_256(f"native-repro-{seed}".encode()).digest(size)


def put(image, at, data):
    image[at:at + len(data)] = data


# ------------------------------------------------------------------ fixtures

def ines():
    """An iNES file: the 16-byte header, one 16 KiB PRG bank, one 8 KiB CHR."""
    header = bytearray(16)
    put(header, 0, b"NES\x1a\x01\x01")
    return bytes(header) + noise(16 * 1024 + 8 * 1024, seed="nes")


def sega_cd_track(sectors=32):
    """A Sega CD data track as a dump holds it: raw MODE1/2352 sectors, each
    with its sync pattern, its address after the two-second lead-in and mode 1,
    sector 0 opening with the disc and ROM headers rcheevos knows it by."""
    def bcd(n):
        return (n // 10) << 4 | n % 10

    track = bytearray(sectors * 2352)
    for s in range(sectors):
        at = s * 2352
        put(track, at + 1, b"\xff" * 10)
        lba = s + 150
        put(track, at + 12, bytes([bcd(lba // 4500), bcd(lba // 75 % 60), bcd(lba % 75), 1]))
        put(track, at + 16, noise(2048, seed=f"segacd-{s}"))
    put(track, 16, b"SEGADISCSYSTEM  REPROTEST ")
    put(track, 16 + 0x100, b"SEGA MEGA DRIVE ")
    return bytes(track)


def gdi(track_line):
    """A .gdi sheet of three tracks of which only the third has a line. Track 3
    is the one a Dreamcast disc is first asked for, so the line is read whether
    the track is asked for by its number or as the first with data."""
    return b"3\n" + track_line


DREAMCAST_FIRST_SECTOR = 45000


def dreamcast_track():
    """A Dreamcast data track as a .gdi sheet names one, and what a disc made
    of it hashes to. 24 cooked sectors of 2048 bytes, the first of them sector
    45000 of the disc, which is where the sheet says the track begins and what
    every sector number inside it is counted from: the boot record with the
    console's name and, 96 bytes in, the name of the program to start; the
    volume descriptor in sector 16; a directory of one file in sector 18; and
    the program in sectors 20 and 21. The hash is of the boot record's first
    256 bytes and then the program."""
    program = noise(3000, seed="dreamcast-program")
    track = bytearray(24 * 2048)

    boot = bytearray(b" " * 256)
    put(boot, 0, b"SEGA SEGAKATANA ")
    put(boot, 96, b"1ST_READ.BIN")
    put(boot, 128, b"REPRO TEST")
    put(track, 0, boot)

    volume = 16 * 2048
    put(track, volume, b"\x01CD001\x01")
    put(track, volume + 128, (2048).to_bytes(2, "little"))      # block size
    put(track, volume + 156 + 2, (DREAMCAST_FIRST_SECTOR + 18).to_bytes(3, "little"))
    put(track, volume + 156 + 10, (2048).to_bytes(4, "little"))  # the directory's length

    name = b"1ST_READ.BIN;1"
    record = bytearray(33 + len(name))
    record[0] = len(record)
    put(record, 2, (DREAMCAST_FIRST_SECTOR + 20).to_bytes(3, "little"))
    put(record, 10, len(program).to_bytes(4, "little"))
    record[32] = len(name)
    put(record, 33, name)
    put(track, 18 * 2048, record)

    put(track, 20 * 2048, program)
    return bytes(track), md5(bytes(boot) + program)


def be32(image, at):
    return int.from_bytes(whole(image, at, 4), "big")


def whole(image, at, size):
    """`size` bytes of `image` from `at`, which have to be there: the answer
    for a file that is right is worked out from nothing past the file's end."""
    assert at + size <= len(image), f"{size:#x} bytes at {at:#x} of a file of {len(image):#x}"
    return bytes(image[at:at + size])


WII_MAGIC = bytes.fromhex("5D1C9EA3")

# Where a Wii disc keeps its table of partitions: four groups, and for each a
# count and where the group's entries are, as an offset divided by four. An
# entry is two words as well, where the partition is and of what kind.
WII_TABLE = 0x40000

# The four bytes of a Wii disc's region code. They are read before the table
# and lie 0xE000 bytes behind it, so a file that holds them holds the table.
WII_REGION = 0x4E000

# The least a Wii image is here when the table is what its row is about: the
# region code, and 0x1C bytes more, so that the file is no whole number of
# sectors (see wii_disc below).
WII_LEAST = WII_REGION + 0x20


def wii_image(size=WII_LEAST, counts=()):
    """`size` bytes of nothing with the Wii magic word and, where the table of
    partitions begins, the count of each group that is given one. A count
    past the end of a shorter file is left out."""
    image = bytearray(size)
    put(image, 0x18, WII_MAGIC)
    for group, count in enumerate(counts):
        put(image, WII_TABLE + group * 8, count.to_bytes(4, "big"))
    return bytes(image[:size])


def wii_entries(size, entries, at=WII_LEAST):
    """A Wii image of `size` bytes whose first group names `len(entries)`
    partitions, with the bytes of those entries at `at`, which is behind the
    region code unless told: as many of them as the size leaves room for."""
    image = bytearray(max(size, at + 8 * len(entries)))
    put(image, 0x18, WII_MAGIC)
    put(image, WII_TABLE, len(entries).to_bytes(4, "big") + (at >> 2).to_bytes(4, "big"))
    for where, kind in entries:
        put(image, at, where.to_bytes(4, "big") + kind.to_bytes(4, "big"))
        at += 8
    return bytes(image[:size])


def nintendo_partition(image, base, shift):
    """What rcheevos hashes of a GameCube disc (`base` 0, `shift` 0) and of a
    Wii partition that is not encrypted (`shift` 2: its offsets and sizes are
    kept divided by four). The header, as long as the apploader's two sizes
    make it and 1 MiB at most; then every segment of main.dol that has a size,
    in the order of the table that the word at 0x420 points to: 18 offsets,
    and 0x90 bytes on, 18 sizes."""
    size = (0x2440 + 0x20 + be32(image, base + 0x2454) + be32(image, base + 0x2458)) & 0xFFFFFFFF
    data = whole(image, base, min(size, 1 << 20))
    dol = base + (be32(image, base + 0x420) << shift)
    for segment in range(18):
        at = be32(image, dol + segment * 4) << shift
        size = be32(image, dol + 0x90 + segment * 4) << shift
        data += whole(image, base + at, size)
    return data


def wii_md5(image):
    """What rcheevos hashes of a Wii disc: its first 0x80 bytes, the four of
    the region code, and for every partition the table names that is not of
    kind 1, which is the console's own update, the title metadata and then
    the data. Of encrypted data that is the 0x7C00 bytes that follow the
    0x400 of hashes in each cluster of 0x8000, for 1024 clusters at most."""
    data = whole(image, 0, 0x80) + whole(image, WII_REGION, 4)
    for group in range(4):
        count = be32(image, WII_TABLE + group * 8)
        entries = be32(image, WII_TABLE + group * 8 + 4) << 2
        for entry in range(count):
            partition = be32(image, entries + entry * 8) << 2
            if be32(image, entries + entry * 8 + 4) == 1:
                continue
            metadata = partition + (be32(image, partition + 0x2A8) << 2)
            data += whole(image, metadata, min(be32(image, partition + 0x2A4), 0x7C00))
            start = be32(image, partition + 0x2B8) << 2
            size = be32(image, partition + 0x2BC) << 2
            if image[0x61] == 0:
                for cluster in range(min(size // 0x8000, 1024)):
                    data += whole(image, start + cluster * 0x8000 + 0x400, 0x7C00)
            else:
                data += nintendo_partition(image, start, shift=2)
    return md5(data)


# Where wii_disc() below puts things: the game partition's own header, in
# which the words at 0x2A4 say how long its title metadata is and where, and
# the words at 0x2B8 where its data is and how long; and the data, which is
# two clusters and has room for a third before the file ends.
WII_PARTITION = 0x50000
WII_DATA = 0x58000


def wii_disc(encrypted, updates=(1, 0, 0, 0), segment_size=0x1FFC, metadata=0x2C0):
    """A Wii disc with every table inside the file, and what it hashes to.
    One game partition, the last of the first group, and before it and in the
    other three groups as many update partitions as `updates` says, which are
    named and never read. The game's data is two clusters, and holds what a
    partition that is not encrypted is read by as well: the apploader's sizes,
    and a main.dol of two segments, the second `segment_size` bytes long.
    Which of the two ways it is read is one byte of the disc's header.

    `metadata` is where in the partition's header the 0x208 bytes of title
    metadata are: behind the four words that name them and the data, as on a
    disc, or before them, for a file that is to end between the two.

    The file is 0x20 bytes longer than a whole number of sectors, like the
    images above, so that the consoles asked before this one, which read a
    disc by its sectors, do not open it."""
    image = bytearray(0x70020)
    put(image, 0, noise(0x80, seed="wii-header"))
    put(image, 0x18, WII_MAGIC)
    image[0x61] = 0 if encrypted else 1
    put(image, WII_REGION, noise(4, seed="wii-region"))

    partition, data = WII_PARTITION, WII_DATA
    at = WII_TABLE + 0x20
    for group, count in enumerate(updates):
        entries = [(0x60000 >> 2, 1)] * count + ([(partition >> 2, 0)] if group == 0 else [])
        put(image, WII_TABLE + group * 8, len(entries).to_bytes(4, "big") + (at >> 2).to_bytes(4, "big"))
        for where, kind in entries:
            put(image, at, where.to_bytes(4, "big") + kind.to_bytes(4, "big"))
            at += 8
    assert at <= 0x4E000

    assert metadata + 0x208 <= 0x2A4 or metadata >= 0x2C0
    put(image, partition + 0x2A4, (0x208).to_bytes(4, "big") + (metadata >> 2).to_bytes(4, "big"))
    put(image, partition + metadata, noise(0x208, seed="wii-metadata"))
    put(image, partition + 0x2B8, (data >> 2).to_bytes(4, "big") + (0x10000 >> 2).to_bytes(4, "big"))

    put(image, data, noise(0x10000, seed="wii-data"))
    put(image, data + 0x420, (0x3000 >> 2).to_bytes(4, "big"))
    put(image, data + 0x2454, (0x100).to_bytes(4, "big") + (0x20).to_bytes(4, "big"))
    table = bytearray(0xD8)
    for segment, where, size in ((1, 0x3400, 0x800), (9, 0x4000, segment_size)):
        put(table, segment * 4, (where >> 2).to_bytes(4, "big"))
        put(table, 0x90 + segment * 4, (size >> 2).to_bytes(4, "big"))
    put(image, data + 0x3000, table)
    assert len(image) == 0x70020
    return bytes(image)


OPERA_SIGNATURE = bytes.fromhex("015A5A5A5A5A01")


def opera_header(root_block):
    """The sector an OperaFS volume opens with. rcheevos reads 132 bytes of
    it: the signature, the size of a block, and the block the root directory
    begins in."""
    sector = bytearray(2048)
    put(sector, 0, OPERA_SIGNATURE)
    put(sector, 0x28, b"repro test")
    put(sector, 0x4C, (2048).to_bytes(4, "big"))
    put(sector, 0x64, root_block.to_bytes(4, "big"))
    return bytes(sector)


def opera_entry(name, kind, block=0, size=0, copies=0):
    """One entry of an OperaFS directory: 0x48 bytes, and four more for each
    further copy of the file the disc holds. Kind 2 is a file."""
    entry = bytearray(0x48 + 4 * copies)
    entry[0x03] = kind
    put(entry, 0x0C, (2048).to_bytes(4, "big"))
    put(entry, 0x10, size.to_bytes(4, "big"))
    put(entry, 0x20, name)
    put(entry, 0x40, copies.to_bytes(4, "big"))
    put(entry, 0x44, block.to_bytes(4, "big"))
    return bytes(entry)


OPERA_LAST = 0xFFFF     # what a directory sector names as its next when it has none


def opera_directory(entries, following=OPERA_LAST, first=0x14, stop=None):
    """One sector of an OperaFS directory: which block of the directory
    follows this one, counted from the directory's first; where its entries
    end, which is `stop` when the sector is to say something else than the
    truth; where they begin; and the entries."""
    sector = bytearray(2048)
    body = b"".join(entries)
    assert first + len(body) <= len(sector)
    put(sector, 0x00, following.to_bytes(4, "big"))
    put(sector, 0x0C, (first + len(body) if stop is None else stop).to_bytes(4, "big"))
    put(sector, 0x10, first.to_bytes(4, "big"))
    put(sector, first, body)
    return bytes(sector)


def opera_image():
    """One sector with the OperaFS signature, blocks of 2048 bytes and a root
    directory in block 16, which the file is too short to hold."""
    return opera_header(root_block=16)


def opera_disc():
    """An OperaFS volume that is right, and what it hashes to: the 132 bytes
    of its header and then the file named LaunchMe. The root directory is two
    sectors. The first has a folder and a file kept in three copies, whose
    entry is longer by that, and names the second as its next; LaunchMe is
    the last entry of the second and ends where the entries are said to end.
    It is 5000 bytes, so two sectors and part of a third."""
    program = noise(5000, seed="opera-program")
    header = opera_header(root_block=2)
    image = (header + bytes(2048)
             + opera_directory([opera_entry(b"Folder", 7, block=4),
                                opera_entry(b"Notes", 2, block=4, size=10, copies=2)], following=1)
             + opera_directory([opera_entry(b"banner", 2, block=4, size=10),
                                opera_entry(b"LaunchMe", 2, block=5, size=len(program))])
             + bytes(2048) + program + bytes(3 * 2048 - len(program)))
    return image, md5(header[:132] + program)


def opera_chain(hops):
    """An OperaFS volume whose root directory is a chain of sectors, each
    naming the next, with LaunchMe in the one reached after `hops` of them;
    and what it hashes to if the search gets that far."""
    program = noise(100, seed="opera-chain")
    header = opera_header(root_block=1)
    image = header
    for block in range(hops):
        image += opera_directory([opera_entry(b"filler", 2, size=1)], following=block + 1)
    image += opera_directory([opera_entry(b"LaunchMe", 2, block=hops + 2, size=len(program))])
    image += program + bytes(2048 - len(program))
    return image, md5(header[:132] + program)


def opera_header_as_directory(root_block=16):
    """One sector that opens an OperaFS volume and can be read as a sector of
    a directory as well, with one entry, LaunchMe, of 64 bytes in block 0.
    The header's fields and the directory's do not overlap: 132 bytes hold
    both. The volume says its root directory is in block 16 unless told,
    which is not in the file; a search that takes what is left in its buffer
    for that sector finds this one there."""
    sector = bytearray(opera_header(root_block=root_block))
    put(sector, 0x0C, (0x14 + 0x48).to_bytes(4, "big"))     # where the entries end
    put(sector, 0x10, (0x14).to_bytes(4, "big"))            # and where they begin
    put(sector, 0x14, opera_entry(b"LaunchMe", 2, block=0, size=64))
    put(sector, 0x4C, (2048).to_bytes(4, "big"))            # inside the entry's name, past its end
    assert len(sector) == 2048 and 0x14 + 0x48 <= 0x64
    return bytes(sector)


GAMECUBE_MAGIC = bytes.fromhex("C2339F3D")


def dol_image(segments=1, size=1 << 30):
    """1496 bytes with the GameCube magic word and a main.dol at 0x500, of
    which `segments` segments are each said to be `size` bytes long, 1 GiB
    unless told. The file ends with the DOL's header, long before the place
    where a disc says how long its own header is."""
    image = bytearray(0x500 + 0xD8)
    put(image, 0x1C, GAMECUBE_MAGIC)
    put(image, 0x420, (0x500).to_bytes(4, "big"))
    for segment in range(segments):
        put(image, 0x500 + 0x90 + segment * 4, size.to_bytes(4, "big"))
    return bytes(image)


# Where gamecube_disc() below puts things: the header ends at 0x27A0, the
# table of main.dol's segments is the 0xD8 bytes from 0x2800, and the last
# segment, which is the table's eighth, begins at 0x4000.
GAMECUBE_HEADER_SIZE = 0x27A0
GAMECUBE_DOL = 0x2800
GAMECUBE_LAST_SEGMENT = 7


def gamecube_disc(header_size=GAMECUBE_HEADER_SIZE, sizes=None):
    """A GameCube disc with everything it names inside the file. Its main.dol
    has three segments: two small ones, and a third of 1 MiB and 0x321 bytes,
    which is read in more than one piece and ends with the file's last byte.

    `header_size` is what the apploader's two sizes are to bring the header
    to. They are added to 0x2460 in 32 bits, so two of them reach any size,
    those below 0x2460 by going all the way round; neither is given its top
    bit, which upstream's reading of a word does not allow for.

    `sizes` says other sizes for segments than the ones they have, by their
    place in the table, for a disc that is to be wrong about them."""
    last = (1 << 20) + 0x321
    image = bytearray(noise(0x4000 + last, seed="gamecube"))
    put(image, 0x1C, GAMECUBE_MAGIC)
    put(image, 0x420, GAMECUBE_DOL.to_bytes(4, "big"))
    both = (header_size - 0x2460) % (1 << 32)
    body = min(both, 0x7FFFFFFF)
    assert both - body <= 0x7FFFFFFF
    put(image, 0x2454, body.to_bytes(4, "big") + (both - body).to_bytes(4, "big"))
    table = bytearray(0xD8)
    for segment, where, size in ((0, 0x2900, 0x180), (3, 0x2B00, 0x1235),
                                 (GAMECUBE_LAST_SEGMENT, 0x4000, last)):
        put(table, segment * 4, where.to_bytes(4, "big"))
        put(table, 0x90 + segment * 4, size.to_bytes(4, "big"))
    for segment, size in (sizes or {}).items():
        put(table, 0x90 + segment * 4, size.to_bytes(4, "big"))
    put(image, GAMECUBE_DOL, table)
    assert len(image) == 0x4000 + last
    return bytes(image)


def cue(track, mode):
    """A cue sheet of one data track."""
    return (b'FILE "%s" BINARY\r\n' % track
            + b"  TRACK 01 %s\r\n" % mode
            + b"    INDEX 01 00:00:00\r\n")


def write_fixtures(directory):
    """Writes every file the rows name, and returns the rows."""
    def write(name, data):
        (directory / name).write_bytes(data)

    nes = ines()
    write("Repro.nes", nes)

    track = sega_cd_track()
    write("Repro CD.bin", track)
    write("Repro CD.cue", cue(b"Repro CD.bin", b"MODE1/2352"))
    write("Repro CD.m3u", b"Repro CD.cue\r\n")
    sega_cd = md5(track[16:16 + 512])   # the first 512 bytes of sector 0's data

    write("mslug.zip", noise(4096, seed="not a zip"))

    # (what the row shows, console, file, the answer). The answer is a hash
    # and its console, or REFUSED, or refused("words the reason has to hold").
    must_pass = [
        ("an iNES file under console 7 is hashed without its header",
         7, "Repro.nes", f"{md5(nes[16:])}|7"),
        ("the same file with no console given is taken for console 7",
         0, "Repro.nes", f"{md5(nes[16:])}|7"),
        # Console 3 has no header to take off a file of this size, so the
        # answer differs from the two above, which it would not if the console
        # on the command line were dropped on its way to rcheevos.
        ("the same file under console 3 is hashed whole",
         3, "Repro.nes", f"{md5(nes)}|3"),
        ("a cue and its track under console 9",
         9, "Repro CD.cue", f"{sega_cd}|9"),
        ("a playlist naming that cue under console 9",
         9, "Repro CD.m3u", f"{sega_cd}|9"),
        # With no console a cue is tried as a PlayStation, a PlayStation 2 and
        # a Dreamcast disc before it is tried as this one. The file above that
        # is hashed with no console is taken by the first console asked, so
        # these two are what holds the going on to the next, and the console
        # printed being the one that answered: the path every row below with
        # no console given is run through.
        ("the same cue with no console given is refused by three consoles and taken by the fourth",
         0, "Repro CD.cue", f"{sega_cd}|9"),
        ("the same playlist with no console given",
         0, "Repro CD.m3u", f"{sega_cd}|9"),
        ("noise named mslug.zip under console 27 is hashed by its name",
         27, "mslug.zip", f"{md5(b'mslug')}|27"),
        ("a file that is not there, under console 7",
         7, "Missing.nes", REFUSED),
        ("a file that is not there, with no console given",
         0, "Missing.nes", REFUSED),
    ]

    # A file that ends inside its header, or with it. Each of these consoles
    # takes a header off the front of a file that opens with its magic word and
    # hashes what follows. With nothing following, "what follows" was a length
    # below zero, which in C is a very large one; local patch 0005 refuses any
    # file of these consoles that is no longer than the header.
    # (extension, console, header size, a header's first bytes)
    headered = [
        ("nes", 7, 16, b"NES\x1a\x01\x01"),
        ("fds", 81, 16, b"FDS\x1a\x00\x00"),
        ("lnx", 13, 64, b"LYNX\x00\x01"),
        ("a78", 51, 128, b"\x01ATARI7800"),
        ("cart", 55, 32, b"EmuSCV"),
    ]
    for extension, console, size, magic in headered:
        write(f"tiny.{extension}", magic)
        whole = magic + bytes(size - len(magic))
        write(f"header-only.{extension}", whole)
        write(f"header-and-one.{extension}", whole + b"\x5a")
        must_pass += [
            # With no console given, as the library is asked today, and with
            # the console named: .fds goes to console 7 by its extension and
            # to 81 when told, through the same function.
            (f"a .{extension} header cut to {len(magic)} bytes, with no console given",
             0, f"tiny.{extension}", REFUSED),
            (f"the same file under console {console}",
             console, f"tiny.{extension}", REFUSED),
            # The two sides of "no longer than the header": these hold the
            # size written for each console, and that it is the header that is
            # refused and not the first byte after it.
            (f"a .{extension} header of {size} bytes and nothing after it",
             console, f"header-only.{extension}", REFUSED),
            (f"a .{extension} header and one byte, which is what is hashed",
             console, f"header-and-one.{extension}", f"{md5(b'Z')}|{console}"),
        ]
    # Shorter than the magic word itself, so that even the comparison with it
    # read past the end; and no bytes at all, which used to be given the MD5
    # of nothing, a hash like any other to look up.
    write("three.nes", b"NES")
    write("empty.nes", b"")
    must_pass += [
        ("a .nes of 3 bytes, shorter than the word a header opens with",
         0, "three.nes", REFUSED),
        ("an empty .nes", 0, "empty.nes", REFUSED),
    ]

    # A .gdi sheet whose fields are longer than the parser's buffers for them,
    # or end before they should (local patch 0006). The sector size is copied
    # twice, into 16 bytes and from there into the 9 that are left after
    # "MODE1/" in another 16, so twelve digits fit the first and not the second.
    write("digits.gdi", gdi(b"3 45000 4 " + b"9" * 64 + b" track03.bin 0\n"))
    write("digits-12.gdi", gdi(b"3 45000 4 " + b"9" * 12 + b" track03.bin 0\n"))
    write("long.gdi", gdi(b"3 45000 4 2352 " + b"n" * 300 + b" 0\n"))
    write("long-256.gdi", gdi(b"3 45000 4 2352 " + b"n" * 256 + b" 0\n"))
    write("quote.gdi", gdi(b'3 45000 4 2352 "track03.bin 0\n'))
    must_pass += [
        ("a .gdi whose sector size is 64 digits", 0, "digits.gdi", REFUSED),
        ("a .gdi whose sector size is 12 digits", 0, "digits-12.gdi", REFUSED),
        ("a .gdi whose track is a file name of 300 characters", 0, "long.gdi", REFUSED),
        # One more than the 255 that fit with the NUL that ends them.
        ("a .gdi whose track is a file name of 256 characters", 0, "long-256.gdi", REFUSED),
        ("a .gdi whose file name opens a quote and never closes it", 0, "quote.gdi", REFUSED),
    ]
    # Sheets that are right, which every .gdi row above is not. The parser's
    # patched lines are not off to one side where only a bad sheet goes: the
    # digits of the sector size, the step after each number and the copy of
    # the name are run for every line of every sheet. A bound drawn too tight
    # would refuse them all, and all the rows above would still pass, being
    # refusals themselves. So here is a disc of three tracks as a dump holds
    # it, with its data track named plainly and in quotes with a space, and
    # the answer is its hash.
    track, dreamcast = dreamcast_track()
    write("Repro GD (Track 3).bin", track)
    write("repro-gd-03.bin", track)
    sheet = (b"3\n"
             b"1 0 4 2352 repro-gd-01.bin 0\n"
             b"2 600 0 2352 repro-gd-02.raw 0\n")
    line = b"3 %d 4 2048 %%s 0\n" % DREAMCAST_FIRST_SECTOR
    write("valid.gdi", sheet + line % b"repro-gd-03.bin")
    write("valid-quoted.gdi", sheet + line % b'"Repro GD (Track 3).bin"')
    # The data track is the second of two, behind an audio track with a longer
    # name. No line is track 3, so the disc is asked for its first track with
    # data, and asked that way the parser copies the name of every line it
    # passes: the short name lands on the long one, and is the track's name
    # only if it is ended where it ends.
    write("valid-first-data.gdi",
          b"2\n"
          b"1 0 0 2352 repro-gd-audio-track-with-a-long-name.raw 0\n"
          + b"2 %d 4 2048 repro-gd-03.bin 0\n" % DREAMCAST_FIRST_SECTOR)
    # The two sides of "more than nine digits". Nine is no error, and since
    # the track is there and says by its content what its sectors are, the
    # disc hashes. Ten are one more than fit after "MODE1/", by the NUL that
    # ends them. With the track there, a build that let ten through would
    # hash this one too, and so fail the row with no sanitizer to see the
    # byte written.
    nines = b"3\n3 %d 4 %%s repro-gd-03.bin 0\n" % DREAMCAST_FIRST_SECTOR
    write("digits-9.gdi", nines % (b"9" * 9))
    write("digits-10.gdi", nines % (b"9" * 10))
    must_pass += [
        ("a .gdi of three tracks, hashed from its third", 0, "valid.gdi", f"{dreamcast}|40"),
        ("the same sheet with the track's name in quotes and a space in it",
         0, "valid-quoted.gdi", f"{dreamcast}|40"),
        ("the same sheet under console 40", 40, "valid.gdi", f"{dreamcast}|40"),
        ("a .gdi whose data track is its second, after a longer name",
         0, "valid-first-data.gdi", f"{dreamcast}|40"),
        ("a .gdi whose sector size is 9 digits, and whose track is there",
         0, "digits-9.gdi", f"{dreamcast}|40"),
        ("the same with 10 digits", 0, "digits-10.gdi", REFUSED),
    ]

    # A sheet that ends in the middle of its line, after the first, second,
    # third or fourth number. The parser used to step over the character after
    # a number without looking, and here that is the NUL that closes what was
    # read. Past it lies whatever the buffer held before, which no sanitizer
    # minds being read, so these sheets are of the one length that leaves
    # nothing past it: 1023 bytes, all that is read at a time, the line made
    # that long by the spaces it opens with.
    numbers = [b"3", b"45000", b"4", b"2352"]
    for count, after in enumerate(("track", "start", "type", "sector size"), start=1):
        cut = b" ".join(numbers[:count])
        write(f"cut-{count}.gdi", gdi(b" " * (1023 - 2 - len(cut)) + cut))
        must_pass.append((f"a .gdi of 1023 bytes that ends after its {after}",
                          0, f"cut-{count}.gdi", REFUSED))

    # ---- local patch 0001: the table of partitions of a Wii disc, and what is
    # read before it and through it.
    # Each of these was a known gap, or would have been one: counts that no
    # disc has, a table that is not in the file, entries that end with the
    # file. The first three hold all that is read on the way to the table, so
    # that it is the table they are refused for, and the two with a count say
    # so: without the limit the larger is refused as well, by its entries not
    # being there, after 4 GiB were asked for to keep them in.
    write("wii-no-partitions.iso", wii_image())
    write("wii-partition-count.iso", wii_image(counts=(0x20000000,)))
    # 0xFFFFFFFF and 2 are 1 when they are added in 32 bits: a table of one
    # entry is allocated, and four thousand million are written to it.
    write("wii-count-wraps.iso", wii_image(counts=(0xFFFFFFFF, 2)))
    write("wii-1024.iso", wii_image(size=1024))
    for text, name, answer in (
            ("a Wii image with no partitions", "wii-no-partitions.iso", refused("No partitions found")),
            ("a Wii image that counts 0x20000000 partitions", "wii-partition-count.iso",
             refused("more than 64")),
            ("a Wii image whose counts, 0xFFFFFFFF and 2, add up to 1", "wii-count-wraps.iso",
             refused("more than 64")),
            ("a file of 1024 bytes with the Wii magic word", "wii-1024.iso", REFUSED)):
        must_pass += [(f"{text}, with no console given", 0, name, answer),
                      ("the same under console 19", 19, name, answer)]

    # What is read before the table: the disc's first 0x80 bytes and the four
    # of its region code, both of which go into the hash. Neither read was
    # looked at. An image that ends between the table and the region code was
    # given a hash with four bytes in it that the stack happened to hold: one
    # hash with no console given and another as a Wii disc, from one program
    # and one file. Two such images, the table of the first naming a partition
    # at the start of the file, that of the second a console's update, which
    # is not read; and the region code from both sides, with three of its
    # bytes and with all four, where the table is reached and says there is
    # no partition.
    write("wii-header-cut.iso", wii_image(size=0x7F))
    write("wii-table-and-no-more.iso", wii_image(size=WII_TABLE + 0x20, counts=(0, 0, 0, 1)))
    write("wii-update-and-no-more.iso",
          wii_entries(WII_TABLE + 0x28, [(1, 1)], at=WII_TABLE + 0x20))
    write("wii-region-cut.iso", wii_image(size=WII_REGION + 3))
    write("wii-region-whole.iso", wii_image(size=WII_REGION + 4))
    must_pass += [
        ("a Wii image that ends one byte short of its 0x80 of header",
         19, "wii-header-cut.iso", refused("Disc header runs past the end")),
        ("a Wii image that ends with its table, which names a partition, with no console given",
         0, "wii-table-and-no-more.iso", refused("Region code is past the end")),
        ("the same under console 19",
         19, "wii-table-and-no-more.iso", refused("Region code is past the end")),
        ("a Wii image that ends with the entry of its one partition, an update",
         19, "wii-update-and-no-more.iso", refused("Region code is past the end")),
        ("a Wii image that ends inside its region code",
         19, "wii-region-cut.iso", refused("Region code is past the end")),
        ("the same with the whole region code, and no partitions",
         19, "wii-region-whole.iso", refused("No partitions found")),
    ]

    # Two partitions named, both the console's update, which is not hashed.
    # Their entries are behind the region code, and the file ends after the
    # first of them, and then in the middle of the second. The missing words
    # were the last word read once more, a 1, so the second partition was an
    # update as well and the disc hashed.
    updates = [(1, 1), (1, 1)]
    write("wii-entry-missing.iso", wii_entries(WII_LEAST + 8, updates))
    write("wii-entry-cut.iso", wii_entries(WII_LEAST + 12, updates))
    # An entry whose two words have their top bit set: a partition at 16 GiB,
    # of a kind there is none of. Put together signed, as upstream does it,
    # that is a shift C leaves undefined and the sanitized build reports.
    write("wii-entry-top-bit.iso", wii_entries(WII_LEAST + 8, [(0xFFFFFFFF, 0xFFFFFFFF)]))
    must_pass += [
        ("a Wii image whose table names two partitions and holds one",
         19, "wii-entry-missing.iso", refused("Could not read partition table")),
        ("a Wii image that ends in the middle of its second partition's entry",
         19, "wii-entry-cut.iso", refused("Could not read partition table")),
        ("a Wii image whose one partition is said to be at 16 GiB",
         19, "wii-entry-top-bit.iso", refused("Title metadata size and offset")),
    ]

    # Discs that are right. The table is read by the patched lines for every
    # disc there is, so a limit drawn too low, or a read that is taken for
    # short when it is not, would refuse them all while every row above went
    # on passing. One disc read both ways, as encrypted and as not; and the
    # limit of 64 from both sides, in a group that is not the first, and in
    # all four groups at once, since it is a limit for each and not for the
    # sum.
    encrypted = wii_disc(encrypted=True)
    plain_data = wii_disc(encrypted=False)
    sixty_four = wii_disc(encrypted=True, updates=(1, 0, 64, 0))
    four_groups = wii_disc(encrypted=True, updates=(63, 64, 64, 64))
    write("wii-encrypted.iso", encrypted)
    write("wii-decrypted.iso", plain_data)
    write("wii-64-in-a-group.iso", sixty_four)
    write("wii-65-in-a-group.iso", wii_disc(encrypted=True, updates=(1, 0, 65, 0)))
    write("wii-64-in-every-group.iso", four_groups)
    # The same disc, not encrypted, with a segment of its main.dol said to be
    # 16 GiB long, the most a Wii partition can say: local patch 0004, reached
    # through the Wii's own way in. Hashing 16 GiB of what a buffer was left
    # holding takes four times as long as this row is given, so it is also
    # what shows the segment given up at the first piece that is not there,
    # and not at the last.
    write("wii-decrypted-segment.iso", wii_disc(encrypted=False, segment_size=0xFFFFFFFF << 2))
    must_pass += [
        ("a Wii disc, encrypted, with one update partition and one game",
         19, "wii-encrypted.iso", f"{wii_md5(encrypted)}|19"),
        ("the same disc with no console given, refused by five consoles and taken by the sixth",
         0, "wii-encrypted.iso", f"{wii_md5(encrypted)}|19"),
        ("the same disc, not encrypted, hashed by its main.dol",
         19, "wii-decrypted.iso", f"{wii_md5(plain_data)}|19"),
        ("a Wii disc with 64 partitions in its third group",
         19, "wii-64-in-a-group.iso", f"{wii_md5(sixty_four)}|19"),
        ("the same with 65", 19, "wii-65-in-a-group.iso", REFUSED),
        ("a Wii disc with 64 partitions in each of its four groups",
         19, "wii-64-in-every-group.iso", f"{wii_md5(four_groups)}|19"),
        ("a Wii disc, not encrypted, with a main.dol segment of 16 GiB",
         19, "wii-decrypted-segment.iso", refused("segment 9 runs past the end")),
    ]

    # What a partition says of itself, and what it then names. None of it was
    # looked at when it was read, so the disc above, cut off anywhere past its
    # table, was given a hash: of the title metadata the buffer happened to
    # hold, which was not the same on the two builds, and of the last cluster
    # that could be read as many times over as there were clusters missing. The same disc cut at each thing that is
    # read: right after the region code, where the partition is not there at
    # all; inside the second of the two words that name the title metadata;
    # one byte before the end of the title metadata; inside the first cluster;
    # and one byte before the end of the last. Cut where the last cluster ends it is whole, as far
    # as anything reads it, and hashes as the disc does. Each cut is refused
    # by the next read as well, if not by its own, so the reason is asked for.
    for size, text, saying in (
            (WII_REGION + 4, "right after its region code", "Title metadata size and offset"),
            (WII_PARTITION + 0x2A4 + 7, "inside the offset of its title metadata",
             "Title metadata size and offset"),
            (WII_PARTITION + 0x2C0 + 0x208 - 1, "one byte before the end of its title metadata",
             "Title metadata runs past the end"),
            (WII_DATA + 0x4000, "inside its first cluster", "Partition cluster 0 runs past the end"),
            (WII_DATA + 0x10000 - 1, "one byte before the end of its last cluster",
             "Partition cluster 1 runs past the end")):
        write(f"wii-cut-{size:x}.iso", encrypted[:size])
        must_pass.append((f"the Wii disc cut off {text}", 19, f"wii-cut-{size:x}.iso", refused(saying)))
    write("wii-cut-whole.iso", encrypted[:WII_DATA + 0x10000])
    must_pass.append(("the Wii disc cut off where its last cluster ends",
                      19, "wii-cut-whole.iso", f"{wii_md5(encrypted)}|19"))
    # The two words that say where the data is and how long come before the
    # title metadata on that disc, so a file that ends inside them is refused
    # for the metadata. Here the metadata is ahead of them.
    write("wii-cut-data-words.iso",
          wii_disc(encrypted=True, metadata=0x40)[:WII_PARTITION + 0x2B8 + 7])
    must_pass.append(("a Wii disc cut off inside the size of its partition's data",
                      19, "wii-cut-data-words.iso", refused("Partition data offset and size")))

    # 256 partitions, 64 to a group, every one of them the game's, which is
    # said to be 16 GiB long: of that 1024 clusters are hashed, and the file
    # holds three. A cluster that could not be read used to be hashed as the
    # one before it, so this file of 450 KiB was 8 GiB of hashing, nine
    # seconds of it on the machine it was measured on. It is refused at the
    # fourth cluster of the first partition.
    crowd = bytearray(four_groups)
    for entry in range(256):
        put(crowd, WII_TABLE + 0x20 + entry * 8, (WII_PARTITION >> 2).to_bytes(4, "big") + bytes(4))
    put(crowd, WII_PARTITION + 0x2BC, b"\xff\xff\xff\xff")
    write("wii-256-partitions.iso", crowd)
    must_pass.append(("a Wii disc of 256 partitions that are all one, said to be 16 GiB long",
                      19, "wii-256-partitions.iso", refused("Partition cluster 3 runs past the end")))

    # Words with their top bit set, one to a disc, each the first such word
    # that is read: the sanitized build ends at the first shift it reports.
    # Every word of the function is put together unsigned by the patch, and
    # each then means what it says: a kind of partition that is not the
    # update's and so is hashed; title metadata of 2 GiB, of which 0x7C00
    # bytes are hashed like any that is longer than that, and the file has
    # them; title metadata 8 GiB into the partition; data 8 GiB into the disc.
    # A size of data with its top bit set is the 256 partitions above.
    top = (0x80000000).to_bytes(4, "big")
    for at, name, text, answer in (
            (WII_TABLE + 0x20 + 12, "kind", "whose game partition is of kind 0x80000000", None),
            (WII_PARTITION + 0x2A4, "metadata-size", "whose title metadata is said to be 2 GiB long", None),
            (WII_PARTITION + 0x2A8, "metadata-offset", "whose title metadata is said to be 8 GiB on",
             refused("Title metadata runs past the end")),
            (WII_PARTITION + 0x2B8, "data-offset", "whose data is said to be 8 GiB on",
             refused("Partition cluster 0 runs past the end"))):
        image = bytearray(encrypted)
        put(image, at, top)
        write(f"wii-top-bit-{name}.iso", image)
        must_pass.append((f"a Wii disc {text}", 19, f"wii-top-bit-{name}.iso",
                          answer or f"{wii_md5(image)}|19"))

    # ---- local patch 0002: the directory of an OperaFS volume, which is what
    # a 3DO disc is.
    write("opera-short.iso", opera_image())
    # A directory sector that names itself as its next, and two that name
    # each other.
    filler = [opera_entry(b"filler", 2, size=1)]
    write("opera-self.iso", opera_header(1) + opera_directory(filler, following=0))
    write("opera-ring.iso", opera_header(1) + opera_directory(filler, following=1)
                            + opera_directory(filler, following=0))
    write("opera-header-as-directory.iso", opera_header_as_directory())
    must_pass += [
        ("an OperaFS volume whose root directory is past the end, with no console given",
         0, "opera-short.iso", REFUSED),
        ("the same under console 43", 43, "opera-short.iso", REFUSED),
        ("an OperaFS directory sector that names itself as its next",
         43, "opera-self.iso", REFUSED),
        ("two OperaFS directory sectors that name each other",
         43, "opera-ring.iso", REFUSED),
        ("the same with no console given", 0, "opera-ring.iso", REFUSED),
        # Without the test of the read, the search goes on in what the buffer
        # still holds, which is this sector, finds LaunchMe in it and hashes.
        ("an OperaFS header that reads as a directory too, with the real one past the end",
         43, "opera-header-as-directory.iso", REFUSED),
    ]
    # Entries that the sector does not hold. 28 entries from 0x14 leave 12
    # bytes of it, so a 29th would begin inside the sector and end 60 bytes
    # past it. Once the sector says its entries go on for 16 MiB, and once,
    # truly, that they end with the sector. The 29th begins inside on purpose:
    # begun where the sector ends, it would be left alone by a limit that let
    # the entries run up to 0x47 bytes too far as well.
    entries = filler * 28
    write("opera-entries-past.iso",
          opera_header(1) + opera_directory(entries, first=0x14, stop=0xFFFFFF))
    write("opera-entry-cut.iso",
          opera_header(1) + opera_directory(entries, first=0x14, stop=2048))
    # An entry that is LaunchMe, and is all in the sector but for its last
    # byte: 0x47 bytes of it, up to the sector's end. An entry is 0x48, and
    # the byte that is missing is one the file's place is read from.
    launch = opera_entry(b"LaunchMe", 2, block=0, size=64)
    write("opera-entry-47.iso",
          opera_header(1) + opera_directory([launch[:0x47]], first=2048 - 0x47, stop=2048))
    must_pass += [
        ("an OperaFS directory that says its entries go on for 16 MiB",
         43, "opera-entries-past.iso", REFUSED),
        ("an OperaFS directory whose last entry is cut off by the end of the sector",
         43, "opera-entry-cut.iso", REFUSED),
        ("an OperaFS directory whose one entry, LaunchMe, lacks its last byte",
         43, "opera-entry-47.iso", REFUSED),
    ]
    # A directory sector of which the file holds 16 bytes. The rows above with
    # a directory past the end have none of it, and a test that asked only
    # whether anything was read would hold for all of them. These 16 bytes say
    # that no sector follows and that the entries end at 0x5C; where they
    # begin is past the 16, and so is what the buffer held before: the
    # volume's first sector, which reads as a directory with LaunchMe in it.
    # As a track of a cue sheet, which is how a last sector that is not whole
    # is opened at all.
    partial = bytearray(16)
    put(partial, 0x00, OPERA_LAST.to_bytes(4, "big"))
    put(partial, 0x0C, (0x14 + 0x48).to_bytes(4, "big"))
    write("opera-partial.bin", opera_header_as_directory(root_block=1) + partial)
    write("opera-partial.cue", cue(b"opera-partial.bin", b"MODE1/2048"))
    must_pass.append(("an OperaFS volume that ends 16 bytes into its directory",
                      43, "opera-partial.cue", refused("Could not find LaunchMe")))
    # The volume's first sector, cut. A track of a cue sheet, since an .iso
    # that is no whole number of sectors is not opened at all. With 131 bytes
    # it is no 3DO disc; with all 132 it is one whose directory is missing.
    # Both are refused whatever the patch, so it is the reason that is asked
    # for.
    for size in (131, 132):
        write(f"opera-{size}.bin", opera_image()[:size])
        write(f"opera-{size}.cue", cue(b"opera-%d.bin" % size, b"MODE1/2048"))
    must_pass += [
        ("an OperaFS volume that ends one byte short of its 132 of header",
         43, "opera-131.cue", refused("Not a 3DO CD")),
        ("the same with all 132, and nothing after them",
         43, "opera-132.cue", refused("Could not find LaunchMe")),
    ]
    # Volumes that are right: a directory of two sectors, and one of 257,
    # which is as far as the search is let go. One sector more and LaunchMe is
    # not found.
    disc, opera = opera_disc()
    write("opera.iso", disc)
    chain, chained = opera_chain(hops=256)
    write("opera-chain-256.iso", chain)
    write("opera-chain-257.iso", opera_chain(hops=257)[0])
    must_pass += [
        ("an OperaFS volume with LaunchMe in the second sector of its directory",
         43, "opera.iso", f"{opera}|43"),
        ("the same volume with no console given", 0, "opera.iso", f"{opera}|43"),
        # Forced, these two: the consoles asked before this one read sector 16
        # as something else, and what they make of a directory is their affair.
        ("an OperaFS directory of 257 sectors with LaunchMe in the last",
         43, "opera-chain-256.iso", f"{chained}|43"),
        ("the same with 258 sectors", 43, "opera-chain-257.iso", REFUSED),
    ]

    # ---- local patch 0003: a playlist whose first item is a playlist.
    write("self.m3u", b"self.m3u\n")
    write("a.m3u", b"b.m3u\n")
    write("b.m3u", b"a.m3u\n")
    write("LOUD.M3U", b"LOUD.M3U\r\n")
    # The shortest name a playlist can have, which is all extension.
    write(".m3u", b".m3u\n")
    # One character, so that looking four back from its end for ".m3u" is
    # looking before the text that was read.
    write("short.m3u", b"a\n")
    for text, name in (
            ("a playlist that names itself", "self.m3u"),
            ("two playlists that name each other", "a.m3u"),
            ("a playlist in capitals that names itself", "LOUD.M3U"),
            ("a playlist named .m3u and nothing more, that names itself", ".m3u")):
        must_pass += [(f"{text}, with no console given", 0, name, refused("another playlist")),
                      ("the same under console 12", 12, name, refused("another playlist"))]
    must_pass.append(("a playlist whose item is one character, of a file that is not there",
                      0, "short.m3u", REFUSED))

    # ---- local patch 0004: the main.dol of a GameCube disc, and of a Wii
    # partition that is not encrypted.
    write("dol-one-gigabyte.iso", dol_image())
    write("dol-eighteen.iso", dol_image(segments=18, size=0xFFFFFFFF))
    for text, name in (
            ("a GameCube image with a 1 GiB segment in 1496 bytes", "dol-one-gigabyte.iso"),
            ("a GameCube image with 18 segments of 4 GiB in 1496 bytes", "dol-eighteen.iso")):
        must_pass += [(f"{text}, with no console given", 0, name, REFUSED),
                      ("the same under console 16", 16, name, REFUSED)]
    # Those two end before the word that says how long the header is, and are
    # refused there. So the same two lies again, told by a disc that is whole
    # up to its segments: the last one a gigabyte long, which is refused at
    # the second piece of it, and all 18 of them 4 GiB.
    gamecube = gamecube_disc()
    write("gamecube.iso", gamecube)
    write("gamecube-gigabyte.iso", gamecube_disc(sizes={GAMECUBE_LAST_SEGMENT: 1 << 30}))
    write("gamecube-eighteen.iso", gamecube_disc(sizes={n: 0xFFFFFFFF for n in range(18)}))
    # A disc that is right, whose last segment ends with the file; and the
    # same less its last byte.
    write("gamecube-less-one.iso", gamecube[:-1])
    must_pass += [
        ("a GameCube disc of three segments, the last longer than 1 MiB and ending the file",
         16, "gamecube.iso", f"{md5(nintendo_partition(gamecube, 0, 0))}|16"),
        ("the same disc with no console given",
         0, "gamecube.iso", f"{md5(nintendo_partition(gamecube, 0, 0))}|16"),
        ("the same disc without its last byte",
         16, "gamecube-less-one.iso", refused("segment 7 runs past the end")),
        ("the same disc with its last segment said to be 1 GiB long",
         16, "gamecube-gigabyte.iso", refused("segment 7 runs past the end")),
        ("the same disc with 18 segments of 4 GiB",
         16, "gamecube-eighteen.iso", refused("segment 0 runs past the end")),
    ]
    # The header's size brought round to 0x424, the least that holds the word
    # read out of it, to 0x423 and to nothing.
    least = gamecube_disc(header_size=0x424)
    write("gamecube-header-424.iso", least)
    write("gamecube-header-423.iso", gamecube_disc(header_size=0x423))
    write("gamecube-header-0.iso", gamecube_disc(header_size=0))
    must_pass += [
        ("a GameCube disc whose apploader sizes bring its header to 0x424 bytes",
         16, "gamecube-header-424.iso", f"{md5(nintendo_partition(least, 0, 0))}|16"),
        ("the same with 0x423", 16, "gamecube-header-423.iso", refused("shorter than its own fields")),
        ("the same with no header at all", 16, "gamecube-header-0.iso", refused("shorter than its own fields")),
    ]
    # The same disc cut off before its segments, at each thing that is read on
    # the way to them: inside the second of the apploader's two sizes, inside
    # the header, where the header ends, one byte before the end of the table
    # of segments, and where that ends. Each read that comes back short is
    # refused by the next thing that is read as well, if not by itself, so
    # the reason is asked for. With none of the reads looked at, what such a
    # file did was up to what the stack and the allocator held: refused by
    # one build and hashed by the other.
    for size, text, saying in (
            (0x245A, "inside the apploader's sizes", "Apploader sizes"),
            (0x2700, "inside its header", "Partition header runs past the end"),
            (GAMECUBE_HEADER_SIZE, "where its header ends", "main.dol header runs past the end"),
            (GAMECUBE_DOL + 0xD8 - 1, "one byte before the end of main.dol's table",
             "main.dol header runs past the end"),
            (GAMECUBE_DOL + 0xD8, "where main.dol's table ends", "segment 0 runs past the end")):
        write(f"gamecube-cut-{size:x}.iso", gamecube[:size])
        must_pass.append((f"the GameCube disc cut off {text}",
                          16, f"gamecube-cut-{size:x}.iso", refused(saying)))
    # One of the apploader's two sizes with its top bit set, first the one and
    # then the other. Put together signed, as upstream does it, that is a
    # shift C leaves undefined and the sanitized build reports. Unsigned, it
    # is a header of 2 GiB, of which 1 MiB is hashed like any that is longer
    # than that, and the disc has it.
    for at, name in ((0x2454, "body"), (0x2458, "trailer")):
        image = bytearray(gamecube)
        put(image, 0x2454, bytes(8))
        put(image, at, (0x80000000).to_bytes(4, "big"))
        write(f"gamecube-{name}-top-bit.iso", image)
        must_pass.append((f"the GameCube disc with its apploader's {name} said to be 2 GiB long",
                          16, f"gamecube-{name}-top-bit.iso",
                          f"{md5(nintendo_partition(image, 0, 0))}|16"))
    # Nothing but the magic word in 8 KiB: no size for the header, which is
    # past the end, and a main.dol at byte 0 with no segment that has a size.
    # It used to hash, by what was in memory.
    magic_only = bytearray(0x2000)
    put(magic_only, 0x1C, GAMECUBE_MAGIC)
    write("gamecube-magic-only.iso", magic_only)
    must_pass.append(("8 KiB of nothing with the GameCube magic word",
                      16, "gamecube-magic-only.iso", REFUSED))

    # (what the row shows, console, path from the folder of fixtures). The six
    # files there were are rows above since local patches 0001 to 0004.
    #
    # What is left is not a file. An arcade set's hash is the MD5 of its
    # file's name without the extension, and rcheevos finds the length of that
    # by taking one off for the dot. A path that ends in a separator has a
    # name of no letters and no dot, the length is one below zero, which is
    # the most a size can hold, and rcheevos hashes the 64 MiB it cuts that
    # down to, starting where the name would be: a signal as soon as the
    # memory there is runs out. Not patched: rahasher_jni.c refuses a path
    # that ends in either separator before rcheevos sees it, which is held by
    # NativeCrashReproTest, and upstream has since rewritten these lines.
    known_gaps = [
        ("a path that ends in a separator, under console 27",
         27, "mslug.zip/"),
    ]
    return must_pass, known_gaps


# ----------------------------------------------------------------- one run

class Outcome:
    """How one run of rahash_cli ended: `kind` is hash, refused, signal,
    timeout or exit, and `text` what goes with it (for a timeout, the seconds
    it was given)."""

    def __init__(self, kind, text, status=None, stderr=""):
        self.kind = kind
        self.text = text
        self.status = status
        self.stderr = stderr

    def __str__(self):
        if self.kind == "hash":
            return f"hash {self.text}"
        if self.kind == "refused":
            return f"no hash: {self.text!r}"
        if self.kind == "signal":
            return f"signal {self.text}"
        if self.kind == "timeout":
            return f"still running after {self.text} s, killed"
        return f"exit {self.status}: {self.text!r}"

    def sanitizer_report(self):
        """The line of the report, or None when no sanitizer spoke."""
        for line in self.stderr.splitlines():
            if any(word in line for word in SANITIZER_WORDS):
                return line.strip()
        if self.status == SANITIZER_EXIT:
            return f"exit {SANITIZER_EXIT}"
        return None


def run(binary, console, path, extra_env=None, seconds=TIMEOUT_SECONDS):
    env = dict(os.environ)
    env.update(extra_env or {})
    try:
        done = subprocess.run([str(binary), str(console), str(path)], env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=seconds)
    except subprocess.TimeoutExpired as late:   # run() has killed it by now
        return Outcome("timeout", str(seconds),
                       stderr=(late.stderr or b"").decode("utf-8", "replace"))

    out = done.stdout.decode("utf-8", "replace").strip()
    err = done.stderr.decode("utf-8", "replace").strip()
    status = done.returncode
    if status < 0:
        try:
            name = signal.Signals(-status).name
        except ValueError:
            name = "?"
        return Outcome("signal", f"{-status} ({name})", status, err)
    if status == 0 and HASH_LINE.fullmatch(out):
        return Outcome("hash", out, status, err)
    if status == 1 and not out:
        return Outcome("refused", err, status, err)
    return Outcome("exit", (out + " " + err).strip(), status, err)


def check_must_pass(outcome, answer, sanitized):
    """None when the row holds, or what is wrong with it."""
    if sanitized:
        report = outcome.sanitizer_report()
        if report:
            return f"the sanitizer reported: {report}"
    saying = None
    if isinstance(answer, tuple):
        answer, saying = answer
    if answer == REFUSED:
        if outcome.kind != "refused":
            return f"expected no hash and a reason, got {outcome}"
        if not outcome.text:
            return "no hash, as expected, but no reason came with it"
        if saying and saying not in outcome.text:
            return f"no hash, as expected, but the reason does not say {saying!r}: {outcome.text!r}"
        return None
    if outcome.kind != "hash" or outcome.text != answer:
        return f"expected hash {answer}, got {outcome}"
    return None


def check_known_gap(outcome):
    if outcome.kind == "refused":
        return (f"refused cleanly ({outcome.text!r}): this gap is closed, "
                "so make the row one that must pass")
    if outcome.kind not in ("hash", "signal", "timeout"):
        return f"neither a hash, a signal nor a timeout: {outcome}"
    return None


def check_sanitized(binary):
    """What shows that `binary` is not a sanitized build, a line for each
    fault it let through; empty when it reported both. Prints as it goes."""
    missing = []
    print("the sanitized build reports a fault made on purpose:")
    for kind, word, what in FAULTS:
        env = dict(os.environ)
        env.update(SANITIZER_ENV)
        try:
            done = subprocess.run([str(binary), f"--fault={kind}"], env=env,
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, timeout=TIMEOUT_SECONDS)
            status = done.returncode
            report = next((line.strip() for line in
                           done.stderr.decode("utf-8", "replace").splitlines()
                           if word in line), None)
        except subprocess.TimeoutExpired:
            status, report = "none, killed", None
        if status == SANITIZER_EXIT and report:
            print(f"  ok   {what}: exit {status}, {report[:100]}")
        else:
            print(f"  FAIL {what}: exit {status}, "
                  + (report[:100] if report else f"and no line with {word!r} on stderr"))
            missing.append(f"-fsanitize={kind}")
    return missing


# ---------------------------------------------------------------------- main

def build_tools(directory):
    """native/build.sh, into `directory`: the library is built as well, and
    left there, since the script has no way to build the tools alone."""
    script = SHARED / "native" / "build.sh"
    done = subprocess.run(["bash", str(script), str(directory / "out"), f"--tools={directory}"],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if done.returncode != 0:
        sys.stderr.write(done.stdout.decode("utf-8", "replace"))
        raise SystemExit(f"native_repro_test: {script} failed, so there is nothing to run")


def main(argv):
    if len(argv) > 2 or (len(argv) == 2 and argv[1].startswith("-")):
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("usage: native_repro_test.py [tools-dir]", file=sys.stderr)
        return 2

    # A row that ends with a signal is this test working, not something to keep
    # a core file of. Children inherit the limit.
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

    with tempfile.TemporaryDirectory(prefix="native-repro-") as tmp:
        tmp = Path(tmp)
        if len(argv) == 2:
            tools = Path(argv[1]).resolve()
        else:
            tools = tmp / "tools"
            tools.mkdir()
            build_tools(tools)
        plain, sanitized = tools / "rahash_cli", tools / "rahash_cli_sanitized"
        for binary in (plain, sanitized):
            # Without this a missing program would be every known gap "still
            # open": not being there is not a clean refusal either.
            if not (binary.is_file() and os.access(binary, os.X_OK)):
                print(f"native_repro_test: no {binary.name} in {tools}; "
                      "build it with native/build.sh <out> --tools=<dir>", file=sys.stderr)
                return 2

        missing = check_sanitized(sanitized)
        if missing:
            sys.stdout.flush()  # the lines above first, when both go to one log
            print(f"native_repro_test: {sanitized.name} in {tools} is not built with "
                  f"{' and '.join(missing)}, or does not end with {SANITIZER_EXIT} on a report. "
                  "No row was run: on such a build a row with no report shows nothing.",
                  file=sys.stderr)
            return 2

        files = tmp / "files"
        files.mkdir()
        must_pass, known_gaps = write_fixtures(files)

        failures = 0
        builds = ((plain, "plain", None), (sanitized, "sanitized", SANITIZER_ENV))
        for binary, label, env in builds:
            print(f"must pass, {label} build:")
            for what, console, name, answer in must_pass:
                outcome = run(binary, console, files / name, env, MUST_PASS_SECONDS)
                wrong = check_must_pass(outcome, answer, sanitized=env is not None)
                if wrong:
                    failures += 1
                    print(f"  FAIL {what} — {wrong}")
                else:
                    print(f"  ok   {what}: {outcome}")

        if known_gaps:
            print("known gaps, plain build (each of these is a defect still there):")
        else:
            print("known gaps: none")
        for what, console, name in known_gaps:
            # Joined as text: a Path would drop the separator a row may end with.
            outcome = run(plain, console, f"{files}{os.sep}{name}")
            wrong = check_known_gap(outcome)
            if wrong:
                failures += 1
                print(f"  FAIL {what} — {wrong}")
            else:
                print(f"  gap  {what}: {outcome}")

    rows = 2 * len(must_pass) + len(known_gaps)
    if failures:
        print(f"\nnative_repro_test: {failures} of {rows} rows failed")
        return 1
    print(f"\nnative_repro_test: {rows} rows as expected "
          f"({len(must_pass)} that must pass on two builds, {len(known_gaps)} known gaps)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
