import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Type-checks the Android-only runtime code (service, receivers, event sources, block executors)
// against the real Android 14 API, using Robolectric's android-all jar from Maven Central.
// It is compiled only, never run: android-all is the framework's API surface, not a device.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
    sourceSets["main"].kotlin.srcDir("../../../app/src/main/java")
    sourceSets["main"].kotlin.filter.include("dev/autoflow/runtime/**", "dev/autoflow/data/Prefs.kt")
}

dependencies {
    implementation(project(":core"))
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
}
