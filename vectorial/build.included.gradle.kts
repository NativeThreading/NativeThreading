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
    val fieldsFile = file("$generatedDir/com/github/uright008/vec/core/GeneratedFields.java")
    val accessorsFile = file("$generatedDir/com/github/uright008/vec/core/GeneratedAccessors.java")
    val syncFile = file("$generatedDir/com/github/uright008/vec/core/GeneratedSync.java")
    outputs.files(fieldsFile, accessorsFile, syncFile)

    doLast {
        val mcJar = configurations.compileClasspath.get().files
            .find { it.name.contains("minecraft-merged") }
            ?: error("Minecraft merged jar not found on classpath")

        fun runJavap(cls: String): List<String> {
            val proc = ProcessBuilder("javap", "-p", "-cp", mcJar.absolutePath, cls)
                .redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            return lines
        }

        val entityLines = runJavap("net.minecraft.world.entity.Entity")

        // ── Parse fields ──
        data class FieldSpec(val name: String, val type: String, val access: String)
        val fieldRegex = Regex("""^\s+(public|private|protected)\s+(double|float|int|boolean|net\.minecraft\.world\.phys\.Vec3|net\.minecraft\.world\.phys\.AABB|net\.minecraft\.core\.BlockPos)\s+(\w+)\s*;""")
        val fields = entityLines.mapNotNull { line ->
            fieldRegex.find(line)?.let { m ->
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
                if (t != null) FieldSpec(m.groupValues[3], t, m.groupValues[1]) else null
            }
        }
        val fieldNames = fields.map { it.name }.toSet()

        // ── Parse methods (getters + setters) ──
        val getterRegex = Regex("""^\s+public\s+(?:final\s+)?(boolean|int|float|double|net\.minecraft\.world\.phys\.Vec3|net\.minecraft\.world\.phys\.AABB)\s+(\w+)\(\);""")
        val setterRegex = Regex("""^\s+public\s+(?:final\s+)?void\s+(\w+)\(((?:boolean|int|float|double|net\.minecraft\.world\.phys\.Vec3|net\.minecraft\.world\.phys\.AABB)[^)]*)\);""")

        data class MethodInfo(val name: String, val returnType: String, val paramTypes: String)
        val allMethods = entityLines.mapNotNull { line ->
            getterRegex.find(line)?.let { MethodInfo(it.groupValues[2], it.groupValues[1], "") }
                ?: setterRegex.find(line)?.let { MethodInfo(it.groupValues[1], "void", it.groupValues[2]) }
        }
        val methodSet = allMethods.map { it.name }.toSet()

        // ── Map fields to getters/setters ──
        fun String.toTitle() = replaceFirstChar { it.uppercase() }

        val specialGetters = mapOf(
            "bb" to "getBoundingBox",
            "eyeHeight" to "getEyeHeight"
        )
        val specialSetters = mapOf(
            "bb" to "setBoundingBox",
            "position" to null,
            "onGround" to "setOnGround",
            "isInPowderSnow" to "setIsInPowderSnow"
        )

        // Getters that can't have their body replaced (VerifyError candidates)
        val skipTransformGetters = setOf<String>()

        fun guessGetter(fieldName: String): String? {
            specialGetters[fieldName]?.let { return if (it in methodSet) it else null }
            val titled = fieldName.toTitle()
            val candidates = listOf(
                "get$titled", "is$titled", "has$titled", fieldName,
                "was$titled", "should$titled", "needs$titled", "sync$titled",
                "blocks$titled", "on$titled", "can$titled"
            )
            return candidates.firstOrNull { it in methodSet }
        }
        fun guessSetter(fieldName: String): String? {
            specialSetters[fieldName]?.let { return it }
            val setterName = "set${fieldName.toTitle()}"
            return if (setterName in methodSet) setterName else null
        }

        data class Accessor(
            val fieldName: String, val type: String,
            val getterName: String?, val setterName: String?
        )
        val accessors = fields.map { f ->
            Accessor(f.name, f.type, guessGetter(f.name), guessSetter(f.name))
        }

        fun String.toSnake() = replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

        var ordIdx = 0
        val ordinals = mutableMapOf<String, Int>()
        for (f in fields) {
            ordinals[f.name] = ordIdx
            ordIdx += when (f.type) { "Vec3" -> 3; "AABB" -> 6; else -> 1 }
        }

        // ── GeneratedFields.java ──
        val fieldsSb = StringBuilder()
        fieldsSb.append("package com.github.uright008.vec.core;\n\n")
        fieldsSb.append("// AUTO-GENERATED from Entity.class via javap — do not edit\n")
        fieldsSb.append("public final class GeneratedFields {\n")
        fieldsSb.append("    public record Spec(String name, String type, String access) {\n")
        fieldsSb.append("        public boolean isDouble() { return type.equals(\"double\") || type.equals(\"Vec3\"); }\n")
        fieldsSb.append("        public boolean isFloat() { return type.equals(\"float\"); }\n")
        fieldsSb.append("        public boolean isInt() { return type.equals(\"int\"); }\n")
        fieldsSb.append("        public boolean isBoolean() { return type.equals(\"boolean\"); }\n")
        fieldsSb.append("        public boolean isVec3() { return type.equals(\"Vec3\"); }\n")
        fieldsSb.append("        public boolean isAABB() { return type.equals(\"AABB\"); }\n")
        fieldsSb.append("    }\n")
        fieldsSb.append("    public static final Spec[] ALL = {\n")
        for ((i, f) in fields.withIndex()) {
            val comma = if (i < fields.size - 1) "," else " "
            fieldsSb.append("        new Spec(\"${f.name}\", \"${f.type}\", \"${f.access}\")$comma\n")
        }
        fieldsSb.append("    };\n\n")
        fieldsSb.append("    // ── Field ordinals (array index) ──\n")
        for (f in fields) {
            val n = f.name.toSnake()
            when (f.type) {
                "double", "float", "int", "boolean" -> fieldsSb.append("    public static final int $n = ${ordinals[f.name]};\n")
                "Vec3" -> {
                    val base = ordinals[f.name]!!
                    fieldsSb.append("    public static final int ${n}_X = ${base};\n")
                    fieldsSb.append("    public static final int ${n}_Y = ${base + 1};\n")
                    fieldsSb.append("    public static final int ${n}_Z = ${base + 2};\n")
                }
                "AABB" -> {
                    val base = ordinals[f.name]!!
                    fieldsSb.append("    public static final int ${n}_MIN_X = ${base};\n")
                    fieldsSb.append("    public static final int ${n}_MIN_Y = ${base + 1};\n")
                    fieldsSb.append("    public static final int ${n}_MIN_Z = ${base + 2};\n")
                    fieldsSb.append("    public static final int ${n}_MAX_X = ${base + 3};\n")
                    fieldsSb.append("    public static final int ${n}_MAX_Y = ${base + 4};\n")
                    fieldsSb.append("    public static final int ${n}_MAX_Z = ${base + 5};\n")
                }
            }
        }
        fieldsSb.append("    public static final int COUNT = ${ordIdx};\n")
        fieldsSb.append("}\n")
        fieldsFile.parentFile.mkdirs()
        fieldsFile.writeText(fieldsSb.toString())

        // ── GeneratedAccessors.java ──
        val accSb = StringBuilder()
        accSb.append("package com.github.uright008.vec.core;\n\n")
        accSb.append("// AUTO-GENERATED — maps Entity field names to getter/setter methods\n")
        accSb.append("public final class GeneratedAccessors {\n")
        accSb.append("    public record Entry(String fieldName, String type, String getterName, String setterName, int baseOrdinal) {\n")
        accSb.append("        public int ordCount() { return switch (type) { case \"Vec3\" -> 3; case \"AABB\" -> 6; default -> 1; }; }\n")
        accSb.append("        public boolean skipTransform() {\n")
        accSb.append("            return java.util.Set.of(${skipTransformGetters.map { "\"$it\"" }.joinToString(", ")}).contains(fieldName);\n")
        accSb.append("        }\n")
        accSb.append("    }\n")
        accSb.append("    public static final Entry[] ALL = {\n")
        for ((i, a) in accessors.withIndex()) {
            val comma = if (i < accessors.size - 1) "," else " "
            val g = a.getterName?.let { "\"$it\"" } ?: "null"
            val s = a.setterName?.let { "\"$it\"" } ?: "null"
            accSb.append("        new Entry(\"${a.fieldName}\", \"${a.type}\", $g, $s, ${ordinals[a.fieldName]})$comma\n")
        }
        accSb.append("    };\n")
        accSb.append("}\n")
        accessorsFile.parentFile.mkdirs()
        accessorsFile.writeText(accSb.toString())

        // ── GeneratedSync.java ──
        val publicFields = setOf(
            "blocksBuilding", "xo", "yo", "zo", "yRotO", "xRotO",
            "horizontalCollision", "verticalCollision", "verticalCollisionBelow",
            "minorHorizontalCollision", "hurtMarked", "moveDist", "flyDist",
            "fallDistance", "xOld", "yOld", "zOld", "noPhysics", "tickCount",
            "invulnerableTime", "needsSync", "syncPosition", "isInPowderSnow",
            "wasInPowderSnow"
        )
        val syncSb = StringBuilder()
        syncSb.append("package com.github.uright008.vec.core;\n\n")
        syncSb.append("import net.minecraft.world.entity.Entity;\nimport net.minecraft.world.phys.Vec3;\nimport net.minecraft.world.phys.AABB;\n\n")
        syncSb.append("// AUTO-GENERATED — syncs all captured fields from Entity to SoA\n")
        syncSb.append("public final class GeneratedSync {\n")
        syncSb.append("    private GeneratedSync() {}\n\n")
        syncSb.append("    public static void syncAll(Entity entity) {\n")
        syncSb.append("        int id = entity.getId();\n")
        syncSb.append("        int[] slots = SoAStore.INSTANCE.idToSlotCache;\n")
        syncSb.append("        int slot = (id >= 0 && id < slots.length) ? slots[id] : -1;\n")
        syncSb.append("        if (slot < 0) return;\n")
        syncSb.append("        double[][] f = SoAStore.INSTANCE.fields;\n\n")
        for (a in accessors) {
            val o = ordinals[a.fieldName]!!
            val getter = a.getterName
            val isPublic = a.fieldName in publicFields || getter != null
            if (!isPublic) continue
            when (a.type) {
                "double", "float", "int" -> {
                    val src = if (getter != null) "entity.${getter}()" else "entity.${a.fieldName}"
                    syncSb.append("        f[$o][slot] = $src;\n")
                }
                "boolean" -> {
                    val src = if (getter != null) "entity.${getter}()" else "entity.${a.fieldName}"
                    syncSb.append("        f[$o][slot] = $src ? 1.0 : Double.NaN;\n")
                }
                "Vec3" -> {
                    if (getter != null)
                        syncSb.append("        { Vec3 v = entity.${getter}(); if (v != null) { f[$o][slot]=v.x; f[${o+1}][slot]=v.y; f[${o+2}][slot]=v.z; } }\n")
                }
                "AABB" -> {
                    if (getter != null)
                        syncSb.append("        { AABB bb = entity.${getter}(); if (bb != null) { f[$o][slot]=bb.minX; f[${o+1}][slot]=bb.minY; f[${o+2}][slot]=bb.minZ; f[${o+3}][slot]=bb.maxX; f[${o+4}][slot]=bb.maxY; f[${o+5}][slot]=bb.maxZ; } }\n")
                }
            }
        }
        syncSb.append("    }\n")
        syncSb.append("}\n")
        syncFile.parentFile.mkdirs()
        syncFile.writeText(syncSb.toString())

        logger.lifecycle("GeneratedFields: ${fields.size} fields, ${accessors.count { it.getterName != null }} getters, ${accessors.count { it.setterName != null }} setters")
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
        include("com/github/uright008/vec/core/GeneratedFields*.class")
        include("com/github/uright008/vec/core/GeneratedAccessors*.class")
        include("com/github/uright008/vec/core/GeneratedSync.class")
        include("com/github/uright008/vec/core/SoAStore.class")
        include("com/github/uright008/vec/core/SoAStore$*.class")
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
