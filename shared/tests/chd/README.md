# Images written by chdman

Fourteen `.chd` images that `chdman` wrote, of discs that
`../native_repro_test.py` makes: filler and the few bytes a console's hash is
taken from, no game. They are here because the other `.chd` images of the
tests are written by `../chd_fixture.py`, which packs a hunk with what Python
has; these went through chdman's own compressors, and so put real packed
streams through the decoders the hasher's library is built with. They also
show the tracks, the pregap and the filling as chdman itself writes them.

`../native_repro_test.py` hashes each and expects the hash of the disc it was
made from, but for `chdman-gd-cue-bare.chd`, which it expects to be refused:
see below. No test needs chdman.

They were written by chdman 0.289 with

    ./tests/native_repro_test.py --remake-chd

run in `shared/`, which makes the discs in a temporary folder and runs, there,
one command for each image:

| image | command |
| --- | --- |
| `chdman-ps2-dvd.chd` | `chdman createdvd -f -i c-ps2.iso -o chdman-ps2-dvd.chd` |
| `chdman-ps2-dvd-huff.chd` | `chdman createdvd -f -i c-ps2.iso -o chdman-ps2-dvd-huff.chd -c huff` |
| `chdman-ps2-dvd-flac.chd` | `chdman createdvd -f -i c-ps2.iso -o chdman-ps2-dvd-flac.chd -c flac` |
| `chdman-ps2-dvd-zstd.chd` | `chdman createdvd -f -i c-ps2.iso -o chdman-ps2-dvd-zstd.chd -c zstd` |
| `chdman-ps2-dvd-lzma.chd` | `chdman createdvd -f -i c-ps2.iso -o chdman-ps2-dvd-lzma.chd -c lzma` |
| `chdman-ps2-dvd-zlib.chd` | `chdman createdvd -f -i c-ps2.iso -o chdman-ps2-dvd-zlib.chd -c zlib` |
| `chdman-psp-dvd.chd` | `chdman createdvd -f -i c-psp.iso -o chdman-psp-dvd.chd -hs 2048` |
| `chdman-psx-cd.chd` | `chdman createcd -f -i c-psx.cue -o chdman-psx-cd.chd` |
| `chdman-psx-cd-zstd.chd` | `chdman createcd -f -i c-psx.cue -o chdman-psx-cd-zstd.chd -c cdzs` |
| `chdman-pce-cd.chd` | `chdman createcd -f -i c-pce.cue -o chdman-pce-cd.chd` |
| `chdman-segacd-cd-flac.chd` | `chdman createcd -f -i c-segacd.cue -o chdman-segacd-cd-flac.chd -c cdfl -hs 4896` |
| `chdman-gd.chd` | `chdman createcd -f -i c-gd.gdi -o chdman-gd.chd` |
| `chdman-gd-cue.chd` | `chdman createcd -f -i c-gdc.cue -o chdman-gd-cue.chd` |
| `chdman-gd-cue-bare.chd` | `chdman createcd -f -i c-gdc-bare.cue -o chdman-gd-cue-bare.chd` |

The table in `native_repro_test.py` (`CHDMAN_IMAGES`) is what that command
goes by; this one is for reading. A disc changed there leaves its image
behind, the image's row fails, and the images are to be written again.

`chdman-segacd-cd-flac.chd` has two frames to a hunk for a reason: with the
usual eight its eight sectors were one hunk, and chdman 0.289 could not open
the image of one hunk it had just written with that compressor.

The last two are one GD-ROM of five tracks from two cue sheets that differ in
two lines, `REM SINGLE-DENSITY AREA` and `REM HIGH-DENSITY AREA`, which a dump
of a GD-ROM carries. With them chdman 0.289 takes the disc for a GD-ROM and
writes its tracks with no pregap, each pregap counted to the track before it,
and the image is hashed like the cue sheet. Without them it writes the tracks
as a CD's, the audio tracks' pregaps in the image, and the third track then
lies where RetroAchievements' hasher and chdman place a track differently:
that image is refused, and its row holds the refusal.
