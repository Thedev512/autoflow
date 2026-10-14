import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Runs the app's Compose UI (everything under ui/ except ui/android) on Compose Desktop 1.6.0-beta01 (the last one
// whose dependencies are all on Maven Central): renders screenshots at Galaxy Tab size and
// drives the editor with simulated touches. :ui-desktop compile-checks the same code on 1.7.3.
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
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("app-ui"))
    sourceSets["main"].kotlin.srcDir("src/shim/kotlin")
}

// Copy the app's UI sources, adding imports for the shimmed Material3 color roles.
val shimImports = listOf("Lowest", "Low", "", "High", "Highest")
    .joinToString("") { "\nimport androidx.compose.material3.surfaceContainer$it" }
val copyAppUi by tasks.registering(Sync::class) {
    from("../../../app/src/main/java") {
        include("dev/autoflow/ui/**")
        exclude("dev/autoflow/ui/android/**")
    }
    into(layout.buildDirectory.dir("app-ui"))
    filter { line: String -> if (line.startsWith("package ")) line + shimImports else line }
}
tasks.named("compileKotlin") { dependsOn(copyAppUi) }

val cmp = (findProperty("cmp") as String?) ?: "1.6.0-beta01"
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$cmp")
    implementation("org.jetbrains.compose.material3:material3-desktop:$cmp")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:$cmp")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:$cmp")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("screenshots.dir", rootProject.layout.projectDirectory.dir("../../docs/screenshots").asFile.absolutePath)
    systemProperty("skiko.renderApi", "SOFTWARE")
    jvmArgs("-Djava.awt.headless=true")
}
