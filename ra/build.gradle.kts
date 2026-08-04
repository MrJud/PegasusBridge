// Phase 3+6 target — stub vuoto
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.pegasus.bridge.ra"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Same reason as :core — the console table has one home, under shared/.
    sourceSets["main"].java.srcDir("../shared/ra/src/android-shared/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
