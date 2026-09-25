pluginManagement {
    repositories {
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "snapmatica-neoforge"
