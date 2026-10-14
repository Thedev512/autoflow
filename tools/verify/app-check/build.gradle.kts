import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Type-checks the WHOLE app source (Compose UI, Room, WorkManager, activities, runtime) in one go:
// Android 14 API from Robolectric's android-all jar, Compose from Compose Multiplatform 1.7.3, and
// small hand-written signature stubs (stubs/) for the few androidx libraries that are only published
// on Google's Maven (activity, lifecycle, room, work). Compile only; Room's generated code is not checked.
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../../../app/src/main/java")
    sourceSets["main"].kotlin.srcDir("stubs")
}

val cmp = "1.7.3"
dependencies {
    implementation(project(":core"))
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    compileOnly("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$cmp")
    compileOnly("org.jetbrains.compose.material3:material3-desktop:$cmp")
    compileOnly("org.jetbrains.compose.material:material-icons-extended-desktop:$cmp")
}
