pluginManagement {
    repositories {
        maven("https://maven.canvasmc.io/releases") { name = "canvasmc" }
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
        maven { name = "NeoForge"; url = uri("https://maven.neoforged.net/releases") }
        maven { name = "JitPack"; url = uri("https://jitpack.io") }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
    }
}
rootProject.name = "core"
include(":common", ":fabric", ":neoforge")
