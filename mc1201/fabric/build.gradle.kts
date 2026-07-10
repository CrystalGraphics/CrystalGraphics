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

// Merge platform, core, mc1201:common, and freetype-msdfgen-harfbuzz-bindings into BOTH
// tasks.jar and tasks.shadowJar.
//
// tasks.jar must include bundled content because Loom uses the REMAPPED JAR (produced from
// tasks.jar via remapJar) as the mod's classpath when running the dev client. Without bundling
// here, freetype and other project deps are absent from Knot's classloader at runtime even
// though they compile fine as compileOnly project deps.
//
// NOTE: loom.mods { sourceSet(crossProject) } was attempted but triggers Loom trying to apply
// 'fabric-loom-companion' to each cross-project — fails because platform/core/freetype don't
// apply Loom. JAR bundling is the correct approach for Loom dev runs with multi-project mods.
//
// tasks.shadowJar is the distribution artifact (tasks.assemble depends on it).
// Lazy providers — Gradle uses these to wire task-to-task dependencies automatically.
// Using Provider<RegularFile> (not resolved RegularFile) ensures tasks.jar and tasks.shadowJar
// both declare an implicit dependsOn on the upstream :jar tasks; no explicit dependsOn needed.
val platformJar     = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val coreJar         = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val commonJar       = project(":mc1201:common").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val freetypeJar     = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.jar {
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
}

tasks.shadowJar {
    configurations = listOf()  // no runtime classpath shadowing — only explicit inclusions below
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// Extracts Fabric MC 1.20.1 sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when jars change between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts Fabric MC 1.20.1 sources and resources into build/mc-src for local navigation."
    group = "crystalgraphics"

    // genSourcesWithVineflower is Loom's decompile task. dependsOn ensures it runs before extraction.
    // Running this task triggers Vineflower decompilation — may take several minutes on first run.
    //
    // Use the typed GenerateSourcesTask so we can access sourcesOutputJar directly.
    // task.outputs.files.singleFile would throw because GenerateSourcesTask also declares a
    // @LocalState working directory, giving it more than one output file in total.
    val genSources = tasks.named(
        "genSourcesWithVineflower",
        net.fabricmc.loom.task.GenerateSourcesTask::class
    )
    dependsOn(genSources)

    // sourcesOutputJar is the @OutputFile declared by GenerateSourcesTask — the canonical way
    // to consume it without spelunking the loom cache path (which is hash-named).
    val sourcesJar = genSources.flatMap { it.sourcesOutputJar }
    from(zipTree(sourcesJar)) { into("java") }

    // Resources — filter non-class, non-META-INF content from the merged binary jar.
    // Loom publishes the named+merged jar to its local maven under "minecraft-merged" — it
    // lands on the compileClasspath. configurations["minecraft"] is Declarable-only in Gradle 9
    // (resolvedConfiguration() is not permitted on it), so we filter compileClasspath instead.
    // provider {} keeps the resolution lazy — executed only at task execution time.
    val mergedJar = provider {
        configurations["compileClasspath"].resolvedConfiguration.resolvedArtifacts
            .first { it.file.name.startsWith("minecraft-merged") }
            .file
    }
    from(zipTree(mergedJar)) {
        into("resources")
        exclude("**/*.class")
        exclude("META-INF/**")
    }

    into(layout.buildDirectory.dir("mc-src"))
}

// Wire into ideaSyncTask only — NOT classes.
// genSourcesWithVineflower (which extractMcSources depends on) is an optional dev task; forcing
// it on classes would add 2-5 minutes of Vineflower decompilation to every fresh-clone build.
// ideaSyncTask is Loom's dedicated IDE sync hook — the right moment for one-time source gen.
// CLI users who want sources without IDE sync: ./gradlew :mc1201:fabric:extractMcSources
tasks.named("ideaSyncTask") { dependsOn(extractMcSources) }
