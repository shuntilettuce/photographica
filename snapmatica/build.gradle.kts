// Stonecutter gives each Minecraft version its own subproject, named after the
// version, and the four versions do not share a toolchain. 1.21.x is built with
// yarn mappings, which only Loom 1.13 still offers; 26 uses Mojang's own
// mappings, which need Loom 1.16. One `plugins {}` block can only name a single
// Loom, so Loom goes on the buildscript classpath and is applied per version.
buildscript {
    val isModern = project.name.substringBefore('.').toInt() >= 26
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
    }
    dependencies {
        // The plugin marker carries Gradle API-version attributes that differ
        // between the two Looms; depending on the artifact directly sidesteps
        // that variant selection entirely.
        classpath("net.fabricmc:fabric-loom:" + if (isModern) "1.16-SNAPSHOT" else "1.13-SNAPSHOT")
    }
}

plugins {
    // Declared here, before Loom applies it, so that this script keeps the
    // type-safe accessors for `java`, `base`, `processResources` and publishing.
    id("java")
    id("maven-publish")
}

val mcVersion     = project.name
val loaderVersion = extra["loader_version"] as String
val fabricVersion = extra["fabric_version"] as String
val modVersion    = extra["mod_version"]    as String

// 26 dropped yarn in favour of Mojang's own mappings, and moved to Java 25.
val isModern   = mcVersion.substringBefore('.').toInt() >= 26
val javaTarget = if (isModern) 25 else 21

// Read here, at project scope: inside dependencies {} `extra` belongs to the
// dependency handler, which carries none of the version properties.
val yarnMappings = if (isModern) null else extra["yarn_mappings"] as String

// Loom 1.13 registers itself as `fabric-loom`, 1.16 as `net.fabricmc.fabric-loom`.
apply(plugin = if (isModern) "net.fabricmc.fabric-loom" else "fabric-loom")

logger.lifecycle("snapmatica: $mcVersion (mappings=${yarnMappings ?: "mojang"}, java=$javaTarget)")

version = "$modVersion+$mcVersion"
group   = extra["maven_group"] as String

base {
    archivesName = extra["archives_base_name"] as String
}

if (!isModern) {
    // Groovy, so that it binds to whichever Loom extension is on the classpath
    // instead of being compiled against one of the two APIs.
    apply(from = rootProject.file("gradle/loom-legacy.gradle"))
}

repositories {
    mavenCentral()
}

dependencies {
    // Named rather than called through generated accessors: Loom is applied
    // from the buildscript classpath, so no accessors are generated for it.
    "minecraft"("com.mojang:minecraft:$mcVersion")
    if (isModern) {
        // Loom 1.16 applies Mojang's mappings without being asked, and remaps
        // mods off the plain configurations.
        implementation("net.fabricmc:fabric-loader:$loaderVersion")
        implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    } else {
        "mappings"("net.fabricmc:yarn:$yarnMappings:v2")
        "modImplementation"("net.fabricmc:fabric-loader:$loaderVersion")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", mcVersion)
    inputs.property("loader_version", loaderVersion)
    inputs.property("java_version", javaTarget)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to mcVersion,
            "loader_version" to loaderVersion,
            "java_version" to javaTarget
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaTarget
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaTarget)
    }
    withSourcesJar()
}

tasks.jar {
    // The LICENSE lives at the repository root, one level above this Gradle build.
    val license = rootProject.file("../LICENSE")
    if (license.exists()) {
        from(license) {
            rename { "${it}_${base.archivesName.get()}" }
        }
    }
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
