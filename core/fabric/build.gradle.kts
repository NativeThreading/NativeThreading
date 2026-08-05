import org.gradle.api.file.DuplicatesStrategy
plugins { id("net.fabricmc.fabric-loom") }
version = providers.gradleProperty("mod_version").get()
base { archivesName = "core-fabric" }
group = providers.gradleProperty("maven_group").get()
repositories {
    mavenLocal()
    maven { name = "JitPack"; url = uri("https://jitpack.io") }
}
loom { mods { register("core") { sourceSet(sourceSets.main.get()) } } }
dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation(project(":common"))
    implementation("com.github.uright008:vectorial:${providers.gradleProperty("vectorial_version").get()}")
}
sourceSets {
    main {
        java {
            srcDir("../common/src/main/java")
        }
    }
}
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val version = version; inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.jar {
    val projectName = project.name; inputs.property("projectName", projectName)
    from("LICENSE") { rename { "${it}_$projectName" } }
}
