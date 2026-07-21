plugins {
    id("net.fabricmc.fabric-loom")
    `maven-publish`
}

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    compileOnly("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    compileOnly("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("org.javassist:javassist:3.30.2-GA")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.0")
}

// ── Code generation: scan Entity.class with javap ──
val generatedDir = file("common/src/generated/java")

val generateFields by tasks.registering {
    val outputFile = file("$generatedDir/com/github/uright008/vec/core/GeneratedFields.java")
    outputs.file(outputFile)

    doLast {
        // Find the Minecraft merged jar from the compile classpath
        val mcJar = configurations.compileClasspath.get().files
            .find { it.name.contains("minecraft-merged") }
            ?: error("Minecraft merged jar not found on classpath")
        val proc = ProcessBuilder("javap", "-p", "-cp", mcJar.absolutePath, "net.minecraft.world.entity.Entity")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()

        val fields = mutableListOf<Triple<String, String, String>>()
        val r = Regex("""^\s+(public|private|protected)\s+(double|float|int|boolean|net\.minecraft\.world\.phys\.Vec3|net\.minecraft\.world\.phys\.AABB|net\.minecraft\.core\.BlockPos)\s+(\w+)\s*;""", RegexOption.MULTILINE)
        for (m in r.findAll(out)) {
            val t = when {
                m.groupValues[2] == "double" -> "double"
                m.groupValues[2] == "float" -> "float"
                m.groupValues[2] == "int" -> "int"
                m.groupValues[2] == "boolean" -> "boolean"
                m.groupValues[2].contains("Vec3") -> "Vec3"
                m.groupValues[2].contains("AABB") -> "AABB"
                m.groupValues[2].contains("BlockPos") -> "BlockPos"
                else -> null
            }
            if (t != null) fields.add(Triple(m.groupValues[3], t, m.groupValues[1]))
        }

        val sb = StringBuilder()
        sb.append("package com.github.uright008.vec.core;\n\n")
        sb.append("// AUTO-GENERATED from Entity.class via javap — do not edit\n")
        sb.append("public final class GeneratedFields {\n")
        sb.append("    public record Spec(String name, String type, String access) {\n")
        sb.append("        public boolean isDouble() { return type.equals(\"double\") || type.equals(\"Vec3\"); }\n")
        sb.append("        public boolean isFloat() { return type.equals(\"float\"); }\n")
        sb.append("        public boolean isInt() { return type.equals(\"int\"); }\n")
        sb.append("        public boolean isBoolean() { return type.equals(\"boolean\"); }\n")
        sb.append("        public boolean isVec3() { return type.equals(\"Vec3\"); }\n")
        sb.append("        public boolean isAABB() { return type.equals(\"AABB\"); }\n")
        sb.append("    }\n")
        sb.append("    public static final Spec[] ALL = {\n")
        for ((i, f) in fields.withIndex()) {
            val comma = if (i < fields.size - 1) "," else " "
            sb.append("        new Spec(\"${f.first}\", \"${f.second}\", \"${f.third}\")$comma\n")
        }
        sb.append("    };\n\n")

        // Generate ordinal constants for O(1) array indexing
        // Convert camelCase to UPPER_SNAKE_CASE
        fun String.toSnake() = replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

        var idx = 0
        sb.append("    // ── Field ordinals (array index) ──\n")
        for (f in fields) {
            val n = f.first.toSnake()
            when (f.second) {
                "double", "float", "int", "boolean" -> {
                    sb.append("    public static final int ${n} = ${idx};\n")
                    idx++
                }
                "Vec3" -> {
                    sb.append("    public static final int ${n}_X = ${idx};\n")
                    sb.append("    public static final int ${n}_Y = ${idx+1};\n")
                    sb.append("    public static final int ${n}_Z = ${idx+2};\n")
                    idx += 3
                }
                "AABB" -> {
                    sb.append("    public static final int ${n}_MIN_X = ${idx};\n")
                    sb.append("    public static final int ${n}_MIN_Y = ${idx+1};\n")
                    sb.append("    public static final int ${n}_MIN_Z = ${idx+2};\n")
                    sb.append("    public static final int ${n}_MAX_X = ${idx+3};\n")
                    sb.append("    public static final int ${n}_MAX_Y = ${idx+4};\n")
                    sb.append("    public static final int ${n}_MAX_Z = ${idx+5};\n")
                    idx += 6
                }
            }
        }
        sb.append("    public static final int COUNT = ${idx};\n")
        sb.append("}\n")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString())
        logger.lifecycle("GeneratedFields: ${fields.size} fields from Entity.class via javap")
    }
}

sourceSets {
    main {
        java {
            srcDir("common/src/main/java")
            srcDir("common/src/generated/java")
        }
        resources {
            srcDir("fabric/src/main/resources")
            
            
        }
    }
    test {
        java {
            srcDir("common/src/test/java")
        }
    }
}

tasks.named("compileJava").configure { dependsOn(generateFields) }
tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(generateFields) }

tasks.withType<JavaCompile>().configureEach { options.release = 25 }
java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-modules=jdk.incubator.vector",
        "--add-exports=jdk.incubator.vector/jdk.incubator.vector=ALL-UNNAMED"
    )
}

val agentJar by tasks.registering(Jar::class) {
    archiveFileName.set("vectorial-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output) {
        include("com/github/uright008/vec/core/VectorialAgent*.class")
        include("com/github/uright008/vec/core/VectorialTransformer.class")
    }
    from(configurations.runtimeClasspath.get().filter { it.name.contains("javassist") }.map { zipTree(it) })
    manifest {
        attributes(
            "Premain-Class" to "com.github.uright008.vec.core.VectorialAgent",
            "Agent-Class" to "com.github.uright008.vec.core.VectorialAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

tasks.jar {
    dependsOn(agentJar)
    from(agentJar.flatMap { it.archiveFile }) { into("META-INF") }
    from(configurations.runtimeClasspath.get().filter {
        it.name.contains("javassist") || it.name.contains("byte-buddy-agent")
    }.map { zipTree(it) })

}

publishing {
    repositories { mavenLocal() }
}

tasks.processResources {
    val version = providers.gradleProperty("mod_version").get()
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
