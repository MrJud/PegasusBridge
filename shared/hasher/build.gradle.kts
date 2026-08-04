dependencies {
    api(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // 7z archives (LGPLv2.1); zip comes from the JDK
    implementation("org.apache.commons:commons-compress:1.26.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
