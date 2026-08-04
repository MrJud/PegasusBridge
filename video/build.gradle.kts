plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.pegasus.bridge.video"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }  // ARM64 only (Odin2 target)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))

    // Media3 / ExoPlayer stack
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // AndroidX (needed by VideoPlayerActivity)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Networking + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // NewPipe (YouTube stream resolution — GPLv3)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
}
