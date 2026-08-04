import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.0" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Bytecode level 17, so the Android build (which targets 17) can consume
    // these artifacts. Deliberately not `jvmToolchain(17)`: that would demand a
    // JDK 17 installation, and the bytecode target is what actually governs
    // compatibility — the compiler itself can be any newer JDK.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    // Repositories are declared once in settings.gradle.kts, which sets
    // FAIL_ON_PROJECT_REPOS — adding them here as well is an error.

    dependencies {
        add("testImplementation", kotlin("test"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
