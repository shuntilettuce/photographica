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

version = "$modVersion+$mcVersion"
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
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
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
    options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
