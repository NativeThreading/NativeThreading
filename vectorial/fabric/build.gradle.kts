import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
plugins { id("net.fabricmc.fabric-loom") }
version = providers.gradleProperty("mod_version").get()
base { archivesName = "vectorial-fabric" }
group = providers.gradleProperty("maven_group").get()
repositories {
    mavenLocal()
    mavenCentral()
    maven { name = "JitPack"; url = uri("https://jitpack.io") }
}
loom { mods { register("vectorial") { sourceSet(sourceSets.main.get()) } } }
dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("org.javassist:javassist:3.30.2-GA")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")
}
sourceSets { main { java { srcDirs("../common/src/main/java", "../common/src/generated/java") } } }
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val version = version; inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}
tasks.named("compileJava").configure { dependsOn(":common:generateFields") }
tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(":common:generateFields") }
tasks.withType<JavaCompile>().configureEach { options.release = 25 }
java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

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
    val projectName = project.name; inputs.property("projectName", projectName)
    from("LICENSE") { rename { "${it}_$projectName" } }
    from(configurations.runtimeClasspath.get().filter {
        it.name.contains("javassist") || it.name.contains("byte-buddy-agent")
    }.map { zipTree(it) })

}
