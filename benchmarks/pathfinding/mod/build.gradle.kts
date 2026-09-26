plugins {
    id("net.fabricmc.fabric-loom")
}

version = "1.0"
group = "com.github.uright008.benchmark"
base { archivesName = "pathfinding-benchmark" }

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}
tasks.withType<JavaCompile>().configureEach { options.release = 25 }
tasks.test { useJUnitPlatform() }

// The fixture's checks belong to this standalone build, never to the mod release.
tasks.register("validateMixinDiscipline") {
    group = "verification"
    doLast {
        for (config in fileTree("src/main/resources") { include("**/*.mixins.json") }) {
            val text = config.readText()
            require(!Regex(",\\s*[\\]}]").containsMatchIn(text)) {
                "${config.name}: trailing comma in mixin JSON"
            }
            val root = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
            val pkg = root["package"] as String
            for (name in root["mixins"] as List<*>) {
                val source = file("src/main/java/" + (pkg + "." + name).replace('.', '/') + ".java")
                require(source.isFile) { "${config.name}: missing mixin source $source" }
                require(source.readLines().size <= 250) { "$source exceeds the 250-line mixin budget" }
            }
        }
    }
}
tasks.named("check") { dependsOn("validateMixinDiscipline") }
