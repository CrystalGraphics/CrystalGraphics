plugins {
    id("cg-mc1201-loader")
    // Upstream fabric-loom 1.15.x supports Gradle 9.x (1.16 requires 9.4+, 1.15 works on 9.0+).
    // Architectury-loom 1.14.473 was replaced because its Forge mode uses detachedConfiguration
    // resolution without an exclusive lock — a Gradle 9 hard error (not fixable via properties).
    // fabric-loom 1.16.x requires Gradle 9.4+ — that's where the runtimeClasspath
    // exclusive-lock fix lives (1.15.x still triggers it via the jvmArguments getter).
    id("fabric-loom") version "1.16.2"
    id("com.gradleup.shadow") version "9.2.2"
}

group = rootProject.properties["modGroup"] as String
version = rootProject.properties["modVersion"] as String
base { archivesName.set("crystalgraphics-mc1201-fabric") }

dependencies {
    minecraft("com.mojang:minecraft:${rootProject.properties["mc1201.minecraft"]}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${rootProject.properties["mc1201.parchment.mc"]}:${rootProject.properties["mc1201.parchment"]}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${rootProject.properties["mc1201.fabric.loader"]}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${rootProject.properties["mc1201.fabric.api"]}")
}

loom {
    runs {
        named("client") { runDir("runs/client") }
        named("server") { runDir("runs/server") }
    }
}

// Merge platform, core, mc1201:common — same pattern as mc1710
tasks.shadowJar {
    dependsOn(":platform:jar", ":core:jar", ":mc1201:common:jar")
    configurations = listOf()  // no runtime classpath shadowing — only explicit inclusions below
}

afterEvaluate {
    tasks.shadowJar.configure {
        from(zipTree(project(":platform").tasks.named<Jar>("jar").get().archiveFile.get()))
        from(zipTree(project(":core").tasks.named<Jar>("jar").get().archiveFile.get()))
        from(zipTree(project(":mc1201:common").tasks.named<Jar>("jar").get().archiveFile.get()))
    }
}


tasks.assemble { dependsOn(tasks.shadowJar) }
