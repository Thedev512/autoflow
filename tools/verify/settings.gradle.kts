// Off-device checks that need no Android SDK: core unit tests, plus the editor UI compiled
// and rendered on Compose Desktop (same androidx.compose APIs as the app).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

dependencyResolutionManagement {
    repositories {
        // Google's copy of Maven Central first: the main host rate-limits busy build machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "autoflow-verify"

include(":core")
project(":core").projectDir = file("../../core")

include(":ui-desktop")
include(":ui-run")
include(":android-check")
include(":app-check")
