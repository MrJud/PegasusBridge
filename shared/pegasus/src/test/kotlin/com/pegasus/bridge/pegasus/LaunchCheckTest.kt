package com.pegasus.bridge.pegasus

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a launch command works — not merely whether one exists.
 *
 * The first version answered one question, "is this `am start`", and reported
 * everything else as fine. That catches a library imported from a phone and
 * nothing else: an emulator since uninstalled, a Flatpak never installed and a
 * path that has moved all reported runnable and failed when somebody pressed A.
 */
class LaunchCheckTest {

    private lateinit var bin: File

    @BeforeTest fun setUp() { bin = Files.createTempDirectory("launch").toFile() }
    @AfterTest fun tearDown() { bin.deleteRecursively() }

    private fun executable(name: String) = File(bin, name).apply {
        writeText("#!/bin/sh\n"); setExecutable(true)
    }

    private fun check(cmd: String, flatpaks: Set<String> = emptySet()) =
        LaunchCheck.check(cmd, pathDirs = listOf(bin), installedFlatpakIds = flatpaks,
                          isAndroid = false)

    // The case the whole library is in: an Android command on a Linux desktop.
    @Test fun `an android command is wrong here and says so`() {
        val r = check("am start\n  -n com.explusalpha.NesEmu/com.imagine.BaseActivity")
        assertEquals(LaunchCheck.Verdict.WRONG_PLATFORM, r.verdict)
        assertFalse(r.runnable)
        assertTrue(r.detail.contains("Android"), r.detail)
    }

    @Test fun `the same command on android resolves the package it names`() {
        val r = LaunchCheck.check("am start -n com.retroarch/.Foo", isAndroid = true,
                                  installedPackage = { it == "com.retroarch" })
        assertEquals(LaunchCheck.Verdict.RUNNABLE, r.verdict)
        assertEquals("com.retroarch/.Foo", r.executable)
    }

    /**
     * The ordinary state of a real Android library, not an edge case: on the
     * tablet this was written against, eleven of nineteen collections named an
     * emulator that had been uninstalled, and every one of them reported fine.
     */
    @Test fun `an am start naming an uninstalled app is not runnable`() {
        val r = LaunchCheck.check(
            "am start\n  -n com.github.stenzek.duckstation/.EmulationActivity\n" +
            "  -e bootPath \"{file.path}\"",
            isAndroid = true, installedPackage = { it == "com.retroarch" })
        assertEquals(LaunchCheck.Verdict.PACKAGE_NOT_INSTALLED, r.verdict)
        assertFalse(r.runnable)
        assertTrue(r.detail.contains("com.github.stenzek.duckstation"), r.detail)
    }

    /** An implicit intent is not resolved rather than guessed at. */
    @Test fun `an am start with no component is unknown, not broken`() {
        val r = LaunchCheck.check("am start -a android.intent.action.VIEW -d x",
                                  isAndroid = true)
        assertEquals(LaunchCheck.Verdict.UNKNOWN, r.verdict)
        assertTrue(r.runnable)
    }

    /** The mirror image of the desktop case, and the reason the file is shared. */
    @Test fun `a flatpak launch on android is not runnable`() {
        val r = LaunchCheck.check("flatpak run org.libretro.RetroArch \"{file.path}\"",
                                  isAndroid = true)
        assertEquals(LaunchCheck.Verdict.FLATPAK_NOT_INSTALLED, r.verdict)
        assertFalse(r.runnable)
    }

    // The ordinary failure the old check missed entirely.
    @Test fun `an emulator that has been uninstalled is not runnable`() {
        val r = check("/usr/bin/duckstation-qt -batch \"{file.path}\"")
        assertEquals(LaunchCheck.Verdict.NOT_FOUND, r.verdict)
        assertTrue(r.detail.contains("does not exist"), r.detail)
    }

    @Test fun `a name on PATH resolves`() {
        executable("mgba-qt")
        val r = check("mgba-qt \"{file.path}\"")
        assertEquals(LaunchCheck.Verdict.RUNNABLE, r.verdict)
        assertTrue(r.executable.endsWith("mgba-qt"))
    }

    @Test fun `a name that is not on PATH does not`() {
        assertEquals(LaunchCheck.Verdict.NOT_FOUND, check("snes9x-gtk \"{file.path}\"").verdict)
    }

    @Test fun `a flatpak launch is checked against what is installed`() {
        val cmd = "flatpak run io.mgba.mGBA \"{file.path}\""
        assertEquals(LaunchCheck.Verdict.RUNNABLE, check(cmd, setOf("io.mgba.mGBA")).verdict)

        val missing = check(cmd, setOf("com.snes9x.Snes9x"))
        assertEquals(LaunchCheck.Verdict.FLATPAK_NOT_INSTALLED, missing.verdict)
        assertTrue(missing.detail.contains("io.mgba.mGBA"), missing.detail)
    }

    @Test fun `flatpak options before the id are skipped`() {
        val r = check("flatpak run --user --branch=stable net.pcsx2.PCSX2 -batch \"{file.path}\"",
                      setOf("net.pcsx2.PCSX2"))
        assertEquals(LaunchCheck.Verdict.RUNNABLE, r.verdict)
        assertEquals("net.pcsx2.PCSX2", r.executable)
    }

    // A wrapper is not the command; the thing after it is.
    @Test fun `a wrapper prefix is stepped past`() {
        executable("retroarch")
        assertEquals(LaunchCheck.Verdict.RUNNABLE,
                     check("gamemoderun retroarch -L core.so \"{file.path}\"").verdict)
        assertEquals(LaunchCheck.Verdict.RUNNABLE,
                     check("SDL_VIDEODRIVER=wayland retroarch \"{file.path}\"").verdict)
    }

    // A launch command may legitimately be a shell expression or come from an
    // environment variable. Declaring one broken because it could not be resolved
    // would be worse than saying nothing.
    @Test fun `something too unusual to resolve is not called broken`() {
        for (cmd in listOf("\$EMULATOR \"{file.path}\"", "\${'$'}{RUNNER} \"{file.path}\"")) {
            val r = check(cmd)
            assertEquals(LaunchCheck.Verdict.UNKNOWN, r.verdict, "for: $cmd")
            assertTrue(r.runnable, "an unresolvable command must not be reported as broken")
        }
    }

    // A real interpreter on PATH is resolved like anything else — it is the
    // command being run, and whether its script works is not this check's business.
    @Test fun `a shell wrapper resolves to the shell`() {
        executable("sh")
        val r = check("sh -c 'emu \"\$1\"' _ \"{file.path}\"")
        assertEquals(LaunchCheck.Verdict.RUNNABLE, r.verdict)
    }

    @Test fun `no launch command at all is missing rather than broken`() {
        val r = check("")
        assertEquals(LaunchCheck.Verdict.MISSING, r.verdict)
        assertFalse(r.runnable)
    }

    @Test fun `a quoted path with spaces is one token`() {
        val exe = File(bin, "my emulator").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val r = check("\"${exe.absolutePath}\" \"{file.path}\"")
        assertEquals(LaunchCheck.Verdict.RUNNABLE, r.verdict)
    }

    @Test fun `a file that exists but cannot be executed is reported as such`() {
        val f = File(bin, "notexec").apply { writeText("x"); setExecutable(false) }
        val r = check("${f.absolutePath} \"{file.path}\"")
        assertEquals(LaunchCheck.Verdict.NOT_FOUND, r.verdict)
        assertTrue(r.detail.contains("not executable"), r.detail)
    }

    @Test fun `tokenising honours quotes and folds newlines`() {
        assertEquals(listOf("am", "start", "-d", "{file.uri}"),
                     LaunchCheck.tokenise("am start\n  -d \"{file.uri}\""))
    }
}
