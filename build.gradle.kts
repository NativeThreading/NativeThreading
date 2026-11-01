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

// ── Architecture discipline (docs/dev/architecture-discipline.md M2/M4) ────
// M4: every *.mixins.json must be strict JSON (no trailing commas) and every
// listed class must exist as a source file. M2: mixin classes over 250 lines
// are a hard failure (split into implementation classes).
val leavesPluginJson = file("leaves/src/main/resources/leaves-plugin.json")
val mixinConfigFiles = fileTree(rootDir) {
    include("*/fabric/src/main/resources/**/*.mixins.json")
    include("*/neoforge/src/main/resources/**/*.mixins.json")
    include("*/leaves/src/main/resources/**/*.mixins.json")
}

tasks.register("validateMixinDiscipline") {
    group = "verification"
    description = "Architecture discipline M2/M4: strict mixin JSON, classes exist, size budget"
    inputs.files(mixinConfigFiles)
    inputs.file(leavesPluginJson)
    doLast {
        val errors = mutableListOf<String>()
        val slurper = groovy.json.JsonSlurper()
        for (json in mixinConfigFiles) {
            val text = json.readText()
            if (Regex(",\\s*[\\]}]").containsMatchIn(text)) {
                errors += "${json.relativeTo(rootDir).path}: trailing comma before ] or }"
            }
            val root = try {
                slurper.parseText(text) as Map<*, *>
            } catch (e: Exception) {
                errors += "${json.relativeTo(rootDir).path}: unparseable JSON: ${e.message}"
                continue
            }
            val pkg = root["package"] as String
            for (m in (root["mixins"] as List<*>)) {
                val cls = m as String
                val relPath = (pkg + "." + cls).replace('.', '/') + ".java"
                val source = fileTree(rootDir) { include("**/" + relPath) }.files.firstOrNull()
                if (source == null) {
                    errors += "${json.relativeTo(rootDir).path}: listed class ${pkg}.$cls has no source file"
                } else {
                    val lines = source.readLines().size
                    if (lines > 250) {
                        errors += "$relPath: $lines lines > 250 (M2: mixin must be injection-only, split into implementation classes)"
                    }
                }
            }
        }

        // Leaves packaging: leavesclip extracts the whole `mixin.package-name` subtree from the
        // plugin jar into its mixins jar, so the plugin main class must live outside it and every
        // mixin config must resolve inside it.
        val leavesJson = try {
            slurper.parseText(leavesPluginJson.readText()) as Map<*, *>
        } catch (e: Exception) {
            null.also { errors += "leaves-plugin.json: unparseable JSON: ${e.message}" }
        }
        if (leavesJson != null) {
            val features = leavesJson["features"] as? Map<*, *>
            val required = (features?.get("required") as? List<*>)?.map { it.toString() } ?: emptyList()
            if (!required.contains("mixin")) {
                errors += "leaves-plugin.json: features.required must contain \"mixin\""
            }
            val mixin = leavesJson["mixin"] as? Map<*, *>
            val packageName = mixin?.get("package-name")?.toString()
            val mainClass = leavesJson["main"]?.toString()
            if (packageName.isNullOrBlank()) {
                errors += "leaves-plugin.json: mixin.package-name is required"
            } else {
                if (mainClass != null && (mainClass == packageName || mainClass.startsWith("$packageName."))) {
                    errors += "leaves-plugin.json: main class $mainClass lives inside mixin.package-name $packageName and would be extracted from the plugin jar"
                }
                val listedConfigs = (mixin["mixins"] as? List<*>)?.map { it.toString() } ?: emptyList()
                if (listedConfigs.isEmpty()) {
                    errors += "leaves-plugin.json: mixin.mixins must list the mixin configs"
                }
                for (configName in listedConfigs) {
                    val configFile = file("leaves/src/main/resources/$configName")
                    if (!configFile.isFile) {
                        errors += "leaves-plugin.json: mixin config $configName not found under leaves/src/main/resources"
                        continue
                    }
                    val config = try {
                        slurper.parseText(configFile.readText()) as Map<*, *>
                    } catch (e: Exception) {
                        continue // already reported by the mixin config loop above
                    }
                    val configPackage = config["package"]?.toString() ?: ""
                    if (!(configPackage == packageName || configPackage.startsWith("$packageName."))) {
                        errors += "leaves-plugin.json: $configName package $configPackage is outside mixin.package-name $packageName"
                    }
                    if (config["target"]?.toString() != "@env(PREINIT)") {
                        errors += "leaves-plugin.json: $configName must target @env(PREINIT), the leavesclip launcher never leaves that phase"
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException("Architecture discipline violations:\n" + errors.joinToString("\n"))
        }
    }
}
tasks.named("check") {
    dependsOn("validateMixinDiscipline")
}

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
    // Loader shells: the Leaves plugin jar is a separate artifact (no Fabric remap, native
    // leavesclip mixin loader), shipped next to the Fabric aggregate jar in build/libs.
    dependsOn(":leaves:jar")
}

publishing {
    repositories {
        mavenLocal()
    }
}
