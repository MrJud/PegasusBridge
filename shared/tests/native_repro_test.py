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
more than the file holds ends rcheevos with a signal, and one kind leaves it
reading for ever. In a Gradle test worker the first takes the worker with it
and the second holds the build until its job is killed. Here each file is one
process, killed after ten seconds, and what it did is a line of the output.

Every file is made here, from the few bytes that matter and filler; none is a
game. Two kinds of row:

  must pass   The answer is known before the program runs: the MD5 this script
              works out from the bytes by the rule for that console, or no hash
              and a reason. Run on both builds, and given five seconds,
              where each takes a few thousandths of one: a file that is
              refused is to be refused at once. On the sanitized build a report
              fails the row whatever else it printed. A sanitizer left to
              itself prints its report and exits 1, which is what a refusal
              looks like, and the undefined-behaviour one prints and carries
              on; so both are told to exit 97, and 97 or a report on stderr is
              a failure.

  known gap   A file the vendored rcheevos is known to crash on, hang on or
              hash when it should refuse. Run on the plain build only, and the
              row holds as long as the program does anything but refuse
              cleanly: a signal, a timeout or a hash, whichever, and the line
              says which. Which signal is not pinned, since that is the
              allocator's and the kernel's business. The day a row here ends
              with exit 1 and a reason the gap is closed, the row fails, and
              whoever closed it moves it to the rows that must pass.

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


def wii_image(partition_count=None):
    """0x40020 bytes of nothing with the Wii magic word, and at 0x40000, where
    the table of partitions begins, the count of the first group."""
    image = bytearray(0x40020)
    put(image, 0x18, bytes.fromhex("5D1C9EA3"))
    if partition_count is not None:
        put(image, 0x40000, partition_count.to_bytes(4, "big"))
    return bytes(image)


def opera_image():
    """One sector with the OperaFS signature, blocks of 2048 bytes and a root
    directory in block 16, which the file is too short to hold."""
    image = bytearray(2048)
    put(image, 0, bytes.fromhex("015A5A5A5A5A01"))
    put(image, 0x4C, (2048).to_bytes(4, "big"))
    put(image, 0x64, (16).to_bytes(4, "big"))
    return bytes(image)


def dol_image():
    """1496 bytes with the GameCube magic word, a main.dol at 0x500 and one
    segment in it said to be 1 GiB long. The file ends with the DOL's header."""
    image = bytearray(0x500 + 0xD8)
    put(image, 0x1C, bytes.fromhex("C2339F3D"))
    put(image, 0x420, (0x500).to_bytes(4, "big"))
    put(image, 0x500 + 0x90, (1 << 30).to_bytes(4, "big"))
    return bytes(image)


def write_fixtures(directory):
    """Writes every file the rows name, and returns the rows."""
    def write(name, data):
        (directory / name).write_bytes(data)

    nes = ines()
    write("Repro.nes", nes)

    track = sega_cd_track()
    write("Repro CD.bin", track)
    write("Repro CD.cue", b'FILE "Repro CD.bin" BINARY\r\n'
                          b"  TRACK 01 MODE1/2352\r\n"
                          b"    INDEX 01 00:00:00\r\n")
    write("Repro CD.m3u", b"Repro CD.cue\r\n")
    sega_cd = md5(track[16:16 + 512])   # the first 512 bytes of sector 0's data

    write("mslug.zip", noise(4096, seed="not a zip"))

    # (what the row shows, console, file, the answer)
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
        # printed being the one that answered: the path every known gap below
        # is run through.
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

    write("wii-no-partitions.iso", wii_image())
    write("wii-partition-count.iso", wii_image(partition_count=0x20000000))
    write("opera-short.iso", opera_image())
    write("self.m3u", b"self.m3u\n")
    write("a.m3u", b"b.m3u\n")
    write("b.m3u", b"a.m3u\n")
    write("dol-one-gigabyte.iso", dol_image())

    # (what the row shows, file). All with no console given, which is how the
    # library is asked today.
    known_gaps = [
        ("a Wii image with no partitions", "wii-no-partitions.iso"),
        ("a Wii image that counts 0x20000000 partitions", "wii-partition-count.iso"),
        ("an OperaFS volume whose root directory is past the end", "opera-short.iso"),
        ("a playlist that names itself", "self.m3u"),
        ("two playlists that name each other", "a.m3u"),
        ("a GameCube image with a 1 GiB segment in 1496 bytes", "dol-one-gigabyte.iso"),
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
    if answer == REFUSED:
        if outcome.kind != "refused":
            return f"expected no hash and a reason, got {outcome}"
        if not outcome.text:
            return "no hash, as expected, but no reason came with it"
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

        print("known gaps, plain build (each of these is a defect still there):")
        for what, name in known_gaps:
            outcome = run(plain, 0, files / name)
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
