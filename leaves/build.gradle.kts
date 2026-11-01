plugins {
    id("net.fabricmc.fabric-loom")
    `java`
}

import org.gradle.api.file.DuplicatesStrategy

// Same version scheme as the root releaseJar: tag when on a release tag, short SHA otherwise,
// falling back to the gradle property. Keeps the leaves artifact named after the same release.
fun gitVersion(): String {
    val tag = try { ProcessBuilder("git", "describe", "--tags", "--exact-match").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
    if (tag.isNotEmpty() && tag.startsWith("v")) return tag.substring(1)
    val sha = try { ProcessBuilder("git", "rev-parse", "--short=8", "HEAD").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
    if (sha.isNotEmpty()) return sha
    return providers.gradleProperty("mod_version").get()
}

version = gitVersion()
group = providers.gradleProperty("maven_group").get()

base { archivesName = providers.gradleProperty("archives_base_name").get() + "-leaves" }

repositories {
    mavenCentral()
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
}

dependencies {
    // Minecraft classes only: the Leaves runtime is Mojang-mapped, so the classes compiled here
    // (core/common + explosion/common + mixins) are shipped without Loom's intermediary remap.
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")

    // Runtime-provided by the Leaves server: leavesclip's native mixin loader (Mixin 0.8.7),
    // the Paper API, and logging. Never bundle them into the plugin jar.
    compileOnly("net.fabricmc:sponge-mixin:${providers.gradleProperty("mixin_version").get()}")
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paper_api_version").get()}")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("org.slf4j:slf4j-api:${providers.gradleProperty("slf4j_version").get()}")
}

sourceSets {
    main {
        java {
            srcDir("../core/common/src/main/java")
            srcDir("../explosion/common/src/main/java")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val versionString = version.toString()
    val apiVersion = providers.gradleProperty("leaves_api_version").get()
    inputs.property("version", versionString)
    inputs.property("apiVersion", apiVersion)

    filesMatching("leaves-plugin.json") {
        expand("version" to versionString, "apiVersion" to apiVersion)
    }
}

// A Paper-family server runs Mojang-mapped classes; Loom's remap step (aimed at Fabric's
// intermediary runtime) would break every net.minecraft reference and is not applicable.
tasks.matching { it.name == "remapJar" || it.name == "remapSourcesJar" }.configureEach {
    enabled = false
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("${providers.gradleProperty("archives_base_name").get()}-leaves")
    archiveVersion.set(version.toString())
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))

    val projectName = project.name
    inputs.property("projectName", projectName)
    from(rootProject.file("LICENSE")) {
        rename { "${it}_$projectName" }
    }
}
