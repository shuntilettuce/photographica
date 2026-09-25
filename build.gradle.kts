plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.147"
}

val mcVersion    = extra["minecraft_version"] as String
val neoVersion   = extra["neoforge_version"] as String
val modVersion   = extra["mod_version"] as String
val packFormat   = extra["pack_format_number"] as String
val mcRange      = extra["minecraft_version_range"] as String
val neoRange     = extra["neoforge_version_range"] as String

version = "$modVersion+neoforge-$mcVersion"
group = "dev.shunti.snapmatica"

base {
    archivesName = "snapmatica"
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    compileOnly("maven.modrinth:distanthorizonsapi:6.1.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

neoForge {
    version = neoVersion

    runs {
        create("client") {
            client()
            gameDirectory = file("run")
        }
    }

    mods {
        create("snapmatica") {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<ProcessResources>().configureEach {
    // Declared so a version bump reruns this task; expand() alone is not an input.
    inputs.property("mod_version", modVersion)
    inputs.property("minecraft_version", mcVersion)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            // Pinned per build: every NeoForge 1.21.x jar ships side by side, and a range
            // this jar was never built against lets a launcher load it there and crash.
            "minecraft_version_range" to mcRange,
            "neoforge_version_range" to neoRange,
            "mod_version" to modVersion
        )
    }
}
