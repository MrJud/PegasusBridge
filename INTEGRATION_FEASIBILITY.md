# PegasusBridge — Feasibility of the five proposed integrations

Evidence collected on 2026-08-22 against live services, published source and
this machine's real library. Every verdict below names what was measured, so a
reader can disagree with the conclusion without having to redo the work.

Nothing here was taken from memory. Where a claim could be checked by making a
request or reading a source file, it was.

---

## Summary

| # | Proposal | Verdict | The part that decides it |
| --- | --- | --- | --- |
| 1 | Steam achievements and store in-theme | **Feasible**, in two halves | Achievements come from the Steam Web API on both shells. GameNative is a *launcher*, not a data source. |
| 2 | RomM as an extra metadata source | **Feasible, and the best-fitting of the five** | RomM publishes `ra_hash`, `md5_hash` and `crc_hash` per ROM — the Bridge can match by digest, not by title. |
| 3 | Spotify on/off from the theme | **Feasible but narrow** | Transport control is Premium-only. Everything else about it is easy. |
| 4 | Write scraped art into Pegasus' media folders | **Feasible, and it fixes a live bug** | 0 of 40 existing artwork files on this machine can be matched by either Pegasus provider. |
| 5 | Detect emulators and write a `metadata.pegasus` | **Feasible with one hard rule** | Propose, never apply unattended. This library's files carry Android launch commands on a Linux desktop. |

---

## 1. Steam — achievements and store inside the theme

### What was measured

- `ISteamUserStats/GetNumberOfCurrentPlayers` answers without a key: HTTP 200,
  `{"response":{"player_count":52386,"result":1}}`.
- `ISteamUserStats/GetPlayerAchievements` without a key: **HTTP 400**, and the
  body is `<html>…Required parameter 'key' is missing…</html>` — *not JSON*.
  Any client has to treat a non-JSON body as a refusal, the same lesson
  ScreenScraper already taught this codebase.
- The keyless store endpoint `store.steampowered.com/api/appdetails?appids=440`
  answers with product JSON. This is what `SteamStoreClient` already uses.

### GameNative

`utkarshdalal/GameNative`, GPL-3.0, 9.8k stars, last pushed 2026-08-21. Its
`AndroidManifest.xml` and `utils/IntentLaunchManager.kt` give a complete and
**exported** launch contract:

```text
action     app.gamenative.LAUNCH_GAME
category   android.intent.category.DEFAULT
component  app.gamenative/.MainActivity      (android:exported="true")
extras
  app_id            int     Steam appid, must be > 0
  game_source       String  STEAM | EPIC | GOG | AMAZON   (defaults to STEAM)
  container_config  String  optional JSON, rejected above 50 000 bytes
```

What GameNative does **not** offer is any way to read its state. It has Steam
achievement support of its own, and it keeps it in the app's private storage;
there is no content provider, no broadcast, no exported reader. So the Android
half of this proposal cannot be "ask GameNative what the user has unlocked".

### Verdict

Feasible, but as two separate things that the proposal currently treats as one:

- **Achievements and library** come from the Steam Web API, with the user's own
  API key and SteamID held by the Bridge. This works identically on desktop and
  Android, so the theme gets one contract rather than two.
- **Launching** is per-shell: desktop opens `steam://rungameid/<appid>`, Android
  fires the intent above.

Two constraints worth stating before any UI is drawn:

- The Steam Web API answers about achievements **only for public profiles**.
  A private profile is not an error to retry — it is a state to show, and it
  must not be cached as "no achievements".
- RetroAchievements and Steam are different accounts with different games.
  Their totals must never be added together into one number.

Buying anything stays outside the Bridge. Desktop opens the store in the Steam
client; nothing in this project handles payment.

---

## 2. RomM

### What was measured

The public demo at `demo.romm.app` serves its API unauthenticated, so the
response shapes below are copied from real answers rather than from docs.

`GET /api/heartbeat` reports RomM 5.1.0 with IGDB, ScreenScraper, MobyGames,
SteamGridDB, RetroAchievements, LaunchBox, Hasheous, Flashpoint, HLTB and
Libretro all enabled.

`GET /api/platforms` returns, per platform, the identifiers every other source
in this project is keyed by:

```json
{ "slug": "atari2600", "name": "Atari 2600", "rom_count": 31,
  "igdb_id": 59, "ss_id": 26, "ra_id": 25, "moby_id": 28,
  "launchbox_id": 6, "hasheous_id": 12, "tgdb_id": 22 }
```

`GET /api/roms` returns a paged `{items, total, limit, offset}`, and each ROM
carries:

```text
md5_hash  crc_hash  sha1_hash  ra_hash        the identity keys
ra_id  igdb_id  ss_id  sgdb_id  moby_id       the cross-source ids
name  summary  metadatum.genres  companies    the metadata
path_cover_large  path_cover_small            artwork
path_video  merged_screenshots                media
merged_ra_metadata.achievements[]             per-achievement RA data
```

### Why this fits better than the other four

`ra_hash` is **the same rcheevos hash the Bridge already computes**. Verified on
this machine: the Bridge's own hash for `Super Mario Bros. (World).nes` is
`8e3630186e35d477231bf8fd50e54cdd`, and RomM stores that field per ROM for the
same purpose. So a RomM lookup is a digest lookup, not a title match — which is
the whole reason ScreenScraper was worth adding, and the reason IGN and IGDB
will always need a fuzzy matcher.

It is also the only source of the five that returns RetroAchievements *and*
ScreenScraper *and* IGDB metadata in one request, which for a self-hosting user
means one server's quota instead of four.

### Authentication

Two ways in, both documented and both simple:

- A **Client API Token**, created per user under Administration → Client API
  Tokens, formatted `rmm_` + 64 hex characters, sent as
  `Authorization: Bearer rmm_…`. It does not expire unless given an expiry.
- `POST /api/token` with `grant_type=password` for a 15-minute access token and
  a two-week refresh token, scoped with `roms.read`, `platforms.read` and so on.

The token is the right choice here: it is long-lived, scope-limited, and
revocable from the server without touching the Bridge.

### One thing that must be handled, not discovered later

**RomM's API returns credential-bearing URLs.** Measured on the demo:

```text
ss_metadata.box2d_url =
  https://neoclone.screenscraper.fr/api2/mediaJeu.php
    ?devid=zurdi15&devpassword=<REDACTED>&softname=<REDACTED>&…
```

That is the *RomM instance's own* ScreenScraper developer password, in a field
any API consumer receives. The Bridge must strip these before persisting a RomM
record or answering the theme with one — exactly the rule it already applies to
its own ScreenScraper media URLs, for exactly the same reason. `SafeUrl` already
redacts `devpassword` and `sspassword` by name.

### Verdict

Feasible, and the strongest of the five. Recommended as an **opt-in
authoritative source** placed above ScreenScraper in the precedence order when
the user has enabled it, with its own credential block and provenance recorded
per field.

---

## 3. Spotify

### What was measured

- `PUT /v1/me/player/play` and the rest of the player endpoints require the
  `user-modify-playback-state` scope, and the documentation states plainly:
  *"This API only works for users who have Spotify Premium."* A free account is
  refused with 403.
- Reading what is playing (`user-read-playback-state`,
  `user-read-currently-playing`) does not carry the Premium restriction.
- Since April 2025 Spotify validates redirect URIs strictly: loopback **IP
  literals** are allowed over plain HTTP — `http://127.0.0.1:PORT` — while
  `http://localhost:PORT` is **not**. PKCE is the correct flow for an
  application that cannot keep a client secret, which a locally installed daemon
  cannot.

### Verdict

Feasible, and smaller than it sounds — the proposal is right that it is only a
relay. The desktop daemon already runs an HTTP server bound to loopback, which
is precisely what the PKCE redirect needs, so the OAuth callback costs one extra
route rather than a browser-embedding problem.

The honest caveats:

- **Premium or nothing** for play/pause/skip. On a free account the integration
  can show what is playing and can do nothing about it. The settings screen
  should say so before the user authorises anything, rather than after a 403.
- Playback happens in the Spotify client, not in the Bridge and not in the
  theme. Nothing is streamed or decoded here — the Bridge sends a command to an
  app the user already has running.
- On Android, controlling a third-party player is a different mechanism again
  (media session, or Spotify's own app remote), so a desktop-first
  implementation should not promise Android parity.

Lowest value of the five relative to its cost. Worth doing last, disabled by
default, behind its own credential block.

---

## 4. Writing scraped art into Pegasus' media folders

### What was measured

This is the proposal that turned out to be fixing something rather than adding
something.

Pegasus has **two** asset providers, and this machine has both enabled
(`providers.pegasus_media.enabled: true`, `providers.skraper.enabled: true`).
Reading their source settles exactly what each will accept:

**Native** — `src/backend/providers/pegasus_media/MediaProvider.cpp`:

```text
<gameDir>/media/<gameName>/<assetType>.<ext>
```

`<gameName>` may be the ROM file's base name **or the game's title** — both are
put in the lookup map. `<assetType>` comes from `PegasusAssets.cpp`, matched
whole or by prefix, so `boxFront.png` and `boxFront02.png` both work. Image
extensions are `png`, `jpg`, `webp`, `apng`; video `webm`, `mp4`, `avi`; music
`mp3`, `ogg`, `wav`.

**Skraper** — `src/backend/providers/skraper/SkraperAssetsProvider.cpp`:

```text
<gameDir>/{media,skraper,.media}/<assetDir>/<romBaseName>.<ext>
```

and here the name is matched against the ROM's base name **only** — the title is
never consulted. The directory names, in the provider's own priority order:

| Asset | Directories |
| --- | --- |
| box front | `box2dfront`, `supporttexture`, `box3d` |
| box back | `box2dback` |
| box spine | `box2dside` |
| box full | `boxtexture` |
| cartridge | `support` |
| logo | `wheel`, `wheelcarbon`, `wheelsteel` |
| background | `fanart` |
| screenshot | `screenshot` |
| title screen | `screenshottitle` |
| arcade marquee | `screenmarquee`, `screenmarqueesmall` |
| steam grid | `steamgrid` |
| video | `videos` |

### The live defect

This library uses the Skraper layout. Its artwork is named by *sanitised title*
while its ROMs are No-Intro dumps:

```text
roms/nes/Castlevania III - Dracula's Curse (USA).nes
roms/nes/media/box2dfront/Castlevania III Draculas Curse.png
```

Skraper matches on `completeBaseName`, so those two are different games as far
as Pegasus is concerned. Counted across both collections:

| Collection | ROMs | box2dfront files | that Pegasus can match |
| --- | --- | --- | --- |
| nes | 10 | 20 | **0** |
| snes | 10 | 20 | **0** |

Forty artwork files, none reachable. They match the native layout no better —
that one wants `media/<name>/boxFront.png`, a different shape entirely.

### Verdict

Feasible and worth doing first. The Bridge is the one component that knows both
halves — it has the ROM's exact base name from the scan, and it has the fetched
picture — so it is the only place that can name the file correctly without
guessing.

Design constraints that follow from the source above:

- Support both layouts, and default to whichever the collection already uses.
- Write only files the Bridge owns, and record them in a manifest, so an export
  can be undone exactly.
- Never overwrite a file the Bridge did not write. A user's hand-placed cover
  outranks a scraped one.
- Copy, do not move: the Bridge's `artwork/` directory stays the cache, and the
  media directory is a published view of it.

---

## 5. Detecting emulators and generating a `metadata.pegasus`

### What was measured

Every collection in this library carries a launch command, and every one of them
is an **Android** command — on a Linux desktop:

```text
collection: Nintendo Entertainment System
shortname: nes
extensions: bin, fds, nes, nsf, qd, rom, unf, unif, jud
launch: am start
  -n com.explusalpha.NesEmu/com.imagine.BaseActivity
  …
```

`am start` does not exist here. So on this machine every collection is
unlaunchable, and generating a correct desktop launch line is the concrete thing
this proposal delivers.

A second finding fell out of the same files. Every collection declares a `jud`
extension, and `RomScanner`'s extension list is hardcoded and does not include
it — so the scanner's idea of what a ROM is and the collection's own declaration
disagree. On this library it happens not to matter, because the `.jud` files are
zero-byte placeholders. On a library that used a non-standard extension for real
ROMs, an entire platform would scan as zero files with nothing said about it.
The parser this proposal needs is also the fix for that.

### Verdict

Feasible, with one rule that is not negotiable: **propose, never apply.**

A launch command is the one piece of metadata that can lose a user's work if it
is wrong, and the file it lives in is hand-edited. So:

- Discovery probes for known emulators — desktop by executable, `.desktop`
  entry, Flatpak id and AppImage; Android by package name only, and only for
  packages that publish an intent.
- Each candidate is verified before being offered: the binary is executed for
  its version string, so a match is something observed rather than a filename
  that looked right.
- The result is a list of `EmulatorCandidate`s for review. Nothing is written
  until the user picks.
- What is written is a **Bridge-owned overlay** in Pegasus' `metafiles`
  directory, never an edit to a `metadata.pegasus.txt` the user wrote. Pegasus
  reads both, and an overlay can be deleted without taking anything else with
  it.

---

## What this changes about the architecture

Two amendments to `ARCHITECTURE_PROPOSAL.md`, both of which the evidence forced:

1. **Split Steam into three, not two.** The proposal already separates account
   from store. GameNative is a third thing again — a launch target with no
   readable state — and modelling it as a provider would promise data it cannot
   give.

2. **Treat RomM responses as credential-bearing.** The document lists RomM as a
   library authority and says to store its own credentials separately. It does
   not say that RomM's *responses* carry someone else's. They do, measured, and
   the redaction has to happen on the way in.
