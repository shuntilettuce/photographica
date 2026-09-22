plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.147"
}

val mcVersion    = extra["minecraft_version"] as String
val neoVersion   = extra["neoforge_version"] as String
val modVersion   = extra["mod_version"] as String
val packFormat   = extra["pack_format_number"] as String

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
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "minecraft_version_range" to "[1.21,1.22)",
            "neoforge_version_range" to "[21.1.0,)",
            "mod_version" to modVersion
        )
    }
}
