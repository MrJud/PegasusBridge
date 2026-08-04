pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")  // NewPipeExtractor (YouTube stream resolution)
    }
}

rootProject.name = "PegasusBridge"
include(":app", ":core", ":media", ":hasher", ":ra", ":video")
