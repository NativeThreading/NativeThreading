import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.141"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base { archivesName = "${providers.gradleProperty("archives_base_name").get()}-neoforge" }

neoForge {
    version = providers.gradleProperty("neoforge_version").get()
}

java.toolchain.languageVersion = JavaLanguageVersion.of(26)

repositories {
    mavenCentral()
    maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation(project(":core")) { isTransitive = false }
    implementation(project(":explosion")) { isTransitive = false }
    implementation(project(":hopper")) { isTransitive = false }
    implementation(project(":redstone")) { isTransitive = false }
    implementation(project(":vectorial")) { isTransitive = false }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val version = version
    inputs.property("version", version)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to version)
    }

    val subprojects = listOf(
        "parallel-core",
        "explosion",
        "hopper",
        "redstone",
        "vectorial",
    )
    for (sub in subprojects) {
        from("../$sub/neoforge/src/main/resources") {
            into("")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 26
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_26
    targetCompatibility = JavaVersion.VERSION_26
}

tasks.named<Jar>("sourcesJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val projectName = project.name
    inputs.property("projectName", projectName)

    val bundledSubprojectJars = listOf(
        project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":explosion").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":hopper").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":redstone").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":vectorial").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    )

    dependsOn(bundledSubprojectJars)
    from(bundledSubprojectJars.map { zipTree(it) })

    from("LICENSE") {
        rename { "${it}_$projectName" }
    }

    manifest {
        attributes(
            "Agent-Class" to "com.github.uright008.vec.core.VectorialAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}
