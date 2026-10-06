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
| `match-ra`             | RaService         | Which RA game a Pegasus game is (scan index, then catalogue) |
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
computes RA-compatible hashes (with iNES/SMC/N64 header stripping), asks
RetroAchievements about each and writes one `metadata/{gameId}.json` per match.
What it settles about every other file goes into `cache/scan-ledger.json`, so a
miss is not read and asked about again on the next scan.

Each shell supplies what stands around it:

- **Android** — `HasherService` (`hasher/`): the foreground service, the wake
  lock, the notification with its Cancel, the thermal back-off the pipeline
  takes as its throttle, the native hasher (`NativeHasher`), what Android says
  of the connection (`DeviceNetwork`), and the job record below.
- **Desktop** — `BridgeRouter` (`GET /scan`) and `JobRegistry` in
  `shared/daemon`, with `NativeRomHasher` and what the JVM can tell of the
  connection (`HostNetwork`).

Files are hashed 2 at a time on Android and 4 on the desktop. Either can be
told otherwise, to time a library on its own storage: `hashWorkers=N` on the
`pegasus-data://scan` URI (1–8), `--hash-workers=N` on the daemon (1–16).
Neither hashes more files at once than the machine has cores, whatever the
count, and the count each puts in its log or prints is the one it runs with.
Lookups are paced the same whatever the count.

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
| `KEY_REFUSED` | RetroAchievements answered the key with a 401 | `RetroAchievements refused the API key` |
| `OFFLINE` | a request failed without an answer and the device says it has no connection | `No internet connection: stopped after` |
| `SOURCE_DOWN` | 8 lookups in a row got no answer | `RetroAchievements stopped responding after` |

Whichever it was, the file whose lookup failed is in the ledger as `API_RETRY`
and never as `NOT_FOUND`, the ledger and the index are saved, and the next scan
asks only about what this one did not settle. That is why `OFFLINE` waits for
a result whose lookup failed: the lookup knows it has no connection before its
result reaches the collector, and a scan stopped at whichever file went by in
between, one that needed no lookup, would end with that failure in none of its
counts and its file in no ledger.

**No connection.** What the device knows about its connection explains a
failure and prevents nothing. Every request is made. Only when one has failed
without an answer of any kind — an exception, not an HTTP status — does the
lookup ask the device, and if the device says it is offline the lookup does not
go through its other three attempts and their seven seconds of back-off: it
gives up, and the pipeline stops the scan at that result. A device that is
wrong about being offline therefore costs nothing while requests get through,
and one that cannot say, or throws when asked, counts as online. Nothing is
asked before a scan, or during one that makes no request: a library scanned
before is scanned again on a plane and ends `done`, every file cached. The
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
- **The ledger** — logged, and that is all. It is written as a scan ends, and
  the scan ends as it would have. The next one asks again about every file
  that is not a match.

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

### The discovery index

Whenever the pipeline has walked the roots — at the end of a scan, and also of
one that found no ROM, was stopped, was cancelled or failed — it rebuilds
**`metadata/_index.json`**:

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
  of the folder it sits in.

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
and the 137-entry console table live only in the Bridge, reached through
`/ra/match` and `/ra/consoles` (`match-ra` / `ra-consoles` on Android).

What the theme keeps is memoisation, under its own key format (`_memoKey`),
which no longer has to agree with anything the Bridge writes:
- `_gameIdCache` — the answer to a past `/ra/match`, plus manual links
- `_hashVerifyCache` — the ROM verdict, which a match already carries
- `_consoleTable` — the fetched table, mirrored into `api.memory` so labels are
  right on the first frame of the next launch

It still reads `metadata/_index.json` for the two things that are data rather
than logic: `_loadDiscoveredGames` (`index.games[]`) and the on-device
annotation.

`RaMatcher` (`shared/ra/src/android-shared/`) is the one implementation both
shells run — it takes the JSON its caller has already read, so it needs no
filesystem or network of its own. `shared/*/src/android-shared/` exists for
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

`RomScanner.ROM_EXTENSIONS` is now a **default**, not a definition: the daemon
adds whatever each collection's `extensions:` line declares. They disagree more
often than is comfortable — every collection in the development library declares
`jud`, which the built-in list has never heard of.

## 9. What changed vs. legacy

| Concern                  | Before (legacy)                        | Now (Bridge)                          |
| ------------------------ | -------------------------------------- | ------------------------------------- |
| Cover/media scraping     | Theme HTTP via `CoverScraperService.js` | `pegasus-data://scrape-source` verb   |
| IGDB OAuth               | Theme-stored Twitch token              | Bridge-internal, `ensureToken()`      |
| RA discovery cache       | `ra_hashes_cache.json` in theme        | `metadata/_index.json` from Bridge    |
| ROM hashing              | Separate APK (`ra-hasher.*` scripts)   | `:hasher` module, `scan` verb         |
| Video playback           | Separate APK (PegasusVideoPlayer)      | `:video` module, video verbs          |
| Number of installed APKs | 3                                      | 1                                     |
