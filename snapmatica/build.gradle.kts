plugins {
    id("fabric-loom") version "1.13-SNAPSHOT"
    id("maven-publish")
}

val sc = stonecutter
val mcVersion     = sc.current.version
val yarnMappings  = extra["yarn_mappings"] as String
val loaderVersion = extra["loader_version"] as String
val fabricVersion = extra["fabric_version"] as String
val modVersion    = extra["mod_version"]    as String
// The single build target (mcVersion) often covers more than itself — e.g. the 1.21.1 jar
// is also correct for 1.21 and 1.21.3's for 1.21.2 — so the filename states the actual
// covered range rather than just the version it happened to be compiled against.
val mcRange       = extra["mc_range"]       as String

// 1.20.1 ships with (and Fabric Loader for 1.20.1 expects) Java 17, not 21 like every
// other version target in this project. Compiling it at release 21 would produce classes
// that 1.20.1's own bundled JRE can't load.
val javaVersion = if (mcVersion == "1.20.1") JavaVersion.VERSION_17 else JavaVersion.VERSION_21
// Mixin refuses to start at all if this claims a level the running JRE doesn't support —
// unlike the plain compiled bytecode version, which just fails later at class-load time,
// this one throws immediately on launch ("could not be set... not supported by the active
// JRE"), which is exactly the crash that shipped before this was wired up.
val mixinCompatLevel = if (mcVersion == "1.20.1") "JAVA_17" else "JAVA_21"

version = "$modVersion+$mcRange"
group   = extra["maven_group"] as String

base {
    archivesName = extra["archives_base_name"] as String
}

loom {
    mixin {
        useLegacyMixinAp.set(true)
        add(sourceSets.main.get(), "snapmatica.refmap.json")
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://api.modrinth.com/maven")
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    compileOnly("maven.modrinth:distanthorizonsapi:6.1.0")
}

val minecraftRange = extra["minecraft_range"] as String

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_range", minecraftRange)
    inputs.property("loader_version", loaderVersion)
    inputs.property("java_version", javaVersion.majorVersion)
    inputs.property("mixin_compat_level", mixinCompatLevel)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_range" to minecraftRange,
            "loader_version" to loaderVersion,
            "java_version" to javaVersion.majorVersion
        )
    }
    filesMatching("snapmatica.client.mixins.json") {
        expand("mixin_compat_level" to mixinCompatLevel)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion.majorVersion.toInt()
}

java {
    withSourcesJar()
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${base.archivesName.get()}" }
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
