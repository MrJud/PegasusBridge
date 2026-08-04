plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.pegasus.bridge.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // FuzzyMatch is plain Kotlin and the desktop shell needs the identical
    // behaviour, so both shells compile the one copy under shared/ rather than
    // each keeping a version that drifts.
    sourceSets["main"].java.srcDir("../shared/core/src/android-shared/kotlin")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
