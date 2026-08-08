dependencies {
    api(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // 7z archives (LGPLv2.1); zip comes from the JDK
    implementation("org.apache.commons:commons-compress:1.26.1")
    // commons-compress declares xz as an *optional* dependency, so Gradle does
    // not fetch it and 7z archives using LZMA2 — most of them — fail at runtime
    // with NoClassDefFoundError rather than at build time.
    implementation("org.tukaani:xz:1.9")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// src/android-shared holds the files the Android shell compiles too. Keeping them in
// their own source root is what lets both shells use the one copy instead of a clone
// that drifts — which is exactly what happened to Config.kt.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")
