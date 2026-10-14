# Pegasus Bridge

A companion backend for [Pegasus Frontend](https://pegasus-frontend.org/) themes.

Pegasus themes are QML, and QML cannot spawn processes, write files or hash a ROM.
Pegasus Bridge does those things on the theme's behalf and answers over a small
local API, so a theme stays a renderer:

- **RetroAchievements** — profile, per-game progress, and identifying which RA
  game a library entry is, by ROM hash (rcheevos) with a fuzzy fallback. A Wii
  disc is hashed from a `.wbfs`, split or whole, as well as from an `.iso`, and
  a disc of ten consoles from a `.chd` (PlayStation, PlayStation 2, PSP,
  Saturn, Sega CD, Dreamcast, PC Engine CD, 3DO, Neo Geo CD, PC-FX).
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
| Windows | not released, and the daemon has never been started there: the hasher's `rahasher.dll` builds with mingw-w64, and a CI job loads it and runs the hasher's tests on it |

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

On the desktop the file and its folder are kept to their owner. The file can be
read and written by the account the daemon runs as and by nobody else (`0600`),
and is made so before a key goes into it; the `config` folder opens to that
account alone (`0700`). A file or folder an older build left open to others is
narrowed when the daemon starts, without the file being rewritten, and the log
says which, never what is in it. Where the file system has no such permissions,
as on Windows, the file is written as it always was.

On Android that file is `/sdcard/PegasusData/config/credentials.json`, on shared
storage: any app on the device that holds all-files access can read it, every
key in it included.

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
asks for as little as it can, and as a client that can be told apart and told to
wait:

- **one request per console per week**, not one per ROM: the scan asks
  RetroAchievements for the list of a console's games and their hashes, keeps it
  on disk for 7 days and looks every hash of that console up in it;
- **one request at a time**, **a second apart**;
- every request names the program and its version,
  `PegasusBridge/<version> (+https://github.com/MrJud/PegasusBridge)`;
- a **429** is waited out for as long as its `Retry-After` says (a minute when it
  says nothing, and not at all above two minutes: the lookup gives up instead),
  and a **403** is never asked again;
- "the request failed" and "the answer is no" are kept apart, and a failure is
  **never cached** — otherwise a refused request is remembered as *this game does
  not exist* and no later scan ever asks again. A list that could not be had is
  never taken for a list that does not have the hash;
- a server error is retried three times, and after **8 consecutive failed
  lookups** the scan stops with a message instead of grinding through the rest of
  the library;
- a request that fails on a device that knows it has **no internet connection** is
  not retried at all: the scan stops at that first failure and says so, where it
  used to spend half a minute on retries and then blame the source;
- results are cached locally and rescans are incremental, so a second scan of an
  unchanged library makes no network calls at all, and a scan with no API key is
  stopped before it reads a file.

Measured on a 913-ROM library against RetroAchievements, while the lookup still
made a request for every ROM: **913 processed, 0 lookups failed**, first scan
~6.5 min, incremental rescan 32 s with no requests. Before the pacing existed the
same library got roughly 85 answers and refusals for the rest. The lookup by
lists has not been measured against RetroAchievements yet.

---

## Sources being considered

The four sources above cover modern games well and retro games poorly, because they
match on the title. Two databases would fix that; neither is implemented, and neither
will be started before its credentials exist — writing a client against an API you
cannot call produces hundreds of lines nobody can verify.

**[ScreenScraper.fr](https://www.screenscraper.fr)** is the one that matters. It
matches **by ROM hash**, which is what lets ES-DE and Skraper identify a retro dump
that no title search would find, and the Bridge already computes those hashes — the
rcheevos hash for RetroAchievements, and the file's own MD5 and CRC32, which a scan
keeps where it has just read the whole file and a scraper works out when it asks.
Its media map onto this project's categories one for one: `box-2D` to cover, `wheel`/`wheel-hd`
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
stop. The habits above already cover all three — one request at a time, spaced, a
wait the server names waited out, no failure ever cached as a negative, and a stop
after eight consecutive refusals.

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
| which RA game is this file? | `GET /ra/match?title=&platform=&file=` | `match-ra` |
| games on a platform | `GET /ra/search?platform=&term=` | `search-ra-games` |
| the console table | `GET /ra/consoles` | `ra-consoles` |
| RA profile / one game | `GET /ra/profile`, `GET /ra/game?gameId=` | `refresh-ra-profile`, `refresh-ra` |
| scrape from a source | `GET /scrape?source=&op=` | `scrape-source` |
| trailers | `GET /video/search?q=`, `/video/resolve`, `/video/download` | `search-video`, `play-video`, `download-video` |
| hash a ROM tree | `GET /scan?roots=` | `scan` |
| how that scan is going, and at its end where its time went (`metrics`) | `GET /jobs/{id}` | the file `pending/{jobId}.json` |
| credentials | `POST /credentials`, `GET /credentials/status`, `GET /credentials/clear?block=` | `set-credentials`, `credentials-status`, `clear-credentials` |
| which emulators are installed | `GET /emulators` | `emulators` |
| what each collection is and would be proposed | `GET /collections?roots=` | `collections` |
| apply a launch command | `GET /emulators/apply?directory=&launch=` | `apply-emulator` |
| undo that | `GET /emulators/revert?directory=` | `revert-emulator` |
| copy one picture into the library | `GET /export/media?source=&file=&kind=` | `export-media` |
| write the scraped metadata | `POST /export/metadata?directory=` | `export-metadata` |
| start a GameNative game from its marker file | — | `launch-gamenative` |
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

### GameNative games — Android

[GameNative](https://github.com/utkarshdalal/GameNative) runs Steam, GOG, Epic
and Amazon games on Android, and games added to it by hand. It keeps a folder of
marker files for a frontend to list, one per installed game, and keeps the
folder current by itself. A file's name is the game's title, its extension is
the store, and what it holds is the number GameNative starts the game by:
`Hollow Knight.steam` holds `367520`.

| extension | `game_source` | the number |
| --- | --- | --- |
| `.steam` | `STEAM` | the Steam appid |
| `.gog` | `GOG` | GameNative's own |
| `.epic` | `EPIC` | GameNative's own |
| `.amazon` | `AMAZON` | GameNative's own |
| `.pcgame` | `CUSTOM_GAME` | GameNative's own |

Pegasus lists the folder as a collection once this is in it as
`metadata.pegasus.txt`:

```
collection: Windows
shortname: windows
extensions: steam, gog, epic, amazon, pcgame
launch: am start
  -a android.intent.action.VIEW
  -d pegasus-data://launch-gamenative
  --es file "{file.path}"
```

A launch then goes from Pegasus to the Bridge and from the Bridge to
GameNative, and a theme calls `game.launch()` as for any other game.
`launch-gamenative` reads the file and starts `app.gamenative/.MainActivity`
with the action `app.gamenative.LAUNCH_GAME`, the number as the integer extra
`app_id` and the store as `game_source`. It writes no `pending` and no `done`
file and answers nothing. A launch while a game is running reaches the running
GameNative, which changes game by itself.

The path is a string from another app, and nothing of it is passed on. The verb
starts nothing when:

- the intent has the category `BROWSABLE`, which is how a web page would send
  it. Pegasus' launch line does not set it. Such an intent is dropped unread and
  nothing is shown;
- the path is missing, longer than 1024 characters, holds a NUL or is not
  absolute;
- it is not under `/storage/`, or is under `/storage/emulated/0/PegasusData` or
  `/storage/emulated/0/Android`;
- its extension is none of the five, compared in lower case;
- it is not its own canonical form: a link, a `.`, a `..` or a doubled slash is
  in it;
- there is no regular file at it;
- the file holds more than 16 bytes;
- what it holds is not an app id. Space, tab, CR and LF are stripped at both
  ends, and what is left has to be `[1-9][0-9]{0,9}` and no more than
  2147483647.

What the user gets is a toast, in English or Italian. *GameNative is not
installed*. *GameNative could not be started*, when Android refuses the start.
*Pegasus Bridge has no all-files access*, when that is what is missing, since
without it every marker reads as a file that is not there. *Not a GameNative
game*, with the file's name, for every path refused above; which of the reasons
it was is a line in Logcat under `DataLayerRouter`.

Only a `.steam` number means anything outside the install that wrote it. It is
the `appId` the `steam-account` source below takes, which is how a theme shows
the Steam achievements of a game in this collection.

Measured on 2026-10-13 with GameNative 1.2.1, whose folder on that device was
`Emulation/Roms/windows` on the SD card.

### Sources

| source | ops | needs |
| --- | --- | --- |
| `ss` | `game`, `media`, `systems` | ScreenScraper developer pair |
| `sgdb` | `search`, `grids`, `logos`, `heroes`, `screenshots` | SteamGridDB key |
| `igdb` | `token`, `search`, `details`, `covers`, `screenshots`, `artworks` | Twitch client id/secret |
| `ign` | `search`, `details`, `images` | — |
| `steam` | `search`, `assets` | — (public store) |
| `steam-account` | `library`, `progress`, `achievements`, `resolve` | the user's own Steam Web API key and SteamID |
| `romm` | `heartbeat`, `platforms`, `roms` | a RomM server, optionally a `rmm_…` client token |

`steam` and `steam-account` are deliberately separate: the first is public
product data and needs nothing, the second answers only about the signed-in user.

#### `steam-account`

The user's own library and achievements, read with their Web API key
([steamcommunity.com/dev/apikey](https://steamcommunity.com/dev/apikey)) and
their 17-digit SteamID. Steam declines for reasons that are no error of the
request: a profile whose game details are not public, a key it does not take, a
game that has no achievements. So every request this source understands is
answered with `status: "ok"` and a `results` object, and what happened is
`results.state`. `status: "error"` means the op or a parameter was wrong, and
nothing else.

Both shells answer it, from one copy of the code, and what differs is how the
`steam` block gets into `credentials.json`:

```json
"steam": { "apiKey": "…", "steamId": "7656119…" }
```

On the desktop the two are fields of `POST /credentials`, `steamApiKey` and
`steamId`, and `/credentials/clear?block=steam` forgets them. On Android nothing
on the device writes the block. `set-credentials` does not take the two fields,
since its fields travel in the URI of an intent, which is no place for a key,
and `clear-credentials` does not know the block, since any app can send that
verb. The block is copied from the PC over the cable, by a command run on the
PC, and taken away the same way. `credentials-status` reports it on both, as
`configured` and `steamId`, never the key.

That command is `shared/steam-to-device.sh`, run from a checkout of this
repository with the device on the cable and USB debugging allowed:

```bash
shared/steam-to-device.sh                      # the block of ~/.local/share/pegasus-bridge
shared/steam-to-device.sh --prefix <dataRoot>  # of a Bridge installed somewhere else
shared/steam-to-device.sh --credentials FILE   # of any credentials.json
shared/steam-to-device.sh --serial SERIAL      # when more than one device is attached
shared/steam-to-device.sh --remove             # take the block off the device again
```

It reads the `steam` block out of the PC Bridge's `credentials.json`, where a
theme's settings screen had it written, and puts it into the device's
`/sdcard/PegasusData/config/credentials.json`. Only that block changes. Every
other block of the device's file is written back as it was read, and a device
with no file gets one with that block alone. A file there that is not JSON stops
the command before anything is written. The new file is written beside the old
one under a name of its own, `credentials.json.cable`, read back, and then
renamed over it. A command that is stopped in between removes that file. When
it is the device that went away, the file stays there with every key in it, and
the command says so: run again, or with `--remove`, it removes it.

It refuses a blank key, a SteamID that is not 17 digits, and a device with no
`/sdcard/PegasusData`, which is one the Bridge has never run on. The key is
never shown, and on its way it is in no command line and no environment
variable: it goes to adb through a pipe, and no file is made of it on the PC.
The command needs `adb` and `python3`. A key changed on the PC is not changed
on the device until the command is run again.

Every `results` carries `namespace: "steam"`, `state`, `message`, `cached`,
`stale` and `fetchedAt`. `message` is one fixed English sentence per state, never
Steam's own text. A `results` with `stale: true` also carries `staleState`:
`offline`, `rate_limited` or `upstream_error`, which is why Steam gave no new
answer. One that is not stale has no `staleState`.

| op | params | `results` |
| --- | --- | --- |
| `library` | `refresh` | `steamId` and `games[]`, last played first and then most played: `appId`, `name`, `playtimeMinutes`, `playtimeLast2WeeksMinutes`, `lastPlayedAt`, `iconUrl`, `headerUrl`, `launchUri`. A game whose progress is on disk also has `unlocked`, `total` and `progressAt`; they cost no call |
| `progress` | `appIds` (comma-separated, at most 20), `lang`, `refresh` | `items[]`, one per game: `appId`, `state`, `unlocked`, `total`, `cached`, `fetchedAt` |
| `achievements` | `appId`, `lang`, `refresh` | `appId`, `gameName`, `unlocked`, `total`, `progress` (0 to 1) and `achievements[]`: `id`, `title`, `description`, `unlocked`, `unlockedAt`, `iconUrl`, `iconLockedUrl`, `hidden` |
| `resolve` | `vanity` | `steamId`, and nothing else: the one op that still fails with an error |

| `state` | when | for `progress` |
| --- | --- | --- |
| `ok` | answered | |
| `not_configured` | no key, no SteamID, or a SteamID that is not 17 digits. Steam is not asked | ends the request |
| `key_refused` | Steam did not take the key | ends the request |
| `private` | the profile's game details are not public. `library` cannot tell that from a SteamID nobody has, and its sentence names both | ends the request |
| `no_achievements` | the game has none | |
| `rate_limited` | HTTP 429 | ends the request |
| `offline` | Steam could not be reached | ends the request |
| `upstream_error` | Steam answered with something that could not be used | |
| `skipped` | an item that was not asked about | |

A game the account has never started is `ok` with 0 of its total, every
achievement locked: Steam lists them all, none achieved. A tool (Proton, a Steam
Linux Runtime) is `no_achievements`: Steam says "Requested app has no stats" of
it, with a private profile as with a public one, and its schema lists nothing.
The sentence alone does not decide. Said of a game whose schema lists
achievements, it is `ok` with none of them unlocked.

`progress` is for the games on screen. It stops starting calls about 8 seconds
in, and after a state that ends the request, and the games it did not reach come
back `skipped`; the state of the whole answer is the one that ended it, or `ok`.
A game whose count on disk is younger than 10 minutes is answered whatever ended
the request, since it costs no call. Asked again, it finds the games it had
answered on disk.

`lang` is the theme's language code (`it`, `en`, `pt_BR`). The Bridge turns it
into Steam's name for the language, and whatever it does not know into English.

What is kept is under `<dataRoot>/cache/steam/`: a game's schema for 30 days,
per language, and the library and each game's unlock list for 10 minutes.
`refresh=1` asks Steam again, but not about an answer younger than a minute.
Nothing is kept for `private`, `key_refused`, `rate_limited`, `offline` or
`upstream_error`, so a profile made public, or a key put right, is answered for
at the next request. When what is kept has gone old and the call for a new one
ends in `offline`, `rate_limited` or `upstream_error`, the old one is served:
`state` is the old answer's own (`ok`, or `no_achievements`), with `cached:
true`, `stale: true`, and `staleState` naming which of the three it was. It is
not served after `private` or `key_refused`.

Two answers are made of two calls, and either can be the old half. An
`achievements` whose unlock list is new and whose names are from an old schema
is `stale` with the schema call's `staleState`, and `cached: false`. A
`progress` is `stale` when any of its counts is, with the `staleState` of the
first such; its `state` stays the one that ended the request, so after `offline`
it reads `state: "offline"`, `stale: true`, and the old counts in `items`.

What is kept of an account, its library and its unlock lists, is dropped when a
Steam key or SteamID is written through `/credentials` and when the `steam`
block is cleared. The files are named by the SteamID, and would otherwise answer
for a key that is no longer the one in use. The schemas stay. On a device it is
`steam-to-device.sh` that drops them, after it has written the block and after
it has removed it.

`/ra/match` is the one worth knowing about. Ask it *which RetroAchievements game
this is*, with the path of the game's file, and it answers with what the scan
found for that very file: its game, or that it has none and why. A file the
scan cannot speak for gets a fuzzy match by name against the list of its own
console, the one a scan keeps on disk; and when that list cannot be had the
answer is an error with a code, not a no — so a theme needs no matcher, no
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

CI makes that release archive on every push, in the `package` job of
`shared-tests.yml`, and `shared/tests/package_smoke_test.sh` starts the daemon
out of it: the hasher's library has to load from the bundle, `/health` to
answer, a request from a web page to be refused, and SIGTERM to end the daemon
with its `daemon.json` taken away.

`assembleRelease` produces an **unsigned** APK unless you supply a keystore.
Create one yourself and put the details in `local.properties` (git-ignored) —
see the comment at the top of `app/build.gradle.kts`.

The Android hasher library is linked for devices with 16 KB memory pages
(`-Wl,-z,max-page-size=16384`, in `hasher/src/main/cpp/CMakeLists.txt`), and
the build checks the library it has linked and fails when a segment of it is
aligned to less (`check_page_size.cmake`, beside it). Such a library loads on
devices with 4 KB pages as before. It has not been tried on a device with
16 KB pages: the alignment is read off the file, and no such device was at
hand.

### The hasher's native library

The desktop's `librahasher.so` is committed, in `shared/native/out/`, with a
manifest beside it of everything it was compiled from. The tests load that
file, so a change to the C under `hasher/src/main/cpp` has to come with the
library built again, in the same commit. The C is three files of ours,
six of rcheevos' and libchdr with the decompressors it carries:
`rahasher_jni.c`, which the JVM calls, `pb_wbfs.c`, which reads a Wii disc
out of a `.wbfs` container for rcheevos to hash, and `pb_chd.c`, which reads
the tracks of a `.chd` for it through libchdr.

```bash
cd shared
JAVA_HOME=<a JDK 21> ./native/build.sh      # writes native/out/librahasher.so and its manifest
./tests/native_manifest_check.py            # fails when the library is older than its sources
./tests/native_lib_check.sh native/out/librahasher.so
./tests/vendored_check.py                   # rcheevos is upstream plus the patches listed, libchdr is upstream's files, and nothing else
./tests/native_repro_test.py                # files made to hurt rcheevos and the .wbfs and .chd readers, on a plain and a sanitized build
./tests/rcheevos_rhash_test.sh              # rcheevos' own tests of its hashing, on the copy with the patches, plain and sanitized
```

A few of the `.chd` images those rows hash were written by chdman and are
committed, in `shared/tests/chd/`; `./tests/native_repro_test.py --remake-chd`
writes them again and is the one thing here that needs chdman.

`./tools/rahasher_compare.py --rahasher <RAHasher> --ours <dir>/rahash_cli`
hashes the same synthetic disc images with RetroAchievements' own program,
RAHasher, and says where the two part. RAHasher is not in this repository
and nothing here downloads or builds it; `CONTEXT.md` has how it was built
and what the run showed, under "What RAHasher 1.8.4 does".
`./tools/rahasher_compare_test.py` tests the script without it.

It needs gcc. `./native/build.sh <dir> --target=windows` builds `rahasher.dll`
with `x86_64-w64-mingw32-gcc`, on Linux as well as in Git Bash on Windows; a
DLL is not committed. CI builds a fresh library in its `native` job and tests
it, and loads the DLL in its `windows` job, which is the one place the DLL has
anything load it.

### Auditing a scan

To see what a change to hashing does to a real library, scan it before and
after and compare the two tables. The audit runs the scan as the daemon
would, into a data root of its own, asks nobody unless told to, and writes a
row for every file:

```bash
cd shared
./gradlew :daemon:audit -PauditArgs='--audit=<library folder> --out=<file.tsv>'
./tools/audit_report.py <file.tsv> --baseline <the table from before>
```

The report counts the files by collection and state, names every file whose
verdict moved, and looks for hashes that cannot be right whatever
RetroAchievements would say of them. On Linux each row also has the bytes the
scan read for its file, which the report adds up by collection. With
`--keep=<folder>` among the arguments the scan's data root is that folder and
stays there, so the same command run again measures a rescan, and one killed
and run again a scan that resumes. A scan run by the daemon or the tablet
says where its own time went in the record it ends on: see `metrics` under
"Where a scan's time went" in `CONTEXT.md`.

One command does both and ends in a verdict:

```bash
./tools/hash_check.sh --library '<library folder>' --keep-in <a folder outside the repository>
```

The first run writes its table and says there is no baseline; `--accept`
makes a table the baseline. Every run after that is held against it and
ends in one line, `VERDICT: same as the baseline` or `VERDICT: N files
differ from the baseline`, with status 0 only when no file differs (or no
more than `--allow N`) and the scan read no more than a tenth above what
the baseline read for the same files. It also prints where the scan's time
went and keeps a line of each run in `history.tsv`, in that folder. The
folder is not in the repository because the tables name the library's files,
and the script refuses one that is.

### One copy of what both shells run

These are two Gradle builds, and neither includes the other. Code that both
shells run lives in `shared/<module>/src/android-shared/kotlin`: the `shared/`
build compiles and tests it, and the Android module of the same name adds the
directory to its own sources (`:media` takes the one of `scrapers`).

The ROM scan is such code. `RomScanPipeline`, the archive handling, the
RetroAchievements lookup and the lists of consoles it answers from, the ledger
and the job record are in
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
- `ScanCollaborators` — where the service gets its hasher, its lookup and
  that reading of the connection.

So a change under `android-shared` needs both checks. CI runs the first in
`shared-tests.yml` and the second in `android.yml`, on every push that changes
more than Markdown:

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
and the same job of CI runs it:

```bash
./gradlew :hasher:lintDebug   # NewApi: a call newer than minSdk with no version asked first
```

It looks at one module, the `android-shared` directory that module adds
included.

That job also runs the unit tests of the Android modules that have any
(`./gradlew testDebugUnitTest`, today `:core`, `:hasher` and `:pegasus`), and holds the
native library, as it lies in the debug APK, to a layout for pages of 16 KB.
It builds with the NDK the Android Gradle plugin asks for when the build
names none, 27.0.12077973, and CMake 3.22.1, and fails if the build found
others. It then builds the release variant, minified and unsigned, holds its
library to the same, and looks in its code for the class and the method the
library's functions are named after. No job signs an APK or runs one on a
device.

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

**A file is "set aside" in the ledger.** `<dataRoot>/cache/scan-ledger.json`
holds it as `UNHASHABLE` with a detail that begins `set aside:`. The Bridge
ended twice while that file was being hashed, the second time with no other
file in hand, so scans now leave it out and go through the rest of the
library. It is tried again after 30 days, and at the next scan if the file
changes: replace it, or `touch` it to have the same file tried once more.

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
| [libchdr](https://github.com/rtissera/libchdr) | BSD-3-Clause | vendored C sources, built into the native hasher: reads `.chd` disc images |
| [LZMA SDK](https://www.7-zip.org/sdk.html) | public domain | its decoder, as libchdr carries it |
| [miniz](https://github.com/richgel999/miniz) | MIT | its deflate decoder, as libchdr carries it |
| [Zstandard](https://github.com/facebook/zstd) | BSD-3-Clause, of the two it is offered under | its decoder, as libchdr carries it |
| [dr_flac](https://github.com/mackron/dr_libs) | public domain or MIT-0 | the FLAC decoder, as libchdr carries it |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | GPL-3.0 | YouTube search and stream resolution |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 | every HTTP call |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache-2.0 | reading zip and 7z ROM archives |
| [XZ for Java](https://tukaani.org/xz/java.html) | public domain | LZMA2, which most 7z ROMs use |
| [JSON-java](https://github.com/stleary/JSON-java) | JSON licence | parsing, on desktop; Android supplies its own |

The licence texts of what the native hasher is compiled from travel with it:
`hasher/src/main/cpp/licenses/NATIVE-LICENSES.txt` in the sources, the same
file beside `LICENSE` at the top of the desktop archive, and
`assets/NATIVE-LICENSES.txt` inside the APK, beside `assets/LICENSE`, which is
the GPL's text; the build of an APK fails if that file is not in it.

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
