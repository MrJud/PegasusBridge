# PegasusBridge — Context

Single Android APK that consolidates three legacy plugins (PegasusBridge, RetroAchievements ROM Hasher, PegasusVideoPlayer) into one binary. Replaces theme-side HTTP/scraping/hashing logic with a unified service layer reachable via `pegasus-data://` Intent URIs and file-based IPC under `/sdcard/PegasusData/`.

The QML theme (ReStory) was reduced to a **renderer**: it fires verbs, polls for `done/{jobId}.done` markers, and reads result JSON via `XMLHttpRequest("file://…")`.

---

## 1. Repo layout

```
PegasusBridge/
  app/        — DataLayerApp + DataLayerRouter (manifest entry point, URI dispatch)
  core/       — Paths, Config, schema constants (shared by all modules)
  hasher/     — HasherService and the native hasher: the Android shell around the ROM scan
  media/      — MediaService + ScrapeSourceDispatcher (SGDB / IGN / Steam / IGDB clients)
  ra/         — RA profile / achievements / game-list refresh
  video/      — Trailer search/play/download
  shared/     — the desktop daemon, and under <module>/src/android-shared the code both
                shells compile, the ROM scan included (see §3)
  app-debug.apk
```

Build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (also copied at repo root).

---

## 2. IPC contract

### URI scheme

The theme fires Android Intents with `pegasus-data://<verb>?…` URIs. `DataLayerRouter` dispatches based on the path segment.

| Verb                   | Service           | Purpose                                         |
| ---------------------- | ----------------- | ----------------------------------------------- |
| `scan`                 | HasherService     | Scan ROM tree → write `metadata/*.json` + `_index.json` (the shared scan pipeline, §3) |
| `scrape-media`         | MediaService      | Aggregate cover/screenshots/video for a game    |
| `scrape-source`        | MediaService      | Per-source op (SGDB/IGN/Steam/IGDB, see §4)     |
| `refresh-ra-profile`   | RaService         | RA user summary                                  |
| `refresh-ra`           | RaService         | RA played games + completion                    |
| `search-ra-games`      | RaService         | RA full-text search across console              |
| `match-ra`             | RaService         | Which RA game a Pegasus game is (the scan's verdict on the file, then the index, then the name; §3) |
| `ra-consoles`          | RaService         | The console mapping table, so a theme carries none |
| `credentials-status`   | RaService         | Which credentials are set + RA username, never values |
| `clear-credentials`    | RaService         | Forgets one block — what "log out" means            |
| `search-video`         | VideoService      | YouTube trailer search                           |
| `play-video`           | VideoService      | Stream selected trailer                          |
| `download-video`       | VideoService      | Cache trailer locally                            |

Each Intent carries a theme-generated `jobId` (e.g. `gdb_scrape_<ts>_<rand>`) used to correlate output files.

### File-based IPC (`/sdcard/PegasusData/`)

```
config/credentials.json     — user-supplied API keys (steamGridDb, igdb, ra, rawg)
pending/{jobId}.json        — the job's record while it runs. For a scan also how it ended:
                               status, progress, message and seven counters, or an error
                               (see §3). A scan's record stays there after the scan
done/{jobId}.done           — marker file: appears when the job is over. Never empty —
                               Qt reads an empty file over file:// as a missing one
scrape/{jobId}.json         — scrape-media / scrape-source result
search-ra/{jobId}.json      — search-ra-games result
search/{jobId}.json         — search-video result
download/{jobId}.json       — download-video result
metadata/{gameId}.json      — per-game RA metadata (scan output)
metadata/_index.json        — discovery index: { games[], byKey{} } (scan output)
cache/scan-ledger.json      — what a scan settled about each file, misses included, so the
                               next one does not ask again (not read by themes)
cache/scan-inflight.jsonl   — which files a scan has in the hasher, a line as each is begun
                               and ended. A scan that ends in order removes it, or leaves in
                               it only what it did not get to; what the next scan goes by
                               when one did not end so (see §3; not read by themes)
cache/ra-lists/{console}.tsv — RetroAchievements' list of one console's games and their
                               hashes, which a scan looks hashes up in (see §3; not read
                               by themes)
profile/{user}.json         — RA profile cache
completion/{user}.json      — RA completion cache
media/{gameId}.json         — aggregated media cache (scrape-media output)
```

**Job lifecycle**:
1. Theme generates `jobId`, fires `pegasus-data://verb?...&jobId=…`.
2. Service writes pending/{jobId}.json (optional, for visibility — except for a scan,
   which writes it before it walks the directories: the theme takes a scan with no
   record after a few polls for one that has finished).
3. Service does work, writes result atomically (temp file + rename).
4. Service writes `done/{jobId}.done` last, with content → theme polling wakes up.
5. Theme reads result. Nothing deletes the marker.

Atomic writes use `tmp.renameTo(out)` to avoid partial-read races.

---

## 3. The ROM scan — job record, metadata, discovery index

The scan is `RomScanPipeline`, in `shared/hasher/src/android-shared/`. There is
one copy: the Android `:hasher` module and the desktop daemon both compile it,
and the tests of the `shared/` build are its tests. It walks the ROM tree,
computes RA-compatible hashes (with iNES/SMC/N64 header stripping), looks each
up in RetroAchievements' list of its console and writes one
`metadata/{gameId}.json` per match. A list is one request for a whole console,
kept on disk for a week: no request is made for a file, and none for a game
(see [The lists](#the-lists)). What a scan settles about every file goes into
`cache/scan-ledger.json`, a match with the id of its game, so that the next
scan neither reads nor looks up a file that has not changed, a miss no more
than a match.

Each shell supplies what stands around it:

- **Android** — `HasherService` (`hasher/`): the foreground service, the wake
  lock, the notification with its Cancel, the thermal back-off the pipeline
  takes as its throttle, the native hasher (`NativeHasher`), what Android says
  of the connection (`DeviceNetwork`), and the job record below.
- **Desktop** — `BridgeRouter` (`GET /scan`) and `JobRegistry` in
  `shared/daemon`, with `NativeRomHasher` and what the JVM can tell of the
  connection (`HostNetwork`).

Each runs one scan at a time, since two on one data root would write the one
ledger. On Android a start request that finds a scan running is logged and
dropped, and leaves no record under its id. The daemon answers such a
`GET /scan` with the scan that is running: `status` is `running` where it is
`started` for a scan the request began, `jobId` is that scan's, and the id the
request carried becomes a second name for it, so that `GET /jobs/{id}` under
either is the one record. The theme polls the id it chose and never the one in
the answer. The roots of the second request are not scanned.

Both hash with the same library: rcheevos behind one JNI file,
`hasher/src/main/cpp/rahasher_jni.c`, whose two functions are declared once, in
`RcheevosNative`. `hashForConsole` takes the console to hash the file as, or 0
to leave that to the file's extension, and hands back what rcheevos said of a
file it gave no hash for; the scan records that after the file's name
(`the hasher could not read X: <reason>`). `version` is the rcheevos release,
the number of local patches on it and, after a slash, the readers of our own
with the turn each is at (`12.5.0+pb10/wbfs1,chd1`). `NativeHasher` and
`NativeRomHasher` only load the library, and each holds that version against
the one it was built for before any file is hashed. The part before the slash
is the hasher's `engine`, which every verdict is kept under; a reader is
counted for the files it reads and no other. For a path that ends in `.wbfs`
the library answers through `pb_wbfs.c`, as a Wii disc whatever console was
asked, and says with the hash how many bytes were hashed and how many of them
came from blocks the container does not hold. `RcheevosNative.wbfsCaveat`
makes a note of the two, which begins `scrubbed copy` when they are half or
more, and `HashResult.caveat` carries it. No note means that no byte of a
missing block was hashed, not that the copy is a full one: a tool can blank
what a disc does not use inside blocks that are held. A path that ends in `.chd`
is hashed by rcheevos as the disc console that was asked for, through the reader
of tracks in `pb_chd.c` (see "The hasher's library"). The rcheevos it is compiled
from is the folder `hasher/src/main/cpp/rcheevos`: upstream's tree as
`rcheevos.version` beside it names it, file by file, with the patches of
`rcheevos-patches/` on it, which `shared/tests/vendored_check.py` holds it to.

What a file is hashed as is decided before rcheevos sees it, from the file's
collection and its name (`RcConsoles`, `ConsoleChoice`), and `ArchiveAwareHasher`
does what was decided. In a collection the console table knows, rcheevos is told
the console: what it refuses there is kept (`UNHASHABLE`) and not tried at
every scan, unless the file could not be opened. An arcade set is hashed by its
name and never opened; a Neo Geo cartridge kept as one `.neo` file is the one
file of such a collection that is, and is hashed by its ROMs. Any other zip or
7z is opened for the one entry that is the game, and is never hashed as the
file it is: one with no game in it is `NO_PLAYABLE_ENTRY`. Which entries can be
the game is `ArchiveSelector`'s list for the collection's name with what the
collection itself adds (`ArchiveSelector.extensionsFor`): the extensions of
every console of its row's family, the ones the row sends to a console by name
or by size, and the ones its metafile declares, so that a file hashed loose as
a console of the collection is hashed the same from inside an archive. A
playlist is hashed as the first file it names. Only in a
collection nothing is known of is the console still left to the extension, and
a hash rcheevos then gives under a console that is held back
(`RcConsoles.HELD_BACK`: the PlayStation 3, which it tries an `.iso` as) is a
failure that is kept, and not a hash.

A `.chd` is a disc of its collection's console, and is hashed as that disc by
rcheevos' own code through the library's reader of tracks (`pb_chd.c`), loose,
out of an archive, or as the first file a playlist names. That holds for nine
consoles (`ConsoleChoice.CHD_CONSOLES`): PlayStation, PlayStation 2, PSP,
Saturn, Sega CD, Dreamcast, PC Engine CD, 3DO and Neo Geo CD, for each of which
a synthetic disc hashes alike from a `.chd` and from a cue sheet, a `.gdi` or
an `.iso`. The console is the one the collection's row gives a `.chd`, else the
one it gives a `.cue`, else the collection's own: under `megadrive` a `.chd` is
a Sega CD disc, under `pcengine` a PC Engine CD game. In a collection of any
other console the file is `UNSUPPORTED_FORMAT` and not read (`a .chd is read as
a disc of nine consoles, and console 7 is not one of them`): a cartridge
console would hash the container's bytes, and the PC-FX and the Jaguar CD ask
for tracks in ways nothing has held against a disc. In a collection nothing is
known of, rcheevos tries the `.chd` as each console it knows one of, and a hash
it gives under a console outside the nine is a failure that is kept
(`rcheevos takes this .chd for console 49, ...`). A folder of Neo Geo CD
games has a row (`neogeocd`, and `neogeocdjp` as ES-DE keeps the Japanese
one), which is the console and not the arcade board of `neogeo`, and so has
one of Jaguar CD games (`jaguarcd`, `atarijaguarcd`), whose `.chd` is turned
away by its name like any other outside the nine. What the
reader refuses in a collection that is known is `UNHASHABLE` with the reader's
reason in its detail, which begins `CHD:`: a version before 5, a difference
against a parent image, a hunk that cannot be read (the image is cut short or
does not unpack to its checksum), a file that is no CHD. One
refusal is of a sound image: a track that lies after a pregap which
RetroAchievements' own hasher and chdman, which writes the format, place
differently is not hashed, since only one of the two hashes could be the
disc's (see "The `.chd` reader" for which images that is). An image that could
not be opened at all is `HASH_FAILED` and tried at every scan. So is one the
reader refuses where rcheevos was left to guess, as any failure of a guess is:
every console of rcheevos' list has opened the image and been refused, the
detail is what each said, one after another and cut where the room for it
ends, and the next scan opens it as many times again.

A `.wbfs` is a Wii disc wherever it lies, in a collection nothing is known of
as well, and is hashed as one by the library's own reader (`pb_wbfs.c`): in a
collection whose family the Wii is not of it is a stray, and in an arcade
collection it is refused like any file that is no set. The container is read as
Dolphin reads it, since RetroAchievements' hashes of Wii discs are Dolphin's.
Its parts (`Game.wbfs`, `Game.wbf1` to `.wbf9`) have to add up to what its
header says, to the byte; one that is missing is a file that could not be
opened, so it is `HASH_FAILED` and tried at every scan, and a container that is
wrong in itself is `UNHASHABLE` with the reader's reason, which begins `WBFS:`.
A block the container does not hold is read as Dolphin reads it, which is the
container's own first block at the same place and not zeros, so a copy with
such blocks under what is hashed has a hash that is not the disc's. It is
looked up all the same, and matches where somebody's copy made the same way
is in the lists. Where it does not, it is `NOT_FOUND` with the note of
`RcheevosNative.wbfsCaveat` as its detail: `scrubbed copy: 29.1 of 31.7 MiB
hashed are blocks the .wbfs does not hold; RetroAchievements knows a disc by a
full copy` when such bytes are half or more of what was hashed, and below that
the two numbers with `not the hash of a full copy`. A miss with no detail is
not thereby a full copy: no byte of a missing block was hashed, which is all
that is counted. The theme is told `not_in_database` either way. A `.wbfs`
inside an archive is not unpacked and stays `UNSUPPORTED_FORMAT`.

Beside the hash a scan keeps the MD5 and CRC of the file itself (`rom.fileMd5`,
`rom.fileCrc32`), and takes them only where the read they need costs next to
nothing (`ArchiveAwareHasher.digestInScan`): of a loose file of at most 1 MiB,
of one of at most 64 MiB whose console rcheevos hashes whole, which the hash
has just read to its end, and of an archive's entry, in the pass that copies
it out. Every other file leaves a scan with both empty, and the keys are
written all the same: a disc image, a cartridge rcheevos has a parser for
(Nintendo 64, Nintendo DS), any file over 64 MiB, and an arcade set, which is
not opened at all. A scan used to take both of every file, so a metadata file
written before this has them where one written now does not. Nothing reads
them back: a scraper that needs the digests of a file asks `PlainRomHasher`,
which reads it then.

A file that stands for a game the library does not hold is a `PLACEHOLDER` and
never reaches the hasher: an empty file, whatever it is called, or one of at
most 512 bytes that is nothing but text and is not a `.cue`, `.gdi`, `.m3u`,
`.ccd`, `.toc` or `.hex` (`PlaceholderRule`). It is not asked about, it is
counted with the platforms skipped, and the verdict stands for as long as the
file's size and date do. What a file's collection says of it is said first: in
a collection nobody can hash for it is `UNSUPPORTED` as every file there is.
What its name says is not: a sentence called `.cso` or `.cdi` is a placeholder
too, and only a file whose name says it is no game at all (`.txt`, `.cfg`) or
whose bytes are not text is `UNSUPPORTED_FORMAT`. A scan opens a file of a
format nobody reads only when it is small enough to be such a sentence.

A disc in an archive is a `.cue` or a `.gdi` and the tracks it names, and
rcheevos opens the tracks from beside the sheet. `DescriptorSet` reads the names
out of the sheet and finds each among the archive's entries; the sheet and its
tracks are then written into a folder made for that one disc, each track under
the name the sheet uses, hashed there, and removed. A playlist in an archive
leads to the entry it names first. A sheet that names a file the archive does
not hold, or a file anywhere but beside itself, and a `.ccd`, `.toc` or `.mds`,
which rcheevos does not read, are `UNHASHABLE` with the reason, and nothing is
taken out. Every copy is made in the hasher's temporary folder (`tmp` under the
data root on the desktop, the app's cache on Android) under a name that begins
`bridge_`, and whatever of that name is more than a day old is removed when a
scan builds its hasher: a scan that was killed removes nothing itself.

Every verdict in the ledger carries a number, `algorithmVersion`, and stands
only while its file would be given that number today. The number is worked out
(`HashRecipe`) from what the verdict was reached with: the library's version,
the tables a file is judged by, and for each collection its two names, its row
of the console table and the extensions it declares. So an entry added to a
table, or another rcheevos, has every file that did not match looked at again
without anybody raising a number; an edit to one row of the console table has
only those of the collections it is the row of; and a file whose folder is
given to another collection is looked at again as well. A match stands in the
ledger under its number with the id of its game, and is skipped on that while
the game's metadata file is on disk and names a ROM that is the game's still:
that file, or another the scan found that the ledger holds as a match of the
same game. Past a change of number, or with a ledger that lost it, the match
is taken back from the metadata file that describes that very file, by key,
size and date, with no read and no request. A file that shares its game's
metadata file with another, a second dump or a track beside its sheet, is not
described by it, and is then read and looked up once. One thing is in the
number of some files only: a reader of our own in the library
(`HashRecipe.READERS`, today `wbfs1,chd1`) is in the number of the files of its
extension, by the name the scan found the file under, and of no other. So the
build that first reads a `.wbfs` or a `.chd`, or a later one whose reader
answers otherwise, has those files hashed once more and leaves every other
verdict standing; the line all numbers share is what it was,
`containers=wbfs0,chd0` included, which is frozen for that reason. A packed
disc is also reached through a file of another name, an archive that holds
one or a playlist that names one first, and the build that had no reader kept
its verdict for that file, under a number no reader is in. So one verdict is
known by its words instead (`ScanLedger.canSkip`): an `UNSUPPORTED_FORMAT`
whose detail ends in `.<ext> is a format this build has no reader for`, for
an extension that has a reader now, does not stand, and the file is planned
for again at the next scan. Nothing else is taken again by that, and it
happens once: an archive that holds a `.chd` and a playlist of `.chd` or
`.wbfs` discs are hashed, and an archive that holds a `.wbfs` is listed once
more and kept in the words that are true of it now, that it is not unpacked.
A sentence of text under the name of a `.chd` or a `.wbfs` is a placeholder
under a number without the reader, so it is opened once more, its first 513
bytes at most, and kept as a placeholder under the new one. What no table shows is
the code that judges, and for a change to that a number in the recipe is still
raised by hand, `HashRecipe.RULES`, with two beside it for one corner each:
`PLACEHOLDERS` and `CONTAINERS`. The scan logs the line the numbers are made
from and the readers after it, and an audit's table begins with the two
(`# recipe`, `# readers`).

Every file a scan processes ends in one of eleven states (`ScanLedger.State`),
which is what the ledger keeps for it and what the daemon's result counts
under `states`. Each is counted in one of the seven counters of the job
record, and stands for a time before the file is looked at again, while its
size, its date and its number are what they were:

| state | what it says of the file | counter | stands for |
| --- | --- | --- | --- |
| `MATCHED` | a list of the source has a game for the hash | `newEntries`, or `cachedHits` when found unchanged | as long as the file does |
| `NOT_FOUND` | every list the hash was to be looked up in was had whole, and none has it. With a detail only for a `.wbfs` of which blocks the container does not hold were hashed | `unmatched` | 14 days |
| `KNOWN_UNSUPPORTED` | the source knows the dump and does not let it count: untested, incompatible, or in need of a patch. No scan reaches it any more, and an entry an earlier build left stands its time | `incompatible` | 30 days |
| `API_RETRY` | a list the hash was to be looked up in could not be had | `failedLookups` | never kept |
| `UNSUPPORTED` | its collection is of a console nobody can hash for | `skippedPlatforms` | 90 days, and decided again at every scan |
| `PLACEHOLDER` | an empty file, or a few lines of text under a ROM's name | `skippedPlatforms` | as long as the file does |
| `UNSUPPORTED_FORMAT` | the collection can be hashed and this file cannot: a format nobody reads (`.cso`, `.rvz`), a `.wbfs` inside an archive, a `.chd` of a console such a disc is not read as, a file that is no game | `hashFailed` | 90 days, and decided again at every scan where the name says it |
| `NO_PLAYABLE_ENTRY` | an archive that opened and holds no game of its collection | `hashFailed` | 30 days |
| `AMBIGUOUS_ARCHIVE` | an archive in which several entries could each be the game | `hashFailed` | 7 days |
| `UNHASHABLE` | the console of its collection read the file and refused it, or its archive or its disc's sheet is one nothing can be made of. Also a file that was set aside, with a detail that begins `set aside:` (see "When the process dies in a scan") | `hashFailed` | 30 days |
| `HASH_FAILED` | the file could not be read, or not this time | `hashFailed` | never kept |

The four that come of a lookup are the first four, and a scan now ends a file
in three of them: `MATCHED`, `NOT_FOUND` and `API_RETRY`. None costs a request
of its own. A request is for the list of a console, made when the first hash of
that console needs looking up, and `API_RETRY` is what its files end in when
the list could not be had. The other seven never reach a lookup.

A dump RetroAchievements knows and does not let count is not in its lists,
which hold the hashes it supports, so it comes out `NOT_FOUND` like a dump
nobody has heard of. It used to be told apart, by a request about the hash
alone that is no longer made.

### The lists

`RaApiHashLookup` asks `API/API_GetGameList.php?z=<user>&y=<key>&i=<console>&h=1`
for the list of a console: every game RetroAchievements has for it, each with
its id, title, icon, number of achievements and the hashes it is known by,
games with no achievements included, since they hold most of the hashes.
`RaGameList` reads the answer and keeps it as
`cache/ra-lists/<console>.tsv`, a text file with a header (format, console,
when it was fetched, how many games and hashes, the console's name) and a line
per game: id, achievements, icon, hashes and title between tabs. It is read
back line by line, and a file that is cut short, of another format or console,
or whose counts differ from its header is not read and is asked for again.
What a match writes down, its title, icon and count, is what the list says.

A list is answered from for **7 days** from its fetch. It is loaded when the
first hash needs it and not before: from memory, then from the file if it is
fresh, then by one request. So a scan whose files are all settled in the
ledger reads no list and asks nothing, and one that finds its lists on disk
makes no request, on a plane included.

**Which lists.** The pipeline gives each hash the consoles to look it up in, in
the order to try them (`RomScanPipeline.consolesOf`), and the next list is
loaded only after a miss in the one before:

1. the console the file was hashed as;
2. where that console came from the file's extension and not from its
   collection (a collection the console table has no row for, or a stray file
   in one that has), the consoles rcheevos' name for the extension stands for
   (`STANDS_FOR`): a `.fds` is hashed as a NES file and listed under the
   Famicom Disk System, a disc as a Sega CD's and perhaps a Saturn's;
3. the collection's own console, then the rest of its family in the console
   table: a `.gb` file of a Game Boy Color game is hashed as a Game Boy's and
   listed under Game Boy Color;
4. DS beside DSi and DSi beside DS (`FILED_BESIDE`), which no family holds.

Nearly every hash is in the first list, and the others are asked for only
after a miss in it.

**A list that cannot be had** fails the lookups that need it, as `API_RETRY`
and never as a miss: a miss is kept for 14 days and a list is every file of a
console at once. That is a request that failed four times, a status that is
not a success, a body that is no JSON array, an array that is another
console's, holds no game or came without its hashes, and a list past its 7
days whose refresh fails, which is not answered from either. Nothing is written to disk
for a list that is not taken. The failure is remembered for the rest of the
scan and the console is not asked for again, so a console that is down costs
four requests however many files it has. A game the list gives no title or no
count for fails its own hashes and no others.

**Which failures count** towards the stop after 8 (below). Those where the
source is not answering as itself: no answer, a status, a body that is no list
at all. Each lookup that fails for one counts, with or without a request of
its own, so a source that is down stops the scan at the eighth lookup that
fails and the fourth request. The error can name fewer files than eight, as
few as one: a lookup that meets a failure already known costs no time, so the
workers that look hashes up are eight files on while the first result is still
being counted. A whole answer that is no list of the console, an empty array or
another console's games, does not count: it is one console's trouble, its
files are `API_RETRY` and are hashed again at every scan, and the scan goes on
to the collections after it. The other side of that rule is a console whose
request fails every time while the rest answer, a 500 for that one list: it
counts, and every scan stops at that console's eighth lookup and never reaches
the collections after it, until the list can be had.

**A miss** stands 14 days from the scan that looked. The file is then hashed
again and looked up in a list at most 7 days old, so a hash new to
RetroAchievements shows 14 to 21 days later at worst. A list that is fetched
again in between does not have the standing misses looked up in it: the ledger
keeps no hash, and a miss is only looked up again by reading its file.

**Manners.** Every request names the program, its version and where its source
is: `User-Agent: PegasusBridge/<version> (+https://github.com/MrJud/PegasusBridge)`,
the version being `BridgeVersion.NAME`, which a test holds to the Android
app's `versionName`. Requests go one at a time and a second apart. A request
that brought nothing back and a 5xx are asked again after 1, 2 and 4 seconds,
four attempts in all. A 429 is waited out for what its `Retry-After` says, a
minute when it says nothing, and one that asks for more than 120 seconds ends
the lookup at once; three waits at most for a list, so a scan can stand still
for up to six minutes on one, with the wake lock held on Android. A 403, which
from RetroAchievements is a client it has blocked, is never asked again, and
neither is any other 4xx. A long 429 and a 403 have no stop of their own: the
console's files fail, and after eight of them the scan ends as `SOURCE_DOWN`,
whose advice to wait a few minutes is the wrong one for a 403.

Files are hashed 2 at a time on Android and 4 on the desktop. Either can be
told otherwise, to time a library on its own storage: `hashWorkers=N` on the
`pegasus-data://scan` URI (1–8), `--hash-workers=N` on the daemon (1–16).
Neither hashes more files at once than the machine has cores, whatever the
count, and the count each puts in its log or prints is the one it runs with.
Requests are one at a time whatever the count.

### The job record

On Android the service writes `pending/{jobId}.json`, built by `ScanJobRecord`,
and leaves it there when the scan is over. The theme polls it:

- `status` is `running`, `done` or `error`.
- A running or done record has `progress` (0–1), `message`, and seven counters
  that add up to the files processed so far: `newEntries`, `cachedHits`,
  `skippedPlatforms`, `unmatched`, `incompatible`, `hashFailed`,
  `failedLookups`. The message is `Scanning ROM folders…` while the
  directories are walked, `Checking N ROM files…` from the count of them to
  the first result, `[processed/total] file name` after a result, and at the
  end either `No ROMs found` or a sentence that begins `Done — `. A library
  with no ROM in it goes from the first of these to `No ROMs found`.
- An error record has `error` and neither progress nor counters. It is how a
  scan ends when it is cancelled, when credentials are missing, when the
  pipeline stops itself (the error then says what to do next), and when
  anything is thrown — a final `_index.json` that cannot be written included.
- The record a scan ends on has `metrics` as well, when the pipeline returned:
  a done record, `No ROMs found` among them, and the error record of a scan
  the pipeline stopped itself. "Where a scan's time went", below, says what
  is in it.

`done/{jobId}.done` holds the text `done`, written after the record is whole.

Not every result gets a record (`ScanJobRecord.due`). One is written for the
first result and for the last; when a fiftieth of the library has gone by since
the record before, or ten results where that is more, which holds a library
read from its cache to about fifty records; and when a second has gone by since
the record before, which is half the two seconds between the theme's polls. The
last of these is what keeps the record moving through a first scan, where a
result is a file hashed and a lookup answered: a fiftieth of a large library
used to take minutes to go by, and a scan of 13 files that stopped at its
eighth result never wrote a record of a result at all. That second is measured
with `System.nanoTime`, which setting the clock does not move.

### When the pipeline stops a scan

Three things make the pipeline stop before the end of the library
(`RomScanPipeline.AbortCause`). It looks for them in this order, the first and
the last after every result and `OFFLINE` after a result whose lookup failed,
and the sentence the scan ends on is `ScanJobRecord.abortAdvice`, the same on
both shells:

| Cause | When | The error begins |
|-------|------|------------------|
| `KEY_REFUSED` | no key is configured, found before any file is read, or RetroAchievements answered the key with a 401 | `RetroAchievements refused the API key` |
| `OFFLINE` | a request for a list failed without an answer and the device says it has no connection | `No internet connection: stopped after` |
| `SOURCE_DOWN` | 8 lookups in a row failed for a list the source would not give | `RetroAchievements stopped responding after` |

**The key.** Every request for a list carries it, so a scan with none can look
nothing up, and is stopped once its files are counted and before one is read
(`RaHashLookup.keyMissing`): the error says `after 0 of N files`. That is the
desktop daemon and an audit; a desktop scan of a library that is all cached is
turned away as well. The theme sees such a scan end before its first poll, so
it shows `job unknown to the bridge` for one poll and then the sentence. The
Android service turns the start away earlier, with
`Missing RA credentials in credentials.json`. A key RetroAchievements refuses
is refused at the first request that is made and stops the scan at that
result. None is made while the lists a scan needs are on disk and fresh, so a
key revoked since they were fetched goes unnoticed for up to a week, and so
does a wrong one typed in over a good one.

Whichever it was, the file whose lookup failed is in the ledger as `API_RETRY`
and never as `NOT_FOUND`, the ledger and the index are saved, and the next scan
asks only about what this one did not settle. That is why `OFFLINE` waits for
a result whose lookup failed: the lookup knows it has no connection before its
result reaches the collector, and a scan stopped at whichever file went by in
between, one that needed no lookup, would end with that failure in none of its
counts and its file in no ledger.

A scan that is killed runs none of that. It has still written its ledger as
it went: at a result that comes ten seconds or more after the last write,
when something was settled in between. So it loses what it settled after its
last write, which is ten seconds of verdicts at most, and not always the last
ten: a write waits for a result, and one file can take minutes. A match is in
its metadata file before it is in the ledger, and the next scan takes it from
there without a read; one that shares that file with another ROM of its game
is read once more. A killed scan rebuilds no index and leaves its record at
`running`.

A power cut costs the ledger what a kill does: each save is forced to disk
before it takes the place of the ledger there is (`BridgePaths.writeAtomic`
with `durable`), so a whole ledger comes back, the last one saved or, on
storage that will not force a directory, perhaps the one before. Nothing else
a scan writes is forced, so a metadata file or an index written just before
the cut can come back empty or short: the next scan reads that game's ROM and
looks it up once more, and rebuilds the index as every scan does.

**No connection.** What the device knows about its connection explains a
failure and prevents nothing. Every request is made. Only when one has failed
without an answer of any kind — an exception, not an HTTP status — does the
lookup ask the device, and if the device says it is offline the lookup does not
go through its other three attempts and their seven seconds of back-off: it
gives up, and the pipeline stops the scan at that result. A device that is
wrong about being offline therefore costs nothing while requests get through,
and one that cannot say, or throws when asked, counts as online. Nothing is
asked before a scan, or during one that makes no request: a library scanned
before is scanned again on a plane and ends `done`, every file cached, and a
new ROM of a console whose list is on disk and fresh is matched there too. The
first answer that arrives takes the verdict back.

A connection that goes in the middle of a scan stops it the same way, at the
next request that fails, and one that is gone for a moment counts as gone: the
scan that used to ride out a Wi-Fi that dropped and came back, on its seven
seconds of back-off, now ends there and is resumed by the next.

- **Android** asks `ConnectivityManager` (`DeviceNetwork`, permission
  `ACCESS_NETWORK_STATE`). No active network — airplane mode, Wi-Fi off with no
  mobile data — is offline at once. A network Android has not validated, which
  is the one it marks "connected, no internet" (a router with no line out, a
  sign-in page), is offline only when it is still unvalidated at a second
  failed request five seconds or more after the first (`OfflineVerdict`): every
  network is unvalidated for a moment after it is joined. In practice that is
  the fourth attempt of the first lookup, about seven seconds in. A request
  that is answered in between starts those five seconds again, so a network
  Android never validates and that carries requests all the same is not called
  offline for two failures a long way apart. It is called offline, wrongly,
  when one lookup fails all four of its attempts on such a network while
  nothing else is answered: the scan then stops at that one failure with the
  no-internet sentence, though it was RetroAchievements that did not answer.
  A VPN is never taken for offline, since Android does not test one. So with
  a VPN that stays up over no network at all, airplane mode included, a scan
  still ends after 8 failed lookups and as an outage of RetroAchievements.
- **The desktop** has only what the JVM can see (`HostNetwork`): it is offline
  when no network interface is up with an address other than a loopback or a
  link-local one. Anything else is online, a machine whose router has no line
  out included, and such a scan ends after 8 failed lookups as before.

### When the process dies in a scan

The hasher is C entered in the scan's own process, and a file made to mislead
it can end that process. The ledger does not say which file was in hand, so
the next scan came to the same file and died there again, and no file after it
in the walk was reached. `HashTrace` (shared, so the same on both shells and
in an audit) keeps `cache/scan-inflight.jsonl` for that: a line before the
hasher is given a file and a line when it has answered, each one unbuffered
write to a `java.io.FileOutputStream` opened for appending, with nothing
forced to disk. A process that dies leaves its written lines with the system.

What the next scan does with a trace that was left:

- **A file begun and not ended is a suspect**, and nothing more: four files
  are in the hasher at once, and a process is ended from outside far more
  often than by a file. Suspects, and files already set aside, are taken out of
  the queue and gone over first by one coroutine, one at a time, each begun as
  `alone`; the usual producers start when it has finished. A good file gets
  the verdict it would have had. A suspect outside the roots of the scan stays
  in the trace for the scan that walks it.
- **A file begun alone and not ended, with no other begun, is convicted**: the
  process has ended twice with it in hand, the second time with nothing else
  to blame. The scan that next comes to hash it, while its size and date are
  the ones recorded, does not: it is `UNHASHABLE` with the detail
  `set aside: the scan's process ended twice while this file was being hashed,
  …`, for the 30 days of that state. A file of another size or date is another
  file and is tried, alone. So is one whose 30 days are out, which costs a
  real crasher one death a month and not two.
- **A conviction is dropped by the ledger's state**, when the ledger holds the
  file as set aside with the conviction's size and date and that ledger is on
  disk; not by the scan having recorded it. A scan that dies between its save
  and the end leaves both, and the scans after it skip the file on the ledger's
  word and never come to the conviction, which left standing would set the file
  aside again when its month is out. A scan whose save failed keeps it.
  **Not by an entry the file was convicted in spite of.** When the 30 days are
  out the entry is in the ledger still, with the file's size and date, and the
  file is tried; a process that ends in that try leaves a conviction and that
  old entry side by side. So a conviction made of a file the ledger already
  holds as set aside takes note of when that entry was made (`despite`, on its
  line), and only an entry of another time drops it: the one recorded by the
  scan that sets the file aside again. Without that the conviction was dropped
  at every start, and every scan ended on the file before it had come to any
  other.
- At a scan that got to its end, suspects and convictions go by the rule
  `ScanLedger.forget` has for an entry: under a scanned root and not found, or
  gone from disk.

A scan that ends in order leaves no begin in the file, and so does one
stopped with Cancel: the scope waits for the hashes in flight, and the lines
are written around the interruptible call, by the coroutine, so an interrupted
thread writes none. What it leaves is what a later scan has still to know, a
suspect it did not get to or did not walk and a conviction no saved ledger
answers yet, and no file when that is nothing, which is the usual end. A
rescan that hashes nothing does not create it. One conviction can stay for as
long as its file does: that of a file which now has a verdict that takes no
hash (its collection is no longer one that can be hashed, or a metadata file
vouches for it), since the scan never comes to set it aside. It costs
nothing but the file being there and that one file being gone over first.

What is not a death of a file's making, and how far that is known:

- **An orderly stop of the JVM** (the service stopped or restarted, Ctrl-C,
  the machine going down) runs a shutdown hook that writes `{"stopped":true}`
  as the trace's last line, and the begins above it make no suspect and no
  conviction. This is the desktop's: **Android runs no hook for a process it
  kills**, so there a kill and a crash still look alike, a swipe-away and an
  uncaught exception elsewhere in the app among them. The files in hand are
  then suspects, tried alone, and get their verdicts; a good file is set aside
  only by a second such end during the short time it is alone in the hasher.
- **A power cut does leave suspects**: a begin written long enough before it
  is on disk. The last lines may be missing or cut short, and a line that does
  not parse is passed over.
- **A second scan in one process** (Android lets a start request through while
  a cancelled scan still waits for its hashes) is given a trace that reads and
  writes nothing: with two scans hashing, no file is alone. So is any scan
  that starts while another is going, whether or not that one keeps a trace.
- A trace over 16 MiB, or one that cannot be read, is thrown away and logged.
  So is one that could not be written again, at the start or the end of a
  scan: its lines would be read with those the next process writes after
  them, as if all were of one.

Not done: a file that hangs the hasher, which this does not help. There is no
child process for hashing and no time limit for a file.

### When `/sdcard` is full or read-only

The record is a file under the same root as everything else a scan writes, so
a scan that a failed write ends may not be able to say so. Whichever write it
was, the service ends the same way: it logs the failure, tries the error record
and then the marker, logs each of those that fails too (`Scan failed`, `could
not write the error record`, `could not write the done marker`, under the tag
`HasherService`), releases the wake lock, takes the notification down and
stops. Nothing is thrown out of it. What is left for the theme depends on which
write failed first:

- **The first record** — a volume mounted read-only, or one with no room even
  for that. The scan ends there: no ROM is read and RetroAchievements is not
  asked. Nothing of the job is in `pending/` or `done/`, and the theme takes a
  job with no record for one that has finished, at its fifth poll (§2), with
  nothing found. A full volume, unlike a read-only one, can keep an empty
  `pending/{jobId}.json.tmp` and an empty `done/{jobId}.done`; the theme reads
  an empty marker as a missing one.
- **A write during the scan** — a `metadata/{gameId}.json` or a running record.
  The scan ends as any that throws: the pipeline tries the index and the
  ledger, then the service tries the error record. If that can be written, the
  theme shows the scan as failed, with the message of the write that failed.
  If it cannot, the last running record stays in `pending/`, with no marker
  the theme can read, and the theme goes on showing a scan under way at that
  `[processed/total]` for as long as the popup is open, and returns to it
  after a reload: it does not look for the marker while the record says
  `running`. The matches written before the failure are in `metadata/`, and
  the next scan finds them cached.
- **The final `_index.json`** — the ledger has been saved by then. The scan
  ends as an error, which the theme shows or does not as for a write during
  the scan; the record left when it does not is the one of the last file.
- **The ledger** — logged, and that is all. It is written while a scan
  runs, no more often than every ten seconds and only when something was
  settled since the last write, and as the scan ends: the whole file each
  time (74 KB on a tablet with 339 files), through a temporary file that is
  forced to disk and then takes its place in one move, so that what is on
  disk is always a whole ledger. One that fails while the scan runs is tried
  again ten seconds on. The scan ends as it would have. When no write got
  through, the next one asks again about every file that is not a match.

A start request turned away for missing credentials writes an error record
too. When that cannot be written it is logged, and the request ends as it
would have: the notification up and down again, and the service stopped.

On the desktop the record is the body of `GET /jobs/{id}`. The status values
are the same, and a scan the pipeline stopped is an error with the same
sentence. What differs: the message is empty until the first report and
`[processed/total] file name` from then on, the end included — the desktop
writes none of `Scanning ROM folders…`, `Checking N ROM files…`,
`No ROMs found` and the sentence that begins `Done — `, and says what a scan
found in `result`; the record moves on the pipeline's own reports, one for
every fiftieth of the library and one for the last result, with no rule by the
clock, because `JobRegistry` writes its copy in `pending/` for every report it
is given; only `newEntries`,
`cachedHits` and `skippedPlatforms` are published as counters, and none before
the first report; an error keeps the progress, message and counters the job
had; a scan that returned, stopped or not, has a `result` object; the copy in
`pending/` is deleted when the job ends; and the marker is a small JSON object.

What does not differ is `metrics`: a scan that returned, stopped or not, has
the object described under "Where a scan's time went" at the top level of the
body, beside `result` and not in it, and a scan that threw has none. `result`
keeps the eleven keys it had.

### Where a scan's time went

`metrics` is one object, built by `ScanMetrics.Snapshot.toJson()` for both
shells, at the top level of the record a scan ends on. It is written once,
with that record. A record of a scan under way has none, and neither has the
record of a scan that was cancelled or ended by something thrown, which
returned nothing to take it from. The theme reads none of it: it is there for
whoever asks why a scan took what it took. `schemaVersion` is still 1, since
no key that was there has moved or changed.

Two kinds of number are in it, and they do not add up with one another.

- `wallMs`: from the call of the scan to its return, by the clock on the wall
  (`System.nanoTime`).
- `phaseMs`: four spans that follow one another and add up to `wallMs`
  exactly. `walk` is finding the files. `prepare` is loading what earlier
  scans left: the metadata files, the ledger, the trace. `scan` is going over
  every file, hashing and asking where that is needed; on a rescan that finds
  everything settled it is the time it takes to look at each file and its
  entry in the ledger. `finish` is the closing writes of the ledger and the
  index. A library with no ROM has `walk` and `finish` and 0 in the other two.
- `busyMs`: time spent inside `scan`, added up over the workers, so that four
  workers hashing for a minute are four minutes. A number here can be more
  than `wallMs`. `hash` is time in the hasher; divided by `hashWorkers` it is
  the least time the hashing can have taken, and no more than that, because
  files hashed one at a time (see `HashTrace`, above) had the hasher to
  themselves. `lookup` is time in a question put to the lookup, the fetching
  of a console's list included; when two workers wait for one list the wait
  is counted for each. `throttle` is the pauses the caller asked for after a
  hash, as asked: Android's thermal back-off, and 0 on the desktop. `write`
  is the metadata files and the saves of the ledger made while the scan runs.
- `hashWorkers` and `lookupWorkers`: how many hashed and how many asked at
  once.
- `filesHashed`: files handed to the hasher; 0 on a rescan that found
  everything settled. `lookups`: questions put to the lookup, one for each
  distinct hash and the consoles it may be of. One that got no answer is put
  again for the next file with that hash, and counted again.
- `bytesRead`, which may be absent: the bytes the hashing threads asked the
  system for, from `/proc/thread-self/io`. A byte that came from memory counts
  as one that came from the disk, and a file the hasher reads whole is read
  once more for its plain hashes, so a cartridge costs about twice its size
  and a library of them more than it holds. Absent where the system keeps no
  such count (Windows, macOS), where it does not let the process read it, and
  for a scan that hashed nothing. Android lets an app read it only when the
  app can be debugged, so a release build is expected to leave it out; no
  device has been asked yet.
- `slowest`: at most five files, the ones that took the hasher longest,
  slowest first, and an empty list when nothing was hashed. Each has `file`
  (the whole path), `ms`, `size` (the file's length) and, where the system
  counts them, `bytesRead`, which for a disc image is far less than its size.

The end of the body of `GET /jobs/{id}` on Linux, for a first scan of 1973
files:

```
{"schemaVersion":1,"jobId":"scan_1791233730000_1","verb":"scan","status":"done",
 "progress":1.0,"message":"[1973/1973] Example Disc.chd", …, "result":{…},
 "metrics":{"wallMs":51240,
            "phaseMs":{"walk":820,"prepare":310,"scan":49600,"finish":510},
            "busyMs":{"hash":181300,"lookup":2100,"throttle":0,"write":640},
            "hashWorkers":4,"lookupWorkers":2,"filesHashed":1973,"lookups":1890,
            "bytesRead":16857798246,
            "slowest":[{"file":"/library/ps2/Example Disc.chd","ms":9440,
                        "size":4361240576,"bytesRead":5898240}]}}
```

Read as: 51 seconds, of which 49.6 going over the files; four workers were in
the hasher for 181 seconds between them, which is at least 45 of those
seconds; and the slowest file took 9.4 seconds for 5.9 MB read of a 4.3 GB
image. Android's `pending/{jobId}.json` ends with the same object under the
same key, with `throttle` above 0 on a warm device.

### The discovery index

Whenever the pipeline has walked the roots — at the end of a scan, and also of
one that found no ROM, was stopped, was cancelled or failed — it rebuilds
**`metadata/_index.json`** from the metadata files of the games the ledger
holds a match for whose file is there:

```json
{
  "schemaVersion": 1,
  "fetchedAt": 1714000000,
  "count": 37,
  "games": [
    { "gameId": 7236, "title": "...", "platform": "snes",
      "total": 50, "imageIcon": "/Images/12345.png" }
  ],
  "byKey": {
    "supermarioworld|snes": {
      "gameId": 7236, "title": "...", "platform": "snes",
      "imageIcon": "/Images/12345.png", "total": 50
    }
  }
}
```

- `games[]` powers the "discovered on-device" list in the RA hub.
- `byKey{}` is the reverse lookup from a ROM to its game. The Bridge reads it,
  in `RaMatcher` (`/ra/match`, `match-ra`); the theme does not. A key is
  `FuzzyMatch.makeCacheKey` of the ROM's file name without its extension and
  of its collection's short name (the name of the folder it sits in, where no
  metafile declares a collection). It is read by exact key only: the file's
  name with the platform asked, then the title with it. A key of another
  platform never answers.

The index says what is on the device, and the metadata files do not: none is
ever deleted. A game is listed while the ledger holds a `MATCHED` entry of it
whose file exists, any one of them for a game of several files.

- A folder that is not there, a card that is out or a disk not mounted, takes
  its games out of the index at the next scan. A scan with the folder back
  lists them again without reading a file: the metadata file still describes
  each, and the match is taken from it.
- A ledger written before a match carried its game id holds matches that name
  no game. While it holds one whose file exists, no game is left out, since
  that match could be of any, and every rebuild logs at warning level how many
  there are and the path of the first. A scan that goes over such a file
  writes the id into its entry; a file in a folder no scan is given any more
  keeps its entry, and the log is what says so.
- The keys and their shapes are unchanged.

### Which game a file is

`GET /ra/match?title=&platform=&file=` on the desktop, `match-ra` with the same
three values and a `jobId` on Android, answered in `search-ra/{jobId}.json`.
`title` is required, `platform` is the collection's short name and `file` the
path of the game's first file as Pegasus gives it. One class, `RaGameFinder`
(`shared/ra/src/android-shared/`), holds the order for both shells:

1. **The file.** The path is made real, every link in it followed, and looked
   up in the scan's ledger. An entry answers while the file still has the size
   and date the scan saw, or is gone:

   | ledger state | answer |
   | --- | --- |
   | `MATCHED` with a game id | that game, `method: "hash_index"`, `score: 1.0`, `onDevice: true` |
   | `NOT_FOUND`, `KNOWN_UNSUPPORTED` | `no_match`, `reason: "not_in_database"` |
   | `UNSUPPORTED` | `no_match`, `reason: "unsupported"` |
   | `PLACEHOLDER` | `no_match`, `reason: "placeholder"` |
   | `MATCHED` with no game id (a ledger from before a match carried it) | step 2 |
   | `UNSUPPORTED_FORMAT`, `UNHASHABLE`, `NO_PLAYABLE_ENTRY`, `AMBIGUOUS_ARCHIVE` | step 3 |
   | `API_RETRY`, `HASH_FAILED`, or a file whose size or date has changed | step 3 |
   | no entry, no ledger, or no `file` sent | step 2 |

   For a match the `title` is the one in `metadata/<id>.json` and `consoleName`
   that of its `platform`; the title and platform asked stand in for what that
   file does not give.
2. **The index**, by exact key (above). Only for a file the ledger has never
   held, for a match with no game id, and when no path is sent. A file the
   scan has seen and did not match is never answered from it: two dumps of one
   name share a key, and the game under it is the other file's.
3. **The name**, against the games of the platform's own console and of no
   other, with `onDevice: false` and a `method` that is not `hash_index`
   (`rom_filename`, `pegasus_title` or `rom_filename_loose`). The games are
   those of the list a scan keeps, `cache/ra-lists/<console>.tsv`: read from
   disk while it is at most seven days old, asked for with one request when it
   is not, and kept. A list holds only the games that have a hash, so a game
   RetroAchievements lists with none cannot be guessed by name; no file could
   be verified as one. Before any list is looked for:

   - a platform with no console at RetroAchievements is `no_match`,
     `reason: "no_console"`;
   - a console RetroAchievements has and nobody can hash for (`3ds`, `wiiu`)
     is `no_match`, `reason: "unsupported"`, as the ledger's `UNSUPPORTED` is
     by file;
   - no key set is the error `NO_KEY`, said before the disk is read.

   A list that was read and holds no title close enough is `no_match`,
   `reason: "no_name_match"`.

The three answers, each with HTTP 200:

```json
{"status":"ok","gameId":7236,"title":"...","consoleName":"SNES/Super Famicom",
 "score":1.0,"method":"hash_index","onDevice":true,"schemaVersion":1}
{"status":"no_match","gameId":0,"reason":"not_in_database","schemaVersion":1}
{"status":"error","gameId":0,"code":"SOURCE_DOWN",
 "error":"RetroAchievements did not answer: HTTP 503","schemaVersion":1}
```

A match has those eight keys and no others, a no always has its `reason`, and
an error its `code` and a sentence in `error`. A theme that reads only
`status` and `gameId` takes an error for no match, as every failure was
answered before.

| `code` | when | `error` |
| --- | --- | --- |
| `NO_KEY` | the user or the key is blank | `No RetroAchievements API key is set` |
| `KEY_REFUSED` | RetroAchievements answered the key with a 401 | `RetroAchievements refused the API key` |
| `SOURCE_DOWN` | the list could not be had: no answer, another status, a body that is no list, a list past its seven days whose refresh failed | `RetroAchievements did not answer`, and `: <detail>` when there is one (`HTTP 403`, `no answer within 12 s`) |

An error arises only when a name has to be looked up: an answer from the
ledger or the index needs no key and no network. A match tells `NO_KEY` from
`KEY_REFUSED`; a scan's job record says `KEY_REFUSED` for both (above). The
sentence never holds the key or the address asked.

One question waits at most 12 seconds for a list and is then answered
`SOURCE_DOWN`, so a source that is slow or asks for a long wait cannot hold
the daemon's workers. A list that could not be had is remembered for 60
seconds, per user, key and console: a source that is down costs one attempt a
minute, and every question in between is answered at once with the same
error. Questions for one console that arrive together make one request.

What the answer does not cover:

- It is about the one file sent, the first Pegasus lists for the game. A no
  for that file is a no for the game, even when another file of it matched.
- A path written in another letter case than the name on disk, on a file
  system that ignores case, is not found in the ledger: the real path does not
  fold case there. Such a file is answered as one the scan has not seen, by
  the index and then the name.
- A verdict's age and the recipe it was reached under are not looked at. A
  `NOT_FOUND` past its fourteen days is answered `not_in_database` until a
  scan has asked again: it is the scan's last word, and nothing better is
  known without hashing the file.
- A key whose platform half differs from the platform asked no longer answers
  (a game in two collections, a folder named unlike its collection). With a
  path the ledger answers whatever the platform is called. Without one, or
  until one scan has rewritten the matches of a ledger that carry no game id,
  such a game gets a guess by name. One scan after installing settles it.

The legacy `ra_hashes_cache.json` (single mega-file with `external_hashes` and `verify_map`) is gone. `verify_map` was user-stored state and now lives only in `api.memory("ra_hash_verify_map")`.

---

## 4. MediaService — scrape-source dispatcher

`scrape-source` handles all per-source operations as a single verb, parameterised by `source` and `op`.

URI: `pegasus-data://scrape-source?jobId=…&source=X&op=Y&…`

| source  | ops                                                |
| ------- | -------------------------------------------------- |
| `sgdb`  | `search`, `grids`, `logos`, `heroes`, `screenshots` |
| `ign`   | `search`, `details`, `images`                      |
| `steam` | `search`, `assets`                                 |
| `igdb`  | `token`, `search`, `details`, `covers`, `screenshots`, `artworks` |

`ScrapeSourceDispatcher` calls the typed Kotlin clients (`SteamGridDbClient`, `IgnClient`, `SteamStoreClient`, `IgdbClient`), serialises results to JSON matching the **same field shape the legacy CoverScraperService.js used to return**, and writes `scrape/{jobId}.json`.

Notable contracts:
- **IGDB Twitch OAuth** is handled internally by Bridge via `IgdbClient.ensureToken(clientId, clientSecret)`. The theme never sees a token. Credentials read from `credentials.json` (`igdb` block).
- **Steam movie URLs** include both legacy fields (`mp4_480`, `mp4_max`) and modern ones (`mp4`, `hls`, `dash`) so the theme's quality selector keeps working.
- **SGDB** returns `style` and `author`; IGDB images are normalised to the same shape with `style=""`, `author="IGDB"`.

---

## 5. Theme side (ReStory)

The theme is a **QML renderer** that:
- Fires `pegasus-data://` Intents
- Polls `done/{jobId}.done` markers via `XMLHttpRequest("file://…")`
- Reads result JSON and binds to UI

### Bridge adapter: `components/services/CoverScraperService.js`

Same public API as the legacy version (15 callback-style functions: `searchSGDB`, `getSGDBGrids`, `searchIGN`, `searchIGDB`, `getIgdbDetails`, …). Internally each function is a thin wrapper over `_run(source, op, params, extract, callback, timeoutMs)`:

1. Generate `jobId` (`gdb_scrape_<ts>_<rand>`).
2. Fire `pegasus-data://scrape-source?source=…&op=…&jobId=…&...`.
3. Spawn a QML Timer (`Qt.createQmlObject`) that polls `done/{jobId}.done` every 300 ms.
4. On done: read `scrape/{jobId}.json`, run extractor, fire callback.
5. Default timeout: 20 s.

GameDatabase.qml call sites (`Scraper.searchSGDB(...)`, etc.) are unchanged — the swap was internal.

### RAService.qml

The theme no longer matches games. `RAFuzzyMatch.js` (236 lines) and
`RAConsoleMap.js` (169 lines) are deleted; ROM-filename parsing, fuzzy scoring
and the console table live only in the Bridge, reached through
`/ra/match` and `/ra/consoles` (`match-ra` / `ra-consoles` on Android).

What the theme keeps is memoisation, under a key of its own that no longer
has to agree with anything the Bridge writes. `gameKey(game)` makes it, from
the Pegasus game and nothing else, and is the only place that knows its
shape: the title without its brackets, the short name of the game's own
first collection, and the name of its first file (lowercase, no folder,
brackets' contents kept, and without what is ASCII and neither a letter nor
a digit), as in `game|snes|gameusasfc`. The file is there so that two dumps
of one title on one platform are two entries, each with its own game; the
collection is the game's own (`platformOf`) so that an entry opened from
"Last Played" or "Favourites" is the same one as from its platform, and it
is the platform the Bridge is asked with: by `/ra/match`, and by the game
database's `/ra/search` for the games a link can be made to, which a list's
name got a 400 from. The title keeps only the letters and digits of ASCII,
as it always did, since older memory is found by it; the file's name keeps
every character that is not ASCII, so that two games named in another
script, of whose titles nothing is left, are still two entries. Two names
that differ only in what is dropped, `Game (USA).sfc` and `Game [USA].sfc`,
are one key. A game with no file keeps the two parts, `game|snes`. Every
caller hands over the game, and `matchRevised` and `romVerdictChanged` carry
the key, which the details panel compares with the key of the game it shows.
The hub's count, the game database's mark on a search result and the way
back from a game's page ask the service (`linkCounts`, `isLinked`,
`libraryGameFor`) and no longer read the stored map.

Memory written before the file was part of the key has keys of two parts.
When an entry has no id under its own key and there is one under the
two-part key of its title and platform, the id, its verdict and its mark are
moved to the entry's key and the two-part key is removed (`_entryKey`), on
whatever reads the entry first: a lookup, the badge, the game database, the
batch verification, `libraryGameFor`. So the first entry of a title to be
seen takes what was remembered and is then treated like any other, and the
other entry of that title starts with nothing and asks about its own file.
Making, clearing and verifying a link move the older key first as well. A
verdict or a mark under a two-part key with no id beside it is not moved. Of
what the entry's own key holds with no id, a verdict goes unless one comes
with the id, and a mark stays and makes the id that arrives a link by hand
(see the writes, below). The theme before this does not find the three-part
keys and asks again. Stored JSON that is missing, empty, corrupt or not a
map of keys is read as nothing remembered, for all three maps.

The three maps are written together. Each waits a second after its last
change, and whichever is due first takes the others that are waiting with it
(`_saveWaiting`), the marks first, then the verdicts, then the ids; the same
when the theme closes. A map nothing has changed is not written. Written
each on its own they could be half a second apart, and a theme killed in
between had a link's mark under one key and its id under another, which the
next session read as an answer of the Bridge's. With the marks first, a
theme killed between two writes leaves the mark of a link that was being
moved under its entry's key before the id, which is why the move keeps such
a mark. Each write is one `api.memory.set`, which rewrites the theme's
settings file.

- `_gameIdCache` — the answer to a past `/ra/match`, plus manual links. A
  remembered id is asked about again once per session, whatever its ROM
  verdict, when Pegasus gives the game a file to ask with, and follows what
  the scan settled for that file: the file's game
  replaces it and is "verified", the same game makes it "verified" and leaves
  what is on screen alone (`romVerdictChanged`, where a change of game is
  `matchRevised`), and `not_in_database`, `unsupported` or `placeholder`
  take it away. A guess by name, a no for any other reason and an error
  change nothing. Once per session is once whatever happens in it: a scan
  run from the hub does not make the theme ask again about a game it has
  already asked about, so that game's id and badge follow the scan the next
  time the theme starts. A guess by name the theme was given itself is kept
  for the session only
- `_handLinks` — which of those ids the user linked by hand, in
  `api.memory("ra_hand_links")`. Nothing automatic replaces or removes such a
  link or its mark, "verified" or not. Its ROM verdict, which is only what
  the badge says, does follow the scan: the link loses the verdict of the id
  it replaces and gets its own from `verifyRomHash` when it is made (dropped
  when it comes back after the link was replaced or cleared), and after that
  the Bridge is asked about the file once per session, under the same
  conditions as for any other remembered id. `ok` with `onDevice` makes the
  verdict "verified" when it is the linked id and "mismatch" when it is
  another; any other answer that is not an error makes it "unsupported"; an
  error changes nothing and the next opening may ask again.
  `romVerdictChanged` says so when the verdict changed. So a game linked
  before its file was scanned reads "ROM Verified" once a scan has matched
  the file to that very game. Clearing the link clears the mark, and clearing the
  cache or signing out clears every link with its mark. A mark found with no
  id under it, which the theme before the marks leaves when it clears a
  link, goes when an answer of the Bridge's is stored there. Memory written
  before the map existed has no marks, so a link made by hand back then
  counts as an answer of the Bridge's and may be replaced or removed once
- `_hashVerifyCache` — the ROM verdict, which a match already carries
- `_consoleTable` — the fetched table, mirrored into `api.memory` so labels are
  right on the first frame of the next launch

It still reads `metadata/_index.json` for the two things that are data rather
than logic: `_loadDiscoveredGames` (`index.games[]`) and the on-device
annotation.

`RaGameFinder` and `RaMatcher` (`shared/ra/src/android-shared/`) are the one
implementation both shells run. `RaMatcher` takes what its caller has already
read, so it needs no filesystem or network of its own; `RaGameFinder` reads
the ledger, the index and the console's list, decides which of them answers
(§3, *Which game a file is*), and is what `RaSync.matchGame` on the desktop
and `RaService` on Android both call. `shared/*/src/android-shared/` exists for
exactly this: files the Android modules compile via `srcDir`, kept apart from
the rest of `shared/` because those names clash with Android's own.

URL prefixing (`"https://media.retroachievements.org" + relativePath`) for `<Image>` tags is left in the theme — RA returns relative paths and image loading via CDN URL is legitimate render concern, not an API call.

### Documented exceptions (renderer-impure, accepted as low-risk debt)

The theme still does HTTP directly in three places, all out of scope for the consolidation:

1. **RA login validation** — `RALoginPanel.qml` calls `API_GetUserSummary.php` once at credential save time. Boundary check; no Bridge benefit.
2. **RA manual game-picker** — `GameDatabase.qml` calls `API_GetGameList.php` to populate the manual link picker. UX known to need rework; deferred.
3. **News widget** — `NewsService.js` fetches Steam/etc RSS feeds. Outside the scrape contract; cosmetic widget.

These three are **independent of the ROM hasher pipeline** and will not block any Bridge-driven feature.

---

## 6. Credentials

`<dataRoot>/config/credentials.json` is the **only** store. The theme keeps no
copy: it is the UI for entering credentials, nothing more.

```json
{
  "steamGridDb": { "apiKey": "..." },
  "igdb":        { "clientId": "...", "clientSecret": "..." },
  "ra":          { "user": "...", "apiKey": "..." }
}
```

The Bridge reads it through `Config.load()`. A missing block throws
`IllegalStateException` from the dispatcher, which becomes a JSON error result.

### What the theme sees

| operation | desktop | Android |
| --------- | ------- | ------- |
| write one or more fields | `POST /credentials` | `set-credentials` |
| which are configured, and the RA username | `GET /credentials/status` | `credentials-status` |
| forget a block (log out) | `GET /credentials/clear?block=ra` | `clear-credentials` |

`status` returns presence flags plus `ra.user` and **never a secret** — asserted
by tests on both sides, and by the theme's own `check_credentials.qml`. That is
what lets the settings screen show "Configured ••••••••" without holding the
value: `BridgeApi.credentialsStatus()` is synchronous (a QML binding cannot wait
on a callback) with a 3 s cache, invalidated on every write.

Consequences worth knowing:

- **API-key fields start blank.** The stored value is never handed back; typing
  replaces it.
- **Logging out clears the Bridge's copy.** Clearing a theme-side value would
  have left the real credentials in place and logged nobody out.
- **Login verifies through the Bridge** (`/ra/profile` with the credentials just
  stored) rather than with a direct call, so it proves them where they are used.
  A pair RA rejects is cleared again instead of being left behind.
- **One-time migration.** `CredentialsWriter.migrateIfNeeded()` hands over
  anything an older theme kept in `api.memory` — and, on Android, the pre-Bridge
  hasher config — then wipes the theme-side copies. It never overwrites a
  credential the Bridge already has.
- The `steam_api_key` field was **removed**: nothing on either side ever read it,
  and Steam scraping uses public data only.

---

## 7. Build & install

```bash
cd "Pegasus Frontend/Plugins/PegasusBridge"
./gradlew :app:assembleDebug         # produces app/build/outputs/apk/debug/app-debug.apk
adb install -r app-debug.apk
```

After install, on first run the theme creates `/sdcard/PegasusData/*` subdirs (or the Bridge does on its first verb invocation via `Paths.ensureAll()`).

### The hasher's library

The hasher is `rahasher_jni.c`, which the JVM calls, and the list in
`hasher/src/main/cpp/rahasher.sources`: rcheevos' six files, two of ours, and
libchdr with the decompressors it carries. `pb_wbfs.c` reads a Wii disc out of
a `.wbfs` container, for which rcheevos has no reader, and hands rcheevos the
disc through the callbacks rcheevos reads any file by. `pb_chd.c` reads a disc
out of a `.chd`, for which it has none either, and hands rcheevos tracks and
sectors through the callbacks it reads a cue sheet by. Neither is a patch to
rcheevos nor in its folder, which `vendored_check.py` holds to upstream.

**The `.chd` reader.** libchdr (`hasher/src/main/cpp/libchdr`, 48 files of
upstream's master at 47d794151b2b, with no patch; `libchdr.version` names
every file and `vendored_check.py` holds the folder to it) unpacks the hunks,
with miniz, the LZMA SDK's decoder, Zstandard's and dr_flac. It is treated as
code that a file can mislead. `pb_chd.c` reads the 124-byte header itself and
refuses, before libchdr is given the file: any version but 5, an image that is
a difference against a parent, a compressor that is none of the nine a disc
image uses, units that are not 2448 or 2048 bytes, hunks over 1 MiB, more
than 5,592,405 hunks (64 MiB of map in libchdr's memory), and a map or
metadata said to lie outside the file. After libchdr has decoded the map it is
gone over once: a hunk that is a copy must be a copy of an earlier hunk of
data, because libchdr follows a copy of a copy by calling itself and a short
map can describe a chain that uses up the stack (still open upstream at that
commit; the row `selfchain.chd` ends with a signal without the check). The
tracks' lines of text are parsed here, strictly and with bounds, and what
follows the last known field is ignored. A hunk that cannot be read, cut
short or failing its checksum, makes the call one with no hash
(`pb_chd_is_damage`, `reasons.damaged` in the two programs): rcheevos takes a
sector that did not come for the end of the file and would answer the hash of
a part.

Where a track lies is worked out in one function, `place`, in two ways:
as RetroAchievements' own hasher does it (RALibretro `HashCHD.cpp`) and as
chdman, which writes the format, lays the frames out. They agree for a first
track, and for a track whose own pregap is in the image (`PGTYPE` beginning
with `V`) when no earlier track has a pregap; then that value is used. Where
they differ the track is refused with a reason that says so ("lies after a
pregap that RetroAchievements' hasher and chdman place differently"), and no
hash is given that only one of them would give. That refuses a Dreamcast
image whose audio track has a pregap, and a PC Engine CD whose data track's
pregap is not in the image or whose first track has one. What chdman 0.289
makes of a Dreamcast disc was seen, on a disc made for it: of a `.gdi`, and
of a cue sheet that carries the two lines a dump of a GD-ROM has (`REM
SINGLE-DENSITY AREA`, `REM HIGH-DENSITY AREA`), tracks with no pregap at all,
each pregap counted to the track before it, and such an image is hashed like
its cue sheet, the program in the last track too; of the same cue sheet
without those two lines, a CD's tracks with the audio tracks' pregaps in the
image, and that one is refused. What an older chdman wrote is not known here.
A request for the largest track (PC-FX) is refused when that track is not the
last, where RetroAchievements' hasher reads it. A track's first sector is
numbered as that hasher numbers it, by the frames of the tracks before it.

What is shown: `native_repro_test.py` hashes the same synthetic disc as a cue
sheet, a `.gdi` or an `.iso` and as a `.chd` for nine consoles (PlayStation,
PlayStation 2 as a CD, a DVD and a DVD in a CD's frames, PSP, Saturn, Sega
CD, Dreamcast, PC Engine CD, 3DO, Neo Geo CD), with every compressor; fourteen
of the images, in `shared/tests/chd/`, were written by chdman 0.289 from
those discs (a DVD with each compressor, CDs, a cue sheet with a pregap, a
`.gdi`, a GD-ROM's cue sheet with and without the lines that name its
areas), which is what puts real packed streams through the decoders. The
images `chd_fixture.py` writes are read back by chdman as well, each way of
packing a hunk, though no test holds that: none may need chdman.
**What is not shown**: a real game's image against the hash RetroAchievements
lists for it, for a multi-track image with pregaps least of all (what its
hasher answers for the synthetic ones is under "What RAHasher 1.8.4 does",
below); a Dreamcast image whose last
track has a pregap of its own in the image and holds the program, which this
reader opens and reads one pregap away from where a cue sheet of the same
disc is read (the row `dreamcast-last-pregap.chd` holds what happens, not
that it is right; chdman 0.289 wrote no such image of the discs it was
given); any decoder against hostile input beyond the rows there are. One
thing is known of that: handed a FLAC stream that has been spoiled, dr_flac
adds and multiplies past what a signed number holds, which C leaves
undefined and the sanitized build reports; what comes out fails the hunk's
checksum, and no row has such a stream. Another is rcheevos' own and older
than the reader: reading a directory of a disc whose sector numbers have
their top bit set, it shifts a byte into the sign of a number
(`hash_disc.c`), which is undefined as well and reported the same way, from
a `.chd` as from an `.iso`; it is left, since a patch there is another
patch level and with it every verdict taken again. The file a scan has in the hasher is
written down
(`cache/scan-inflight.jsonl`), which is what covers a file that ends the
process inside a decoder.

**What RAHasher 1.8.4 does.** RAHasher is the program RetroAchievements
gives out for hashing a file, part of RALibretro, and `place` above models
its `HashCHD.cpp` from the source. It has now been run beside ours:
`shared/tools/rahasher_compare.py --rahasher <RAHasher> --ours
<dir>/rahash_cli` writes the synthetic images, has both programs hash each
image and the disc it was made from, and prints a line an image with a
class. Nothing of RALibretro is in this repository and no CI job runs it;
CI runs the script's own tests, on two programs they write
(`rahasher_compare_test.py`). The run below was made by hand, with RAHasher
built outside the repository from

    git clone --branch 1.8.4 --depth 1 https://github.com/RetroAchievements/RALibretro
    git -C RALibretro submodule update --init --depth 1 src/miniz src/rcheevos src/libchdr src/RAInterface
    make -C RALibretro -f Makefile.RAHasher ARCH=x64 HAVE_CHD=1 CC='gcc -std=gnu17 -DHAVE_UNISTD_H'

(commit 38c3741598b3551347fbce752c604d536d470d63; `git submodule status`:
RAInterface 9b999253eee5b5236f090405b9215c8c9eef00c4, libchdr
cb077337d53392454e7100a0fd07139ca678e527, miniz
36b6c99f622a61b7f97d0238461d4d723aa1322b, rcheevos
f1417fbf2236936e8334533628b2948dfa4a46d0; gcc 16.2.1, which refuses the
bundled zlib without the two additions to `CC`), and ours with
`./native/build.sh <a folder outside the checkout> --tools=<dir>`.

49 images: the 14 chdman wrote and 35 of `chd_fixture.py`'s, the ones made
to be damaged left out. 41 are `AGREE`: both programs give the same hash,
which is the disc's. Among them are 13 of chdman's 14, with the two that
were in doubt: `chdman-gd-cue.chd`, a GD-ROM from a cue sheet whose last
track has a pregap, and `chdman-pce-cd.chd`, an audio track and then a data
track with a pregap. No line is `VERSION`: the newer rcheevos inside
RAHasher hashes each of the ten plain discs as ours does. The other eight
lines, `-` being no hash:

| image | console | ours | RAHasher | the disc | class |
|---|---|---|---|---|---|
| `chdman-gd-cue-bare.chd` | 40 | - | - | c24ded9a… | BOTH-REFUSE |
| `dreamcast-redump-cue.chd` | 40 | - | - | c24ded9a… | BOTH-REFUSE |
| `dreamcast-gdi-2026.chd` | 40 | - | - | c24ded9a… | BOTH-REFUSE |
| `dreamcast-last-pregap.chd` | 40 | - | - | c24ded9a… | BOTH-REFUSE |
| `pce-unstored-pregap.chd` | 76 | - | - | 8c88cff9… | BOTH-REFUSE |
| `jaguar.chd` | 77 | - | - | none expected | BOTH-REFUSE |
| `pce-largest-first.chd` | 49 | - | 8c88cff9… | 8c88cff9… | WE-REFUSE-THEIRS-IS-THE-DISC'S |
| `dreamcast-last-first.chd` | 40 | c24ded9a… | 96ab8781… | c24ded9a… | DIFFER |

What it says, line by line.
- Every image `place` refuses for a pregap the two ways of placing a track
  differ on, RAHasher gives no hash for either ("Not a Dreamcast CD", "Not a
  PC Engine CD"): the three Dreamcast images whose audio track has a pregap,
  chdman's own among them, and the PC Engine CD whose pregap is not in the
  image. So the model of where RAHasher looks was right, and by refusing we
  give up no hash RAHasher would have given.
- `dreamcast-last-pregap.chd`, which the reader opens and then finds no
  program in, RAHasher ends the same way ("Could not read boot executable").
- `pce-largest-first.chd`: asked for the largest track of an image whose
  largest is the first, RAHasher gives the hash both programs give for a cue
  sheet of the same two tracks. The reader refuses that image on the ground
  that RetroAchievements' hasher reads the largest track where the last one
  lies; for this image at least, 1.8.4 does not.
- `dreamcast-last-first.chd`, a GD-ROM whose program begins its fourth
  track, in the very sector after the third: ours gives the disc's hash,
  which is also what both programs give for a `.gdi` sheet of the same four
  tracks, and RAHasher gives another for the image. It says it found the
  program at sector 45024 and hashed 3000 bytes from there, where the third
  track has ended: it answered a read one sector past that track, which is
  the read this reader refuses so that rcheevos goes on to the last track.
  The script ends with status 1 for this line. It is a hand-made image of
  an edge, not a disc anybody has, and it is RAHasher's reading of the image
  that left the disc, not ours.

What it does not say: which hash RetroAchievements lists for a real game.
RAHasher is what a person checks a file with, and it is not what the
emulators players earn achievements in read a `.chd` with. So "RAHasher
gives nothing" does not make a refusal free, and the choice for the images
`place` refuses is among three, **none taken here**: keep refusing; follow
RAHasher, which for these gives nothing; or hash the disc where chdman put
it, which is the hash of the cue sheet. The same holds for the largest
track that is not the last, where RAHasher now gives the disc's. `pb_chd.c`
is as it was. A real multi-track image and the lists would settle one case;
`--also <console>:<file>` adds a file of one's own to the table.

The decoders' `assert()` is compiled out of all three libraries (`NDEBUG`, in
`build.sh` and in `CMakeLists.txt`), so a stream made to mislead one ends in
the decoder's own error or the hunk's checksum and not in the end of the
daemon or the app; the sanitized test program is built with the asserts in.
`native_lib_check.sh` refuses a desktop library or a DLL that still calls
what `assert()` ends the process through; nothing checks the Android one,
which was read by hand.

No variable of the library is a thread's own. `pb_wbfs.c` kept one, the
container rcheevos was reading: rcheevos' callback for opening a file is
given a path and nothing else. The mingw-w64 gcc of `windows-latest` has no
thread-local storage of its own and makes it out of libgcc and winpthreads,
which are linked into the DLL, and winpthreads calls `assert()`: the DLL was
refused for `_assert` there, and on no machine whose compiler has such
storage. The container now carries a copy of its path as its last member,
the iterator's path points at that copy for the one `rc_hash_generate`, and
the callback finds the container a known number of bytes before the pointer
it is handed. That counts on rcheevos handing on the iterator's own pointer
and opening one file. Of a pointer that is not the member the callback can
tell one thing without reading through it: the path begins one byte off the
boundary a container is aligned to, so memory that `malloc` or `strdup`
handed out, which is what a copy of the path would be, gives a container
that begins off it, and rcheevos is told the file did not open. A pointer
into the middle of some other text can pass. So it is held from outside as
well: `rahash_cli --handed`, which `native_repro_test.py` runs on both
builds before any row, has rcheevos hash a file that does not open, a disc
and a package, and sees that the callback is given the iterator's own path,
once. `native_lib_check.sh` refuses a `librahasher.so` that has a segment of
thread-local storage, the committed one and the one the `native` job builds,
so the next such variable is found on Linux and named.

Nothing of the reader, of libchdr or of the decoders is shared between two
calls, with one exception read off the source and not seen to matter:
dr_flac writes what the CPU can do into process-wide flags each time a
decoder is made, the same values from every thread.

Memory for one open image, measured on the Linux test program as the peak
resident size: 1.9 MiB for a file that is not there, 2.1 MiB for an `.iso`,
2.3 MiB for a CD image in chdman's usual hunks, 4.6 MiB at the 1 MiB hunk
limit with four compressors, 19.5 MiB for a map of a single-layer DVD's
1,150,000 hunks, 86.5 MiB for a map at the limit of 5,592,405 hunks (the map
and, while it is decoded, its packed form). A scan hashes several files at
once, each with its own. Those are what is touched. What is asked of the
system can be more: a Zstandard hunk says how far back it may refer, and one
made to say 128 MiB, which is as much as the decoder takes unasked, has it
reserve that much for as long as the image is open, of which no more than a
hunk is ever written to. chdman's own hunks say their own size.

The reader made each library larger: `librahasher.so` 412,744 bytes where it
was 97,288, `rahasher.dll` 502,100 where it was 188,908, the stripped arm64
library of a debug build 304,920 where it was 81,632. No option removes the
decoders' unused functions.

Three builds compile that list:

- **Android** — CMake, with the app, for each ABI. Linked for pages of 16 KB
  (`-Wl,-z,max-page-size=16384` in `hasher/src/main/cpp/CMakeLists.txt`, given
  there whatever the NDK would do by itself), which a library laid out for
  4 KB does not load on and newer devices have. After every link
  `check_page_size.cmake` reads the library's program headers and fails the
  build when a `LOAD` segment is aligned to less, naming it, and removes the
  library it refused: ninja leaves the output of a failed step in place, and
  with the option put back as it was would take that file for built;
  `shared/tests/page_size_check_test.py` holds that check to libraries made
  to fail it, in CI's `native` job, since no job builds Android. **It has
  been loaded on no device with 16 KB pages**: none is at hand, the tablet
  has pages of 4 KB, and what is checked is what the file says of itself.
- **Linux** — `shared/native/build.sh`, into `shared/native/out/librahasher.so`,
  which is **committed**, with `librahasher.manifest` beside it: the SHA-256 of
  the library and of every file it was compiled from, the flags and the
  compiler. The tests and the daemon load that file. A commit that changes the
  C has to carry the library built again, and
  `shared/tests/native_manifest_check.py` fails one that does not;
  `native_lib_check.sh` holds what the library exports and the oldest glibc it
  loads on. `:daemon:installDist` and `package.sh` build it in place, so
  neither is to be left having changed the tracked file by accident.
- **Windows** — the same script with `--target=windows`, or run in Git Bash,
  builds `rahasher.dll` with mingw-w64. It is not committed. It has been built
  and its imports and exports read (`native_lib_check.sh` reads a DLL too, and
  refuses one that calls a `printf_s` function, which ends the process on a
  message too long). **Nothing has loaded it**: the `windows` job of
  `shared-tests.yml` builds it on `windows-latest` and runs the hasher's tests
  against it, and the one run of that job stopped before those tests, at
  `native_lib_check.sh`, for the `_assert` told of above.

  The DLL does call `strcpy_s`, which ends the process the same way when
  what it is to copy does not fit, and that name is not on the list of what
  it may not call. rcheevos' parser of a `.gdi` sheet has the four calls
  (`cdreader_open_gdi_track`): a track's file name and its sector size are
  copied into the buffers kept for the largest track, and back. Each is
  copied from a buffer the parser filled itself and ended with a NUL, into
  one of the same size, 256 bytes for the name and 16 for the digits, of
  which the bounds before them let in 255 characters and nine digits (the
  second bound is local patch 0006's). So no call has anything to refuse.
  As rcheevos stands none is even run: they are for a sheet asked for its
  largest track, which the PC-FX alone asks for. What is asked for is then
  no track's number but a mark, 4294967293, and the parser passes over
  every line whose number is another: every line a disc's sheet has. A
  line numbered -3 reads as the mark, and is taken for the track asked
  for by the branch before the one with the copies. A patch that took them
  out would raise the patch level, and every device would hash its
  unmatched files again for an answer that is the same. It is held from
  outside: `NativeCrashReproTest` hands the library sheets whose name and
  sector size are as long as the bounds admit, which hash, and one longer,
  which are refused in words, in every job that runs the hasher's tests,
  the `windows` job among them; `native_repro_test.py` has the same rows on
  the sanitized program. Both expect a PC-FX disc's `.gdi` to be refused,
  so that an rcheevos which comes to read one, and with it to run the
  copies, fails them and is read again, and both have the sheet with the
  line numbered -3. **Not shown on Windows**, like everything
  else of the DLL: whether a track's name of 255 characters opens there
  depends on the system's limit for a path, and where it does not the test
  takes the refusal for an answer.

CI's `native` job builds a fresh Linux library and runs the tests on that as
well as on the committed one, and runs `shared/tests/native_repro_test.py`:
files made to hurt rcheevos, and containers and images made to hurt the
`.wbfs` and `.chd` readers, each in a process of its own under a timeout, on a
plain build and on one with the address and undefined-behaviour sanitizers.
The `.chd` images are written by `shared/tests/chd_fixture.py`.
`hasher/src/main/cpp/rcheevos-patches/PATCHES.md` says what each local patch is
for and which of those rows hold it.

### Auditing a scan

`./gradlew :daemon:audit -PauditArgs='--audit=<folder> --out=<file.tsv>'`, from
`shared/`, runs one scan as the daemon would, into a data root of its own, and
writes a row for every file: its collection, state, console, hash and whether
the hash was looked up (`ScanAudit`). It makes no request unless given
`--lookup`; `--oracle=<file>` answers from hashes recorded earlier. With
`--lookup` the key is read from the data root named by `--data-root`, the
lists are kept in the audit's own data root, and the table says under
`# requests` how many requests were made, how many lists were fetched and how
many read from disk; with no key there the audit stops before it hashes. Each row
also says how many bytes the scan asked the system for while it hashed the
file (`read`, counted on Linux only), which is how a change to what a scan
reads is measured. With `--keep=<dir>` the data root is that folder and is
left there, so a second audit on it is a rescan and one killed and started
again is a scan that resumes.
`shared/tools/audit_report.py` reads the table, counts it by collection and
state, compares it with the table of another build row by row, and looks for
hashes that are wrong whatever the source would say (a descriptor hashed as
its own text, a file hashed by its name outside an arcade collection, any hash
in a collection that has no algorithm, a Game Boy hash for a file that is not
one). It is how a change to hashing is judged on a real library before it is
merged.

The table also has the scan's own account of its time, in a `# metrics`
line: the object a daemon puts in the record a scan ends on ("Where a scan's
time went", above), written by the same function. The report prints it,
beside the baseline's when that table has the line, and judges nothing by
it. What it can hold is what was read: `--max-read-growth <percent>` adds up
the `read` column over the files both tables have at one size and fails when
this table's sum is that much above the baseline's. It needs no `# metrics`
line, so it judges a table against one of an older build, and a file the
library gained since is not counted as growth. The report's last line is
always a verdict: `VERDICT: same as the baseline`, `VERDICT: N files differ
from the baseline`, `VERDICT: no baseline`, or `VERDICT: failed:` with the
reasons.

`shared/tools/hash_check.sh --library '<root>[|<root>...]' --keep-in <dir>` is
the two made one command: it audits the library with the build it is run
from, asking nobody, reports against `<dir>/baseline.tsv` with no file
allowed to differ (`--allow N` for a change that is meant to move some) and
no more than a tenth more read, prints the time and the verdict, and ends
with the report's status. `--accept` makes a table the baseline, this run's
or, given alone, the last run's. Each run leaves its table, its report and a
line in `<dir>/history.tsv`: the date, the commit, the recipe, the files,
the files hashed, the bytes read, the scan's milliseconds by the clock and
in the hasher, the files that differ and the verdict. That file is the
benchmark there is: one library on one machine, a line for each build.
**`<dir>` is outside the repository**, baseline and history with it: a table
names every file of the library it is of. Not held by any test: the script
itself, which was run by hand on generated files; the report it calls has
its tests.

---


### Service modes (Linux)

`install.sh` installs one of two arrangements, and switching removes the other's
units:

| | always-on (default) | `--on-demand` |
| --- | --- | --- |
| starts | at login | on the first connection |
| stops | at logout | after `--idle-time` (default 60s) with no connection |
| resident cost | ~200 MB | none while idle |
| port | dynamic, published in `daemon.json` | fixed (default 38700, probed upward if taken) |
| first request after idle | — | ~0.3 s |

On-demand is three units. systemd holds the public port; the first connection
starts `systemd-socket-proxyd`, which pulls up the daemon behind it on an
internal port and exits once idle, taking the daemon with it via
`StopWhenUnneeded=yes`.

The proxy exists because **a JVM cannot adopt systemd's inherited listening
descriptor** — `System.inheritedChannel()` reads fd 0, and systemd passes the
socket on fd 3 with `Accept=no`.

Three things this arrangement needs, each learned by getting it wrong first:

- **`daemon.json` must outlive the daemon.** It is how the theme learns which
  port to knock on, and knocking is what starts the daemon; deleting it at
  shutdown makes the wake-up unreachable. `--advertise-port=` puts the public
  port in the file, marks it `"managed": true`, and suppresses the delete. The
  installer writes it too, for the run before the daemon has ever started.
- **`Type=notify`, not `simple`.** With `simple` the unit counts as started at
  fork, so the proxy connects before the JVM has bound anything and the first
  request of every cold start comes back empty. The daemon signals readiness by
  running `systemd-notify --ready` (hence `NotifyAccess=all`) — the notify socket
  is a Unix *datagram* socket, which the JDK's channel API cannot open.
- **`SuccessExitStatus=143`.** A JVM killed by SIGTERM exits 143, which systemd
  calls a failure; with `Restart=on-failure` that restarts the daemon the instant
  the idle timeout stops it.

A ROM scan keeps connections flowing, so it never idles out mid-run. Closing
Pegasus during a scan does stop it — and costs little, because a rescan is
incremental.

### Requests from a web page

Listening on 127.0.0.1 keeps other machines out. It does nothing about a web
page open in a browser on the same machine: the browser is a local process and
sends a GET to loopback for any page that asks, as a picture, a script, a frame
or a `fetch`. Every route answers a GET and several of them write —
`/emulators/apply` puts a `launch:` command in a file Pegasus later runs — and
with `--on-demand` the port is a fixed one, 38700 unless another was asked for.
Until this was looked at the server checked nothing and put
`Access-Control-Allow-Origin: *` on every answer, so a page could also *read*:
`/health` gives the data root and other routes list collections and
directories, enough to then plant a command in one. A request to
`/emulators/apply` with `Origin: https://attacker.invalid` and a foreign `Host`
was answered 200 and the file was written.

`CrossSiteGuard` now refuses every request a browser makes, which it knows by
the headers a browser writes and a page's script cannot write, change or take
away. `MicroHttpServer` asks it before the handler and instead of it:

| header | served | refused | what it catches |
| --- | --- | --- | --- |
| `Host` | absent; `127.0.0.1`, `localhost`, `[::1]`, any port | any other name, `0.0.0.0`, empty; twice in one request is a 400 | DNS rebinding: a page that points its own name at 127.0.0.1 is same-origin to the browser, which on plain http under that name adds none of the headers below, and its name is still in `Host` |
| `Origin` | absent | present, whatever it names: another site, `null`, this machine | a fetch to another origin, a form that posts, every WebSocket handshake |
| any name that begins `Sec-` | absent | present, whatever it says | `Sec-Fetch-Site` and its kin are on every request a current browser makes to this address but a WebSocket handshake, which has `Sec-WebSocket-Key`; a prefetch has `Sec-Purpose` too |

A refusal is a 403 with the JSON error body every other error has (so a theme
that is ever refused shows the reason rather than "no response"), and a line of
the log: method, path, the header and its value, each cut to 120 printable ASCII
characters. Never the query, where `/credentials` takes its keys. The line is at
the lowest level, which keeps it out of nothing: the daemon prints every level,
so it is in the journal. A page can have these sent as fast as its browser
will, so twenty a minute are written and the rest counted, the count written
with the first refusal of a later minute.

**The daemon's callers send none of these headers**, which is what lets every
request that has one be refused. A QML `XMLHttpRequest` was pointed at a
listener that prints what it receives, from Qt 5.15.19 (`qml` and `qmlscene`;
5.15 is what Pegasus is built on) and from Qt 6.12.0, synchronous and
asynchronous. All of it:

```
GET /health HTTP/1.1
Host: 127.0.0.1:23871
Connection: Keep-Alive
Accept-Encoding: gzip, deflate          (Qt 6: zstd, br, gzip, deflate)
Accept-Language: it-IT,en,*
User-Agent: Mozilla/5.0
```

No `Origin`, no `Sec-Fetch-Site`, no `Referer`. curl sends `Host`, `User-Agent`
and `Accept`. So the rules cost the daemon's own callers nothing — and for the
same reason they stop no process on this machine, which sends what it likes.

**No value of a browser's headers is served**, though two of them look safe.
`Sec-Fetch-Site: none` is what a browser says of an address the person typed,
and an `Origin` on this machine looks like a neighbour. A daemon that served
both was put in front of Chrome 155, and `/emulators/apply` wrote its file three
ways:

- A page of another site with a speculation rule naming the daemon's URL. The
  browser prefetched it, nobody having clicked anything, as `Sec-Purpose:
  prefetch`, `Sec-Fetch-Site: none`, `Sec-Fetch-Mode: navigate`.
- An address of another site given to the browser, as a person types one or
  opens a bookmark, which answered 302 with the daemon's URL in `Location`. The
  redirect was followed as `Sec-Fetch-Site: none`, `Sec-Fetch-User: ?1`. `none`
  says who started the request, not who chose where it ends.
- A page served from another port of this machine opening a WebSocket to the
  daemon. The handshake is a GET with `Upgrade: websocket`,
  `Origin: http://localhost:<that port>` and no `Sec-Fetch-Site` at all, and the
  handler ran on the GET.

All three are refused now, and so is the person who opens the daemon's address
in a browser: the 403 says to use curl, and by arriving shows the daemon is up.
The daemon serves no page, so `same-origin` and an origin of its own have
nobody to belong to.

- **No `Host` passes.** An HTTP/1.0 client sends none and a browser always sends
  one, so a request without it is not the one the rule is looking for.
- **Any port in `Host`.** Under socket activation the theme talks to the port
  systemd holds and `systemd-socket-proxyd` passes the bytes on unchanged, so
  `Host` names a port the daemon never opened. Tried with the proxy itself in
  front of a daemon: the theme's `BridgeApi.js` found it through `daemon.json`,
  read and wrote; the refusals were refused through it as well.
- **`0.0.0.0` is refused by name.** Linux delivers a connection to it to the
  socket bound on 127.0.0.1, and `Host` then says `0.0.0.0:<port>`.
- **Two `Host`s are a 400**, as RFC 9112 has it. The parser keeps one value for
  a name, the last, and a refused name followed by an allowed one was served.
- **Only a space or a tab is taken off a value.** `127.0.0.1` followed by a
  no-break space or a control character was trimmed into the allowed name.
- **No `Access-Control-Allow-Origin` at all**, rather than one that echoes an
  allowed origin. A QML `XMLHttpRequest` does not ask for it: it read the
  listener's answers, which carried none. With no such header no page of another
  origin can read an answer, and an `OPTIONS` preflight is approved for nobody.
  Every answer also carries `Cross-Origin-Resource-Policy: same-origin` and
  `X-Content-Type-Options: nosniff`, for the browser that gets a request
  through: it may not hand the answer to another origin's page as a picture or
  a script.
- **The whole `Sec-` prefix, not a list.** It is the prefix kept for headers a
  page's script may not set, so the next one a browser invents is refused
  before anyone here has heard of it. `Upgrade` is not asked: `Origin` and
  `Sec-WebSocket-Key` already refuse a browser's WebSocket, and clients that are
  not browsers send `Upgrade: h2c`.

Chrome 155, headless, on pages served from `localhost` and from another port of
127.0.0.1, every way tried of making it ask the daemon for `/emulators/apply`: a
picture, a frame, a `no-cors` and a `cors` fetch, a beacon, a link prefetch, a
script that sets `location`, the speculation rule (prefetch and prerender), the
redirect, the WebSocket, and the daemon's address given to the browser itself.
Each request carried `Origin` or a `Sec-` header, was answered 403 and wrote
nothing. Under a name mapped to 127.0.0.1 the same browser sent no `Sec-` header
and no `Origin`, only that name in `Host`, and was refused on it.

What this does **not** do:

- **A browser too old to send `Sec-Fetch-Site`** (Chrome before 76, Firefox
  before 90, Safari before 16.4; none of them was tried), on a request that
  carries no `Origin` — an `<img>`, a link, a form that GETs, a prefetch — is
  not recognised, and while writes are GETs it can be a write. Rebinding is
  still caught, by `Host`, and a WebSocket by `Origin`.
- **Only Chrome was tried.** That every current browser holds
  `http://127.0.0.1` and `http://localhost` trustworthy, and so sends
  `Sec-Fetch-Site` to them, is taken from the specification. One that did not
  would be the old browser above.
- **Any local process can call the daemon.** Nothing here is authentication.
- **Nobody can look at `/health` in a browser.** curl answers the same question.
- **A sign-in that ends on the daemon** (the Spotify callback of
  `INTEGRATION_FEASIBILITY.md`) arrives as a browser's navigation from another
  site and is refused like any other. Its route will need an exception of its
  own in the guard, held to a one-time `state` value the daemon gave out.
- The router does not look at the method, so a request that passes is served
  whatever its verb.

Not done here: a token the theme would have to present, moving the writes to
POST. The Android app has no HTTP server and none of this applies to it.

## 8. Writing into the user's library

Two of the daemon's endpoint groups reach outside `<dataRoot>` and into the
directories the user owns and hand-edits. They are in `PegasusRoutes`, apart
from the rest, because that is a different risk from calling a remote API.

### Media export

Pegasus has **two** asset providers and they disagree about what a filename
means. Both were read from Pegasus' source rather than its documentation,
because the documentation gives the directory names and not the matching rule:

- `pegasus_media/MediaProvider.cpp` — `media/<name>/boxFront.png`, where
  `<name>` may be the ROM's base name **or the game's title**.
- `skraper/SkraperAssetsProvider.cpp` — `media/box2dfront/<romBaseName>.png`,
  matched against the ROM's base name **only**.

`AssetLayout` knows both, detects which a collection already uses, and names
files with Qt's `completeBaseName` — everything before the *last* dot, so
`Super Mario Bros. (World)` keeps its full stop.

Every file written goes into `cache/export-manifest.json`. That is what lets the
exporter refuse to overwrite a picture it did not write, and lets `revert`
remove exactly its own and leave everything else — including a file somebody has
since edited, which is a decision rather than a leftover.

### Emulator discovery

`EmulatorDiscovery` probes PATH and Flatpak for known emulators and **runs each
one** for its version string, so a file called `mame` that is not MAME is
reported unverified rather than offered. It proposes; it never applies.

`/emulators/apply` writes a Bridge-owned overlay,
`zz-pegasusbridge.metadata.pegasus.txt`. If the collection's own metadata file
already declares a launch command it refuses first, because Pegasus resolves two
files declaring one collection with `get_or_create_collection` plus
`setCommonLaunchCmd` — which overwrites — and `find_metafiles_in` iterates with
a bare `QDirIterator` and no sort flag. Last parsed wins, and which one that is
depends on the filesystem. `standAside=1` backs the user's file up and comments
out only its launch block, leaving one declaration and a byte-exact undo.

### Choosing an emulator

Two scopes, matching what the theme offers: the Quick menu picks one for a
collection, the game database overrides it for a single title. Pegasus supports
both — a collection `launch:` and a per-game `launch:`, the latter through
`GameAttrib::LAUNCH_CMD` calling `setLaunchCmd` on the game.

`/launch/options` returns **every** emulator that handles the platform, best
first, each with a sentence saying why it is there. A proposal whose
alternatives cannot be seen is a decision made for somebody rather than offered
to them. The selection is reported three ways — `chosenForGame`,
`chosenForCollection`, `effective` — so a UI can distinguish "chosen here" from
"inherited" from "just the default", which is what makes a clear action mean
anything.

`config/launch-preferences.json` holds the choices, and deliberately not the
overlay: that file is regenerated by every metadata export, and a decision
somebody made must not be a casualty of scraping a collection again. It stores
the emulator's **id**, not its command — a command frozen at the moment of
choosing goes stale when a Flatpak is replaced by a native package, while an id
lets discovery re-derive the command and lets an uninstalled emulator show up as
`notInstalled` instead of a launch line that fails.

### The scanner's extension list

`RomScanner.ROM_EXTENSIONS` is now a **default**, not a definition: a scan adds
whatever the `extensions:` lines of a file's collection declare, on Android and
on the desktop alike, and in the folders under the collection's own as well.
The two need not agree. In the development library they never met: its
collections declare no extension and name each game with a `file:` line, a
placeholder called `.jud`, and a scan does not read `file:` lines, so those are
simply not scanned.

Which collection a file is in is read where Pegasus reads it: the nearest
`metadata.pegasus.txt` at or above the file's folder that declares a
`collection:` (`CollectionResolver`). The scan takes the file's platform from
that collection's `shortname:`, so `psx/<game>/<game>.cue` is a `psx` game and
not one of a platform called `<game>`. A folder under no such file is still
taken for the collection its name says.

One metafile may declare several collections. A collection that lists a folder
in `directories:` has it. Otherwise the folders say it themselves, from the one
right under the metafile down: the first that is called what one of the
collections is called (`megadrive` answers to `genesis`) is that collection's,
and one called for a console that none of them is, is the folder it is called.
Only where the folders say nothing is the first collection declared taken, with
a warning in the log.

## 9. What changed vs. legacy

| Concern                  | Before (legacy)                        | Now (Bridge)                          |
| ------------------------ | -------------------------------------- | ------------------------------------- |
| Cover/media scraping     | Theme HTTP via `CoverScraperService.js` | `pegasus-data://scrape-source` verb   |
| IGDB OAuth               | Theme-stored Twitch token              | Bridge-internal, `ensureToken()`      |
| RA discovery cache       | `ra_hashes_cache.json` in theme        | `metadata/_index.json` from Bridge    |
| ROM hashing              | Separate APK (`ra-hasher.*` scripts)   | `:hasher` module, `scan` verb         |
| Video playback           | Separate APK (PegasusVideoPlayer)      | `:video` module, video verbs          |
| Number of installed APKs | 3                                      | 1                                     |
