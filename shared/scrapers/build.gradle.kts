dependencies {
    api(project(":core"))
    // ScreenScraper identifies a game by the ROM's digest, so the dispatcher has to be
    // able to compute one. Only `PlainRomHasher` is used from here — never the native
    // rcheevos path, which is optional on desktop and would take this whole source down
    // on any machine missing the library, for a reason unrelated to ScreenScraper.
    implementation(project(":hasher"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// src/android-shared holds the files the Android shell compiles too. Keeping them in
// their own source root is what lets both shells use the one copy instead of a clone
// that drifts — which is exactly what happened to Config.kt.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")
