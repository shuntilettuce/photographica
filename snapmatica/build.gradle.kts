plugins {
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
    id("maven-publish")
}

val sc = stonecutter
val mcVersion     = sc.current.version
val loaderVersion = extra["loader_version"] as String
val fabricVersion = extra["fabric_version"] as String
val modVersion    = extra["mod_version"]    as String

// 26 dropped yarn in favour of Mojang's own mappings, and moved to Java 25.
val isModern   = mcVersion.substringBefore('.').toInt() >= 26
val javaTarget = if (isModern) 25 else 21

version = "$modVersion+$mcVersion"
group   = extra["maven_group"] as String

base {
    archivesName = extra["archives_base_name"] as String
}

if (!isModern) {
    loom {
        mixin {
            useLegacyMixinAp.set(true)
            add(sourceSets.main.get(), "snapmatica.refmap.json")
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    if (isModern) {
        mappings(loom.officialMojangMappings())
    } else {
        mappings("net.fabricmc:yarn:${extra["yarn_mappings"] as String}:v2")
    }
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
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
