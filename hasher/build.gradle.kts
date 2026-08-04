plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
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
