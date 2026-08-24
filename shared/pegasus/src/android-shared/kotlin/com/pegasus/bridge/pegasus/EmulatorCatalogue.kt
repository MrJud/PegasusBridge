package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.FuzzyMatch
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an installed emulator is, how a list of them is ordered, and how one is
 * described to a caller.
 *
 * This sits in `android-shared` while [EmulatorDiscovery] does not, and the
 * split is the whole point. *Finding* an emulator is entirely platform
 * specific — the desktop reads `$PATH` and asks `flatpak list`, Android asks
 * its package manager, and neither idea exists on the other. Everything after
 * that is identical: the same fields describe a candidate, the same rules order
 * it, and a theme that has learnt to read one shell's answer must not have to
 * learn the other's.
 *
 * The first draft of the Android port had its own candidate class and its own
 * ranking. That is how `Config.kt` drifted, and the two would have drifted the
 * same way — so the second draft is this file, and the platform halves supply
 * nothing but [EmulatorCandidate]s.
 */

/**
 * How an emulator was found, which is also what can be trusted about it.
 *
 * [ANDROID_PACKAGE] existed here before Android could produce one: the desktop
 * needs the constant to say "this collection's launch line names an Android
 * package, and this is not Android".
 */
enum class EmulatorKind { NATIVE, FLATPAK, APPIMAGE, DESKTOP_ENTRY, ANDROID_PACKAGE }

/**
 * One emulator that is installed, and everything a person needs to judge it.
 *
 * [verified] is true only when the emulator identified itself rather than being
 * inferred from a filename. What that costs differs per shell — a desktop binary
 * has to be executed for it, an Android package simply states its version — and
 * [confidence] carries the difference in words, because "on PATH at /usr/bin"
 * and "guessed from a Flatpak id" deserve different trust from the person
 * reviewing the list.
 */
data class EmulatorCandidate(
    val id: String,
    val displayName: String,
    val platforms: List<String>,
    /** The binary, the Flatpak id or the Android package — whatever names it. */
    val executable: String,
    val launchCommand: String,
    val kind: EmulatorKind,
    val verified: Boolean,
    val version: String = "",
    val confidence: String = "",
    /**
     * Whether this candidate can actually read the library.
     *
     * Null when it was not checked, which is the honest answer for a native
     * binary: it runs unsandboxed and reads whatever the user can.
     *
     * For a Flatpak it is the difference between installed and usable, and the
     * two are not the same. Measured on a real install: PCSX2 ships with
     * `filesystems=xdg-config/kdeglobals:ro;xdg-run/gamescope-0:ro` and Snes9x
     * with `filesystems=home`, while the library sits on an external mount under
     * `/run/media`. Both were installed, both were verified, and neither could
     * open a single ROM. A proposal that launches an emulator onto a file it
     * cannot see is worse than no proposal.
     *
     * On Android it is null more often than not, and deliberately so — see
     * `AndroidEmulators` for why the question cannot be answered there without
     * a permission this app has no business holding.
     */
    val canReadLibrary: Boolean? = null,
    /** What to run to fix [canReadLibrary], when it is false. */
    val grantCommand: String = "",
    /**
     * Why [canReadLibrary] is null, when a caller might have expected an answer.
     *
     * Empty when the field speaks for itself. Android fills it in, because
     * "not checked" and "cannot be checked from here" are different claims and
     * a review screen should not present the second as the first.
     */
    val readabilityUnknownBecause: String = "",
    /**
     * Cores this emulator conventionally uses for [platforms], when its launch
     * command still has a `{core}` in it.
     *
     * Hints, not findings, and the field is named so nobody mistakes one for the
     * other. On Android the reason is concrete: RetroArch keeps its cores in
     * `/data/user/0/com.retroarch/cores`, which is app-private, so no amount of
     * looking will say which are installed. Offering the conventional filename
     * lets a review screen present a choice; claiming it is installed would be
     * a lie that fails at launch.
     */
    val coreHints: List<String> = emptyList(),
    /**
     * Whether the *launch line* is known to work, as opposed to the emulator
     * being known to exist.
     *
     * Two different claims, and conflating them is a real failure mode. On the
     * desktop a launch command is built from an executable that was resolved
     * and run, so it is true by construction. On Android the command names an
     * activity inside the package, and the package manager will happily confirm
     * an app while the activity named is the wrong one — which is exactly what
     * happened to Lime3DS, whose first probe pointed at its settings screen.
     */
    val launchVerified: Boolean = true,
    /**
     * How to open this emulator on nothing at all.
     *
     * For an emulator that keeps its own library and exports no way to be
     * handed a file — Lemuroid, Egg NS — opening it *is* most of what a person
     * wanted, because the game is already in there and two taps away. Refusing
     * to offer that because it is not a real launch helps nobody.
     *
     * Deliberately **not** [launchCommand]. That field means "hand this
     * emulator this game", and putting an app-opener in it would make
     * [canTakeARom] true, let a review screen present it as a working launch,
     * and land somebody on an emulator's main menu wondering why their game
     * did not start. The two facts are kept in two fields so nothing has to
     * guess which one it is holding.
     */
    val appLaunchCommand: String = "",
    /**
     * Whether the missing access is a thing anybody could grant.
     *
     * An Android app only gets All files access if it *asks* for it in its
     * manifest. One that never asked has no toggle in Settings — not hidden,
     * absent — and no `appops` line will stick either. Measured across seven
     * emulators on the tablet: RetroArch is the only one that asks, and the
     * only one a path launch works for.
     *
     * The distinction is the whole difference between "you forgot to grant
     * this" and "this cannot be granted by you or anyone". Telling somebody to
     * enable a setting that does not exist is worse than telling them nothing.
     */
    val allFilesGrantable: Boolean = false
) {
    /** Whether the launch line still has a decision in it that discovery cannot make. */
    val needsCore: Boolean get() = launchCommand.contains("{core}")

    /**
     * Opens, but never with the game.
     *
     * True for exactly the case above: something to run, and it will not carry
     * the chosen game with it. Anything acting on this must say so to the
     * person before doing it.
     */
    val opensAppOnly: Boolean get() = launchCommand.isEmpty() && appLaunchCommand.isNotEmpty()

    /**
     * Whether this launch hands over a **path**, as opposed to a document URI.
     *
     * The distinction decides whether [canReadLibrary] matters at all. A path
     * or a `file://` URI is opened by the emulator itself, as itself, so it
     * needs its own read access to the library. A `content://` document URI is
     * opened against a grant the *caller* passes with the intent, and needs the
     * emulator to hold no storage permission whatsoever.
     *
     * PPSSPP is the proof and the reason this is a field: it holds no all-files
     * access and no `READ_EXTERNAL_STORAGE`, and it runs games perfectly,
     * because its line is `{file.documenturi}`.
     */
    val handsOverAPath: Boolean get() =
        launchCommand.contains("{file.path}") || launchCommand.contains("{file.uri}")

    /**
     * Known to be about to fail: a path handed to something that cannot read it.
     *
     * This is the third state, and it is not the same as either of the others.
     * The emulator exports a perfectly good way in — unlike Lemuroid — and the
     * launch line is right. It will still do nothing, because the app has no
     * permission to read the file it is being pointed at. ColEm is the measured
     * case: handed the same ROM from a directory it *can* read, the identical
     * intent boots the game.
     */
    val pathLaunchWillFail: Boolean get() = canReadLibrary == false && handsOverAPath

    /**
     * The sentence to show beside this candidate, or empty when there is nothing
     * to warn about.
     *
     * One field rather than one per case, because a review screen has one place
     * to put it; [opensAppOnly] and [pathLaunchWillFail] are the machine-readable
     * halves for anything that needs to branch.
     */
    val caveat: String get() = when {
        opensAppOnly ->
            "$displayName keeps its own library and exports no way to be handed a file, so " +
            "this opens it on its own menu — the game has to be picked there. Nothing else " +
            "about it is known to be wrong."
        pathLaunchWillFail && allFilesGrantable ->
            "$displayName knows how to take a game — this launch line is right — but it has " +
            "no permission to read the library, so it will open on nothing. Give it access to " +
            "all files and the same line works. This is not the emulator being unsuitable."
        pathLaunchWillFail ->
            "$displayName cannot be handed a file on this version of Android, and there is " +
            "nothing to switch on: it never asks for access to all files, so no such setting " +
            "exists for it. Open it and use its own file picker, or pick an emulator that " +
            "does ask — this is the app not having kept up with scoped storage, and no " +
            "permission you grant will change it."
        else -> ""
    }

    /**
     * Whether this can be handed a game at all.
     *
     * False for an emulator that is installed and exports no way to receive
     * one — it manages its own library and nothing outside it can say "open
     * this file". Listed anyway, because a person looking for the emulator they
     * installed should find it rather than wonder whether the Bridge is blind.
     */
    val canTakeARom: Boolean get() = launchCommand.isNotEmpty()
}

/**
 * The order a review screen shows candidates in, and the sentence explaining it.
 *
 * Pure: it takes candidates and returns candidates, so both shells share it and
 * a test needs no emulator installed to exercise it.
 */
object EmulatorRanking {

    /**
     * Every candidate that handles [platform], best first.
     *
     * All of them, not just the winner. "Propose, never apply" is not honoured by
     * a proposal whose alternatives are invisible — that is a decision made for
     * somebody and shown to them afterwards. A review screen needs the list, and
     * an apply takes whatever the person picked out of it.
     *
     * The order, and the reason for each step:
     *
     * 1. **Can it read the library.** An emulator that cannot open the ROM fails
     *    at the moment somebody presses A, whatever else is true of it.
     * 2. **Did it identify itself.** A binary that answered a version probe, a
     *    Flatpak whose metadata names one, a package the system has a version for.
     * 3. **Is it RetroArch.** Last among equals: it covers every platform, so it
     *    is never the most specific answer, and its command still needs a core
     *    that discovery has no way to choose.
     * 4. **How many platforms it claims.** Fewer means more specialised, and a
     *    specialist is the better default for its own system.
     */
    fun rankedFor(platform: String, candidates: List<EmulatorCandidate>): List<EmulatorCandidate> {
        val norm = FuzzyMatch.normalizePlatform(platform)
        return candidates.filter { norm in it.platforms }.sortedWith(
            // Before anything else: one that can be handed a game beats one
            // that cannot, however well regarded the second is.
            compareByDescending<EmulatorCandidate> { it.canTakeARom }
                .thenByDescending { it.canReadLibrary != false }
                .thenByDescending { it.verified }
                .thenBy { it.id == "retroarch" }
                .thenBy { it.platforms.size }
                .thenBy { it.displayName }
        )
    }

    /** The best candidate for [platform], or null. Shorthand over [rankedFor]. */
    fun bestFor(platform: String, candidates: List<EmulatorCandidate>): EmulatorCandidate? =
        rankedFor(platform, candidates).firstOrNull()

    /**
     * Why a candidate sits where it does, in one sentence a person can read.
     *
     * [peers] is the whole ranked list, and it is there for one case: two
     * candidates that are equally verified, equally able to read the library and
     * equally specialised are separated only by name. Saying the first is
     * "dedicated to this platform, and verified" would imply a distinction that
     * does not exist — measured on a real machine, where Gopher64 and
     * Mupen64Plus tie exactly for N64 and the order is alphabetical.
     */
    fun rankReason(
        c: EmulatorCandidate,
        position: Int,
        peers: List<EmulatorCandidate> = emptyList()
    ): String {
        val tied = peers.count { it.id != c.id && ranksEqually(it, c) }
        return when {
            !c.canTakeARom ->
                "installed, but it declares no way to be handed a game — it keeps its own library"
            c.canReadLibrary == false -> "cannot read the library as installed"
            position == 0 && peers.size == 1 -> "the only one installed for this platform"
            c.id == "retroarch" -> "covers everything, so never the most specific choice"
            !c.verified -> "installed, but it did not identify itself"
            tied > 0 && position == 0 ->
                "as good a fit as the other ${if (tied == 1) "one" else "$tied"}; " +
                "listed first by name, so either will do"
            tied > 0 -> "as good a fit as the one above; the order between them is just the name"
            position == 0 -> "dedicated to this platform, and verified"
            else -> "also handles this platform"
        }
    }

    /** Whether two candidates are separated by nothing but their names. */
    private fun ranksEqually(a: EmulatorCandidate, b: EmulatorCandidate): Boolean =
        (a.canReadLibrary != false) == (b.canReadLibrary != false) &&
        a.verified == b.verified &&
        (a.id == "retroarch") == (b.id == "retroarch") &&
        a.platforms.size == b.platforms.size

    /** The order candidates are listed in before any platform is named. */
    fun overall(candidates: List<EmulatorCandidate>): List<EmulatorCandidate> =
        candidates.sortedWith(
            compareByDescending<EmulatorCandidate> { it.verified }.thenBy { it.displayName })
}

/**
 * One candidate as a proposal, in the shape both shells answer with.
 *
 * Here rather than in each shell's router for the reason the whole file exists:
 * a theme reads this object, and a field that appears on one platform and not
 * the other is a bug the theme discovers at runtime on somebody else's device.
 */
fun EmulatorCandidate.toProposalJson(
    position: Int = 0,
    peers: List<EmulatorCandidate> = emptyList()
): JSONObject = JSONObject()
    .put("emulator", id)
    .put("displayName", displayName)
    .put("launchCommand", launchCommand)
    .put("verified", verified)
    .put("version", version)
    .put("kind", kind.name.lowercase())
    .put("canReadLibrary", canReadLibrary ?: JSONObject.NULL)
    .put("grantCommand", if (canReadLibrary == false) grantCommand else JSONObject.NULL)
    .put("readabilityUnknownBecause",
         readabilityUnknownBecause.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
    .put("needsCore", needsCore)
    .put("coreHints", JSONArray(coreHints))
    .put("launchVerified", launchVerified)
    .put("canTakeARom", canTakeARom)
    .put("appLaunchCommand", appLaunchCommand.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
    .put("opensAppOnly", opensAppOnly)
    .put("handsOverAPath", handsOverAPath)
    .put("pathLaunchWillFail", pathLaunchWillFail)
    .put("allFilesGrantable", allFilesGrantable)
    .put("caveat", caveat.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
    .put("why", EmulatorRanking.rankReason(this, position, peers))

/** One candidate as an entry in the `/emulators` list. */
fun EmulatorCandidate.toListJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("displayName", displayName)
    .put("platforms", JSONArray(platforms))
    .put("executable", executable)
    .put("launchCommand", launchCommand)
    .put("kind", kind.name.lowercase())
    // The difference between "this told us what it is" and "a file with the
    // right name exists", which is what decides whether a person should accept
    // the proposal without checking.
    .put("verified", verified)
    .put("version", version)
    .put("confidence", confidence)
    .put("canReadLibrary", canReadLibrary ?: JSONObject.NULL)
    .put("readabilityUnknownBecause",
         readabilityUnknownBecause.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
    .put("needsCore", needsCore)
    .put("coreHints", JSONArray(coreHints))
    .put("launchVerified", launchVerified)
    .put("canTakeARom", canTakeARom)
    .put("appLaunchCommand", appLaunchCommand.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
    .put("opensAppOnly", opensAppOnly)
    .put("handsOverAPath", handsOverAPath)
    .put("pathLaunchWillFail", pathLaunchWillFail)
    .put("allFilesGrantable", allFilesGrantable)
    .put("caveat", caveat.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
    // The one command that fixes it, ready to show or to run.
    .put("grantCommand", if (canReadLibrary == false) grantCommand else JSONObject.NULL)
