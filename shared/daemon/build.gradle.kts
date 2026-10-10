plugins {
    application
}

dependencies {
    api(project(":core"))
    api(project(":scrapers"))
    api(project(":ra"))
    api(project(":hasher"))
    api(project(":video"))
    api(project(":pegasus"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass.set("com.pegasus.bridge.daemon.BridgeDaemon")
}

/**
 * Compiles the rcheevos JNI library for the host.
 *
 * Wired into the build rather than left as a manual step: the library used to be
 * produced by hand into a build/ directory, where `gradle clean` deleted it and
 * the daemon then started without a ROM hasher for no visible reason.
 */
val nativeDir = file("$rootDir/native/out")

val buildNative by tasks.registering(Exec::class) {
    val script = file("$rootDir/native/build.sh")
    val cpp = file("$rootDir/../hasher/src/main/cpp")
    val libraryName = System.mapLibraryName("rahasher")
    onlyIf { script.exists() && !org.gradle.internal.os.OperatingSystem.current().isWindows }
    // What the script reads, file by file, and the two files it writes. The
    // whole of native/ used to be the input and native/out the output, one
    // inside the other, so that the library the task had just written was a
    // change to what it is built from. rc_compat.h is the one header the
    // sources include from above their own folder, and rc_version.h and
    // pb_patchlevel.h are where the JNI file takes the version it reports.
    inputs.file(script)
    inputs.file(File(cpp, "rahasher_jni.c"))
    inputs.file(File(cpp, "pb_patchlevel.h"))
    inputs.file(File(cpp, "rahasher.sources"))
    inputs.file(File(cpp, "jni.map"))
    inputs.dir(File(cpp, "rcheevos/src/rhash"))
    inputs.dir(File(cpp, "rcheevos/include"))
    inputs.file(File(cpp, "rcheevos/src/rc_compat.h"))
    inputs.file(File(cpp, "rcheevos/src/rc_version.h"))
    outputs.files(File(nativeDir, libraryName),
                  File(nativeDir, libraryName.substringBeforeLast('.') + ".manifest"))
    commandLine("bash", script.absolutePath)
    // build.sh needs a JDK for jni.h; Gradle's own is guaranteed to be one.
    environment("JAVA_HOME", System.getProperty("java.home"))
}

// The native library ships beside the jars, where nativeLibraryCandidates() looks.
tasks.named<Sync>("installDist") {
    dependsOn(buildNative)
    from(nativeDir) { into("lib/native") }
}

/**
 * Runs one scan as the daemon would and writes a row for every file (ScanAudit):
 *
 *     ./gradlew :daemon:audit -PauditArgs='--audit=<folder> --out=<file.tsv> [...]'
 *
 * It does not depend on buildNative, and that is the point of having it. An
 * audit is how one build's hashes are compared with another's, and the library
 * it measures has to be the one that is committed in native/out and that the
 * tests load. installDist compiles a new one over it first, so a daemon
 * installed for the purpose would measure a library nobody has committed, and
 * leave the tracked file changed behind it.
 *
 * The arguments are cut where a space is followed by `--`, and nowhere else: a
 * folder of a library may well have a space in its name. Paths that are not
 * absolute start from shared/.
 */
tasks.register<JavaExec>("audit") {
    group = "verification"
    description = "Scans the folders in -PauditArgs as the daemon would and writes one row per file."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootDir
    environment("PEGASUS_BRIDGE_NATIVE", File(nativeDir, System.mapLibraryName("rahasher")).absolutePath)
    args((findProperty("auditArgs") as String?).orEmpty().trim()
        .split(Regex("\\s+(?=--)")).filter { it.isNotEmpty() })
    doFirst {
        require(args.orEmpty().any { it.startsWith("--audit=") }) {
            "nothing to audit: pass -PauditArgs='--audit=<folder> --out=<file.tsv>'"
        }
    }
}
