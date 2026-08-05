import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.141"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()
base { archivesName = "core-neoforge" }

neoForge {
    version = providers.gradleProperty("neoforge_version").get()
}

java.toolchain.languageVersion = JavaLanguageVersion.of(26)

repositories {
    mavenCentral()
}

dependencies {
}

sourceSets {
    main {
        java { srcDir("../common/src/main/java") }
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val version = version
    inputs.property("version", version)
    filesMatching("META-INF/neoforge.mods.toml") { expand("version" to version) }
    from("../fabric/src/main/resources") {
        exclude("fabric.mod.json")
        into("")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)
    from("LICENSE") { rename { "${it}_$projectName" } }
}
