dependencies {
    api(project(":core"))
    // Discovery reads what a scan already knows about a ROM — its canonical path
    // and the collection it sits in — rather than re-deriving either.
    implementation(project(":hasher"))
    // Only for CancellationException, which every catch(Throwable) in this
    // project has to let through — see the archive-hashing comments for the
    // Error that taught it.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// src/android-shared holds the files the Android shell compiles too. Keeping them in
// their own source root is what lets both shells use the one copy instead of a clone
// that drifts — which is exactly what happened to Config.kt.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")
