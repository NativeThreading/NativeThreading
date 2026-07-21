import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    id("net.fabricmc.fabric-loom") apply false
    base
    `maven-publish`
}

fun gitVersion(): String {
    val tag = try { ProcessBuilder("git", "describe", "--tags", "--exact-match").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
    if (tag.isNotEmpty() && tag.startsWith("v")) return tag.substring(1)
    val sha = try { ProcessBuilder("git", "rev-parse", "--short=8", "HEAD").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
    if (sha.isNotEmpty()) return sha
    return providers.gradleProperty("mod_version").get()
}

val modVersion = gitVersion()
val modName = providers.gradleProperty("archives_base_name").get()

tasks.register<Jar>("releaseJar") {
    dependsOn(":fabric:jar")
    archiveBaseName = modName
    archiveVersion = modVersion
    destinationDirectory = layout.buildDirectory.dir("libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val fabricJar = project(":fabric").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    from(zipTree(fabricJar))

}

tasks.named("assemble") {
    dependsOn("releaseJar")
}

publishing {
    repositories {
        mavenLocal()
    }
}
