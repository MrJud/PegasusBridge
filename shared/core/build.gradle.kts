dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.json:json:20240303")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

    // src/android-shared holds the files the Android shell compiles too.
    // Keeping them in their own source root is what lets both shells use the
    // one copy: the rest of this module has names that clash with Android's.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")

// RcConsolesTest reads four files that are no source of this module: the two
// of the vendored rcheevos its table is a copy of, and the two build files
// that say what of rcheevos is compiled. Named here, so that a change to one
// of them alone runs the test again. Left unnamed, the task was up to date
// and the test passed on the run before the change, which is the one case
// the test is there for.
tasks.named<Test>("test") {
    val cpp = rootProject.file("../hasher/src/main/cpp")
    inputs.files(File(cpp, "rcheevos/include/rc_consoles.h"), File(cpp, "rcheevos/src/rhash/hash.c"),
                 File(cpp, "CMakeLists.txt"), rootProject.file("native/build.sh"))
        .withPropertyName("sourcesTheTableIsHeldTo").withPathSensitivity(PathSensitivity.NONE)
    // And BridgeVersionTest reads the build file of the Android app, for the
    // same reason: a version raised there alone has to run the test.
    inputs.file(rootProject.file("../app/build.gradle.kts"))
        .withPropertyName("buildFileTheVersionIsHeldTo").withPathSensitivity(PathSensitivity.NONE)
}
