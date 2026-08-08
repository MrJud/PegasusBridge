plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    sourceSets["main"].java.srcDir("../shared/scrapers/src/android-shared/kotlin")
    namespace  = "com.pegasus.bridge.media"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    // ScreenScraper identifies a game by the ROM's digest, so the dispatcher has to be
    // able to compute one — and the archive codecs it needs are already declared here.
    // Only `PlainRomHasher` is used, never the native rcheevos path.
    implementation(project(":hasher"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
