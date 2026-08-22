package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which emulator was chosen, per collection and per game.
 *
 * The theme's half is a list and a selection — the Quick menu for a whole
 * collection, the game database for one title. Both are decisions a person made,
 * and the architecture has one rule about those: they win, and a sync never
 * overwrites them.
 */
class LaunchPreferencesTest {

    private lateinit var root: File
    private lateinit var lib: File
    private lateinit var prefs: LaunchPreferences

    @BeforeTest fun setUp() {
        BridgeLog.current = NoopLog
        root = Files.createTempDirectory("prefs").toFile()
        lib = Files.createTempDirectory("prefs-lib").toFile()
        prefs = LaunchPreferences(File(root, LaunchPreferences.FILE_NAME))
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively(); lib.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun collection(name: String) = File(lib, name).apply { mkdirs() }
    private fun rom(dir: File, name: String) = File(dir, name).apply { writeText("rom") }

    private fun save() = prefs.save { f, t -> BridgePaths.writeAtomic(f, t) }
    private fun reopen() = LaunchPreferences(File(root, LaunchPreferences.FILE_NAME))

    @Test fun `a game inherits its collection's choice until it has one`() {
        val n64 = collection("n64")
        val game = rom(n64, "Banjo-Kazooie (USA).z64")

        prefs.setCollection(n64, "gopher64")
        assertEquals("gopher64", prefs.effectiveFor(game, n64))
        assertNull(prefs.forGame(game), "the game has made no choice of its own")

        prefs.setGame(game, "mupen64plus")
        assertEquals("mupen64plus", prefs.effectiveFor(game, n64))
        assertEquals("gopher64", prefs.forCollection(n64), "the collection's is untouched")
    }

    // A UI has to show "inherited" differently from "chosen here", or the clear
    // action means nothing.
    @Test fun `clearing a game returns it to the collection's choice`() {
        val n64 = collection("n64")
        val game = rom(n64, "Banjo-Kazooie (USA).z64")
        prefs.setCollection(n64, "gopher64")
        prefs.setGame(game, "mupen64plus")

        assertTrue(prefs.clearGame(game))
        assertNull(prefs.forGame(game))
        assertEquals("gopher64", prefs.effectiveFor(game, n64))
        assertFalse(prefs.clearGame(game), "clearing twice changes nothing")
    }

    @Test fun `choices survive a restart`() {
        val n64 = collection("n64")
        val game = rom(n64, "Banjo-Kazooie (USA).z64")
        prefs.setCollection(n64, "gopher64")
        prefs.setGame(game, "mupen64plus")
        save()

        val again = reopen()
        assertEquals("gopher64", again.forCollection(n64))
        assertEquals("mupen64plus", again.forGame(game))
    }

    @Test fun `the per-game choices of one collection are listed by file name`() {
        val n64 = collection("n64")
        val snes = collection("snes")
        val a = rom(n64, "Banjo-Kazooie (USA).z64")
        val b = rom(n64, "GoldenEye 007 (USA).z64")
        prefs.setGame(a, "mupen64plus")
        prefs.setGame(b, "gopher64")
        prefs.setGame(rom(snes, "Super Mario World (USA).sfc"), "snes9x")

        val inN64 = prefs.gamesIn(n64)
        assertEquals(mapOf("Banjo-Kazooie (USA).z64" to "mupen64plus",
                           "GoldenEye 007 (USA).z64" to "gopher64"), inN64)
    }

    // The stored value is an id, not a command: a command frozen at the moment of
    // choosing goes stale when the Flatpak is replaced by a native package.
    @Test fun `what is stored is an emulator id`() {
        val n64 = collection("n64")
        prefs.setCollection(n64, "gopher64")
        save()
        val text = File(root, LaunchPreferences.FILE_NAME).readText()
        assertTrue(text.contains("gopher64"), text)
        assertFalse(text.contains("flatpak run"), "a command was frozen into the preferences")
    }

    @Test fun `choices for roms that are gone are dropped`() {
        val n64 = collection("n64")
        val a = rom(n64, "A.z64")
        val b = rom(n64, "B.z64")
        prefs.setGame(a, "gopher64"); prefs.setGame(b, "mupen64plus")

        prefs.forget(setOf(a.canonicalPath))
        assertEquals("gopher64", prefs.forGame(a))
        assertNull(prefs.forGame(b))
    }

    @Test fun `an unreadable file is treated as unset rather than failing`() {
        File(root, LaunchPreferences.FILE_NAME).writeText("{ not json")
        val p = reopen()
        assertNull(p.forCollection(collection("n64")))
    }

    // ── What the export does with them ──────────────────────────────────────

    @Test fun `a game whose choice differs from its collection gets its own launch`() {
        val e = GameEntry(title = "Banjo-Kazooie", fileName = "Banjo-Kazooie (USA).z64",
                          developer = "Rare",
                          launch = "flatpak run com.github.Rosalie241.RMG \"{file.path}\"")
        val t = e.render()
        assertTrue(t.contains("launch: flatpak run com.github.Rosalie241.RMG"), t)
        // Before the description, so a long synopsis cannot come between the
        // game and the command that runs it.
        assertTrue(t.indexOf("launch:") < (t.indexOf("description:").takeIf { it >= 0 } ?: Int.MAX_VALUE))
    }

    // A chosen emulator is reason enough for an entry even with no metadata: it
    // is a decision, and it has nowhere else to be expressed.
    @Test fun `a launch alone is enough to write an entry`() {
        val bare = GameEntry(title = "X", fileName = "X.z64")
        assertEquals("", bare.render())
        assertTrue(bare.copy(launch = "emu \"{file.path}\"").render().isNotEmpty())
    }
}
