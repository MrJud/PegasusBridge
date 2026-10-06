// Pure Kotlin/JVM half of PegasusBridge.
//
// Kept as its own Gradle build, not as modules of the Android one, for a
// practical reason: configuring an Android project needs the Android SDK, so
// folding these in would make them unbuildable anywhere the SDK is missing —
// including the Linux box this port is developed on. As a standalone build they
// compile and test with nothing but a JDK.
//
// The Android build does not include this one. Five of its modules add a
// src/android-shared/kotlin directory from here to their own sources, so what
// both shells run is compiled twice from one copy: :core, :hasher, :ra and
// :pegasus take the one of the module of the same name, and :media takes that
// of :scrapers. :app and :video take none, and :video here has none to give.
// The desktop daemon is the :daemon module below.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")  // NewPipeExtractor
    }
}

rootProject.name = "pegasus-bridge-shared"

include(":core")
include(":scrapers")
include(":ra")
include(":hasher")
include(":video")
include(":pegasus")
include(":daemon")
