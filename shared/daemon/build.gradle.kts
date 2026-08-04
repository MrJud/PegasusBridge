plugins {
    application
}

dependencies {
    api(project(":core"))
    api(project(":scrapers"))
    api(project(":ra"))
    api(project(":hasher"))
    api(project(":video"))
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
    onlyIf { script.exists() && !org.gradle.internal.os.OperatingSystem.current().isWindows }
    inputs.dir("$rootDir/native")
    inputs.dir("$rootDir/../hasher/src/main/cpp/rcheevos/src/rhash")
    outputs.dir(nativeDir)
    commandLine("bash", script.absolutePath)
    // build.sh needs a JDK for jni.h; Gradle's own is guaranteed to be one.
    environment("JAVA_HOME", System.getProperty("java.home"))
}

// The native library ships beside the jars, where nativeLibraryCandidates() looks.
tasks.named<Sync>("installDist") {
    dependsOn(buildNative)
    from(nativeDir) { into("lib/native") }
}
