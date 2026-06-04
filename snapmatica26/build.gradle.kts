plugins {
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
    id("maven-publish")
}

val mcVersion     = "26.1.2"
val loaderVersion = "0.19.2"
val fabricVersion = "0.149.1+26.1.2"
val modVersion    = "0.2.0"

version = "$modVersion+$mcVersion"
group   = "dev.shunti"

base {
    archivesName = "snapmatica"
}


repositories {}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", mcVersion)
    inputs.property("loader_version", loaderVersion)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to mcVersion,
            "loader_version" to loaderVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = base.archivesName.get()
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
