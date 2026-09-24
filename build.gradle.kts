plugins {
    id("java")
    id("net.neoforged.moddev.legacyforge") version "2.0.147"
}

val mcVersion  = extra["minecraft_version"] as String
val forgeVer   = extra["forge_version"] as String
val modVersion = extra["mod_version"] as String
val packFormat = extra["pack_format_number"] as String

version = "$modVersion+forge-$mcVersion"
group = "dev.shunti.snapmatica"

base {
    archivesName = "snapmatica"
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
    maven("https://repo.spongepowered.org/repository/maven-public/") {
        content { includeGroup("org.spongepowered") }
    }
}

dependencies {
    compileOnly("maven.modrinth:distanthorizonsapi:6.1.0")
    // Emits snapmatica.refmap.json; without it reobfJar has no Mojang->SRG mapping
    // for the names the mixins reference.
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

legacyForge {
    version = forgeVer

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

// Forge 1.20.1 runs on SRG names, so the mixin annotation processor has to emit a
// refmap that maps the Mojang names in the mixins onto SRG at runtime.
mixin {
    config("snapmatica.mixins.json")
    add(sourceSets.main.get(), "snapmatica.refmap.json")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<ProcessResources>().configureEach {
    // Declared so a version bump reruns this task; expand() alone is not an input.
    inputs.property("mod_version", modVersion)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(
            "minecraft_version_range" to "[1.20.1,1.21)",
            "forge_version_range" to "[47,)",
            "loader_version_range" to "[47,)",
            "mod_version" to modVersion,
            "mixin_compat_level" to "JAVA_17",
            "pack_format_number" to packFormat
        )
    }
    filesMatching("snapmatica.mixins.json") {
        expand("mixin_compat_level" to "JAVA_17")
    }
}
