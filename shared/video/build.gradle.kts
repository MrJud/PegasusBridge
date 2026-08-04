dependencies {
    api(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // YouTube stream resolution (GPLv3). Pure Java, so it runs on desktop JVM too.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
}
