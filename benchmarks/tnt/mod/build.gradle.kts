plugins { id("net.fabricmc.fabric-loom") }
version = "1.0"
group = "com.github.uright008.benchmark"
base { archivesName = "tnt-observer" }
dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile>().configureEach { options.release = 25 }
tasks.test { useJUnitPlatform() }
tasks.register("validateMixinDiscipline") {
    group = "verification"
    doLast {
        for (config in fileTree("src/main/resources") { include("**/*.mixins.json") }) {
            val text = config.readText()
            require(!Regex(",\\s*[\\]}]").containsMatchIn(text)) { "Trailing comma: $config" }
            val root = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
            for (name in root["mixins"] as List<*>) {
                val source = file("src/main/java/" + (root["package"].toString() + "." + name).replace('.', '/') + ".java")
                require(source.isFile && source.readLines().size <= 250) { "Missing or oversized mixin: $source" }
            }
        }
    }
}
tasks.named("check") { dependsOn("validateMixinDiscipline") }
