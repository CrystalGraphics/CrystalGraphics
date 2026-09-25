// The `fabric` branch — Fabric through Loom, one node per Minecraft version (`versions/<version>/`,
// whose gradle.properties pins mc.version, the loader, fabric-api and Parchment).

import cgbuildlogic.commonNode
import cgbuildlogic.registerThinRename
import cgbuildlogic.stubMode
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.GenerateSourcesTask
import net.fabricmc.loom.task.RemapJarTask

plugins {
    id("cg-modern-loader")
    // Upstream fabric-loom 1.15.x supports Gradle 9.x (1.16 requires 9.4+, 1.15 works on 9.0+).
    // Architectury-loom 1.14.473 was replaced because its Forge mode uses detachedConfiguration
    // resolution without an exclusive lock — a Gradle 9 hard error (not fixable via properties).
    // fabric-loom 1.16.x requires Gradle 9.4+ — that's where the runtimeClasspath
    // exclusive-lock fix lives (1.15.x still triggers it via the jvmArguments getter).
    // Applied below, on a real node only (cgbuildlogic.StubMode).
    id("fabric-loom") version "1.16.2" apply false
    // No version: settings.gradle.kts pins it, and repeating it here is refused once build-logic --
    // which the root applies for the descriptors -- has put shadow on the root buildscript classpath.
    id("com.gradleup.shadow")
}

if (!stubMode) {
    apply(plugin = "fabric-loom")
    val loom = the<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:${property("mc.version")}")
        "mappings"(loom.layered {
            officialMojangMappings()
            // Parchment starts at 1.16.5; a node below it pins none and gets Mojang's names alone.
            findProperty("parchment.version")?.let {
                parchment("org.parchmentmc.data:parchment-${property("parchment.mc")}:$it@zip")
            }
        })
        "modImplementation"("net.fabricmc:fabric-loader:${property("fabric.loader")}")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${property("fabric.api")}")
    }

    // Per NODE: relative to versions/<version>/, so two versions never share a world.
    loom.runs {
        named("client") { runDir("runs/client") }
        named("server") { runDir("runs/server") }
    }
}

/** Loom's named -> intermediary table, which generateStubs cuts this node's stub.tiny from. */
val loomMappings = { LoomGradleExtension.get(project).mappingConfiguration.tinyMappings.toFile() }

// NO fabric.mod.json OF ITS OWN (J11.1b): a node takes the merged one, which cg-modern-loader adds to
// its resources -- it names only the bootstrapper and ORs every node's range, so it is right for every
// node and carries the real version rather than a `${version}` placeholder to expand.

// Merge platform, core, the common node, and freetype-msdfgen-harfbuzz-bindings into BOTH
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
val commonJar       = project.commonNode.tasks.named<Jar>("jar").flatMap { it.archiveFile }
val freetypeJar     = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").flatMap { it.archiveFile }
// THE VARIANT SELECTOR (J11.0). Every CrystalGUI host on every loader calls VariantBootstrap from its
// own entry point, and on fabric CrystalGraphics arrives as a MOD JAR -- com.crystalgraphics is excluded
// from the consumer's runtimeClasspath on purpose, so a module absent from this jar is absent full stop.
// A dedicated server died at the crystalgui entrypoint with
// NoClassDefFoundError: com/crystalgraphics/mc/shared/VariantBootstrap. Forge and neoforge take theirs
// from additionalRuntimeClasspath instead, which is why this was fabric's alone.
val mcSharedJar     = project(":runtime:mc:shared").tasks.named<Jar>("jar").flatMap { it.archiveFile }
// TIER 1 FOR LWJGL3, which PlatformServiceModern assembles over. Same J9 move and the same hole as
// mc-shared above: the backend left runtime/mc/modern/common -- which IS bundled -- for a module that
// was bundled nowhere. CrystalGraphicsFabricCommon names Lwjgl3GLBackend at class-definition time, so
// its absence is a NoClassDefFoundError out of defineClass rather than a late one at first use.
val lwjgl3Jar       = project(":runtime:lwjgl:3").tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.jar {
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
    from(zipTree(mcSharedJar))
    from(zipTree(lwjgl3Jar))
}

tasks.shadowJar {
    configurations = listOf()  // no runtime classpath shadowing — only explicit inclusions below
    from(zipTree(platformJar))
    from(zipTree(coreJar))
    from(zipTree(commonJar))
    from(zipTree(freetypeJar))
    from(zipTree(mcSharedJar))
    from(zipTree(lwjgl3Jar))
}

// Not on `assemble` (J7): the merged single jar is the shipping artifact, and the fat per-loader jar
// nothing installs was the most expensive thing in this build. `./gradlew shadowJar` still builds one.

// A real node only: in stub mode there are no sources to extract.
if (!stubMode) {
    // Extracts this node's Minecraft sources and resources into build/mc-src for local navigation.
    // Sync (not Copy) removes stale files when jars change between toolchain version bumps.
    val extractMcSources = tasks.register<Sync>("extractMcSources") {
        description = "Extracts this node's Minecraft sources and resources into build/mc-src for local navigation."
        group = "crystalgraphics"

        // genSourcesWithVineflower is Loom's decompile task. dependsOn ensures it runs before extraction.
        // Running this task triggers Vineflower decompilation — may take several minutes on first run.
        //
        // Use the typed GenerateSourcesTask so we can access sourcesOutputJar directly.
        // task.outputs.files.singleFile would throw because GenerateSourcesTask also declares a
        // @LocalState working directory, giving it more than one output file in total.
        val genSources = tasks.named(
            "genSourcesWithVineflower",
            GenerateSourcesTask::class
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
    // CLI users who want sources without IDE sync: ./gradlew :runtime:mc:modern:fabric:1.20.1:extractMcSources
    tasks.named("ideaSyncTask") { dependsOn(extractMcSources) }
}

// -- The thin jar, remapped (J1) -------------------------------------------------------------------
//
// A SECOND remap task rather than a reconfiguration of the first: `remapJar` produces the fat jar
// this loader ships today, and both artifacts have to keep building until the root merge replaces
// the fat one. Remapping is what makes a thin jar production-shaped here, exactly as reobfuscation
// does on Forge -- intermediary is what a Fabric mod's class references must be. A node in stub mode
// renames through tiny-remapper and the committed stub.tiny instead (registerThinRename).
val remapThinJar = registerThinRename("thinShadowJar", "thin", loomMappings) {
    tasks.register<RemapJarTask>("remapThinJar") {
        group = "build"
        description = "The thin jar at intermediary names -- the merge's input from this loader."
        inputFile.set(tasks.named<AbstractArchiveTask>("thinShadowJar").flatMap { it.archiveFile })
        archiveClassifier.set("thin")
    }
}

// Registered by cg-modern-loader with what a CrystalGraphics thin jar may contain; only the jar is ours.
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") {
    jar.set(remapThinJar.flatMap { it.archiveFile })
}
tasks.named("assemble") { dependsOn(remapThinJar) }
