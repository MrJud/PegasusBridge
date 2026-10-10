# Local patches on the vendored rcheevos

`../rcheevos` is upstream rcheevos 12.3.0 with the patches listed here applied
to it. Each is also a file in this folder, a diff against the upstream file with
paths from the top of the rcheevos tree:

    git apply --directory=hasher/src/main/cpp/rcheevos hasher/src/main/cpp/rcheevos-patches/<patch>

They live outside `../rcheevos` because that folder's own `.gitignore` hides
several extensions, and so that the folder can be compared with an upstream
tree file by file. In the sources every changed place carries a comment that
begins `local patch` and the patch's number. `../pb_patchlevel.h` holds how
many there are.

The numbers are names, not an order: no patch touches a line another does.

A patch is here because a file that is not a valid image of its console made
rcheevos crash, write outside a buffer, or hand back a hash of nothing. None
changes what a valid file hashes to. Every patch has rows in
`shared/tests/native_repro_test.py`, which makes the offending files from a
few bytes each and runs them on a plain build and on one with the address and
undefined-behaviour sanitizers; the rows are named below so that a patch lost
in an upgrade shows as those rows failing. Where a patch changes lines that a
valid file runs through as well, there are rows with valid files beside them,
whose answer is a hash: a bound drawn too tight refuses the good with the bad,
and rows that only want a refusal would all still pass.

When rcheevos is upgraded: apply each patch to the new tree, drop what upstream
has taken, write the diffs again, and keep this file and the patch level true.

## 0005-short-headers.patch

**What.** `rc_hash_7800`, `rc_hash_lynx`, `rc_hash_nes` (which is also the
Famicom Disk System's) and `rc_hash_scv` in `src/rhash/hash_rom.c` refuse a
file that is no longer than the console's header: 128, 64, 16 and 32 bytes.
The error says so, with the size.

**Why.** Each of the four looks for a magic word at the start of the file and,
finding it, hashes what follows the header: `buffer + header`, of length
`buffer_size - header`. For a file shorter than the header that length is a
negative number in an unsigned type, which is a very large one, and the MD5
runs off the end of the buffer until the process dies. A download cut off after
its first bytes is such a file. A file of exactly the header's size was hashed
as zero bytes, and one shorter than the magic word was compared with it past
its own end.

**Upstream.** Fixed another way by 12.5.0, which skips the header test when the
file is not longer than the header and hashes the short file whole. That stops
the crash and gives a hash for six bytes of header, which is looked up like any
other and found nowhere. Here such a file is an error, so that it is recorded
as a file that could not be hashed. On 12.5.0 this patch goes above upstream's
guards and is still needed for that reason.

**Rows.** `tiny.nes`, `tiny.fds`, `tiny.lnx`, `tiny.a78` and `tiny.cart` (a
header cut after its magic word), each with no console given and with its own;
`header-only.*` and `header-and-one.*` for the same five, which hold the four
sizes from both sides; `three.nes` and `empty.nes`.

## 0006-gdi-bounds.patch

**What.** In `cdreader_open_gdi_track` in `src/rhash/cdreader.c`, the parser of
a `.gdi` track sheet:

- a sector size of more than 9 digits is an error;
- a file name is measured before it is copied, and one that does not fit the
  256 bytes kept for it is an error;
- a quoted file name whose closing quote never comes is an error, and a name
  without quotes ends at the end of the text if no space comes first;
- the character after each numeric field is stepped over only when there is
  one;
- the sector size starts out empty.

Unlike the tests of patch 0005, which stand in front of what was there, these
are on the way of every line of every sheet: the digits, the step after each
number and the copy of the name are the parser itself, rewritten.

**Why.** A line of the sheet is `track lba type sectorsize filename offset`,
and the parser copied the sector size and the file name into buffers of 16 and
256 bytes for as long as it met digits, or met no space or quote. The digits
are then copied once more, behind `MODE1/` in another 16 bytes, where 9 fit;
that is where the limit of 9 comes from, a real one being 4. After each number
the parser moved one character on without looking, so a line that ended early
took it past the NUL that closes the text and into whatever the buffer held
before, which it went on to read as the rest of the line. And when the track
asked for has no line at all, the sector size was copied to `MODE1/` without
ever having been set.

**Upstream.** 12.5.0 has the bound on the file name and the error for an
unclosed quote, with these two messages, and nothing else of this patch. Its
loops stop at `end`, which for a sheet longer than one read is three quarters
of the way through the buffer, so there a name that crosses that point is cut
or refused though all of it was read; here they stop at the NUL, which no
valid line reaches. The digits, the steps past the end and the unset sector
size are as they were in 12.3.0.

**Rows.** `digits.gdi` (64 digits) and `digits-12.gdi` (fits the first buffer,
not the second), `long.gdi` and `long-256.gdi` (a name of 300 characters, and
of one more than fits), `quote.gdi`, and `cut-1.gdi` to `cut-4.gdi` (the sheet
ends after the first to the fourth number of its line, and is as long as one
read). On the unpatched code only the sanitized build shows these: the plain
one writes over its own stack, finds no such file and ends as if it had
refused.

Sheets that are right, with a track made for them, and the hash as the answer:
`valid.gdi` (three tracks, the third's name plain) and `valid-quoted.gdi` (the
name in quotes, with a space in it); `valid-first-data.gdi`, whose data track
is its second and follows a longer name, which is what holds the NUL written
after a copied name; and `digits-9.gdi`, nine digits where the track is there
to say its own sector size. `digits-10.gdi` beside it is refused. Those two are
the limit of nine from both sides, and since the track is there, a build that
let ten digits through would hash the sheet: the row fails on the plain build
too, where the one byte written past the buffer is seen by nobody. The unpatched
code hashes that sheet.

What no row holds, and is there by reading:

- the sector size starting out empty. What is read there otherwise is whatever
  the stack held, which differs from build to build and which neither
  sanitizer reports;
- the sheet being closed before each of the three new errors is returned. A
  file left open is not memory, and the leak sanitizer says nothing of it.
  Counted by hand through `/proc/self/fd`: hashing `digits.gdi`, `long.gdi` or
  `quote.gdi` leaves no descriptor open, and two with that error's close
  taken out.

**Left as upstream has it.** When the track a sheet names opens but neither
its content nor the sheet says what its sectors are, the function reports
"Could not open" and frees the track without closing the file; the parser of a
cue sheet, above it in the same file, closes it. That is two descriptors for
each time such a sheet is hashed, in 12.5.0 as well. A sheet with no line for
the track asked for goes the same way where a folder can be opened as a file,
since the name is then empty and what is opened is the sheet's own folder:
`cut-1.gdi` to `cut-3.gdi` do, and so does the first of the two tries at
`valid-first-data.gdi`, which then hashes.
