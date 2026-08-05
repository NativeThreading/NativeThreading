plugins {
    id("net.fabricmc.fabric-loom")
    `maven-publish`
}

import org.gradle.api.file.DuplicatesStrategy

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base { archivesName = providers.gradleProperty("archives_base_name").get() }

repositories {
    mavenCentral()
}

loom {
    mods {
        register("native-threading") {
            sourceSet(sourceSets.main.get())
        }
    }
    runs {
        named("server") {
        }
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "native-threading-gametest"
        enableGameTests = true
        eula = true
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("org.javassist:javassist:3.30.2-GA")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")
    include("org.javassist:javassist:3.30.2-GA")
    include("net.bytebuddy:byte-buddy-agent:1.15.11")
}

// Aggregate all submodule sources into the root JAR
sourceSets {
    main {
        java {
            srcDir("../core/common/src/main/java")
            srcDir("../explosion/common/src/main/java")
            srcDir("../redstone/common/src/main/java")
            srcDir("../vectorial/common/src/main/java")
            srcDir("../vectorial/common/src/generated/java")
        }
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val version = version
    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }

    // Merge submodule fabric resources (mixin configs, exclude their fabric.mod.json)
    from("../core/fabric/src/main/resources") { exclude("fabric.mod.json"); into("") }
    from("../explosion/fabric/src/main/resources") { exclude("fabric.mod.json"); into("") }
    from("../redstone/fabric/src/main/resources") { exclude("fabric.mod.json"); into("") }
    from("../vectorial/fabric/src/main/resources") { exclude("fabric.mod.json"); into("") }
    from(project(":vectorial").tasks.named<Jar>("agentJar").flatMap { it.archiveFile }) {
        into("META-INF")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.compileJava {
    dependsOn(":vectorial:generateFields")
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.named("sourcesJar") {
    dependsOn(":vectorial:generateFields")
}

tasks.named("sourcesJar", Jar::class) {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)

    from("LICENSE") {
        rename { "${it}_$projectName" }
    }

}
