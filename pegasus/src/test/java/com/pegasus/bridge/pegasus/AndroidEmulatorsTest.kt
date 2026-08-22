package com.pegasus.bridge.pegasus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Discovery, ranking and the one thing that would break silently.
 *
 * The fixtures are the packages actually installed on the Galaxy Tab S8+ this
 * was developed against — RetroArch, PPSSPP, DraStic and Lime3DS — because a
 * test built from an imagined device proves nothing about a real one.
 */
class AndroidEmulatorsTest {

    /** Verbatim what the tablet's package manager answered on 2026-08-22. */
    private val onTheTablet = mapOf(
        "com.retroarch" to "1.22.2_GIT",
        "org.ppsspp.ppsspp" to "1.17.1",
        "com.dsemu.drastic" to "r2.5.2.2a",
        "io.github.lime3ds.android" to "2126.0-googleplay",
        "com.pixelrespawn.linkboy" to "3.11.0",
        "com.xiaoji.egggame" to "5.3.5"
    )

    private fun discover(platform: String? = null) =
        AndroidEmulators.discover(installed = { onTheTablet[it] }, platform = platform)

    @Test fun `only installed packages become candidates`() {
        val ids = discover().map { it.id }.toSet()
        assertEquals(setOf("retroarch", "ppsspp", "drastic", "lime3ds", "linkboy", "eggns"), ids)
    }

    /**
     * Linkboy was installed and invisible until somebody said their GBA
     * emulator was missing. It was missing from the probe table, and the sweep
     * that built that table was a keyword grep that looked for `myboy`.
     */
    @Test fun `an emulator that keeps its own library is still recognised`() {
        val lb = discover().first { it.id == "linkboy" }
        assertTrue(lb.verified)
        assertEquals("3.11.0", lb.version)
        assertTrue("gba" in lb.platforms)
    }

    /**
     * …and never proposed. It exports `VIEW` on `linkboy://` and `MAIN`, and
     * nothing that takes a file, so there is no launch line to write. Saying
     * that is the answer; inventing one would produce a button that does
     * nothing.
     */
    @Test fun `an emulator with no way in is listed but cannot be driven`() {
        val lb = discover().first { it.id == "linkboy" }
        assertFalse(lb.canTakeARom)
        assertEquals("", lb.launchCommand)
        assertFalse(lb.launchVerified)
        assertTrue(lb.confidence, lb.confidence.contains("no way to be handed a game"))
    }

    /** And it never outranks something that can actually be handed a game. */
    @Test fun `something drivable always outranks something that is not`() {
        val ranked = EmulatorRanking.rankedFor("gba", discover())
        assertEquals(listOf("retroarch", "linkboy"), ranked.map { it.id })
        assertTrue(EmulatorRanking.rankReason(ranked.last(), 1, ranked)
                       .contains("no way to be handed a game"))
    }

    /**
     * Egg NS emulates the Switch, and `switch` was being reported as having no
     * emulator installed at all. It has one; it just cannot be driven either.
     */
    @Test fun `the switch has an emulator installed, undrivable though it is`() {
        val best = EmulatorRanking.bestFor("switch", discover())
        assertEquals("eggns", best?.id)
        assertFalse(best!!.canTakeARom)
    }

    /**
     * The package manager states a version without the package being run, so
     * unlike the desktop there is no such thing here as an unverified find.
     */
    @Test fun `every android candidate is verified, and none was executed`() {
        assertTrue(discover().all { it.verified })
        assertTrue(discover().all { it.kind == EmulatorKind.ANDROID_PACKAGE })
        assertEquals("1.22.2_GIT", discover().first { it.id == "retroarch" }.version)
    }

    /**
     * Whether another app can read the library is not knowable from here, and
     * saying so is the point — a null with no explanation reads as "not checked".
     */
    @Test fun `readability is unknown and says why`() {
        val ra = discover().first { it.id == "retroarch" }
        assertEquals(null, ra.canReadLibrary)
        assertTrue(ra.readabilityUnknownBecause.contains("GET_APP_OPS_STATS"))
    }

    /** Two RetroArch builds are two ways to run one thing, not two choices. */
    @Test fun `one candidate per emulator even with several packages installed`() {
        val both = AndroidEmulators.discover(
            installed = { mapOf("com.retroarch" to "1.22.2_GIT",
                                "com.retroarch.aarch64" to "1.22.2_GIT")[it] })
        assertEquals(1, both.count { it.id == "retroarch" })
        // The preferred package wins: aarch64 is listed first in the probe.
        assertEquals("com.retroarch.aarch64", both.first { it.id == "retroarch" }.executable)
    }

    /**
     * The real case on the tablet: `snes` has 735 ROMs, its launch line names
     * Snes9x EX+, and Snes9x EX+ is not installed. RetroArch is, and handles it.
     */
    @Test fun `snes falls to retroarch when the dedicated emulator is absent`() {
        val ranked = EmulatorRanking.rankedFor("snes", discover())
        assertEquals(listOf("retroarch"), ranked.map { it.id })
        assertTrue(ranked.single().needsCore)
    }

    /** A specialist outranks RetroArch for its own system. */
    @Test fun `the dedicated emulator wins over retroarch`() {
        assertEquals("drastic", EmulatorRanking.bestFor("nds", discover())?.id)
        assertEquals("ppsspp", EmulatorRanking.bestFor("psp", discover())?.id)
    }

    /** RetroArch's line still has a decision in it, and the hints are labelled hints. */
    @Test fun `retroarch offers core hints rather than pretending to know`() {
        val ra = EmulatorRanking.rankedFor("snes", discover(platform = "snes")).single()
        assertTrue(ra.launchCommand.contains("{core}"))
        // Absolute, under the package's own private core directory: a bare
        // filename is not what RetroArch resolves, and the library's one
        // working RetroArch line spells the path out in full.
        assertTrue(ra.coreHints.toString(),
                   ra.coreHints.contains("/data/data/com.retroarch/cores/snes9x_libretro_android.so"))
        // A dedicated emulator has no hole to fill, so it offers none.
        assertTrue(EmulatorRanking.bestFor("psp", discover())!!.coreHints.isEmpty())
    }

    /** The launch line is the multi-line form Pegasus writes, and it is complete. */
    @Test fun `the launch command is a whole am start`() {
        val psp = EmulatorRanking.bestFor("psp", discover())!!
        val lines = psp.launchCommand.lines().map { it.trim() }
        assertEquals("am start", lines.first())
        assertTrue(lines.contains("-n org.ppsspp.ppsspp/.PpssppActivity"))
        // Without this an emulator relaunched from Pegasus resumes its previous
        // game instead of loading the one that was picked.
        assertTrue(lines.contains("--activity-clear-task"))
        assertFalse("no placeholder should be left behind",
                    psp.launchCommand.contains("{package}"))
    }

    /** RetroArch's config path is per-package, so it has to follow the package. */
    @Test fun `the retroarch config path names the package that was found`() {
        val ra = AndroidEmulators.discover(installed = {
            if (it == "com.retroarch.aarch64") "1.22.2_GIT" else null }).single()
        assertTrue(ra.launchCommand.contains(
            "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"))
    }

    /**
     * Lime3DS is a Citra fork and keeps Citra's class names inside its own
     * application id. The first probe pointed at `.features.settings.ui.
     * SettingsActivity` — a real class, and the settings screen — so the app
     * resolved, discovery was confident, and pressing A would have opened
     * preferences. Read off the tablet afterwards; pinned here so it stays read.
     */
    @Test fun `lime3ds launches citra's emulation activity, not its settings`() {
        val lime = discover().first { it.id == "lime3ds" }
        assertTrue(lime.launchCommand.contains(
            "-n io.github.lime3ds.android/org.citra.citra_emu.activities.EmulationActivity"))
        assertFalse(lime.launchCommand.contains("Settings"))
        assertTrue(lime.launchVerified)
    }

    /**
     * A launch line written from documentation is not the same claim as one
     * transcribed from a library that launches games, and the answer says which.
     */
    @Test fun `an unverified launch line is reported as unverified`() {
        val guessed = AndroidEmulators.discover(installed = {
            if (it == "org.vita3k.emulator") "0.1" else null }).single()
        assertTrue(guessed.verified)          // the package really is installed
        assertFalse(guessed.launchVerified)   // the command naming its activity is not
        assertTrue(guessed.confidence, guessed.confidence.contains("never run here"))

        val known = discover().first { it.id == "ppsspp" }
        assertTrue(known.launchVerified)
        assertTrue(known.confidence, known.confidence.contains("confirmed on this device"))
    }

    /**
     * The failure this guards against has no symptom.
     *
     * From API 30 a package not named in `<queries>` is invisible, and
     * `getPackageInfo` throws exactly the same exception it throws for one that
     * is genuinely absent. Add a probe, forget the manifest, and discovery
     * reports a device with no emulators on it and no error anywhere.
     */
    @Test fun `every probed package is declared in the manifest`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)
        val declared = Regex("""<package\s+android:name="([^"]+)"""")
            .findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        val probed = AndroidEmulators.KNOWN_PACKAGES

        assertEquals("packages probed but not visible to the app",
                     emptySet<String>(), probed - declared)
        assertEquals("packages declared but no longer probed",
                     emptySet<String>(), declared - probed)
    }
}
