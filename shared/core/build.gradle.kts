dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.json:json:20240303")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

    // src/android-shared holds the files the Android shell compiles too.
    // Keeping them in their own source root is what lets both shells use the
    // one copy: the rest of this module has names that clash with Android's.
sourceSets["main"].kotlin.srcDir("src/android-shared/kotlin")
