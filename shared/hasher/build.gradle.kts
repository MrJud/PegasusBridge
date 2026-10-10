import java.time.Duration

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

// GoldenHashTest runs the real rcheevos, and it must be the librahasher in
// native/out — the one native/build.sh writes there, installDist ships and the
// daemon loads — not whatever a library search turns up first, which could be an
// older build that still passes. Declared as an input so a rebuilt library runs
// the tests again — as a file collection, which may be empty, because a single
// input file that is missing is a Gradle validation error, and a missing library
// has to be the test's failure, with the test's message.
//
// -Ppegasus.nativeLibrary=<file> names another library to run them on: one that
// build.sh has just written somewhere else, which is how CI tests a build of its
// own without writing over the committed one. A path that is not absolute starts
// from shared/. A property that names nothing loadable is not passed over for
// the committed library: the tests fail on it. A slip in the property's own
// name is another matter, since Gradle takes any -P without a word: the run
// would be an ordinary one on the committed library, looking like a run on the
// library named. So a -P that begins pegasus. and is not this one stops the
// build. A slip before that dot is still not seen; CI, where it would matter,
// reads which library was loaded off the test's own output.
val nativeLibraryProperty = "pegasus.nativeLibrary"
gradle.startParameter.projectProperties.keys
    .filter { it.startsWith("pegasus.") && it != nativeLibraryProperty }
    .let { strangers ->
        if (strangers.isNotEmpty())
            throw GradleException("-P${strangers.joinToString(", -P")}: no such property. " +
                "The one there is, is -P$nativeLibraryProperty=<file>")
    }

tasks.named<Test>("test") {
    val library = (findProperty(nativeLibraryProperty) as String?)?.let { rootProject.file(it) }
        ?: rootProject.file("native/out/" + System.mapLibraryName("rahasher"))
    inputs.files(library).withPropertyName("nativeLibrary").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("pegasus.bridge.nativeLibrary", library.absolutePath)

    // AuditReportListsTest reads the audit's report script and holds its
    // lists to the console table and the ledger's states. The script is no
    // source of this module, so it is named here: without that an edit to it
    // alone leaves the task up to date, and the test passes on the run
    // before the edit.
    inputs.file(rootProject.file("tools/audit_report.py")).withPropertyName("auditReportScript")
        .withPathSensitivity(PathSensitivity.NONE)

    // NativeCrashReproTest makes each native call in a JVM it starts itself,
    // since the files it hands over are ones that used to end the process, and
    // that JVM needs the tests' class path. It is given here and not read off
    // the worker's java.class.path: what Gradle starts its workers with is its
    // own affair. Worked out when the task runs, so that configuring the build
    // resolves nothing.
    val testClasspath = sourceSets["test"].runtimeClasspath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dpegasus.bridge.testClasspath=${testClasspath.asPath}")
    })

    // NativeConsoleHashTest and the golden tests call the library inside the
    // worker's own JVM, where a call that never comes back has nobody to end
    // it. The files they hand over are ones rcheevos is done with in
    // milliseconds, and the whole task in well under a minute; were one of
    // them to hang all the same, this ends the task with a failure, where CI
    // would have held it until the job's own limit.
    timeout.set(Duration.ofMinutes(10))
}

// src/android-shared holds the files the Android shell compiles too. Keeping them in
// their own source root is what lets both shells use the one copy instead of a clone
// that drifts — which is exactly what happened to Config.kt.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")
