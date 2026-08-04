// Pure Kotlin/JVM half of PegasusBridge.
//
// Kept as its own Gradle build, not as modules of the Android one, for a
// practical reason: configuring an Android project needs the Android SDK, so
// folding these in would make them unbuildable anywhere the SDK is missing —
// including the Linux box this port is developed on. As a standalone build they
// compile and test with nothing but a JDK.
//
// The Android app consumes them through `includeBuild("shared")`; the desktop
// daemon will depend on them directly.

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
include(":daemon")
