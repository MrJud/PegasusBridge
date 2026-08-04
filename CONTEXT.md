# PegasusBridge — Context

Single Android APK that consolidates three legacy plugins (PegasusBridge, RetroAchievements ROM Hasher, PegasusVideoPlayer) into one binary. Replaces theme-side HTTP/scraping/hashing logic with a unified service layer reachable via `pegasus-data://` Intent URIs and file-based IPC under `/sdcard/PegasusData/`.

The QML theme (ReStory) was reduced to a **renderer**: it fires verbs, polls for `done/{jobId}.done` markers, and reads result JSON via `XMLHttpRequest("file://…")`.

---

## 1. Repo layout

```
PegasusBridge/
  app/        — DataLayerApp + DataLayerRouter (manifest entry point, URI dispatch)
  core/       — Paths, Config, schema constants (shared by all modules)
  hasher/     — ROM scanning + RA hash matching → metadata/*.json + metadata/_index.json
  media/      — MediaService + ScrapeSourceDispatcher (SGDB / IGN / Steam / IGDB clients)
  ra/         — RA profile / achievements / game-list refresh
  video/      — Trailer search/play/download
  app-debug.apk
```

Build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (also copied at repo root).

---

## 2. IPC contract

### URI scheme

The theme fires Android Intents with `pegasus-data://<verb>?…` URIs. `DataLayerRouter` dispatches based on the path segment.

| Verb                   | Service           | Purpose                                         |
| ---------------------- | ----------------- | ----------------------------------------------- |
| `scan`                 | HasherService     | Scan ROM tree → write `metadata/*.json` + `_index.json` |
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
pending/{jobId}.json        — request payload (written by service on accept; for scan jobs
                               includes newEntries, cachedHits, skippedPlatforms counters)
done/{jobId}.done           — empty marker file: appears when result is ready
scrape/{jobId}.json         — scrape-media / scrape-source result
search-ra/{jobId}.json      — search-ra-games result
search/{jobId}.json         — search-video result
download/{jobId}.json       — download-video result
metadata/{gameId}.json      — per-game RA metadata (HasherService output)
metadata/_index.json        — discovery index: { games[], byKey{} } (HasherService output)
profile/{user}.json         — RA profile cache
completion/{user}.json      — RA completion cache
media/{gameId}.json         — aggregated media cache (scrape-media output)
```

**Job lifecycle**:
1. Theme generates `jobId`, fires `pegasus-data://verb?...&jobId=…`.
2. Service writes pending/{jobId}.json (optional, for visibility).
3. Service does work, writes result atomically (temp file + rename).
4. Service touches `done/{jobId}.done` last → theme polling wakes up.
5. Theme reads result, deletes done marker.

Atomic writes use `tmp.renameTo(out)` to avoid partial-read races.

---

## 3. HasherService — discovery index

`HasherService` scans the configured ROM tree, computes RA-compatible hashes (with iNES/SMC/N64 header stripping), matches against the RA hash catalog, and writes one `metadata/{gameId}.json` per match.

After every scan it builds **`metadata/_index.json`**:

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
    "super mario world|snes": {
      "gameId": 7236, "title": "...", "platform": "snes",
      "imageIcon": "/Images/12345.png", "total": 50
    }
  }
}
```

- `games[]` powers the "discovered on-device" list in the RA hub.
- `byKey{}` is the reverse-lookup map used by `_lookupFromApkCache` and `_checkExternalHashCache`. Key format: `normalize(title)|shortName` (FuzzyMatch.makeCacheKey).

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

## 8. What changed vs. legacy

| Concern                  | Before (legacy)                        | Now (Bridge)                          |
| ------------------------ | -------------------------------------- | ------------------------------------- |
| Cover/media scraping     | Theme HTTP via `CoverScraperService.js` | `pegasus-data://scrape-source` verb   |
| IGDB OAuth               | Theme-stored Twitch token              | Bridge-internal, `ensureToken()`      |
| RA discovery cache       | `ra_hashes_cache.json` in theme        | `metadata/_index.json` from Bridge    |
| ROM hashing              | Separate APK (`ra-hasher.*` scripts)   | `:hasher` module, `scan` verb         |
| Video playback           | Separate APK (PegasusVideoPlayer)      | `:video` module, video verbs          |
| Number of installed APKs | 3                                      | 1                                     |
