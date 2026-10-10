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
rcheevos crash, write outside a buffer, never come back, or hand back a hash
of nothing or of bytes the file does not have. None changes what a valid file
hashes to. Every patch has rows in
`shared/tests/native_repro_test.py`, which makes the offending files from a
few bytes each and runs them on a plain build and on one with the address and
undefined-behaviour sanitizers; the rows are named below so that a patch lost
in an upgrade shows as those rows failing. Where a patch changes lines that a
valid file runs through as well, there are rows with valid files beside them,
whose answer is a hash: a bound drawn too tight refuses the good with the bad,
and rows that only want a refusal would all still pass. And where a file would
be refused a few lines further on if the patched line were not there, the row
says what the reason has to say, since nothing else tells the two apart.

When rcheevos is upgraded: apply each patch to the new tree, drop what upstream
has taken, write the diffs again, and keep this file and the patch level true.

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
  allocation that fails is an error;
- the file is not closed where the function gives up for want of memory or of
  partitions.

**Why.** The table is four groups, for each a count of partitions and where
their entries are. The function added the four counts in 32 bits, allocated
eight bytes for each partition of the sum, and then filled the table group by
group, by the counts. Counts of 0xFFFFFFFF and 2 add up to 1: eight bytes
allocated, and entries written for as long as the process lasted. A count of
0x20000000 times eight is nothing in 32 bits, with the same end. And an image
with no partitions was closed here and again by `rc_hash_wii`, which had
opened it, at which the C library ends the process.

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

**Upstream.** 12.5.0 has taken out the two closes and nothing more: the sum,
the allocation and the reads are as in 12.3.0. The patch does not apply there
as it is, because its lines sit beside the ones upstream removed.

**Rows.** For the table: `wii-no-partitions.iso` (the second close),
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

- the size as a `size_t`, the test of the allocation, and the close taken out
  where the first buffer cannot be had: memory does not run out to order. With
  64 to a group the size cannot overflow any more;
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
bytes is compared with what the stack held; and `rc_hash_wiiware`, for a
`.wad`, tests neither its reads nor its allocations. Before the header is
read, six bytes of the buffer it will be read into are handed to the verbose
message.

## 0002-3do-directory.patch

**What.** In `rc_hash_3do` in `src/rhash/hash_disc.c`, which looks through the
root directory of an OperaFS volume for the file `LaunchMe`:

- the first read has to bring the 132 bytes of volume information, or the
  disc is not a 3DO disc;
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

**Upstream.** 12.5.0 has the test of the first read, written as it is here,
and nothing else of this patch.

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
buffer, where the sector before still is), `opera-131.cue` and `opera-132.cue` (the first sector cut one byte
short of the 132, and at them: both are refused with or without the patch,
so the rows ask for the reason).

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

**Upstream.** 12.5.0 is as 12.3.0 here.

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

**Upstream.** 12.5.0 is as 12.3.0 here, the two closes included; this patch
applies to it as it is.

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

What no row holds: the two closes taken out, for the reason given under 0001;
and the first of the two tests on the apploader's sizes, since a file that
ends before the first ends before the second.

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
