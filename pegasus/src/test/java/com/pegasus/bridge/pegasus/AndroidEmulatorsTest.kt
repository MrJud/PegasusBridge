package com.pegasus.bridge.pegasus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * `io.github.lime3ds.android` is labelled **Azahar** on the tablet: Azahar
     * is what Lime3DS became and it kept the package id so installs carried
     * over. The probe table's name is a fallback, not the answer.
     */
    @Test fun `an emulator is called what it calls itself`() {
        val found = AndroidEmulators.discover(
            installed = { onTheTablet[it] },
            labelOf = { if (it == "io.github.lime3ds.android") "Azahar" else null })
        val a = found.first { it.id == "lime3ds" }
        assertEquals("Azahar", a.displayName)
        // …and the table's name is still said, so nobody has to guess which
        // probe answered.
        assertTrue(a.confidence, a.confidence.contains("known here as Lime3DS"))
    }

    /** With no label to be had, the table's name carries it. */
    @Test fun `the table's name is the fallback, not the default`() {
        assertEquals("Lime3DS", discover().first { it.id == "lime3ds" }.displayName)
    }

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
     * Linkboy takes the game's *title*, which is its filename without the
     * extension — not its path, and not a document URI. Both of those it parses
     * and answers "could not find game", because it keeps its own library and
     * looks entries up by name. Established against the device, four tries in.
     */
    @Test fun `linkboy is launched by title, which needs no encoding`() {
        val lb = discover().first { it.id == "linkboy" }
        assertTrue(lb.canTakeARom)
        val lines = lb.launchCommand.lines().map { it.trim() }
        assertTrue(lb.launchCommand,
                   lines.contains("-d \"linkboy://emulator/{file.basename}\""))
        // Reached through its own scheme, so no activity is named.
        assertTrue(lb.launchCommand, lines.none { it.startsWith("-n ") })
        assertFalse(lb.needsCore)
    }

    /** Something that cannot be handed a game never outranks something that can. */
    @Test fun `something drivable always outranks something that is not`() {
        val eggns = AndroidEmulators.discover(installed = {
            if (it == "com.xiaoji.egggame") "5.3.5" else null })
        assertFalse(eggns.single().canTakeARom)
        assertTrue(EmulatorRanking.rankReason(eggns.single(), 0, eggns)
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
     * Whether another app can read the library is usually not knowable, and
     * saying so is the point — a null with no explanation reads as "not checked".
     *
     * The reason is carried from whoever answered, so a caller that injects no
     * resolver gets a null and no story, and the real one on the device gets
     * both. `RetroArch` is the case that cannot be decided: it asks for All
     * files access and the grant is an app op nothing here may read.
     */
    @Test fun `readability is unknown and says why`() {
        val plain = discover().first { it.id == "retroarch" }
        assertEquals(null, plain.canReadLibrary)
        assertEquals("", plain.readabilityUnknownBecause)

        val asked = AndroidEmulators.discover(
            installed = { onTheTablet[it] },
            storageOf = { AndroidEmulators.StorageAccess(
                null, "asks for All files access; the grant is an app op", grantable = true) }
        ).first { it.id == "retroarch" }
        assertEquals(null, asked.canReadLibrary)
        assertTrue(asked.readabilityUnknownBecause.contains("app op"))
        assertEquals("", asked.grantCommand)
    }

    /** A definite no carries the command that fixes it, and nothing else does. */
    @Test fun `a package with no storage permission is told how to get one`() {
        val blocked = AndroidEmulators.discover(
            installed = { onTheTablet[it] },
            storageOf = { AndroidEmulators.StorageAccess(false, "", grantable = true) }
        ).first { it.id == "retroarch" }
        assertEquals(false, blocked.canReadLibrary)
        assertTrue(blocked.grantCommand, blocked.grantCommand.contains("MANAGE_EXTERNAL_STORAGE allow"))
        assertEquals("", blocked.readabilityUnknownBecause)
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

    /**
     * Every `-e` names a value, because `am` refuses one that does not.
     *
     * This was wrong and it was silent: RetroArch only checks that `QUITFOCUS`
     * is present, so the bare `-e QUITFOCUS` looked fine — while `am` was taking
     * `--activity-clear-task` as its value and dropping the flag. Rendered
     * without the trailing flags, as any caller using `launchCommand` on its own
     * does, the same line makes `am` throw and nothing starts.
     */
    @Test fun `every extra passed to am carries a value`() {
        for (probe in AndroidEmulators.PROBES) {
            val words = probe.args.flatMap { it.split(" ") }.filter { it.isNotEmpty() }
            words.forEachIndexed { i, word ->
                if (word != "-e") return@forEachIndexed
                val name  = words.getOrNull(i + 1)
                val value = words.getOrNull(i + 2)
                assertNotNull("${probe.id}: -e with nothing after it", name)
                assertTrue("${probe.id}: '-e $name' has no value, so am would take " +
                           "'${value ?: "the next flag"}' as one",
                           value != null && value != "-e" && !value.startsWith("--"))
            }
        }
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
    /**
     * An emulator that cannot be handed a game can still be opened.
     *
     * Egg NS keeps its own library and exports nothing taking a file, so there
     * is no launch to offer — but opening it is most of what the person wanted,
     * because the game is already in there. The two facts live in two fields:
     * `launchCommand` stays empty, so `canTakeARom` stays false and nothing
     * mistakes this for a working launch.
     */
    @Test fun `an emulator with no way in still offers a way to open it`() {
        val eggns = AndroidEmulators.discover(
            installed = { onTheTablet[it] },
            launcherOf = { "com.xiaoji.egggame.MainActivity" }
        ).single { it.id == "eggns" }

        assertEquals("", eggns.launchCommand)
        assertFalse(eggns.canTakeARom)
        assertTrue(eggns.opensAppOnly)
        assertTrue(eggns.appLaunchCommand,
                   eggns.appLaunchCommand.contains("android.intent.category.LAUNCHER"))
        assertTrue(eggns.caveat, eggns.caveat.contains("its own menu"))
    }

    /** An emulator that *can* take a game is never offered the app-only door. */
    @Test fun `an emulator that takes a game is offered no app-only launch`() {
        val ra = AndroidEmulators.discover(
            installed = { onTheTablet[it] },
            launcherOf = { "some.Launcher" }
        ).single { it.id == "retroarch" }

        assertEquals("", ra.appLaunchCommand)
        assertFalse(ra.opensAppOnly)
        assertEquals("", ra.caveat)
    }

    private fun candidate(launch: String, canRead: Boolean?, grantable: Boolean = true) =
        EmulatorCandidate(
            id = "x", displayName = "ColEm", platforms = listOf("adam"),
            executable = "com.fms.colem", launchCommand = launch,
            kind = EmulatorKind.ANDROID_PACKAGE, verified = true, canReadLibrary = canRead,
            allFilesGrantable = grantable)

    /**
     * The difference that decides what to tell a person, and it is not academic.
     *
     * An app only gets All files access if it asks for it. ColEm, CPCemu,
     * MAME4droid, DroidArcadia and Azahar never ask — so the Settings toggle is
     * absent rather than hidden, and `appops set` does not stick. Measured:
     * granting ColEm READ_EXTERNAL_STORAGE succeeded and changed nothing,
     * because it targets API 35. Telling somebody to enable a setting their
     * device does not have wastes their evening.
     */
    @Test fun `an ungrantable blockage says so instead of asking for a setting`() {
        val ungrantable = candidate("am start -d \"{file.uri}\"", canRead = false, grantable = false)
        assertTrue(ungrantable.pathLaunchWillFail)
        assertTrue(ungrantable.caveat, ungrantable.caveat.contains("nothing to switch on"))
        assertTrue(ungrantable.caveat, ungrantable.caveat.contains("its own file picker"))

        val grantable = candidate("am start -d \"{file.uri}\"", canRead = false, grantable = true)
        assertTrue(grantable.caveat, grantable.caveat.contains("Give it access to all files"))
    }

    /**
     * The third state: it knows how, it just cannot read the file.
     *
     * Different from an emulator that cannot be driven — the launch line is
     * right and the emulator is capable. ColEm proved it: handed the same ROM
     * from a directory it can read, the identical intent booted the game.
     */
    @Test fun `a path handed to something that cannot read it is a known failure`() {
        val c = candidate("am start -d \"{file.uri}\"", canRead = false)
        assertTrue(c.canTakeARom)
        assertFalse(c.opensAppOnly)
        assertTrue(c.pathLaunchWillFail)
        assertTrue(c.caveat, c.caveat.contains("no permission to read the library"))
        assertTrue(c.caveat, c.caveat.contains("not the emulator being unsuitable"))
    }

    /**
     * A document URI is opened against the *caller's* grant, so the receiver
     * needing no permission is the normal case rather than a problem.
     *
     * PPSSPP is why this is not merely theory: it holds no all-files access,
     * answers false here, and runs games.
     */
    @Test fun `a document uri is not a failure even with no read access`() {
        val c = candidate("am start -d \"{file.documenturi}\"", canRead = false)
        assertFalse(c.handsOverAPath)
        assertFalse(c.pathLaunchWillFail)
        assertEquals("", c.caveat)
    }

    /** Undecidable is not the same as false, and must not produce a warning. */
    @Test fun `an unknown read permission warns about nothing`() {
        val c = candidate("am start -d \"{file.uri}\"", canRead = null)
        assertTrue(c.handsOverAPath)
        assertFalse(c.pathLaunchWillFail)
        assertEquals("", c.caveat)
    }

    /** No launcher activity to be had, so nothing offered and none invented. */
    @Test fun `an emulator with no launcher activity offers nothing`() {
        val eggns = AndroidEmulators.discover(installed = { onTheTablet[it] })
            .single { it.id == "eggns" }
        assertEquals("", eggns.appLaunchCommand)
        assertFalse(eggns.opensAppOnly)
    }

}

/**
 * `config/emulators.json` — the table as data rather than as code.
 *
 * It exists because the table was wrong twice in one day and both fixes needed
 * a new APK: Linkboy was missing entirely, and Lime3DS pointed at its settings
 * screen. Neither should have required a rebuild.
 */
class EmulatorConfigTest {

    private fun installed(vararg pkgs: String) = { p: String -> if (p in pkgs) "1.0" else null }

    @Test fun `a new id is added to the built-in table`() {
        val c = AndroidEmulators.parseConfig("""
            { "schemaVersion": 1, "emulators": [
              { "id": "myboy", "displayName": "My Boy!", "platforms": ["gba"],
                "packages": ["com.fastemulator.gba"],
                "component": ".EmulatorActivity",
                "args": ["-a android.intent.action.VIEW", "-d \"{file.uri}\""] } ] }
        """.trimIndent())
        assertEquals(emptyList<String>(), c.rejected)
        assertEquals(1, c.probes.size)

        val found = AndroidEmulators.discover(
            installed = installed("com.fastemulator.gba"), config = c).single()
        assertEquals("My Boy!", found.displayName)
        assertTrue(found.canTakeARom)
        assertTrue(found.confidence, found.confidence.contains("your emulators.json"))
        // Somebody else's line, not one this project has run.
        assertFalse(found.launchVerified)
    }

    /**
     * A name written in the file beats the app's own label, which beats the
     * built-in table. Found on the device: the file renamed Linkboy and the
     * answer still said "Linkboy", because the label was winning over a choice
     * somebody had made deliberately.
     */
    @Test fun `a name from the file wins over the app's own label`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "linkboy", "displayName": "Il mio Linkboy",
                               "platforms": ["gba"], "packages": ["com.pixelrespawn.linkboy"],
                               "args": ["-d x"] } ] }
        """.trimIndent())
        val f = AndroidEmulators.discover(
            installed = installed("com.pixelrespawn.linkboy"), config = c,
            labelOf = { "Linkboy" }).single()
        assertEquals("Il mio Linkboy", f.displayName)

        // With no name given, the label still wins over the table.
        val noName = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "linkboy", "platforms": ["gba"],
                               "packages": ["com.pixelrespawn.linkboy"], "args": ["-d x"] } ] }
        """.trimIndent())
        assertEquals("Linkboy", AndroidEmulators.discover(
            installed = installed("com.pixelrespawn.linkboy"), config = noName,
            labelOf = { "Linkboy" }).single().displayName)
    }

    /** The reason the file exists: correcting a built-in without a new APK. */
    @Test fun `an existing id replaces the built-in, keeping its place`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [
              { "id": "lime3ds", "displayName": "Azahar", "platforms": ["3ds"],
                "packages": ["io.github.lime3ds.android"],
                "component": ".SomeOtherActivity",
                "args": ["-d \"{file.uri}\""] } ] }
        """.trimIndent())
        val merged = AndroidEmulators.probesWith(c)
        assertEquals(1, merged.count { it.id == "lime3ds" })
        assertTrue(merged.first { it.id == "lime3ds" }.component.contains("SomeOtherActivity"))
        // Position kept, so a review screen does not reshuffle under somebody.
        assertEquals(AndroidEmulators.PROBES.indexOfFirst { it.id == "lime3ds" },
                     merged.indexOfFirst { it.id == "lime3ds" })
    }

    @Test fun `a bad entry is dropped and named, and the rest survive`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [
              { "displayName": "no id" },
              { "id": "nopkg", "platforms": ["gba"] },
              { "id": "noplat", "packages": ["a.b"] },
              { "id": "good", "platforms": ["gba"], "packages": ["a.b.c"], "args": ["-d x"] } ] }
        """.trimIndent())
        assertEquals(listOf("good"), c.probes.map { it.id })
        assertEquals(3, c.rejected.size)
        assertTrue(c.rejected.toString(), c.rejected.any { it.contains("no id") })
    }

    /**
     * A hole nobody can fill would be written into the library as a literal.
     *
     * `zx81` is the point: no built-in core hint, none in the file, none on the
     * entry. A warning and not a rejection, because the entry is kept — saying
     * "rejected" for something that was added is what this used to do.
     */
    @Test fun `a core placeholder no one can fill is called out`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "x", "platforms": ["zx81"], "packages": ["a.b"],
                               "args": ["-e LIBRETRO {core}"] } ] }
        """.trimIndent())
        assertTrue(c.warnings.toString(), c.warnings.any { it.contains("{core}") })
        assertTrue(c.rejected.toString(), c.rejected.isEmpty())
        assertEquals(listOf("x"), c.probes.map { it.id })
    }

    /**
     * Correcting RetroArch's launch line must not require restating its cores.
     *
     * The built-in table already names one for `snes`, so the placeholder is
     * fillable and there is nothing to warn about. This warned before, which
     * made a correct entry look broken.
     */
    @Test fun `a core placeholder the built-in table covers is not called out`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "x", "platforms": ["snes"], "packages": ["a.b"],
                               "args": ["-e LIBRETRO {core}"] } ] }
        """.trimIndent())
        assertTrue(c.warnings.toString(), c.warnings.isEmpty())
    }

    /**
     * The file can name a core for a platform this build was compiled without.
     *
     * `3do` is the case that forced it: RetroArch runs it through Opera, the
     * built-in table has no entry, and before this the only way to say so was a
     * new APK.
     */
    @Test fun `the file can teach a core for a platform the build never knew`() {
        val c = AndroidEmulators.parseConfig("""
            { "coreHints": { "3do": ["opera_libretro_android.so"] },
              "emulators": [ { "id": "retroarch", "platforms": ["3do"],
                               "packages": ["com.retroarch"],
                               "component": ".browser.retroactivity.RetroActivityFuture",
                               "args": ["-e LIBRETRO {core}"] } ] }
        """.trimIndent())
        assertEquals(mapOf("3do" to listOf("opera_libretro_android.so")), c.coreHints)
        assertTrue(c.warnings.toString(), c.warnings.isEmpty())
        assertEquals(listOf("/data/data/com.retroarch/cores/opera_libretro_android.so"),
                     AndroidEmulators.coreHintsFor("retroarch", "com.retroarch", "3do", c))
    }

    /** The file wins over the built-in, because the reason to write one is that it is wrong. */
    @Test fun `a core named in the file replaces the built-in one`() {
        val c = AndroidEmulators.parseConfig("""
            { "coreHints": { "snes": ["bsnes_hd_libretro_android.so"] } }
        """.trimIndent())
        assertEquals(listOf("/data/data/com.retroarch/cores/bsnes_hd_libretro_android.so"),
                     AndroidEmulators.coreHintsFor("retroarch", "com.retroarch", "snes", c))
    }

    /**
     * Asking for a platform with no core anywhere answers nothing.
     *
     * It used to answer the union across every platform RetroArch handles,
     * whose first entry is the NES core — so `link-emulators --useHints` would
     * have written `fceumm` into a 3DO collection and called it a launch.
     */
    @Test fun `a platform with no core answers nothing rather than everything`() {
        assertTrue(AndroidEmulators.coreHintsFor("retroarch", "com.retroarch", "3do").isEmpty())
    }

    /** Broken JSON leaves the built-in table alone rather than emptying it. */
    @Test fun `an unreadable file changes nothing`() {
        val c = AndroidEmulators.parseConfig("{ not json ")
        assertTrue(c.probes.isEmpty())
        assertTrue(c.note, c.note.contains("not valid JSON"))
        assertEquals(AndroidEmulators.PROBES, AndroidEmulators.probesWith(c))
    }

    /** A file from a newer build is left alone rather than half-read. */
    @Test fun `a future schema is refused whole`() {
        val c = AndroidEmulators.parseConfig("""{ "schemaVersion": 99, "emulators": [
            { "id": "x", "platforms": ["gba"], "packages": ["a.b"] } ] }""")
        assertTrue(c.probes.isEmpty())
        assertTrue(c.note, c.note.contains("schema 99"))
    }

    /** An entry with no args records something recognised but not drivable. */
    @Test fun `an entry with no args is recognised and not proposed`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "weird", "platforms": ["gba"], "packages": ["a.b"] } ] }
        """.trimIndent())
        val f = AndroidEmulators.discover(installed = installed("a.b"), config = c).single()
        assertFalse(f.canTakeARom)
    }

    /**
     * The catch worth stating out loud: the manifest is fixed at build time and
     * cannot name a package added to the file afterwards, and Android hides
     * undeclared packages from API 30.
     */
    @Test fun `a package the manifest cannot know about is warned about`() {
        val c = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "myboy", "platforms": ["gba"],
                               "packages": ["com.fastemulator.gba"], "args": ["-d x"] } ] }
        """.trimIndent())
        val w = AndroidEmulators.visibilityWarning(c, canSeeAllPackages = false)
        assertTrue(w.orEmpty(), w!!.contains("com.fastemulator.gba"))
        assertTrue(w, w.contains("<queries>"))

        // …and nothing to warn about once the build can ask about any package.
        assertEquals(null, AndroidEmulators.visibilityWarning(c, canSeeAllPackages = true))

        // …and no warning when the file only corrects something already declared.
        val known = AndroidEmulators.parseConfig("""
            { "emulators": [ { "id": "linkboy", "platforms": ["gba"],
                               "packages": ["com.pixelrespawn.linkboy"], "args": ["-d x"] } ] }
        """.trimIndent())
        assertEquals(null, AndroidEmulators.visibilityWarning(known))
    }
}
