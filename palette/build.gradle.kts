import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    base
    id("net.fabricmc.fabric-loom") apply false
}

val modArchiveName = "palette"
val modVersion = providers.gradleProperty("mod_version")

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
