import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    base
    `maven-publish`
    id("net.fabricmc.fabric-loom") apply false
}

val modArchiveName = "native-threading-core"
val modVersion = providers.gradleProperty("mod_version")

group = providers.gradleProperty("maven_group").get()
version = modVersion.get()

val releaseJar by tasks.registering(Jar::class) {
    dependsOn(":fabric:jar", ":neoforge:jar")
    archiveBaseName = modArchiveName
    archiveVersion = modVersion
    destinationDirectory = layout.buildDirectory.dir("libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(zipTree(project(":fabric").layout.buildDirectory.file("libs/$modArchiveName-fabric-${modVersion.get()}.jar")))
    from(zipTree(project(":neoforge").layout.buildDirectory.file("libs/$modArchiveName-neoforge-${modVersion.get()}.jar")))
}

tasks.named("assemble") {
    dependsOn(releaseJar)
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = modArchiveName
            artifact(releaseJar)
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/uright008/core")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("github.actor").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("github.token").orNull
            }
        }
    }
}
