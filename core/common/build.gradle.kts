plugins {
    id("net.fabricmc.fabric-loom")
}
version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()
repositories {
    mavenLocal()
    maven { name = "JitPack"; url = uri("https://jitpack.io") }
}
dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    compileOnly("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    compileOnly("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    compileOnly("com.github.uright008:vectorial:${providers.gradleProperty("vectorial_version").get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation("com.github.uright008:vectorial:${providers.gradleProperty("vectorial_version").get()}")
}
sourceSets { test { java { srcDirs("src/test/java") } } }
tasks.test {
    useJUnitPlatform()
}
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
