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
// The one file of native/out that this system loads: librahasher.so,
// librahasher.dylib or rahasher.dll.
val nativeLibraryName: String = System.mapLibraryName("rahasher")

/**
 * On Windows the script needs two programs that Windows does not come with: a
 * bash to run it, Git's or MSYS2's, and the mingw-w64 compiler. Each is looked
 * for along PATH, here, and bash is then started by the file that was found.
 * Asked to start `bash`, Windows looks in its own folders before PATH, and
 * what it has there starts a Linux beside Windows, in which a path of this
 * checkout names nothing.
 */
val onWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
fun onPath(program: String): File? {
    val windowsOwn = File(System.getenv("SystemRoot") ?: "C:\\Windows")
    return System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "$program.exe") }
        .firstOrNull { it.isFile && !it.startsWith(windowsOwn) }
}
val windowsBash = if (onWindows) onPath("bash") else null
val windowsCompiler = if (onWindows) onPath("x86_64-w64-mingw32-gcc") else null

val buildNative by tasks.registering(Exec::class) {
    val script = file("$rootDir/native/build.sh")
    val cpp = file("$rootDir/../hasher/src/main/cpp")
    val libraryName = nativeLibraryName
    // On Windows the library is built where there is something to build it
    // with, and is otherwise left as it is found: a DLL built elsewhere and
    // put in native/out, or none.
    onlyIf { script.exists() && (!onWindows || (windowsBash != null && windowsCompiler != null)) }
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
    inputs.file("$rootDir/native/include/win32/jni_md.h")
    inputs.dir(File(cpp, "rcheevos/src/rhash"))
    inputs.dir(File(cpp, "rcheevos/include"))
    inputs.file(File(cpp, "rcheevos/src/rc_compat.h"))
    inputs.file(File(cpp, "rcheevos/src/rc_version.h"))
    outputs.files(File(nativeDir, libraryName),
                  File(nativeDir, libraryName.substringBeforeLast('.') + ".manifest"))
    commandLine(windowsBash?.absolutePath ?: "bash", script.absolutePath)
    // build.sh needs a JDK for jni.h; Gradle's own is guaranteed to be one.
    environment("JAVA_HOME", System.getProperty("java.home"))
}

// The native library ships beside the jars, where nativeLibraryCandidates() looks.
// This system's and no other: native/out has the library that is committed,
// which is Linux's, and on Windows that file went into the distribution in
// place of the DLL nothing had built, to be found by nobody. Where there is
// no library for this system the distribution has none, and says so; the
// daemon it starts scrapes and cannot scan, and package.sh makes no bundle
// of it.
tasks.named<Sync>("installDist") {
    dependsOn(buildNative)
    from(nativeDir) {
        include(nativeLibraryName)
        into("lib/native")
    }
    val library = File(nativeDir, nativeLibraryName)
    doLast {
        if (!library.isFile)
            logger.warn("No ${library.name} in ${library.parentFile}: this distribution has no ROM hasher. " +
                "native/build.sh builds it" +
                (if (onWindows) ", from Git Bash or MSYS2 with x86_64-w64-mingw32-gcc on PATH" else "") + ".")
    }
}

/**
 * Runs one scan as the daemon would and writes a row for every file (ScanAudit):
 *
 *     ./gradlew :daemon:audit -PauditArgs='--audit=<folder> --out=<file.tsv> [--keep=<dir>] [...]'
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
    environment("PEGASUS_BRIDGE_NATIVE", File(nativeDir, nativeLibraryName).absolutePath)
    args((findProperty("auditArgs") as String?).orEmpty().trim()
        .split(Regex("\\s+(?=--)")).filter { it.isNotEmpty() })
    doFirst {
        require(args.orEmpty().any { it.startsWith("--audit=") }) {
            "nothing to audit: pass -PauditArgs='--audit=<folder> --out=<file.tsv>'"
        }
    }
}
