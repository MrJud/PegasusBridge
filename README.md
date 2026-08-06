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

The only theme using it today is **ReStory**. Any theme can: see [The API](#the-api).

---

## Install — Linux

Download the release archive, check it, unpack it, run the installer.

```bash
tar xzf pegasus-bridge-<version>-linux-x86_64.tar.gz
sha256sum -c pegasus-bridge-<version>-linux-x86_64.tar.gz.sha256

cd pegasus-bridge-<version>-linux-x86_64
./install.sh --theme ~/.config/pegasus-frontend/themes/ReStory
```

Everything lands under `$HOME`; nothing needs root. The archive carries its own
Java runtime, so **no JDK or JRE is required**.

- `~/.local/share/pegasus-bridge/app` — the daemon
- `~/.local/share/pegasus-bridge/` — its data (caches, credentials, scan index)
- `<theme>/bridge.json` — how the theme finds the data root

### Always on, or on demand?

By default the daemon starts at login and stays up, holding about 200 MB.

```bash
./install.sh --theme <theme> --on-demand
```

installs it socket-activated instead: systemd holds the port, the daemon starts
on the first connection and stops again after a minute of silence. It costs
nothing while Pegasus is closed, and the first request after an idle period
takes about a third of a second. Tune with `--idle-time 5min` or `--port 38700`.

Switching between the two modes is just re-running the installer with or without
the flag.

### Uninstall

```bash
./install.sh --uninstall --theme <theme>
```

Your data under `~/.local/share/pegasus-bridge` is left alone; delete it by hand
if you want it gone.

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
- results are cached locally and rescans are incremental, so a second scan of an
  unchanged library makes no network calls at all.

Measured on a 913-ROM library against RetroAchievements: **913 processed, 0 lookups
failed**, first scan ~6.5 min, incremental rescan 32 s with no requests. Before the
pacing existed the same library got roughly 85 answers and refusals for the rest.

---

## The API

Desktop: HTTP on loopback. The daemon writes its port to
`<dataRoot>/daemon.json`; read that, then speak HTTP.

Android: `pegasus-data://<verb>?…` intents, with results written as JSON under
`<dataRoot>`.

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

./gradlew test        # 139 tests

# Android
cd ..
./gradlew assembleDebug
```

`assembleRelease` produces an **unsigned** APK unless you supply a keystore.
Create one yourself and put the details in `local.properties` (git-ignored) —
see the comment at the top of `app/build.gradle.kts`.

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

`<dataRoot>/daemon.json` must exist and its port must answer `/health`.

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
