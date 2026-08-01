pluginManagement {
    repositories {
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
        maven { name = "NeoForge"; url = uri("https://maven.neoforged.net/releases") }
        maven("https://maven.canvasmc.io/releases") { name = "canvasmc" }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
    }
}

rootProject.name = "vectorial"
include(":common", ":fabric", ":neoforge")
