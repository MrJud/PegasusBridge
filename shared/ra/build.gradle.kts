dependencies {
    api(project(":core"))
    // A match is answered from what the scan settled about the file, and
    // the ledger and the lists of consoles are the hasher's.
    api(project(":hasher"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

    // src/android-shared holds the files the Android shell compiles too.
    // Keeping them in their own source root is what lets both shells use the
    // one copy: the rest of this module has names that clash with Android's.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")
