package com.pegasus.bridge.pegasus

import java.io.File

/**
 * Where Pegasus looks for a game's pictures.
 *
 * There are two layouts, not one, and this project has to write whichever a
 * collection already uses. Both were read out of Pegasus' own source rather than
 * from documentation, because the documentation gives the directory names and
 * not the matching rule, and the matching rule is where a library goes wrong.
 *
 * ── Native, `providers/pegasus_media/MediaProvider.cpp` ────
 *
 *     <gameDir>/media/<gameName>/<assetType>.<ext>
 *
 * The provider takes the asset file's directory, removes the `/media` segment,
 * and looks the result up in a map that holds **two** keys per game: the ROM
 * file's base name, and the game's *title*. So either will match.
 *
 * The asset type is matched whole or **by prefix**, so `boxFront.png` and
 * `boxFront02.png` are both box art — which is how a game gets several.
 *
 * ── Skraper, `providers/skraper/SkraperAssetsProvider.cpp` ─
 *
 *     <gameDir>/{media,skraper,.media}/<assetDir>/<romBaseName>.<ext>
 *
 * Here the file name is matched against the ROM's base name **only**. The title
 * is never consulted — `build_gamepath_db` puts nothing else in the map.
 *
 * That difference is not academic. On the library this was written against,
 * every collection uses the Skraper layout and every picture is named after the
 * game's *title* with the punctuation stripped, while the ROMs are No-Intro
 * dumps:
 *
 *     roms/nes/Castlevania III - Dracula's Curse (USA).nes
 *     roms/nes/media/box2dfront/Castlevania III Draculas Curse.png
 *
 * Counted across the nes and snes collections: 20 ROMs, 40 pictures, and Pegasus
 * can match **none** of them. Naming the file correctly is the entire job, and
 * the Bridge is the only component that knows both halves.
 */
object AssetLayout {

    /**
     * The kinds this project fetches, and what each is called in each layout.
     *
     * [nativeName] is the file's base name in the native layout; [skraperDirs]
     * are the directory names the Skraper provider searches, in its own priority
     * order — the first is what an export writes, the rest are what it must
     * recognise as already-present.
     */
    enum class Kind(
        val nativeName: String,
        val skraperDirs: List<String>,
        val video: Boolean = false
    ) {
        BOX_FRONT("boxFront", listOf("box2dfront", "supporttexture", "box3d")),
        BOX_BACK("boxBack", listOf("box2dback")),
        BOX_SPINE("boxSpine", listOf("box2dside")),
        BOX_FULL("boxFull", listOf("boxtexture")),
        CARTRIDGE("cartridge", listOf("support")),
        LOGO("logo", listOf("wheel", "wheelcarbon", "wheelsteel")),
        BACKGROUND("background", listOf("fanart")),
        SCREENSHOT("screenshot", listOf("screenshot")),
        TITLESCREEN("titlescreen", listOf("screenshottitle")),
        ARCADE_MARQUEE("marquee", listOf("screenmarquee", "screenmarqueesmall")),
        STEAMGRID("steamgrid", listOf("steamgrid")),
        VIDEO("video", listOf("videos"), video = true);

        /** The Skraper directory an export writes into. */
        val primarySkraperDir: String get() = skraperDirs.first()
    }

    /**
     * The Bridge's own media kinds, mapped onto Pegasus'.
     *
     * `wallpaper` becomes `BACKGROUND` rather than a box type: what
     * ScreenScraper serves for it is fanart, and Pegasus' background slot is
     * where a theme expects a full-bleed picture.
     */
    fun kindOf(bridgeKind: String): Kind? = when (bridgeKind.lowercase()) {
        "cover", "box", "boxfront" -> Kind.BOX_FRONT
        "wheel", "logo"            -> Kind.LOGO
        "wallpaper", "background", "fanart" -> Kind.BACKGROUND
        "screenshot"               -> Kind.SCREENSHOT
        "titlescreen"              -> Kind.TITLESCREEN
        "video"                    -> Kind.VIDEO
        "marquee"                  -> Kind.ARCADE_MARQUEE
        "cartridge", "disc"        -> Kind.CARTRIDGE
        "steamgrid", "grid"        -> Kind.STEAMGRID
        "boxback"                  -> Kind.BOX_BACK
        else                       -> null
    }

    enum class Style {
        /** `media/<gameName>/<assetType>.<ext>` — matches a title or a file name. */
        NATIVE,
        /** `media/<assetDir>/<romBaseName>.<ext>` — matches a file name only. */
        SKRAPER
    }

    /**
     * Extensions each provider will accept, taken from `allowed_asset_exts`.
     *
     * Note `webp` and `apng`, which the published documentation does not
     * mention: writing one is fine, and refusing to write one because a doc page
     * was incomplete would lose the smallest format of the four.
     */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "webp", "apng")
    val VIDEO_EXTENSIONS = setOf("webm", "mp4", "avi")

    /** The media roots each provider searches, native first. */
    val MEDIA_DIR_NAMES = listOf("media", ".media", "skraper")

    /**
     * Which layout a collection is already using.
     *
     * Guessing wrong writes a file Pegasus will not read, so this looks rather
     * than assuming: a `media/` directory whose children are asset *type* names
     * is Skraper's; one whose children are game names is the native layout. An
     * empty or absent directory has no answer, and [fallback] decides — Skraper,
     * because it is what every scraping tool in this ecosystem produces and so
     * what a user is most likely to add to later.
     */
    fun detectStyle(collectionDir: File, fallback: Style = Style.SKRAPER): Style {
        val mediaDir = MEDIA_DIR_NAMES.map { File(collectionDir, it) }.firstOrNull { it.isDirectory }
            ?: return fallback
        val children = mediaDir.listFiles { f -> f.isDirectory }?.map { it.name.lowercase() }
            ?: return fallback
        if (children.isEmpty()) return fallback

        val skraperDirs = Kind.entries.flatMap { it.skraperDirs }.toSet()
        return if (children.any { it in skraperDirs }) Style.SKRAPER else Style.NATIVE
    }

    /** The media root to write into: an existing one, or `media/`. */
    fun mediaRoot(collectionDir: File): File =
        MEDIA_DIR_NAMES.map { File(collectionDir, it) }.firstOrNull { it.isDirectory }
            ?: File(collectionDir, MEDIA_DIR_NAMES.first())

    /**
     * Where one asset belongs.
     *
     * [romBaseName] is the ROM's name with its extension removed — Qt's
     * `completeBaseName`, which strips only the **last** dot, so
     * `Super Mario Bros. (World).nes` gives `Super Mario Bros. (World)` and not
     * `Super Mario Bros`. Getting that wrong is a silent miss on every ROM whose
     * title contains a full stop, which is a great many of them.
     *
     * [index] numbers additional assets of one kind: Pegasus matches the type by
     * prefix, so `screenshot02.png` is a screenshot. Zero writes the bare name.
     */
    fun pathFor(
        collectionDir: File,
        style: Style,
        kind: Kind,
        romBaseName: String,
        extension: String,
        index: Int = 0
    ): File {
        val root = mediaRoot(collectionDir)
        val ext = extension.lowercase().removePrefix(".")
        val suffix = if (index > 0) "%02d".format(index) else ""
        return when (style) {
            Style.NATIVE  -> File(File(root, romBaseName), "${kind.nativeName}$suffix.$ext")
            Style.SKRAPER -> File(File(root, kind.primarySkraperDir), "$romBaseName.$ext")
        }
    }

    /**
     * Qt's `completeBaseName`: everything before the last dot.
     *
     * Spelled out rather than using [File.nameWithoutExtension], which agrees
     * today but is a different promise — and the promise that matters is the one
     * Pegasus makes, since it is the side doing the matching.
     */
    fun completeBaseName(file: File): String = file.name.substringBeforeLast('.', file.name)

    /**
     * Every place Pegasus would already find an asset of this kind for this ROM.
     *
     * Used to leave a user's own picture alone: an export that overwrote a
     * hand-placed cover with a scraped one would be destroying the more
     * considered of the two.
     */
    fun existingCandidates(
        collectionDir: File,
        kind: Kind,
        romBaseName: String,
        title: String = ""
    ): List<File> {
        val out = mutableListOf<File>()
        val exts = if (kind.video) VIDEO_EXTENSIONS else IMAGE_EXTENSIONS
        for (rootName in MEDIA_DIR_NAMES) {
            val root = File(collectionDir, rootName)
            if (!root.isDirectory) continue
            // Skraper: media/<assetDir>/<romBaseName>.<ext>
            for (dir in kind.skraperDirs) for (e in exts)
                out += File(File(root, dir), "$romBaseName.$e")
            // Native: media/<name>/<assetType>.<ext>, and the name may be the title.
            for (name in listOfNotNull(romBaseName, title.takeIf { it.isNotBlank() }))
                for (e in exts) out += File(File(root, name), "${kind.nativeName}.$e")
        }
        return out.filter { it.isFile && it.length() > 0 }
    }
}
