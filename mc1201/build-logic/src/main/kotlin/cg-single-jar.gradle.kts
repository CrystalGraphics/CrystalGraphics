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
    libraryProjects =
        listOf(":core", ":platform", ":freetype-msdfgen-harfbuzz-bindings", ":mc-shared"),
    serviceOwners = listOf(":core", ":platform"),

    // JOML is the one that matters: a jar containing `org/joml` is a split package against
    // Minecraft's own module on Forge and NeoForge, and 1.7.10 has no JOML at all -- so the union
    // ships, relocated, and CrystalGUI applies the identical rewrite over no classes of its own so
    // the two mods keep naming one type. The OBJ and glTF loaders ship for a plainer reason: the
    // 1.20.x jars have never carried them, so `CgMeshLoader.load("*.gltf")` cannot work there today.
    // J9 replaces JOML with a vendored `com.crystalgraphics.math` and its entry goes.
    relocations = listOf(
        "org.joml" to "com.crystalgraphics.shadow.org.joml",
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
        relocatedClasses.set(mapOf("com/crystalgraphics/mc/platform/Lifecycle1201.class" to 3))
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

dependencies {
    "singleJarLibs"("org.joml:joml-jdk8:1.10.1") { exclude(group = "org.jetbrains.kotlin") }
    "singleJarLibs"("de.javagl:obj:0.4.0")
    "singleJarLibs"("de.javagl:jgltf-model:2.0.4")
}
