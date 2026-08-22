package com.pegasus.bridge.scrapers

/**
 * How Android launches a Steam, Epic, GOG or Amazon game through GameNative.
 *
 * The contract is not guessed. It was read out of GameNative's own
 * `AndroidManifest.xml` and `utils/IntentLaunchManager.kt` at
 * `utkarshdalal/GameNative` (GPL-3.0), where `MainActivity` is
 * `android:exported="true"` and carries an intent filter for
 * `app.gamenative.LAUNCH_GAME`:
 *
 * ```
 * action     app.gamenative.LAUNCH_GAME
 * category   android.intent.category.DEFAULT
 * component  app.gamenative/.MainActivity
 * extras
 *   app_id            int     the store's app id, must be > 0
 *   game_source       String  STEAM | EPIC | GOG | AMAZON, defaults to STEAM
 *   container_config  String  optional JSON, refused above 50 000 bytes
 * ```
 *
 * ── What this deliberately is not ──────────────────────────
 *
 * A *launcher*, and nothing else. GameNative has Steam achievement support of
 * its own, and it keeps it in the app's private storage: there is no content
 * provider, no broadcast and no exported reader, so the Bridge cannot ask it
 * what a user has unlocked and must not pretend to. Achievements come from the
 * Steam Web API — [SteamAccountClient] — which answers the same way on both
 * shells and so gives the theme one contract instead of two.
 *
 * This class builds the parameters and nothing more. Constructing the `Intent`
 * needs `android.content`, which the shared code cannot import; the Android
 * shell does that with what is here.
 */
object GameNativeLaunch {

    const val PACKAGE = "app.gamenative"
    const val ACTIVITY = "app.gamenative.MainActivity"
    const val ACTION = "app.gamenative.LAUNCH_GAME"
    const val CATEGORY = "android.intent.category.DEFAULT"

    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"
    const val EXTRA_CONTAINER_CONFIG = "container_config"

    /**
     * The stores GameNative validates against.
     *
     * An unrecognised value is not rejected there — `IntentLaunchManager` falls
     * back to `STEAM` — but sending one anyway would mean asking for a GOG game
     * and silently getting the Steam app of the same id, which is a different
     * game. So this refuses locally instead.
     */
    enum class Source { STEAM, EPIC, GOG, AMAZON }

    /** GameNative's own cap. Larger is dropped there, so it is refused here. */
    const val MAX_CONFIG_BYTES = 50_000

    data class Request(
        val appId: Int,
        val source: Source = Source.STEAM,
        val containerConfigJson: String = ""
    ) {
        val extras: Map<String, Any> get() = buildMap {
            // An Int extra, not a String: `getIntExtra` returns -1 for a String
            // and the launch is refused for an "invalid app_id" that was correct.
            put(EXTRA_APP_ID, appId)
            put(EXTRA_GAME_SOURCE, source.name)
            if (containerConfigJson.isNotBlank())
                put(EXTRA_CONTAINER_CONFIG, containerConfigJson)
        }
    }

    sealed interface Result {
        data class Ok(val request: Request) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Validates a launch before an intent is built for it.
     *
     * Everything checked here is checked again by GameNative, and refused there
     * with a log line inside another app that nobody reading the theme will ever
     * see. Refusing locally is what lets the failure carry a reason.
     */
    fun requestFor(appId: Int, source: String = "STEAM", containerConfigJson: String = ""): Result {
        if (appId <= 0) return Result.Rejected("app_id must be a positive store id, not $appId")
        val src = Source.entries.firstOrNull { it.name.equals(source, ignoreCase = true) }
            ?: return Result.Rejected(
                "unknown game source '$source' — GameNative knows " +
                Source.entries.joinToString("/") { it.name })
        if (containerConfigJson.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES)
            return Result.Rejected("the container config is over GameNative's ${MAX_CONFIG_BYTES}-byte limit")
        return Result.Ok(Request(appId, src, containerConfigJson))
    }

    /**
     * The `am start` line the intent corresponds to.
     *
     * Not used to launch anything — it is what a Pegasus collection's `launch:`
     * field would contain on Android, and what a person needs when a launch does
     * not work and they want to try it from a shell.
     */
    fun asAmStartCommand(request: Request): String = buildString {
        append("am start -n $PACKAGE/$ACTIVITY -a $ACTION")
        append(" --ei $EXTRA_APP_ID ${request.appId}")
        append(" --es $EXTRA_GAME_SOURCE ${request.source.name}")
    }
}
