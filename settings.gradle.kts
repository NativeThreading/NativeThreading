pluginManagement {
    repositories {
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
        maven { name = "NeoForge"; url = uri("https://maven.neoforged.net/releases") }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
    }
}

plugins {
}

rootProject.name = "NativeThreading"

include(":fabric")
include(":neoforge")
include(":core")
include(":explosion")

project(":core").buildFileName = "build.included.gradle.kts"
project(":explosion").buildFileName = "build.included.gradle.kts"
