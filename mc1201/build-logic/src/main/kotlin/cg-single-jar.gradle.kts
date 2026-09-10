import cgbuildlogic.SingleJarSpec
import cgbuildlogic.registerSingleJarPipeline

// ── One jar for every loader (J4) ────────────────────────────────────────────────────────────────
//
// Four thin jars, each this loader's own classes at its own production names, plus the renderer and
// its libraries ONCE. Every loader reads its own descriptor, constructs its own entry class, and
// never defines a class from another variant -- so those classes' references to a Minecraft it is not
// running are never resolved.
//
// The PIPELINE is shared with every project on this build and lives in ../../singlejar-logic; what is
// here is the part that is CrystalGraphics' own.

plugins {
    // `java` rather than `base`: jvmdg's DowngradeJar reads `sourceSets` when it is created.
    java
    id("com.gradleup.shadow")
    id("xyz.wagyourtail.jvmdowngrader")
}

tasks.named<Jar>("jar") { enabled = false }

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

jvmdg.defaultShadeTask { enabled = false }
jvmdg.defaultTask { enabled = false }
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)

repositories {
    mavenCentral()
}

// Captured at SCRIPT scope: inside a task-configuration block `property(...)` resolves against the
// TASK, not the project.
val singleJarModId = providers.gradleProperty("modId").orElse("crystalgraphics").get()

registerSingleJarPipeline(SingleJarSpec(
    modId = singleJarModId,
    fileName = "$singleJarModId-${project.version}.jar",
    shadePath = "com/crystalgraphics/shadow",

    // Named per loader because each toolchain names its own production step.
    thinJars = listOf(
        ":mc1710" to "reobfThinJar",
        ":mc1201:forge" to "reobfThinShadowJar",
        ":mc1201:neoforge" to "thinShadowJar",
        ":mc1201:fabric" to "remapThinJar",
    ),
    // Tier 1 (§12) joins the library list rather than any loader's thin jar: one compiled copy of
    // each LWJGL family, added once for every variant, never remapped -- which is the whole reason
    // the tier exists. A loader bundling its own would put four copies in the merge to reject.
    libraryProjects = listOf(":core", ":platform", ":freetype-msdfgen-harfbuzz-bindings",
                             ":mc-shared", ":mc-lwjgl2", ":mc-lwjgl3"),
    serviceOwners = listOf(":core", ":platform"),

    // JOML IS NOT RELOCATED AND IS NOT IN THIS JAR. Both halves of that are D2, decided the hard way.
    //
    // `com.crystalgraphics.api` takes JOML types in seven public signatures -- PoseStack,
    // CgRenderCommand, CgFrameData, CgViewFrustum, CgShaderBindings, CgShaderProgram,
    // CgVertexConsumer -- so a relocation rewrites OUR OWN API, and a consumer holding the
    // org.joml.Matrix4f Minecraft just handed them cannot pass it to us. Vendoring under our own
    // package respells that breakage rather than fixing it.
    //
    // And carrying `org/joml` unrelocated is not an option either. MEASURED 2026-09-10 (E-J9-JOML):
    // Forge 1.20.1 dies in module resolution before any log line is written --
    //     java.lang.module.ResolutionException:
    //         Modules org.joml and crystalgraphics export package org.joml to module minecraft
    // -- because MC 1.19.3+ has a real named org.joml module. ModLauncher reads the package list off
    // the archive, so F1's "a class is inert until defined" does not reach it.
    //
    // So the jar ships NONE, and each target gets JOML from where it already lives: the game supplies
    // it on 1.19.3+, and 1.7.10 -- which has no JOML and no module system -- takes the companion
    // `jomlJar` below. The forbidden prefix keeps a transitive from putting it back.
    relocations = listOf(
        "de.javagl" to "com.crystalgraphics.shadow.de.javagl",
        "com.fasterxml.jackson" to "com.crystalgraphics.shadow.com.fasterxml.jackson",
    ),

    manifest = mapOf(
        "FMLCorePluginContainsFMLMod" to true,
        "ForceLoadAsMod" to true,
        "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
        "MixinConfigs" to "mixins.crystalgraphics.json",
        "Implementation-Version" to project.version.toString(),
        "Automatic-Module-Name" to singleJarModId,
    ),
    fabricThinJar = ":mc1201:fabric" to "remapThinJar",

    extraContent = {
        // Kotlin rides in on a JOML transitive and is never used.
        exclude("module-info.class", "kotlin/**", "org/jetbrains/kotlin/**", "*.xcf")
    },

    configureCheck = {
        forbiddenPrefixes.set(
            listOf("META-INF/versions/", "org/joml/", "de/javagl/", "com/fasterxml/"))
        // The `:core` MODULE's packages, which are not called `core`: api is the public surface, gl
        // the implementation, text the font stack.
        expectSingle.set(listOf("com/crystalgraphics/api/", "com/crystalgraphics/gl/",
                                "com/crystalgraphics/text/"))
        relocatedClasses.set(mapOf("com/crystalgraphics/mc/modern/platform/LifecycleModern.class" to 3))
        requiredEntries.set(listOf(
            "META-INF/mods.toml", "fabric.mod.json", "mcmod.info", "pack.mcmeta",
            "mixins.crystalgraphics.json",
            "com/crystalgraphics/mc/shared/LoaderProbe.class",
            "com/crystalgraphics/mixins/early/CrystalGraphicsMixins.class",
        ))
        requiredManifest.set(mapOf(
            "FMLCorePluginContainsFMLMod" to "true",
            "ForceLoadAsMod" to "true",
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "mixins.crystalgraphics.json",
            "Fabric-Loom-Mixin-Remap-Type" to "",
        ))
    },
))

// The companion's contents, and deliberately NOT `singleJarLibs`: nothing here reaches the merged jar.
val jomlCompanion: Configuration by configurations.creating

dependencies {
    jomlCompanion("org.joml:joml-jdk8:1.10.1") { exclude(group = "org.jetbrains.kotlin") }
    "singleJarLibs"("de.javagl:obj:0.4.0")
    "singleJarLibs"("de.javagl:jgltf-model:2.0.4")
}

// ── The JOML companion, for LWJGL2 targets only (D2) ────────────────────────────────────────────
//
// A plain library jar: `org/joml/**` at its real names and nothing else. FML 1.7.10 puts every jar in
// `mods/` on the LaunchWrapper classpath whether or not it declares a mod, which is what makes this
// work with no descriptor and no entry point.
//
// INSTALL IT ON 1.7.10 AND 1.12.2 ONLY. On 1.19.3+ the game already has JOML as a named module, and a
// second one in `mods/` reproduces exactly the ResolutionException the note above records -- so this
// is the one artefact here that is NOT "install everywhere". `deploySingleJars` knows that.
val jomlJar by tasks.registering(Jar::class) {
    group = "single jar"
    description = "JOML for the LWJGL2 targets, whose Minecraft does not ship it. NOT for 1.19.3+."
    archiveFileName.set("crystalgraphics-joml-${project.version}.jar")
    from(jomlCompanion.map { zipTree(it) }) {
        // Its own module descriptor would make it a named module and defeat the point on any loader
        // that reads one; the licence travels, because MIT asks it to.
        exclude("module-info.class", "META-INF/maven/**")
    }
    manifest {
        attributes(
            "Implementation-Title" to "JOML, for CrystalGraphics on LWJGL2 targets",
            "Implementation-Version" to project.version.toString(),
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// It is part of building the artefacts, not an extra step somebody has to remember.
tasks.named("singleJar") { dependsOn(jomlJar) }
