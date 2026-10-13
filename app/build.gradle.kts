import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from local.properties (git-ignored) or the
// environment, never from this file: the keystore and its passwords are the
// maintainer's, and a repository is the wrong place for either.
//
//   RELEASE_STORE_FILE=/absolute/path/to/pegasus-bridge.jks
//   RELEASE_STORE_PASSWORD=...
//   RELEASE_KEY_ALIAS=pegasus-bridge
//   RELEASE_KEY_PASSWORD=...
//
// Create the keystore yourself with:
//   keytool -genkeypair -v -keystore pegasus-bridge.jks -alias pegasus-bridge \
//           -keyalg RSA -keysize 4096 -validity 10000
//
// With none of it set the release APK is built unsigned, which is fine for
// testing and cannot be installed by a user.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    (localProps.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

val hasReleaseSigning = listOf(
    "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"
).all { signingValue(it) != null }

android {
    namespace  = "com.pegasus.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pegasus.bridge"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile     = file(signingValue("RELEASE_STORE_FILE")!!)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias      = signingValue("RELEASE_KEY_ALIAS")
                keyPassword   = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "PegasusBridge: no release signing configured — the APK will be unsigned. " +
                    "See the comment at the top of app/build.gradle.kts.")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/**
 * Fails the build of an APK that does not carry the GPL's text.
 *
 * The app is under the GPL, which asks that its text travel with every copy,
 * and the notices inside the APK say it does, as LICENSE. The file gets there
 * from another module (:hasher copies the repository's LICENSE into its
 * assets), and an APK without it installs and runs like any other: nothing
 * but a look inside the archive tells the two apart. So the look is taken at
 * every build, of every APK the variant produced, and the bytes are held to
 * the file at the top of the repository.
 */
abstract class ApkLicenceCheck : DefaultTask() {
    @get:InputDirectory
    abstract val apks: DirectoryProperty

    @get:InputFile
    abstract val licence: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = licence.get().asFile.readBytes()
        val built = apks.get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (built.isEmpty()) throw GradleException("no APK under ${apks.get().asFile}: nothing was checked")
        for (apk in built) {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/LICENSE")
                    ?: throw GradleException("${apk.name} has no assets/LICENSE: the GPL's text must travel with the app")
                val found = zip.getInputStream(entry).use { it.readBytes() }
                if (!found.contentEquals(expected)) {
                    // Two texts of one length say nothing by their sizes.
                    val at = (0 until minOf(found.size, expected.size)).firstOrNull { found[it] != expected[it] }
                    throw GradleException("assets/LICENSE in ${apk.name} is not the repository's LICENSE " +
                                          "(${found.size} bytes, and the file has ${expected.size}" +
                                          (at?.let { "; the first that differs is byte $it" } ?: "") + ")")
                }
            }
        }
    }
}

// Part of assembling, so that no APK is handed over unchecked: there is no
// separate step to forget. And of installing, which packages an APK and puts
// it on a device without assembling anything.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<ApkLicenceCheck>("check${name}ApkLicence") {
            apks.set(variant.artifacts.get(SingleArtifact.APK))
            licence.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "install$name" }.configureEach { dependsOn(check) }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":media"))
    implementation(project(":hasher"))
    implementation(project(":ra"))
    implementation(project(":video"))
    implementation(project(":pegasus"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
