# Pegasus Bridge

A companion backend for [Pegasus Frontend](https://pegasus-frontend.org/) themes.

Pegasus themes are QML, and QML cannot spawn processes, write files or hash a ROM.
Pegasus Bridge does those things on the theme's behalf and answers over a small
local API, so a theme stays a renderer:

- **RetroAchievements** — profile, per-game progress, and identifying which RA
  game a library entry is, by ROM hash (rcheevos) with a fuzzy fallback.
- **Scraping** — SteamGridDB, IGDB, IGN and Steam.
- **Trailers** — YouTube search, stream resolution and download.

One codebase, two shells: an Android APK and a desktop daemon. The contract a
theme sees is the same on both, so a theme written against it works on either.

---

## Status

| | state |
| --- | --- |
| Linux (x86_64) | working, verified end to end |
| Android (arm64, API 26+) | working, verified on device |
| Windows | not started |

---

## Compatible themes

| theme | uses the Bridge for |
| --- | --- |
| [ReStory](https://github.com/MrJud/ReStory) | RetroAchievements, scraping, trailers |
| *your theme* | anything in [The API](#the-api) |

Nothing here is specific to one theme. ReStory is simply the first, and it is the
shortest way to see the API used in practice — it holds no matching or scraping
logic of its own, it asks and renders. If you have wired up another theme, open a
pull request and add it here.

---

## Install — Linux

Download the release archive, check it, unpack it, run the installer.

```bash
tar xzf pegasus-bridge-<version>-linux-x86_64.tar.gz
sha256sum -c pegasus-bridge-<version>-linux-x86_64.tar.gz.sha256

cd pegasus-bridge-<version>-linux-x86_64
./install.sh
```

Everything lands under `$HOME`; nothing needs root. The archive carries its own
Java runtime, so **no JDK or JRE is required**.

- `~/.local/share/pegasus-bridge/app` — the daemon
- `~/.local/share/pegasus-bridge/` — its data (caches, credentials, scan index)
- `~/.config/pegasus-frontend/bridge.json` — how a theme finds the data root

### How themes find it

A theme is QML: it cannot expand `~` or read an environment variable, so the
absolute data root has to be written somewhere it can reach by relative path.
The installer does that for you, and there is nothing to configure.

It writes `bridge.json` into your Pegasus configuration directory, where every
theme can see it, and a copy into each installed theme whose own sources ask for
one. A theme directory that is a symlink to a working copy is left untouched —
the shared file covers it without the installer writing inside your repository.

Installed a theme afterwards? Point it at the Bridge without reinstalling:

```bash
~/.local/share/pegasus-bridge/app/install.sh --link-only
```

`--theme DIR` points one theme explicitly, repeat it for several, and
`--no-themes` skips this step entirely.

### Always on, or on demand?

By default the daemon starts at login and stays up, holding about 200 MB.

```bash
./install.sh --on-demand
```

installs it socket-activated instead: systemd holds the port, the daemon starts
on the first connection and stops again after a minute of silence. It costs
nothing while Pegasus is closed, and the first request after an idle period
takes about a third of a second. Tune with `--idle-time 5min` or `--port 38700`.

Switching between the two modes is just re-running the installer with or without
the flag.

### Uninstall

```bash
./install.sh --uninstall
```

Your data under `~/.local/share/pegasus-bridge` is left alone; delete it by hand
if you want it gone. Pointer files are removed too, but only the ones naming
this data root — a second install, or a file you wrote yourself, survives.

---

## Install — Android

1. Install the APK (allow installing from unknown sources when prompted).
2. **Open the app once and grant "All files access."** It will ask. This is not
   optional and it is the most common reason for a silent failure: the Bridge
   writes its results to `/sdcard/PegasusData`, and without that permission it
   starts, does its work, and writes nothing. Nothing else reports an error —
   the theme simply waits.
   You can confirm it was granted with:
   ```
   adb shell appops get com.pegasus.bridge MANAGE_EXTERNAL_STORAGE
   ```
3. Install the theme as usual. There is nothing to configure: the data root is
   `/sdcard/PegasusData` on every Android device.

The APK is not on any store. It asks for `MANAGE_EXTERNAL_STORAGE` because
Pegasus, the Bridge and the theme are three separate apps that have to read each
other's files, which scoped storage does not allow.

---

## Credentials

The Bridge stores every API key, in `<dataRoot>/config/credentials.json`. A theme
never keeps a copy — it can ask which are configured, and it gets back yes/no
plus the RetroAchievements username, never a value.

Enter them from the theme's own settings screen. What you need:

| service | what to get | where |
| --- | --- | --- |
| RetroAchievements | username + Web API key | [retroachievements.org/settings](https://retroachievements.org/settings) → API keys |
| SteamGridDB | API key — required for covers, logos and heroes | [steamgriddb.com/profile/preferences/api](https://www.steamgriddb.com/profile/preferences/api) |
| IGDB | Client ID + Client Secret, via a Twitch application | [dev.twitch.tv/console/apps](https://dev.twitch.tv/console/apps) |

Steam and IGN need no key.

### Developer credentials, if a source ever needs them

Some databases issue a *developer* credential to the application itself, separate
from the user's own account. Such a credential is never committed: it is read at
build time from `local.properties` or the environment, exactly as the Android
release signing config already reads `RELEASE_STORE_FILE`. A build with none set
simply omits that source.

This matters because the repository is public — anything committed here is
extractable by anyone, and an application-wide credential that leaks gets revoked
for every user of the app, not just the one who leaked it.

---

## Behaving well against remote APIs

Every source here is someone else's server, usually run for a community rather
than for profit, and the scan is the part that could hurt one. So the hash lookup
is paced rather than parallelised as hard as the network allows:

- at most **2 requests in flight**, spaced **≥250 ms** apart;
- "the request failed" and "the answer is no" are kept apart, and a failure is
  **never cached** — otherwise a refused request is remembered as *this game does
  not exist* and no later scan ever asks again;
- a rejection is retried, and after **8 consecutive failures** the scan stops with
  a message instead of grinding through the rest of the library;
- a request that fails on a device that knows it has **no internet connection** is
  not retried at all: the scan stops at that first failure and says so, where it
  used to spend half a minute on retries and then blame the source;
- results are cached locally and rescans are incremental, so a second scan of an
  unchanged library makes no network calls at all.

Measured on a 913-ROM library against RetroAchievements: **913 processed, 0 lookups
failed**, first scan ~6.5 min, incremental rescan 32 s with no requests. Before the
pacing existed the same library got roughly 85 answers and refusals for the rest.

---

## Sources being considered

The four sources above cover modern games well and retro games poorly, because they
match on the title. Two databases would fix that; neither is implemented, and neither
will be started before its credentials exist — writing a client against an API you
cannot call produces hundreds of lines nobody can verify.

**[ScreenScraper.fr](https://www.screenscraper.fr)** is the one that matters. It
matches **by ROM hash**, which is what lets ES-DE and Skraper identify a retro dump
that no title search would find, and the Bridge already computes those hashes for
RetroAchievements — the rcheevos hash plus the file's own MD5 and CRC32. Its media
map onto this project's categories one for one: `box-2D` to cover, `wheel`/`wheel-hd`
to logo, `fanart` to wallpaper, `ss`/`sstitle` to screenshots, and `video`. It also
answers with region- and language-aware metadata, which is the only route to
descriptions in a language other than English.

Access needs a `devid`/`devpassword` issued to the application, on the condition that
it is integrated only into entirely free software — this project is GPL-3.0, is not
sold, and carries no advertising. Each user additionally supplies their own account
to raise their quota, which the existing `credentials.json` model already handles.
The application credential itself would be read at build time and never committed,
as described under [Developer credentials](#developer-credentials-if-a-source-ever-needs-them).

Its quotas are per-thread and per-day, and the refusals are specific: **429** too many
threads, **430** the daily allowance, **431** too many unrecognised ROMs. The last one
matters here, because a real library contains bad dumps and placeholder files that will
never be recognised, and hammering on them is exactly the behaviour a quota is meant to
stop. The pacing above already covers all three — two requests in flight, spaced, no
failure ever cached as a negative, and a stop after eight consecutive refusals.

**[TheGamesDB](https://thegamesdb.net)** would come second and stay small. It has no
hash lookup at all, only name search, so it forfeits the advantage that motivates the
work, and its public key allows roughly 1000–1500 requests per month per IP — a single
scan of a 913-ROM library would spend a month's allowance in one go. It belongs as a
per-game source inside the Game Database panel, used when someone opens one game and
asks, never as a bulk scanner.

---

## The API

Desktop: HTTP on loopback. The daemon writes its port to
`<dataRoot>/daemon.json`; read that, then speak HTTP.

The daemon is for programs on the machine, not for web pages, and not for a
browser at all. A request is answered `403` before any endpoint sees it when its
`Host` is not `127.0.0.1`, `localhost` or `[::1]`, or when it carries a header
only a browser writes: an `Origin`, or any header whose name begins `Sec-`
(`Sec-Fetch-Site` is on everything a current browser sends). So an address
opened in a browser is refused too; ask with curl. No answer carries
`Access-Control-Allow-Origin`. A QML `XMLHttpRequest` and curl send none of
those headers, so a theme has nothing to do, and must not add one. This is not
authentication: any local process can still call the daemon.

Android: `pegasus-data://<verb>?…` intents, with results written as JSON under
`<dataRoot>`.

### Finding `<dataRoot>` from a theme

On Android it is always `/sdcard/PegasusData`. On desktop it is wherever the
user installed the Bridge, and QML cannot expand `~` or read the environment, so
the installer writes it into a `bridge.json` your theme reaches by relative path:

```js
// from <theme>/components/data/, i.e. two directories below the theme root
var POINTERS = ["../../bridge.json",        // this theme's own copy
                "../../../../bridge.json"]; // shared by every theme
```

Try them in order and take the first with a `dataRoot`. **Read both.** The first
is absent whenever the theme directory is a symlink to a working copy, which the
installer will not write into; the second is absent on an install that predates
it. `Qt.resolvedUrl` works inside a `.pragma library` and resolves against the
file it is written in — mind the depth of *that* file, not of the caller.

Point the installer at a theme it did not detect with
`install.sh --link-only --theme <dir>`; a theme is detected by naming
`bridge.json` in its own `.qml` or `.js` sources.

| what you want | desktop | Android |
| --- | --- | --- |
| is it alive, which credentials are set | `GET /health` | — |
| which RA game is this? | `GET /ra/match?title=&platform=&file=` | `match-ra` |
| games on a platform | `GET /ra/search?platform=&term=` | `search-ra-games` |
| the console table | `GET /ra/consoles` | `ra-consoles` |
| RA profile / one game | `GET /ra/profile`, `GET /ra/game?gameId=` | `refresh-ra-profile`, `refresh-ra` |
| scrape from a source | `GET /scrape?source=&op=` | `scrape-source` |
| trailers | `GET /video/search?q=`, `/video/resolve`, `/video/download` | `search-video`, `play-video`, `download-video` |
| hash a ROM tree | `GET /scan?roots=` | `scan` |
| credentials | `POST /credentials`, `GET /credentials/status`, `GET /credentials/clear?block=` | `set-credentials`, `credentials-status`, `clear-credentials` |
| which emulators are installed | `GET /emulators` | `emulators` |
| what each collection is and would be proposed | `GET /collections?roots=` | `collections` |
| apply a launch command | `GET /emulators/apply?directory=&launch=` | `apply-emulator` |
| undo that | `GET /emulators/revert?directory=` | `revert-emulator` |
| copy one picture into the library | `GET /export/media?source=&file=&kind=` | `export-media` |
| write the scraped metadata | `POST /export/metadata?directory=` | `export-metadata` |
| what has been exported, and undo it | `GET /export/status`, `GET /export/revert` | `export-status`, `export-revert` |
| which emulators could run this | `GET /launch/options?file=` or `?directory=` | `launch-options` |
| remember a choice | `GET /launch/select?file=&emulator=` | `launch-select` |
| forget one | `GET /launch/clear?file=` | `launch-clear` |
| declare a collection nobody declared | — | `propose-collections`, `apply-collection` |
| point every broken collection at something that runs | — | `link-emulators` |
| who claims to open this extension | — | `discover-by-intent` |

The last six write into the **user's** directories rather than the data root, so
they follow two rules the rest do not need: nothing is written that was not
asked for by name, and nothing is written that cannot be taken back exactly.
`/emulators` proposes and never applies; `/emulators/apply` refuses outright if
the collection's own metadata file already sets a launch command, because
Pegasus keeps whichever file it parses last and does not sort them.
`/export/media` will not overwrite a picture the Bridge did not write, and
`/export/revert` removes only what is in its manifest.

### Choosing an emulator

`/launch/options` answers with **every** emulator that handles the platform,
best first, each carrying why it sits where it does — a proposal whose
alternatives cannot be seen is a decision made for somebody. It reports the
choice three ways, because a UI needs to tell them apart: `chosenForGame`,
`chosenForCollection`, and `effective`, which is the first of those that is set,
falling back to the best candidate.

`/launch/select` records a choice and writes nothing into the library. Passing
`file=` sets it for one game, `directory=` for a whole collection — the game
database and the Quick menu respectively. `/launch/clear` returns a game to
inheriting from its collection.

Choices live in `config/launch-preferences.json`, **not** in the metafile the
Bridge writes: that file is regenerated by every metadata export, and a decision
somebody made must not be a casualty of scraping a collection again. What is
stored is the emulator's id rather than its command, so an emulator that has
moved or been repackaged keeps working, and one that has been uninstalled shows
up in `notInstalled` instead of becoming a launch line that fails.

`/export/metadata` renders them: the collection's choice as the collection
`launch:`, and a `launch:` on any game that differs from it — Pegasus supports
both, `GameAttrib::LAUNCH_CMD` calling `setLaunchCmd` on the game.

### Teaching it an emulator it does not know — Android

Which emulators exist, and how each one is handed a game, is a table. It has to
be: a manifest says a door exists, not what to say at it. Linkboy's own manifest
actively misleads, declaring no file handler while `linkboy://emulator/<title>`
works perfectly.

The system *can* be asked who claims an extension, and this project said it
could not — on the strength of a probe that asked about a bare `file://` path
with no MIME type and got nothing back. An intent filter is matched on scheme,
type and path together; asked with a `content://` URI and a type, the same
device answers freely. See [`discover-by-intent`](#discovery-by-intent), which
is a generator of candidates and still not a table.

What the table does not have to be is *code*. `config/emulators.json` is read on
every `emulators` call and merged over the built-in one:

```json
{
  "schemaVersion": 1,
  "coreHints": {
    "3do": ["opera_libretro_android.so"]
  },
  "emulators": [
    {
      "id": "myboy",
      "displayName": "My Boy!",
      "platforms": ["gba"],
      "packages": ["com.fastemulator.gba", "com.fastemulator.gbafree"],
      "component": ".EmulatorActivity",
      "args": ["-a android.intent.action.VIEW", "-d \"{file.uri}\""],
      "coreHints": []
    }
  ]
}
```

The top-level **`coreHints`** is keyed by platform, and it is what teaches a
build a libretro core it was compiled without — `3do`, `apple2`, `amiga` and
`amstradcpc` had no entry, so before this existed neither could be given one
without a new APK. What the file says wins over the built-in table, because the
reason to write one is usually that the built-in is wrong. A platform named
nowhere answers with no core at all rather than with every core the emulator
knows, which is how a 3DO disc was once offered the NES core.

`id`, `platforms` and `packages` are required; everything else is optional. An
entry whose `id` matches a built-in **replaces** it, keeping its position, which
is how a wrong launch line gets corrected without waiting for a new APK. An
unknown id is added. `packages` is in preference order, for an emulator that
ships under several. Leaving `component` empty reaches the app through its own
URI scheme instead of naming an activity — Linkboy needs that. Leaving `args`
empty records an emulator that can be *recognised* and not driven.

Placeholders are Pegasus', not the Bridge's: `{file.path}`, `{file.uri}`,
`{file.documenturi}`, `{file.basename}`. Which one an emulator wants is a
property of the emulator and only trying it establishes — DraStic takes a
`file://` URI, PPSSPP a document URI, and Linkboy the bare title.

A malformed entry is dropped and named rather than taking the file with it, and
unreadable JSON leaves the built-in table exactly as it was. The `emulators`
answer carries a `config` block saying what was read, what was refused and why —
a configuration that is silently ignored is worse than none. `rejected` means an
entry was dropped; `warnings` means it was kept and something about it is worth
saying, which used to be reported as a rejection.

### Discovery by intent

`discover-by-intent` asks the package manager which installed activities claim
to open a given extension. Name them with `extensions=dsk,cpr`, or point it at a
collection with `directory=…` and it reads them off the metadata file.

Asked properly the question over-answers: on the tablet this was written
against, `application/zip` alone returns fifteen activities, eleven of which are
file managers, archivers, an APK splitter, an ebook reader and a 3D modelling
app. So every query is run twice — once for the real extension, once for a
control extension nothing can plausibly have claimed — and the control's answers
are subtracted. What survives asked for that extension **by name**:

| query | before | after |
| --- | --- | --- |
| `.dsk` + `application/octet-stream` | 24 | ColEm |
| `.iso` + `application/octet-stream` | 24 | PPSSPP |
| `.dsk`, no type | 6 | Azimuth |
| `.adf` + `application/octet-stream` | 23 | nothing |

Two limits, both returned rather than hidden. An app declaring a MIME type
broadly is *in* the control set and cannot be told apart from a file manager at
all — MAME4droid and CPCemu are exactly that, and arrive under `alsoAnswered`
alongside RAR. And nothing here establishes which placeholder an app wants;
`draftEntry` is therefore an `emulators.json` entry with the launch line left
out. It proposes; it never adds.

**One limit, and it is Android's.** From API 30 a package the app's manifest
does not name under `<queries>` is invisible, and `getPackageInfo` throws the
same exception for it as for one that is not installed. The manifest is fixed
when the APK is built, so a package added to this file afterwards may be
installed and still not be found. The Bridge cannot fix that from inside the
file, so it says so: `config.visibilityWarning` names the packages affected.
Correcting an emulator the build already knows about is unaffected.

### Sources

| source | ops | needs |
| --- | --- | --- |
| `ss` | `game`, `media`, `systems` | ScreenScraper developer pair |
| `sgdb` | `search`, `grids`, `logos`, `heroes`, `screenshots` | SteamGridDB key |
| `igdb` | `token`, `search`, `details`, `covers`, `screenshots`, `artworks` | Twitch client id/secret |
| `ign` | `search`, `details`, `images` | — |
| `steam` | `search`, `assets` | — (public store) |
| `steam-account` | `library`, `achievements`, `resolve` | the user's own Steam Web API key and SteamID |
| `romm` | `heartbeat`, `platforms`, `roms` | a RomM server, optionally a `rmm_…` client token |

`steam` and `steam-account` are deliberately separate: the first is public
product data and needs nothing, the second answers only about the signed-in user
and can be refused because their profile is private — which is a state to show,
not an error to retry.

`/ra/match` is the one worth knowing about. Ask it *which RetroAchievements game
this is* and it answers from the ROM hash index when it can and a fuzzy match
against the console catalogue when it cannot — so a theme needs no matcher, no
filename parser and no console table of its own. ReStory deleted 400 lines of
QML when it moved to it.

See `CONTEXT.md` for the full contract.

---

## Building from source

Needs JDK 21. Building the Android app also needs the Android SDK and NDK — the
ROM hasher is native code (rcheevos) compiled per ABI.

```bash
# Desktop daemon: a self-contained bundle with its own runtime
cd shared
./package.sh /tmp/bridge-bundle
MAKE_TARBALL=1 BRIDGE_VERSION=v0.2.0 ./package.sh /tmp/bridge-bundle   # release archive

./gradlew test
./tests/check_test_counts.py   # every declared @Test also ran (JUnit drops some silently)

# Android
cd ..
./gradlew assembleDebug
```

`assembleRelease` produces an **unsigned** APK unless you supply a keystore.
Create one yourself and put the details in `local.properties` (git-ignored) —
see the comment at the top of `app/build.gradle.kts`.

### One copy of what both shells run

These are two Gradle builds, and neither includes the other. Code that both
shells run lives in `shared/<module>/src/android-shared/kotlin`: the `shared/`
build compiles and tests it, and the Android module of the same name adds the
directory to its own sources (`:media` takes the one of `scrapers`).

The ROM scan is such code. `RomScanPipeline`, the archive handling, the
RetroAchievements lookup, the ledger and the job record are in
`shared/hasher/src/android-shared`, and the tests in `shared/hasher` and
`shared/daemon` are the tests of what the tablet runs. What stays in the
Android `hasher/` module is what only Android has:

- `HasherService` — the foreground service, the wake lock, the notification
  with its Cancel, the thermal back-off, and the writing of the job's record
  and marker;
- `NativeHasher` — loads the rcheevos library built for the device. The
  library's functions are declared once for both shells, in `RcheevosNative`
  under `android-shared`, and one C file, `hasher/src/main/cpp/rahasher_jni.c`,
  is compiled into the tablet's library and the desktop's;
- `DeviceNetwork` — what Android's `ConnectivityManager` says of the
  connection, asked when a lookup's request has failed;
- `ScanCollaborators` and `RomScanExtensions` — where the service gets its
  hasher, its lookup, that reading of the connection and each collection's
  extensions.

So a change under `android-shared` needs both checks, and CI runs only the
first:

```bash
cd shared && ./gradlew test && ./tests/check_test_counts.py   # the shared tests
cd .. && ./gradlew :app:assembleDebug                          # compiles the same files against Android
```

The second needs the SDK: copy `local.properties.template` to
`local.properties` and set `sdk.dir`, or export `ANDROID_HOME`. It compiles
against API 35, so it notices a call the Android class library does not have at
all. It says nothing of one that came after Android 8, which the app installs
from (`minSdk` 26): `PowerManager.currentThermalStatus`, of Android 10, compiled
for as long as only a `catch` stood around it. Lint is the check for `minSdk`,
and CI does not run it either:

```bash
./gradlew :hasher:lintDebug   # NewApi: a call newer than minSdk with no version asked first
```

It looks at one module, the `android-shared` directory that module adds
included.

---

## Troubleshooting

**Nothing happens, on Android.** Almost always the missing "All files access"
grant — see above.

**"missing steamGridDb.apiKey in credentials.json".** The key was never saved to
the Bridge. Re-enter it in the theme's settings.

**Nothing happens, on Linux.** Check the daemon:

```bash
systemctl --user status pegasus-bridge          # always-on install
systemctl --user status pegasus-bridge-proxy.socket   # on-demand install
journalctl --user -u pegasus-bridge -f
```

`<dataRoot>/daemon.json` must exist and its port must answer `/health`:

```bash
curl http://127.0.0.1:<port>/health
```

**`403`, "refused: …".** The request named the daemon by something other than
`127.0.0.1`, `localhost` or `[::1]`, or came from a browser: the daemon answers
curl and the theme, and not an address opened in a browser tab. The daemon's
log says which header it went by.

**The daemon is healthy and the theme still shows nothing.** Then the theme
never found the data root, and it has no way to tell you so: every request is
dropped before it is sent, with no error anywhere. Check that a pointer exists —

```bash
cat ~/.config/pegasus-frontend/bridge.json
```

— and if it does not, or names the wrong path, write it again:

```bash
~/.local/share/pegasus-bridge/app/install.sh --link-only
```

**Scanning does nothing.** The native hasher failed to load; `/health` says so.
Scraping and RetroAchievements still work without it.

---

## Licence

**GNU General Public License v3.0** — see [LICENSE](LICENSE).

The choice is not arbitrary: the Bridge bundles NewPipeExtractor, which is GPLv3,
in both the Android APK and the desktop archive. Distributing those artifacts
under anything more permissive would not be allowed, so the whole is GPLv3 and
stays free for everyone downstream.

### Third-party components

| component | licence | how it is used |
| --- | --- | --- |
| [rcheevos](https://github.com/RetroAchievements/rcheevos) | MIT | vendored C sources, built into the native hasher |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | GPL-3.0 | YouTube search and stream resolution |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 | every HTTP call |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache-2.0 | reading zip and 7z ROM archives |
| [XZ for Java](https://tukaani.org/xz/java.html) | public domain | LZMA2, which most 7z ROMs use |
| [JSON-java](https://github.com/stleary/JSON-java) | JSON licence | parsing, on desktop; Android supplies its own |

Data and media fetched from third-party databases stay under their own terms —
they are cached locally for the user who requested them and are never
redistributed by this project.

### Verifying what you run

Dependencies are pinned by version *and* by checksum. `gradle/verification-metadata.xml`
records a SHA-256 for every artifact Gradle downloads — one for the Android build
and one for `shared/`, since they are separate Gradle builds — so a build fails
loudly if any of them ever changes underneath you. This matters most for
NewPipeExtractor, which comes from JitPack and is built from a git tag rather
than served as an immutable published artifact.

Regenerate them by running the real build tasks, not `help`: some artifacts —
`aapt2` among them — are only resolved once resource processing actually runs.

```bash
./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease
cd shared && ./gradlew --write-verification-metadata sha256 build
```

Released binaries ship a `.sha256` next to them, and the Android APK is signed.
