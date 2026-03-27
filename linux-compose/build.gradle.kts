import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.tasks.Jar
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "com.cmux"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // JNA for native PTY access
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.cmux.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "cmux"
            packageVersion = "0.1.0"
            description = "cmux - Terminal with vertical tabs and AI notifications"
            vendor = "cmux"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }

        buildTypes.release {
            proguard {
                isEnabled = false
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

val stableUberJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Build a stable launcher jar with manifest Class-Path"
    archiveFileName.set("cmux-linux-x64-${project.version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tmp/stable-uber"))

    manifest {
        attributes["Main-Class"] = "com.cmux.MainKt"
    }

    from(sourceSets.main.get().output)
    dependsOn("classes")

    doFirst {
        val cp = configurations.runtimeClasspath.get()
            .files
            .sortedBy { it.name }
            .joinToString(" ") { "lib/${it.name}" }
        manifest.attributes["Class-Path"] = cp
    }
}

val overwriteComposeUberJar by tasks.registering {
    dependsOn(stableUberJar)

    doLast {
        val source = stableUberJar.get().archiveFile.get().asFile.toPath()
        val outputDir = layout.buildDirectory.dir("compose/jars").get().asFile.toPath()
        val outputLibDir = outputDir.resolve("lib")
        Files.createDirectories(outputDir)
        Files.createDirectories(outputLibDir)

        configurations.runtimeClasspath.get()
            .files
            .sortedBy { it.name }
            .forEach { file ->
                Files.copy(
                    file.toPath(),
                    outputLibDir.resolve(file.name),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

        val target = outputDir.resolve("cmux-linux-x64-${project.version}.jar")
        val temp = outputDir.resolve(".cmux-linux-x64-${project.version}.jar.tmp-${System.nanoTime()}")

        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

tasks.matching { it.name == "packageUberJarForCurrentOS" }.configureEach {
    finalizedBy(overwriteComposeUberJar)
}
