import java.util.Properties

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

dependencies {
    implementation(project(":core"))
    implementation(project(":media"))
    implementation(project(":hasher"))
    implementation(project(":ra"))
    implementation(project(":video"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
