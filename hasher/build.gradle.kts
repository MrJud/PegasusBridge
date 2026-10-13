plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    sourceSets["main"].java.srcDir("../shared/hasher/src/android-shared/kotlin")
    // The library is compiled from other people's code as well, whose
    // licences ask that their text travel with every copy of it. The app is
    // such a copy, so the texts go into it as they are, as assets:
    // NATIVE-LICENSES.txt, which has them all, and the two files it has
    // them from that are kept in that folder. The licence of PegasusBridge
    // itself, which that file says comes with it as LICENSE, is put beside
    // them further down.
    sourceSets["main"].assets.srcDir("src/main/cpp/licenses")
    namespace  = "com.pegasus.bridge.hasher"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags("") } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/**
 * The repository's LICENSE as an asset of the same name.
 *
 * The GPL asks that its text travel with every copy of the program, and
 * NATIVE-LICENSES.txt, which is in the app already, says it does, as LICENSE.
 * There is one such file, at the top of the repository, and it is copied from
 * there at every build: a second copy kept in this module would be the one
 * nobody remembers on the day the first is edited. The app's own build checks
 * that the file reached the APK (checkDebugApkLicence in app/build.gradle.kts).
 */
abstract class LicenceAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licence: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = assets.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        licence.get().asFile.copyTo(File(dir, "LICENSE"))
    }
}

// A task for each variant: the plugin gives the task its folder when the
// folder is added to a variant's assets, and one task has one folder.
androidComponents {
    onVariants { variant ->
        val licenceAsset = tasks.register<LicenceAsset>("${variant.name}LicenceAsset") {
            licence.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licenceAsset, LicenceAsset::assets)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    // 7z support (LGPLv2.1)
    implementation("org.apache.commons:commons-compress:1.26.1")
    // commons-compress declares xz as an *optional* dependency, so Gradle does
    // not fetch it and 7z archives using LZMA2 — most of them — fail at runtime
    // with NoClassDefFoundError rather than at build time.
    implementation("org.tukaani:xz:1.9")
}
