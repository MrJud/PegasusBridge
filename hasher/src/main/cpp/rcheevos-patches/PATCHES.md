# Local patches on the vendored rcheevos

`../rcheevos` is upstream rcheevos 12.5.0 with the patches listed here applied
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
rcheevos crash, write or read outside a buffer, never come back, lose a file
it had opened, or hand back a hash of nothing or of bytes the file does not
have. None changes what a valid file hashes to. Every patch but the last has
rows in `shared/tests/native_repro_test.py`, which makes the offending files
from a few bytes each and runs them on a plain build and on one with the
address and undefined-behaviour sanitizers; the rows are named below so that a
patch lost in an upgrade shows as those rows failing. The program the rows are
run with also counts the files it has open before and after, and a row that
leaves one open fails on either build. Where a patch changes lines that a
valid file runs through as well, there are rows with valid files beside them,
whose answer is a hash: a bound drawn too tight refuses the good with the bad,
and rows that only want a refusal would all still pass. And where a file would
be refused a few lines further on if the patched line were not there, the row
says what the reason has to say, since nothing else tells the two apart.

`../rcheevos.version` says which upstream tree the folder is: the release, the
commit, and every file of it with the id git gives its content.
`shared/tests/vendored_check.py` holds the folder to that list and to the
patches here: every file of upstream's tree in git and no other, each as
upstream has it or as upstream has it once the patches are taken off again,
and a section below for every patch file, as many as the patch level says.

When rcheevos is upgraded: put the new tree in the folder's place with
`git add -f`, since the tree's own `.gitignore` hides files of it from a plain
add; apply each patch to it, drop what upstream has taken, and write the diffs
again; write the manifest again from the upstream tree as it was unpacked, its
`upstream` and `patch` lines by hand and a `file` line for every file,

    find . -type f | sed 's|^\./||' | LC_ALL=C sort | while IFS= read -r f; do
        echo "file $(git hash-object --no-filters "$f") $f"; done

with `left-out` in place of `file` for one that is kept out of the copy on
purpose, which the check then wants absent; and keep this file and the patch
level true. Then build the Android library from nothing (remove `hasher/.cxx`
first). A tree copied out of an archive with its dates carries upstream's,
which are older than the objects of the last build, and a build that goes by
dates takes a file the release changed and no patch touched for one it has
compiled already: after the upgrade to 12.5.0 the app's library was linked
with 12.3.0's `hash_zip.c` under the new version's name until that was done.
`shared/native/build.sh` compiles every file every time and is not caught by
this. What the upgrade from 12.3.0 to
12.5.0 took off these patches is said under each, where it says what upstream
does.

## 0001-wii-partition-table.patch

**What.** In `rc_hash_wii_disc` in `src/rhash/hash_disc.c`, which reads the
table of partitions a Wii disc has at 0x40000 and hashes what the table leads
to:

- every read has to bring back all that was asked for: the disc's header and
  its region code, each of the table's eight words, the two words of every
  partition's entry, and for every partition that is hashed the four words
  that say where its title metadata and its data are, the title metadata, and
  each cluster of the data. One that does not is an error and ends the
  function;
- every word is put together as an unsigned number;
- a group that names more than 64 partitions is an error;
- the size of the table to allocate is worked out as a `size_t`, and an
  allocation that fails is an error.

**Why.** The table is four groups, for each a count of partitions and where
their entries are. The function added the four counts in 32 bits, allocated
eight bytes for each partition of the sum, and then filled the table group by
group, by the counts. Counts of 0xFFFFFFFF and 2 add up to 1: eight bytes
allocated, and entries written for as long as the process lasted. A count of
0x20000000 times eight is nothing in 32 bits, with the same end.

No read was looked at, before the table, in it or after it. What a read did
not fill was used as it stood: hashed, or taken for a count, an offset or a
size. So an image cut short was given a hash, and not always the same one. An
image that ends with its table has no region code, and the four bytes hashed
in its place were what the stack held: one program gave one file two hashes,
by whether it had been told the console or had found it out. An image that
ends after the first word of its table, which says 1, was four groups of one
partition each. A partition the file does not hold to its end was hashed as
the title metadata the buffer happened to hold, which the plain build and the
sanitized one did not agree on, and, for each of its clusters, as the last
cluster that could be read: for 256 partitions said to be as long as can be,
8 GiB of hashing out of a file of 450 KiB, nine seconds of it where it was
measured.

64 is a guess with room in it. A disc has two or three partitions, and no
image was at hand to see the most there are. It is one constant,
`MAX_GROUP_PARTITION_COUNT`, and it is a limit for each group: the sum of
four counts is where the defect was, and a limit on a sum that wraps limits
nothing. So a table may still name 256 partitions, and 1024 clusters of each
are hashed. With every cluster having to be in the file that takes an image
of 32 MiB at the least, and it is all a file can make this function do:
measured with such an image, 9.5 seconds for 256 partitions that are all the
same one, and 2.4 for 64. A limit on the sum beside the one on each group
would be one line more.

**Upstream.** Up to 12.3.0 an image with no partitions was closed here and
again by `rc_hash_wii`, which had opened it, at which the C library ends the
process, and this patch took that close out, with the one where the first
buffer cannot be had. 12.5.0 has taken both out itself, and they are no longer
part of the patch. It has nothing else of it: the sum, the allocation and the
reads are as they were.

**Rows.** For the table: `wii-no-partitions.iso` (the second close, which is
upstream's to keep out now and a row still),
`wii-partition-count.iso` (0x20000000) and `wii-count-wraps.iso` (0xFFFFFFFF
and 2, which is also what needs the table's words unsigned), each with no
console given and as a Wii disc; the two with a count ask for the reason,
since the first of them is refused without the limit as well, by its entries
not being there. `wii-1024.iso` is the file that ends long before any of it.
`wii-entry-missing.iso` and `wii-entry-cut.iso` name two partitions and end
after the first entry and inside the second.

For what is read before the table: `wii-header-cut.iso` (one byte short of
the header), `wii-table-and-no-more.iso` and `wii-update-and-no-more.iso`
(the table is whole and the region code is past the end; these are the files
that had two hashes), `wii-region-cut.iso` (three of the region code's four
bytes), beside which `wii-region-whole.iso` gets as far as the table.

For what a partition names: the disc that is right, cut off right after its
region code, inside the second word that names its title metadata, one byte
before the end of the title metadata, inside its first cluster and one byte
before the end of its last (`wii-cut-4e004.iso`, `-502ab`, `-504c7`,
`-5c000`, `-67fff`), beside which `wii-cut-whole.iso`, cut where the last
cluster ends, hashes as the disc does; `wii-cut-data-words.iso`, which ends
inside the word that says how long the data is; and
`wii-256-partitions.iso`, the nine seconds, refused at the first cluster
that is not there. Each of these is refused by the next read if not by its
own, so each asks for the reason.

For the words: `wii-entry-top-bit.iso`, `wii-top-bit-kind.iso`,
`wii-top-bit-metadata-size.iso`, `wii-top-bit-metadata-offset.iso` and
`wii-top-bit-data-offset.iso` each have one word with its top bit set that is
the first such word read, and `wii-256-partitions.iso` has the sixth, the
size of the data. Put together signed, each is a report on the sanitized
build. Two of the six are discs that hash.

Discs that are right, and the hash as the answer: `wii-encrypted.iso` and
`wii-decrypted.iso`, one disc read both ways; `wii-64-in-a-group.iso`, beside
which `wii-65-in-a-group.iso` is refused, the group being the third of the
four; and `wii-64-in-every-group.iso`, 256 partitions in all, which a limit
on the sum would refuse.

What no row holds, and is there by reading:

- the size as a `size_t` and the test of the allocation: memory does not run
  out to order. With 64 to a group the size cannot overflow any more;
- the test of the table's eight words. The region code is read before the
  table and lies behind it in the file, so a file that has the one has the
  other, and one that ends inside the table is refused for its region code.
  The test is there for a read that fails for another reason than the file
  ending;
- the first of the two tests on each pair of words: a partition's entry, the
  size and offset of the title metadata, the offset and size of the data. A
  file that ends before the first word of a pair ends before the second, and
  that test refuses it;
- the byte at 0x61 that says whether the disc is encrypted has no test of its
  own: it is inside the header, and is in the file if the header is.

**Left as upstream has it.** `rc_hash_wii`, which calls this function, reads
the four bytes of the magic word without a look, so a file shorter than 28
bytes is compared with what the stack held. `rc_hash_wiiware`, for a `.wad`,
tested none of its reads either, and was left so here until patch 0007.

## 0002-3do-directory.patch

**What.** In `rc_hash_3do` in `src/rhash/hash_disc.c`, which looks through the
root directory of an OperaFS volume for the file `LaunchMe`:

- a directory sector that cannot be read whole ends the search;
- the entries of a sector end where the sector does, whatever it says, and an
  entry is looked at only if its 0x48 bytes are all inside;
- a sector that names itself as the next ends the search, and so does the
  257th sector a directory sends it on to.

A search that ends without the file is the error it always was, "Could not
find LaunchMe".

**Why.** A directory sector says where its entries begin and end and which
sector comes next. The read of it was not looked at, so past the end of an
image the sector read before was still in the buffer and was walked again,
and since it does not name itself as the last, again: an image cut off
before its directory never came back. The end of the entries is three bytes
of the sector, so up to 16 MiB into a buffer of 2048 bytes on the stack. And
nothing kept count of the sectors, so two that name each other were followed
for ever.

A sector holds 28 entries, so 257 of them are a directory of some seven
thousand files, in the root alone.

**Upstream.** The first read, of the 132 bytes of volume information, was not
looked at either, and this patch tested it as 12.5.0 now does itself, in the
same words: that test is upstream's and no longer part of the patch. 12.5.0
has nothing else of it.

**Rows.** `opera-short.iso` (the root directory is past the end; it was a
known gap, killed after ten seconds), `opera-self.iso` and `opera-ring.iso`
(a sector that names itself, two that name each other),
`opera-header-as-directory.iso` (the volume's first sector laid out so that
it reads as a directory with `LaunchMe` in it: with the read not looked at,
that is what the search finds in its buffer, and the file hashes),
`opera-entries-past.iso` and `opera-entry-cut.iso` (entries said to go on for
16 MiB, and a last entry cut by the end of the sector; on the unpatched code
the second is refused by the plain build and shown by the sanitized one
alone; the entry that is too many begins 12 bytes before the sector's end,
so that entries let run up to 0x47 bytes past it are seen as well),
`opera-entry-47.iso` (the one entry is `LaunchMe` and lacks its last byte,
which is the other side of "all 0x48 bytes inside"), `opera-partial.cue` (the
file holds 16 bytes of the directory's sector: a test that asked only
whether anything was read would let the search go on in the rest of the
buffer, where the sector before still is), `opera-131.cue` and `opera-132.cue`
(the first sector cut one byte short of the 132, and at them: the first is
upstream's test now, and both are refused with or without the patch, so the
rows ask for the reason).

Volumes that are right: `opera.iso`, whose directory is two sectors, with an
entry longer than 0x48 bytes in the first and `LaunchMe` as the last entry of
the second, ending where the entries end; `opera-chain-256.iso`, a directory
of 257 sectors with the file in the last, beside which `opera-chain-257.iso`,
one sector longer, is refused.

What no row holds: the test for a sector that names itself. The count of
sectors ends that search as well, 256 rounds later.

**Left as upstream has it.** The sectors of `LaunchMe` itself are read without
a look at what came back, so an image that ends before the file does is
given a hash, of the file as far as it goes and then of what the buffer held.
The block numbers and sizes, three bytes each, are multiplied as `int`.

## 0003-playlist-in-playlist.patch

**What.** `rc_hash_get_first_item_from_playlist` in `src/rhash/hash.c` reports
"Playlist refers to another playlist" and gives no item when the first item
of an `.m3u` ends in `.m3u` itself, in capitals or not.

**Why.** A playlist is hashed as its first item. Both places that ask for the
item hand it back to the code that brought them there:
`rc_hash_generate_from_playlist`, when a console is given, hashes the item as
that console, which for a playlist is the same function again; and
`rc_hash_initialize_iterator_m3u`, when none is, puts the item in place of
the path and works out the consoles for it, which for a playlist is itself
again. Nothing counts how deep that goes, so a playlist that names itself, or
two that name each other, went round until the stack was used up. The one
test is in the function both get the item from.

A playlist of a playlist that does end is refused with them. A set of discs
is not listed that way.

**Upstream.** 12.5.0 has nothing of it, and the patch went onto it as it was.

**Rows.** `self.m3u`, `a.m3u` with `b.m3u`, `LOUD.M3U` (capitals) and `.m3u`
(the name that is all extension, and the shortest the test has to see), each
with no console given and as a PlayStation disc, and each asked for the
reason; `short.m3u`, whose item is one character, so that a test which looked
four characters back without counting them would read before the text.
`Repro CD.m3u`, which names a cue sheet, is the playlist that is right, and
has been a row since the first.

## 0004-dol-short-reads.patch

**What.** In `rc_hash_nintendo_disc_partition` in `src/rhash/hash_disc.c`,
which hashes a GameCube disc and a Wii partition that is not encrypted, by
the disc's header and the segments of its `main.dol`:

- every read has to bring back all that was asked for: the apploader's two
  sizes, the header, the table of `main.dol`'s segments, and each piece of
  each segment. One that does not is an error and ends the function;
- the apploader's two sizes are put together as unsigned numbers, as every
  other word of the function already was;
- a header of fewer than 0x424 bytes is an error;
- the file is not closed where the function gives up for want of memory.

**Why.** The table says where each of 18 segments is and how long, and the
function read that many bytes, a mebibyte at a time, and hashed the buffer
each time without a look at what the read had brought. A file of 1496 bytes
with one segment said to be a gigabyte long was given a hash, after a second
and more of hashing the same buffer; with 18 segments of 4 GiB it was 72 GiB.
Leaving the loop at the first short read would not do: the callers make a
hash of whatever was fed in when the function says it succeeded, so it has to
say it did not.

The reads before the segments were as blind. The length of the header is
0x2460 and two sizes from the disc; where those are past the end of the file,
they were what the stack held, and so then was everything worked out from
them, and the bytes of the header that the file did not have were hashed as
the allocator left them. The same cut-off image was refused by one build and
given a hash by another, which is how it was found: with the segments alone
tested, the plain build refused three such images and the sanitized build
hashed them. And the three sizes are added in 32 bits, so two of them can
bring the header's length round to less than the 0x424 bytes that hold the
word read out of it next, down to none.

**Upstream.** 12.5.0 has nothing of it, the two closes included, which it took
out of the Wii's function and left in this one; the patch went onto it as it
was.

**Rows.** `dol-one-gigabyte.iso` and `dol-eighteen.iso`, the files of 1496
bytes, with no console given and as a GameCube disc. They end before the
apploader's sizes and are refused there now, so the same two lies are told
again by a disc that is whole up to its segments: `gamecube-gigabyte.iso`
and `gamecube-eighteen.iso`. `gamecube-less-one.iso` is the disc that is
right without its last byte, which is the last byte of its last segment.
`wii-decrypted-segment.iso` has a segment of 16 GiB in a Wii partition, and
is the row that shows a segment given up at its first missing piece and not
its last: hashing that much takes four times what a row is given.
`gamecube-header-423.iso` and `gamecube-header-0.iso` have sizes that bring
the header to 0x423 bytes and to none. `gamecube-cut-245a.iso`, `-2700`,
`-27a0`, `-28d7` and `-28d8` are the disc cut off inside the apploader's
sizes, inside the header, where the header ends, one byte before the end of
`main.dol`'s table and where that ends; each is refused by the next read if
not by its own, so each row asks for the reason. `gamecube-magic-only.iso`
is 8 KiB of nothing with the magic word, which used to hash.
`gamecube-body-top-bit.iso` and `gamecube-trailer-top-bit.iso` have one of
the apploader's sizes at 0x80000000: put together signed that is a report on
the sanitized build, and unsigned it is a header longer than the mebibyte
that is hashed of any, so both are discs that hash.

Discs that are right: `gamecube.iso`, three segments of which the last is
longer than a mebibyte, so read in two pieces, and ends with the file;
`gamecube-header-424.iso`, the least header there can be; and
`wii-decrypted.iso` under patch 0001.

What no row holds: the two closes taken out, since memory does not run out to
order; and the first of the two tests on the apploader's sizes, since a file
that ends before the first ends before the second.

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
as a file that could not be hashed. The patch stands above upstream's guards,
each of which it makes always true, and is still needed for that reason:
without it the rows below hash.

**Rows.** `tiny.nes`, `tiny.fds`, `tiny.lnx`, `tiny.a78` and `tiny.cart` (a
header cut after its magic word), each with no console given and with its own;
`header-only.*` and `header-and-one.*` for the same five, which hold the four
sizes from both sides; `three.nes` and `empty.nes`.

## 0006-gdi-bounds.patch

**What.** In `cdreader_open_gdi_track` in `src/rhash/cdreader.c`, the parser of
a `.gdi` track sheet:

- a sector size of more than 9 digits is an error;
- a file name ends where the text that was read ends, and not sooner: a
  quoted one whose closing quote has not come by then is an error, and one
  without quotes ends there if no space comes first;
- the character after each numeric field is stepped over only when there is
  one;
- the sector size starts out empty;
- a track that opened, and is given up because nothing says what its sectors
  are, is closed.

Unlike the tests of patch 0005, which stand in front of what was there, these
are on the way of every line of every sheet: the digits, the step after each
number and the end of the name are the parser itself.

**Why.** A line of the sheet is `track lba type sectorsize filename offset`,
and the parser copies the sector size into a buffer of 16 bytes for as long as
it meets digits. The digits are then copied once more, behind `MODE1/` in
another 16 bytes, where 9 fit; that is where the limit of 9 comes from, a real
one being 4. After each number the parser moves one character on without
looking, so a line that ends early takes it past the NUL that closes the text
and into whatever the buffer held before, which it goes on to read as the rest
of the line. And when the track asked for has no line at all, the sector size
is copied to `MODE1/` without ever having been set.

The name is measured where it lies and copied if it fits, which is upstream's
doing. But upstream ends its measuring at `end`, and `end` is the end of the
text only for a sheet that one read holds: for a longer one it is three
quarters of the way through the buffer, the point after which no new line is
begun. A name that lies across that point is all there, since the read went on
to the buffer's end, and is refused all the same when it is quoted, as a quote
that never closes, and cut off when it is not, and then not found. A disc of
many tracks with long names has a sheet as long as that. Here a name ends at
the NUL, which no line that is whole reaches.

The last of the five is not a bound. When the track a sheet names opens but
neither its content nor the sheet says what its sectors are, the function
reports "Could not open" and frees the track; the file it had opened stayed
open, where the parser of a cue sheet, above it in the same file, closes its
own. A sheet with no line for the track asked for goes the same way wherever a
folder can be opened as a file, since the name is then empty and what opens
is the sheet's own folder. And that is not only a sheet that is wrong: a
Dreamcast disc is asked for its third track, where a GD-ROM has its data,
before its first data track is looked for, so a sheet of fewer than three
tracks that is right and hashes lost one descriptor on the way, at every
hash. Measured: one for `valid-first-data.gdi`, which hashes, two for
`cut-1.gdi` to `cut-3.gdi`. A sheet with a third track loses none. A process
may hold a thousand or so, and a scan is one process.

**Upstream.** Up to 12.3.0 the name was copied as the digits are, for as long
as no space or quote came, into 256 bytes, and this patch measured it first.
12.5.0 does that itself, with the two errors the patch had taken from it,
"Quoted string without closing quote" and "Cannot copy %u byte filename into
%u byte buffer": the bound on the name is upstream's and no longer part of
the patch, which now only moves where its measuring stops. The digits, the
steps past the end, the unset sector size and the track left open are as they
were.

**Rows.** `digits.gdi` (64 digits) and `digits-12.gdi` (fits the first buffer,
not the second), and `cut-1.gdi` to `cut-4.gdi` (the sheet ends after the
first to the fourth number of its line, and is as long as one read). On the
unpatched code only the sanitized build shows these: the plain one writes
over its own stack, finds no such file and ends as if it had refused.
`long.gdi` and `long-256.gdi` (a name of 300 characters, and of one more than
fits) and `quote.gdi` are rows of upstream's bound now, kept for the day it
moves.

Sheets that are right, with a track made for them, and the hash as the answer:
`valid.gdi` (three tracks, the third's name plain) and `valid-quoted.gdi` (the
name in quotes, with a space in it); `valid-first-data.gdi`, whose data track
is its second and follows a longer name, which is what holds the NUL written
after a copied name; `valid-long.gdi` and `valid-long-quoted.gdi`, sheets of
1100 bytes whose track's name lies across byte 768, which is what holds the
name ending at the NUL: upstream as it is refuses both; and `digits-9.gdi`,
nine digits where the track is there to say its own sector size. `digits-10.gdi` beside it is refused. Those two are
the limit of nine from both sides, and since the track is there, a build that
let ten digits through would hash the sheet: the row fails on the plain build
too, where the one byte written past the buffer is seen by nobody. The unpatched
code hashes that sheet.

For the track that is closed: `valid-first-data.gdi`, which hashes, and
`cut-1.gdi` to `cut-3.gdi`, which are refused, each end with a file left open
when the close is taken out, on both builds. The same count holds the close
before the error for the digits, which no row held while descriptors were
counted by hand: with that one taken out, `digits.gdi`, `digits-12.gdi` and
`digits-10.gdi` leave two open.

What no row holds, and is there by reading:

- the sector size starting out empty. What is read there otherwise is whatever
  the stack held, which differs from build to build and which neither
  sanitizer reports.

## 0007-wiiware-reads.patch

**What.** In `rc_hash_wiiware` in `src/rhash/hash_disc.c`, which hashes a
WiiWare package, a `.wad`, by its title metadata and each of its contents:

- every read has to bring back all that was asked for: the three sizes of the
  header, the title metadata, the count of contents, the two words of each
  content's size, and each content as far as it is hashed. One that does not
  is an error and ends the function;
- every word is put together as an unsigned number;
- where a content begins is counted in 64 bits.

**Why.** No read was looked at. The header says how long the certificate
chain, the ticket and the title metadata are; the title metadata says how
many contents follow it, in two bytes, and how long each is; and for each the
function allocated a buffer of that length, up to 64 MiB, read into it and
hashed all of it, whatever the read had brought. So a package cut short was
given a hash, of what the allocator had left in the buffer, and not the same
one twice: the rows below got one hash from the plain build and another from
the sanitized one.

And a package could have far more hashed than it holds. One of 544 bytes with
no certificate, no ticket and no title metadata, and 65535 where the count
would be, was 64 MiB for each of 65535 contents, 4 TiB. Measured on the code
as it was: with 32 in place of 65535 it took 2.6 seconds, and with 65535 it
was still running when it was killed after twenty. Inside a scan nothing kills
it. The hash runs on a thread the scan can interrupt, and a loop in C does
not look for the interrupt: the worker is gone until the function comes back,
and a scan that is cancelled waits for it.

With every read tested, a content has to be in the file to be hashed. That
alone leaves one way round: the place a content begins was a 32-bit number,
the sum of the sizes before it, and two sizes can bring it back to the start
of the file, where there are bytes to read. In 64 bits each content lies
after the one before it, and all a package can have hashed is what it holds,
once.

Like patches 0001 and 0004, these tests are on the way of every package that
is right. What makes them safe is what the code did without them: a read that
came back short was hashed as the buffer stood, memory nothing had been read
into, so a package that fails one of these tests never had a hash that was
the same twice. No hash that could be recorded for a game is lost.

**Upstream.** 12.5.0 tests the two allocations and nothing else of it.

**Rows.** `wad-65535.wad`, the 544 bytes, with no console given and as a Wii
file. `wad-header-cut.wad`, `wad-metadata-cut.wad`, `wad-count-cut.wad` and
`wad-content-cut.wad`: a package that ends inside its header, inside its
title metadata, before the count of its contents, and one byte short of the
16 its last content is rounded to; each asks for the reason, and on the
unpatched code each hashes. `wad-content-long.wad`, whose last content is
said to be 4 GiB long. `wad-top-bit.wad`, whose first word has its top bit
set: put together signed, a report on the sanitized build.
`wad-wraps.wad`, 64 MiB and 640 bytes of nothing with two contents, the first
as long as 4 GiB less what comes before it and the second 64 bytes, which in
32 bits begins at byte 0: the unpatched code hashes it, and here its second
content "runs past the end of the file".

Packages that are right, and the hash as the answer: `wad.wad`, three
contents of which the first is no whole number of 16 bytes and the last is
five bytes, with no console given and as a Wii file; and
`wad-last-unfilled.wad`, the same with nothing after the 16 bytes its last
content is rounded to, which a test that asked for the 0x40 bytes a content
is filled out to would refuse.

What no row holds: the test of the first of each content's two size words,
since a file that ends before the first ends before the second. And no
package from a real console was at hand: the layout is the one the function
itself reads.

**Left as upstream has it.** A size of no bytes is allocated as such, which
the C libraries here answer with a pointer and another may answer with none:
that package is then refused for want of memory.

## 0008-cue-bounds.patch

**What.** In `cdreader_open_cue_track` in `src/rhash/cdreader.c`, the parser of
a cue sheet:

- the number after `INDEX` and after `TRACK` is stepped over only as far as
  the text goes;
- a track's mode is copied only as far as the text goes, and the rest of its
  16 bytes cleared; the sector size is read out of that copy;
- a file's name that is written without quotes and is not there is of no
  length.

**Why.** The sheet is read 1023 bytes at a time into a buffer on the stack,
with a NUL after what was read. After `INDEX` and `TRACK` the parser looked
for the blank that follows the number without looking for that NUL, so a sheet
that ends with the number, a download cut short, sent it on through whatever
the buffer held before and out of the buffer, to the first blank on the
stack. The mode was 16 bytes copied from wherever the line had got to: within
15 bytes of the buffer's end, that is what lies behind it. And the sector size
was read six bytes on from that place, whether the text had six bytes left or
not. A name without quotes had its first character stepped over unseen; when
that was the NUL, the name went on into what the buffer held before.

None of this writes anything. The plain build reads memory that is its own
and carries on, as a rule to the same answer; the sanitized one reports each.
It is here because a cue sheet is the commonest descriptor there is, and what
is behind a buffer on the stack is not always the process's own.

**Upstream.** 12.5.0 has nothing of it.

**Rows.** `cue-track-ends.cue` (ends with the number after `TRACK`),
`cue-index-ends.cue` (with the number after `INDEX`) and
`cue-mode-at-the-end.cue`, 1022 bytes whose last is the blank after a track's
number, so that the mode begins two bytes from the end of the buffer. Each
names the track of a Sega CD disc that is there, whole, before it ends, and
each hashes as that disc: a mode that is not there is not missed where the
track says what its own sectors are. On the unpatched code the sanitized
build reports all three and the plain build hashes them.

What no row holds: the name of no length. The byte read past the NUL is still
inside the buffer, and what follows it there is the sheet's own earlier
text, which ends.

**Left as upstream has it.** The numbers of a sheet are worked with as `int`:
minutes, seconds and frames multiplied out, a sector size taken from the
mode, sectors times their size. A sheet that writes absurd ones makes sums
that do not fit, which both compilers used here wrap round and the
undefined-behaviour sanitizer reports. The track is then looked for in the
wrong place and not found.

## 0009-pce-cd-iso.patch

**What.** In `rc_hash_from_file` in `src/rhash/hash.c`, an `.iso` hashed as
console 76, the PC Engine CD, is an error: "Unsupported console for buffer
hash: 76", which is what any other file that is not a sheet is told there.

**Why.** A PC Engine CD game is hashed from its cue sheet. For another file
`rc_hash_from_file` reads the whole of it into memory and hands it to
`rc_hash_from_buffer`, which has no case for this console and says so. But
before it says so it looks at the extension, and a `.cue`, `.m3u`, `.iso` or
`.chd` it sends back to `rc_hash_from_file` to be opened as a disc. Three of
the four have a case of their own there. An `.iso` has none, is read into
memory again and handed on again, a buffer of the file's size each time,
until the stack is used up: a signal, on both builds, for any `.iso` at all.

Only a caller that names the console gets there; with none given an `.iso`
is not tried as this one. `ConsoleChoice` never asks for it. The library is
not to depend on that.

**Upstream.** 12.5.0 has nothing of it.

**Rows.** `pce.iso`, 3 KiB of filler under console 76, asked for the reason;
`pce.bin`, the same bytes, which was refused in those words already.

## 0010-message-length.patch

**What.** `rc_hash_dispatch_message_va` in `src/rhash/hash.c`, which puts
together every message that has a number or a name in it, uses `vsnprintf`
wherever the compiler is C99 or later, and `vsprintf_s` only where it is not.
The order of the two was the other way round.

**Why.** The message is written into 1024 bytes. `vsnprintf` cuts one that is
longer; `vsprintf_s`, one of the "secure" functions of Microsoft's C library,
calls a buffer too small an invalid parameter, and a process that has set no
handler for one is ended there. rcheevos took `vsprintf_s` wherever the
compiler says it has the secure functions, which mingw-w64 always says, so the
DLL was built with it. The longest message is "Could not open" with the path
of a track: the folder of a sheet and the name the sheet gives, 255 bytes at
the most. A sheet some 750 bytes deep, which is 250 characters of a script
that takes three bytes each, with a track that is not there, was the end of
the daemon on Windows.

On Linux, macOS and Android nothing changes: `vsnprintf` was what was
compiled there.

**Upstream.** 12.5.0 has nothing of it.

**Rows.** None can show it where the rows are run, since no program for
Windows is run there. What holds the patch is `shared/tests/native_lib_check.sh`,
which reads the table of what a DLL calls and refuses one that calls a
function whose name ends in `printf_s`. The DLL built before the patch calls
`__stdio_common_vsprintf_s` and is refused; the one built with it calls none.
Nothing has loaded either.

## Known, and not patched

Found by the review of the whole branch with the sanitized program and files
made for the purpose, and left, each for the reason given. None is on the
way of a row.

- `rc_hash_zip_file` in `src/rhash/hash_zip.c` reads the name of an entry of
  a zip's directory by the length the entry gives, without holding it to the
  directory it was read into: past the end of that block for an entry that
  lies. It is reached for an `.arduboy` and a `.dosz` file and for nothing
  else, and a scan picks up neither extension unless a collection lists it.
  The whole of that reader, the entries' own headers after the directory,
  has had no reading here, and a bound on one line of it would say more than
  is known.
- A word is put together from four bytes as `byte << 24` in many places no
  patch touches: the header of a DS cartridge in `hash_rom.c`, the
  directories of an ISO 9660 volume and of a 3DO one in `hash_disc.c`. A byte
  of 0x80 or more is then a shift C leaves undefined and the sanitizer
  reports. Both compilers used here give the number the code means.

## Never patched: an arcade set's name of no letters

`rc_hash_arcade` in `src/rhash/hash_rom.c` hashes the name of the file without
its extension. Up to 12.3.0 it worked out how long that is as
`ext - filename - 1`, the one being for the dot, whether the name had a dot or
not. A path that ends in `/` or `\` has an empty name and no dot, so the
length was one below zero, which in a `size_t` is the largest there is;
`rc_hash_buffer` cut it down to 64 MiB and hashed that much memory from where
the name would be, and the process ended with a signal when it reached memory
that was not its own.

No file did this, only a path, and only under console 27, which a caller has
to name. So it was stopped where paths come in, and still is:
`../rahasher_jni.c` refuses a path that ends in either separator, for every
console, before rcheevos is asked, since to rcheevos such a path names no
file whatever the console. `NativeCrashReproTest` holds that, in a JVM of its
own.

**Upstream.** 12.5.0 hashes the whole name when it has no extension, so the
subtraction is no longer reached with an empty name. The same path gives the
MD5 of no bytes, and a name with no dot in it is hashed whole where 12.3.0
left off its last letter. Both are rows of
`shared/tests/native_repro_test.py`, where the path was the one known gap
until then; it has none now.

## Not patched: what 12.5.0 added

Two consoles' worth of code that no patch here touches, and that files from
outside now reach:

- `rc_hash_neogeo_cart` in `src/rhash/hash_rom.c`, for a `.neo` file: 4096
  bytes of header, then the ROMs, which are what is hashed. It tests the magic
  word and that there is something after the header, and reads the rest by the
  size the file system gave. The rows are a cartridge that is right, with no
  console given and as an arcade game, and files that end in the header, at it
  and one byte after it.
- `rc_hash_ps3` in `src/rhash/hash_disc.c`: of a disc image the `PARAM.SFO`
  and the `EBOOT.BIN` found through its directories, of any other file that
  file and a `PARAM.SFO` looked for beside or above it. With no console given,
  an `.iso` and a `.chd` are now tried as this console among the others. It is
  compiled in and not used: `RcConsoles.HELD_BACK` has console 82, no
  collection is hashed as it, and a hash rcheevos gives under it when it is
  left to guess is turned into a failure. The rows are a disc that is right,
  with no console given and as console 82, which is what shows the guess does
  land there, and the same disc cut short. A file of a disc is hashed as far
  as the image goes, for this console as for the PlayStation 2 and the PSP
  before it, by the same lines.
