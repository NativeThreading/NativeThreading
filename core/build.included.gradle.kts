plugins {
    id("net.fabricmc.fabric-loom")
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    compileOnly("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    compileOnly("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    // Optional: vectorial EntityDataView for SIMD batch operations
    compileOnly(project(":vectorial"))
    // JOCL for GPU-accelerated batch entity queries
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation(project(":vectorial"))
}

sourceSets {
    main {
        java {
            srcDir("common/src/main/java")
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

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val version = providers.gradleProperty("mod_version").get()
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
