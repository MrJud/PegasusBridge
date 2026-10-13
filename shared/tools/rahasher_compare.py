#!/usr/bin/env python3
"""Hashes the synthetic disc images with our hasher and with RAHasher, the
program RetroAchievements gives out for the purpose, and says where the two
part.

    ./tools/rahasher_compare.py --rahasher <RAHasher> --ours <tools-dir>/rahash_cli
                                [--also <console>:<file> ...] [--out <table.tsv>]

RAHasher is part of RALibretro and is not in this repository, nor is it
downloaded or built by anything here: whoever runs this has built it. Our
program is the one `native/build.sh <dir outside the checkout> --tools=<dir>`
leaves in <dir>. Nothing is asked of RetroAchievements, and no CI job runs
this script; tools/rahasher_compare_test.py tests it with two programs it
writes itself.

The images are the ones tests/native_repro_test.py makes, written to a
temporary folder:

  - the images chdman wrote (tests/chd/), each with the disc it was made
    from. Both programs hash the image and the disc.
  - the images tests/chd_fixture.py writes that are well formed, and the ones
    our reader refuses although nothing in them is damaged: a track it will
    not place. For these the answer the test expects of a hashed image
    stands for the disc, and a refused one is held against the image of the
    same disc that is hashed. Some have their disc written here, as a sheet
    of the same tracks (discs() below): the one image the two programs were
    found to part on, so that its line says which of them left the disc, and
    the ones a console that asks for the largest track gets another answer
    for from the image than from the sheet, or might.

The images made to be damaged, a map that runs past the end or a hunk with
another checksum, are left out. What a program does with those is its own
robustness and says nothing of where a track is.

One line an image, with tabs between: the image, the console, our hash and
RAHasher's of the image, the disc, our hash and RAHasher's of the disc, and
a class. `-` is no hash. RAHasher's hash is the first line it prints when
that is 32 hexadecimal digits, and anything else is none; it is run without
-v, which prints messages where the hash goes.

  AGREE                           both give the same hash
  BOTH-REFUSE                     neither gives one
  WE-REFUSE-THEIRS-IS-THE-DISC'S  we give none and RAHasher gives the disc's
  WE-REFUSE-THEIRS-IS-ANOTHER     we give none and RAHasher gives another
  ONLY-OURS                       we give a hash and RAHasher none
  THEIRS-CRASHED                  RAHasher was ended by a signal, or was
                                  still running after 60 seconds
  VERSION                         the two differ on the image, and differ on
                                  the plain disc already: the rcheevos inside
                                  each is another, and the image is not why
  DIFFER                          we give a hash and RAHasher another, for a
                                  disc both hash alike

DIFFER is the one the exit status is for: one of the two readers is wrong
about that image. Where the line has a disc and both hash it to what we give
for the image, it is RAHasher's reading of the image that left the disc.
What the table can not say is which hash RetroAchievements
lists for a real game: that takes a real disc and the lists. --also adds a
line for a file of the caller's own, with no disc to hold it against.

Exit status 0; 1 when a line is DIFFER; 2 when a program cannot be run, which
includes ours ending any way but with a hash or a refusal.
"""

import argparse
import os
import re
import resource
import signal
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tools/ or tests/
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tests"))

TIMEOUT_SECONDS = 60
HASH = re.compile(r"[0-9a-f]{32}")
NONE = "-"
CRASHED = "crashed"

# The images our reader refuses with nothing in them damaged, beyond the ones
# refused in the words native_repro_test.PLACED_DIFFERENTLY, and for each the
# image of the same disc that is hashed. The first holds its last track's
# pregap in the image and is opened, and the program is then not found. The
# second is a PC-FX disc whose data track is the first of two: asked for the
# largest track, both programs read the audio for it. The third is one whose
# data track lies behind a track of another kind and the same length, which
# both read it as. No image of either's disc is hashed, so the disc is
# written here, as the others of discs() are.
ALSO_REFUSED = {"dreamcast-last-pregap.chd": "dreamcast-last.chd",
                "pcfx-first.chd": None,
                "pcfx-kind-of-largest.chd": None}
# The images asked for their largest track whose disc is written here.
LARGEST_AS_SHEETS = ("pce-largest-first.chd", "pcfx-first.chd", "pcfx-two.chd", "pcfx-last-misplaced.chd",
                     "pcfx-kind-of-largest.chd", "pcfx-length-of-largest.chd")
# A GD-ROM whose program begins its fourth track. RAHasher 1.8.4 was found to
# give for it a hash that is not the disc's, so its line has the disc itself.
LAST_FIRST = "dreamcast-last-first.chd"
# The same for the images that are not placed: the disc each was written from.
NOT_PLACED = {"pce-unstored-pregap.chd": "pce.chd",
              "dreamcast-redump-cue.chd": "dreamcast.chd",
              "dreamcast-gdi-2026.chd": "dreamcast.chd"}

COLUMNS = ["image", "console", "ours", "rahasher", "disc", "ours-on-disc", "rahasher-on-disc", "class"]


class CannotRun(Exception):
    """One of the two programs is not there, or ours ended as it never should."""


class Line:
    """An image to hash and what it is held against: `disc` a file both
    programs are to hash as well, or `expected` a hash that stands for one."""

    def __init__(self, image, console, disc=None, expected=None):
        self.image, self.console, self.disc, self.expected = image, console, disc, expected


def cue_sheet(name, tracks, write):
    """Writes a disc as a cue sheet, a file to a track, and returns the
    sheet's name. The tracks are the ones chd_fixture.cd makes the image of:
    a pregap that is in the image is in the track's file, ahead of its first
    sector, and the sheet says so with an INDEX 00."""
    stem = "d-" + name.rsplit(".", 1)[0]
    sheet = b""
    for number, track in enumerate(tracks, 1):
        sectors = track["sectors"]
        mode = b"AUDIO" if track["type"] == "AUDIO" else b"MODE1/%d" % len(sectors[0])
        gap = track.get("pregap", 0) if track.get("pgtype", "").startswith("V") else 0
        file = f"{stem}-{number}.bin"
        write(file, b"".join(sectors))
        sheet += b'FILE "%s" BINARY\r\n  TRACK %02d %s\r\n' % (file.encode("ascii"), number, mode)
        if gap:
            sheet += b"    INDEX 00 00:00:00\r\n"
        sheet += b"    INDEX 01 %02d:%02d:%02d\r\n" % (gap // 4500, gap // 75 % 60, gap % 75)
    write(stem + ".cue", sheet)
    return stem + ".cue"


def last_first_disc(repro, write):
    """Writes the disc LAST_FIRST is an image of, as a .gdi sheet of its four
    tracks, and returns the sheet's name. The third track and the program
    are made as native_repro_test.chd_rows makes them for the image."""
    third, _ = repro.dreamcast_track()
    last_at = repro.DREAMCAST_FIRST_SECTOR + len(third) // 2048
    moved = bytearray(third)
    repro.put(moved, 18 * 2048 + 2, last_at.to_bytes(3, "little"))
    program = bytes(moved[20 * 2048:22 * 2048])
    repro.put(moved, 20 * 2048, bytes(2 * 2048))
    write("d-last-1.bin", b"".join(repro.raw_sectors(repro.noise(8 * 2048, seed="gd-low") + bytes(292 * 2048), 1)))
    write("d-last-2.raw", bytes(100 * 2352))
    write("d-last-3.iso", bytes(moved))
    write("d-last-4.iso", program + bytes(6 * 2048))
    write("d-last.gdi", b"4\n1 0 4 2352 d-last-1.bin 0\n2 450 0 2352 d-last-2.raw 0\n"
                        b"3 %d 4 2048 d-last-3.iso 0\n4 %d 4 2048 d-last-4.iso 0\n"
                        % (repro.DREAMCAST_FIRST_SECTOR, last_at))
    return "d-last.gdi"


def discs(repro):
    """The images whose disc is written here, and for each what writes it and
    returns the sheet's name: the discs asked for their largest track, as cue
    sheets of the tracks native_repro_test gives the images, and LAST_FIRST."""
    table = {name: (lambda write, name=name, tracks=tracks: cue_sheet(name, tracks, write))
             for name, tracks in repro.largest_track_discs()
             if name in LARGEST_AS_SHEETS}
    table[LAST_FIRST] = lambda write: last_first_disc(repro, write)
    return table


def fixture_lines(directory):
    """Writes the images into `directory` and returns their lines: chdman's
    first, in the order of CHDMAN_IMAGES, then the fixtures by name."""
    import native_repro_test as repro

    def write(name, data):
        (directory / name).write_bytes(data)

    rows = repro.chd_rows(write)
    repro.chdman_sources(write)
    lines = []
    for name, source, _, console, _ in repro.CHDMAN_IMAGES:
        write(name, (repro.CHDMAN_FOLDER / name).read_bytes())
        lines.append(Line(name, console, disc=source))

    hashed = {}     # image -> the hash its row expects, without the console
    kept = {}
    for _, console, name, answer in rows:
        # A row with no console given is the same image asked another way,
        # and RAHasher is always told the console.
        if console == 0 or not str(name).lower().endswith(".chd"):
            continue
        if isinstance(answer, str) and repro.HASH_LINE.fullmatch(answer):
            hashed[str(name)] = answer.split("|")[0]
            kept.setdefault(str(name), console)
        elif answer == repro.EITHER:
            kept.setdefault(str(name), console)
        elif (isinstance(answer, tuple) and answer[1] == repro.PLACED_DIFFERENTLY) or str(name) in ALSO_REFUSED:
            kept.setdefault(str(name), console)
    stands_for = dict(ALSO_REFUSED, **NOT_PLACED)
    written = discs(repro)
    for name in sorted(kept, key=str.lower):
        if name in written:
            lines.append(Line(name, kept[name], disc=written[name](write)))
            continue
        expected = hashed.get(name) or hashed.get(stands_for.get(name))
        lines.append(Line(name, kept[name], expected=expected))
    return lines


def run(argv, cwd, seconds):
    """(status, stdout, stderr) of a program, the status None when it was
    still running at the end of its time."""
    try:
        done = subprocess.run([str(a) for a in argv], cwd=cwd, stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=seconds)
    except subprocess.TimeoutExpired:
        return None, "", ""
    except OSError as e:
        raise CannotRun(f"{argv[0]}: {e.strerror}")
    return done.returncode, done.stdout.decode("utf-8", "replace"), done.stderr.decode("utf-8", "replace")


def ours(binary, console, path, seconds):
    """Our hash of a file, or None when it is refused in order."""
    status, out, err = run([binary, console, path.name], path.parent, seconds)
    out = out.strip()
    if status == 0 and re.fullmatch(HASH.pattern + r"\|[0-9]+(\|[0-9]+\|[0-9]+)?", out):
        return out.split("|")[0]
    if status == 1 and not out:
        return None
    how = (f"still running after {seconds} s" if status is None
           else f"signal {signal.Signals(-status).name}" if status < 0
           else f"exit {status}: {(out + ' ' + err).strip()!r}")
    raise CannotRun(f"{binary} on {path.name} under console {console}: {how}")


def theirs(binary, console, path, seconds):
    """RAHasher's hash of a file, None when it gives none, CRASHED when it
    was ended by a signal or by its time. Only the line is read, and not
    the status it ends with: with -v it prints messages where the hash goes,
    one of them a hash it then gives up, and so a line is a hash by its form
    and by being the first."""
    status, out, _ = run([binary, console, path.name], path.parent, seconds)
    if status is None or status < 0:
        return CRASHED
    first = out.split("\n", 1)[0].strip()
    return first if HASH.fullmatch(first) else None


def classify(our_image, their_image, our_disc, their_disc):
    """The class of a line. The disc's hashes are None where there is no disc
    or a program gave none for it."""
    if their_image == CRASHED:
        return "THEIRS-CRASHED"
    if our_image is None and their_image is None:
        return "BOTH-REFUSE"
    if our_image == their_image:
        return "AGREE"
    if our_image is None:
        # RAHasher's own word for the disc where it has one: it is its
        # reading of the image that is being held against its reading of
        # the disc.
        disc = their_disc if their_disc not in (None, CRASHED) else our_disc
        return "WE-REFUSE-THEIRS-IS-THE-DISC'S" if their_image == disc else "WE-REFUSE-THEIRS-IS-ANOTHER"
    if our_disc != their_disc and their_disc != CRASHED:
        return "VERSION"
    return "ONLY-OURS" if their_image is None else "DIFFER"


def compare(lines, our_binary, their_binary, directory, seconds=TIMEOUT_SECONDS):
    """A row of the table for each line, as a list of cells."""
    table = []
    for line in lines:
        image = directory / line.image
        our_image = ours(our_binary, line.console, image, seconds)
        their_image = theirs(their_binary, line.console, image, seconds)
        if line.disc is not None:
            disc = directory / line.disc
            our_disc = ours(our_binary, line.console, disc, seconds)
            their_disc = theirs(their_binary, line.console, disc, seconds)
            shown = (line.disc, our_disc or NONE, their_disc or NONE)
        else:
            # The answer that stands for the disc is nobody's reading of
            # one, so it is in neither program's column.
            our_disc = their_disc = line.expected
            shown = (line.expected or NONE, NONE, NONE)
        table.append([line.image, str(line.console), our_image or NONE, their_image or NONE, *shown,
                      classify(our_image, their_image, our_disc, their_disc)])
    return table


def also(text):
    console, _, path = text.partition(":")
    if not console.isdigit() or not path:
        raise argparse.ArgumentTypeError(f"'{text}' is not <console>:<file>")
    return int(console), Path(path)


def main(argv):
    parser = argparse.ArgumentParser(
        prog="rahasher_compare.py", description="Compares our hasher with RAHasher over the synthetic discs.")
    parser.add_argument("--rahasher", required=True, type=Path, metavar="PROGRAM")
    parser.add_argument("--ours", required=True, type=Path, metavar="PROGRAM")
    parser.add_argument("--also", action="append", default=[], type=also, metavar="CONSOLE:FILE")
    parser.add_argument("--out", type=Path, metavar="TABLE")
    try:
        args = parser.parse_args(argv[1:])
    except SystemExit as e:
        return 2 if e.code else 0

    for binary in (args.rahasher, args.ours):
        if not (binary.is_file() and os.access(binary, os.X_OK)):
            print(f"rahasher_compare: {binary} is not a program that can be run", file=sys.stderr)
            return 2
    for _, path in args.also:
        if not path.is_file():
            print(f"rahasher_compare: no file at {path}", file=sys.stderr)
            return 2
    our_binary, their_binary = args.ours.resolve(), args.rahasher.resolve()

    # A program ended by a signal is a line of the table, not something to
    # keep a core file of. Children inherit the limit.
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

    try:
        with tempfile.TemporaryDirectory(prefix="rahasher-compare-") as tmp:
            tmp = Path(tmp)
            table = compare(fixture_lines(tmp), our_binary, their_binary, tmp)
        for console, path in args.also:
            path = path.resolve()
            table += compare([Line(path.name, console)], our_binary, their_binary, path.parent)
    except CannotRun as e:
        print(f"rahasher_compare: {e}", file=sys.stderr)
        return 2

    text = "".join("\t".join(cells) + "\n" for cells in [COLUMNS] + table)
    sys.stdout.write(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    classes = Counter(cells[-1] for cells in table)
    print(f"{len(table)} images: " + ", ".join(f"{classes[c]} {c}" for c in sorted(classes)), file=sys.stderr)
    return 1 if classes["DIFFER"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
