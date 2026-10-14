import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Compile-checks the app's Compose UI (everything under ui/ except ui/android) against
// Compose Multiplatform 1.7.3, which matches the androidx Compose 1.7 / Material3 1.3 the app uses.
// Compose Desktop 1.7 needs a few androidx jars from Google's Maven to *run*, so the screenshot
// tests live in :ui-run, which uses Compose Desktop 1.6 (self-contained on Maven Central).
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
    sourceSets["main"].kotlin.filter.include("dev/autoflow/ui/**")
    sourceSets["main"].kotlin.filter.exclude("dev/autoflow/ui/android/**")
}

val cmp = "1.7.3"
dependencies {
    implementation(project(":core"))
    compileOnly("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$cmp")
    compileOnly("org.jetbrains.compose.material3:material3-desktop:$cmp")
    compileOnly("org.jetbrains.compose.material:material-icons-extended-desktop:$cmp")
}
