plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    // The half of :pegasus that is the same on both shells — the metadata
    // parser, the media exporter, the export manifest and the launch
    // preferences. Discovery is not among them and is not here: see
    // AndroidEmulators for what replaces it.
    sourceSets["main"].java.srcDir("../shared/pegasus/src/android-shared/kotlin")

    namespace  = "com.pegasus.bridge.pegasus"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// AndroidEmulatorsTest reads src/main/AndroidManifest.xml at runtime to check it
// against the probe table. Gradle cannot infer that, and without saying so the
// task reports UP-TO-DATE after the manifest changes — which would have hidden
// the very drift the test exists to catch.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // ArtifactKey, BridgeLog, FuzzyMatch and Paths. Nothing else: the shared
    // half of this module reads and writes files and speaks to no network.
    implementation(project(":core"))
    // For ScreenScraperSystemMap only: CollectionInference takes the system
    // table as a parameter precisely so the shared half needs no scraper, and
    // this is the shell wiring it from the module that owns the file's format.
    implementation(project(":media"))
    implementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
