import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.jar.JarFile

// ── One jar for every loader (J4) ────────────────────────────────────────────────────────────────
//
// Four thin jars, each this loader's own classes at its own production names, plus the renderer and
// its libraries ONCE. Every loader reads its own descriptor, constructs its own entry class, and
// never defines a class from another variant -- so those classes' references to a Minecraft it is not
// running are never resolved.
//
// NO DOWNGRADE STEP HERE, and that is the difference from CrystalGUI rather than an omission: the
// GTNH convention downgrades `mc1710`'s own chain, and `core`/`platform` are Java 17 bytecode that
// FML 1.7.10's ASM 5 scanner cannot read -- so this jar IS downgraded, by the same jvmdg the
// CrystalGUI pipeline uses. See `downgradeSingleJar` below.

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

/**
 * Third-party libraries the merged jar carries, all relocated.
 *
 * JOML is the one that matters: a jar containing `org/joml` is a split package against Minecraft's
 * own module on Forge and NeoForge, and 1.7.10 has no JOML at all -- so the union ships, relocated,
 * and CrystalGUI applies the identical rewrite over no classes of its own so the two mods keep
 * naming one type. The OBJ and glTF loaders ship for a plainer reason: the 1.20.x jars have never
 * carried them, so `CgMeshLoader.load("*.gltf")` cannot work there today. J9 replaces JOML with a
 * vendored `com.crystalgraphics.math` and this entry goes.
 */
val singleJarLibs: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    singleJarLibs("org.joml:joml-jdk8:1.10.1") { exclude(group = "org.jetbrains.kotlin") }
    singleJarLibs("de.javagl:obj:0.4.0")
    singleJarLibs("de.javagl:jgltf-model:2.0.4")
}

val singleJarServiceOwners = listOf(project(":core"), project(":platform"))
val singleJarServicesDir = layout.buildDirectory.dir("single-jar/services").get().asFile

val mergeSingleJarServices = tasks.register("mergeSingleJarServices") {
    group = "build"
    description = "Unions every bundled module's META-INF/services so none shadows another."
    val sources = singleJarServiceOwners.mapNotNull { owner ->
        owner.extensions.getByType(SourceSetContainer::class.java)["main"].output.resourcesDir
            ?.let { File(it, "META-INF/services") }
    }
    inputs.files(sources).withPropertyName("serviceDirs").optional(true)
    outputs.dir(singleJarServicesDir)
    dependsOn(singleJarServiceOwners.map { "${it.path}:processResources" })
    val out = singleJarServicesDir
    doLast {
        val byService = linkedMapOf<String, MutableList<String>>()
        sources.filter { it.isDirectory }.forEach { directory ->
            directory.listFiles().orEmpty().forEach { file ->
                val providers = byService.getOrPut(file.name) { mutableListOf() }
                file.readLines().map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() && it !in providers }
                    .forEach { providers.add(it) }
            }
        }
        val dir = File(out, "META-INF/services")
        dir.mkdirs()
        dir.listFiles().orEmpty().forEach { it.delete() }
        byService.forEach { (service, providers) ->
            File(dir, service).writeText(buildString {
                appendLine("# Unioned by mergeSingleJarServices; see its declaration.")
                providers.forEach { appendLine(it) }
            })
        }
    }
}

// Captured at SCRIPT scope: inside a task-configuration block `property(...)` resolves against the
// TASK, not the project.
val singleJarModId = providers.gradleProperty("modId").orElse("crystalgraphics").get()
val singleJarFileName = "$singleJarModId-${project.version}.jar"

// Named per loader because each toolchain names its own production step.
val singleJarThinJars = listOf(
    ":mc1710" to "reobfThinJar",
    ":mc1201:forge" to "reobfThinShadowJar",
    ":mc1201:neoforge" to "thinShadowJar",
    ":mc1201:fabric" to "remapThinJar",
)

/** Compiled once, shipped once. */
val singleJarLibraryProjects =
    listOf(":core", ":platform", ":freetype-msdfgen-harfbuzz-bindings", ":mc-shared")

val singleShadowJar = tasks.register<ShadowJar>("singleShadowJar") {
    group = "build"
    description = "Four loaders and one renderer in one jar, before downgrading."
    archiveClassifier.set("merged")
    configurations = emptyList()
    destinationDirectory.set(layout.buildDirectory.dir("single-jar"))

    singleJarThinJars.forEach { (path, task) ->
        val jar = project(path).tasks.named<AbstractArchiveTask>(task)
        dependsOn(jar)
        from(jar.map { zipTree(it.archiveFile) }) { exclude("META-INF/services/**") }
    }
    singleJarLibraryProjects.forEach { path ->
        val jar = project(path).tasks.named<Jar>("jar")
        dependsOn(jar)
        from(jar.map { zipTree(it.archiveFile) }) { exclude("META-INF/services/**") }
    }
    from(mergeSingleJarServices)

    singleJarLibs.forEach { from(zipTree(it)) }
    relocate("org.joml", "com.crystalgraphics.shadow.org.joml")
    relocate("de.javagl", "com.crystalgraphics.shadow.de.javagl")
    relocate("com.fasterxml.jackson", "com.crystalgraphics.shadow.com.fasterxml.jackson")

    val descriptors = tasks.named("generateMergedDescriptors")
    dependsOn(descriptors)
    from(descriptors)

    // Kotlin rides in on a JOML transitive and is never used.
    exclude("module-info.class", "kotlin/**", "org/jetbrains/kotlin/**", "*.xcf")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "FMLCorePluginContainsFMLMod" to true,
            "ForceLoadAsMod" to true,
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "mixins.crystalgraphics.json",
            "Implementation-Version" to project.version.toString(),
            "Automatic-Module-Name" to singleJarModId,
        )
    }

    // Loom's own attributes, copied from the fabric thin jar rather than restated -- eleven of them,
    // measured in J0, and the PREFIX is copied so a Loom upgrade that adds a twelfth is carried.
    // `Multi-Release` is deliberately not among them: this module's 1.7.10 jar carries it today, and
    // it would put modern classes under META-INF/versions where FML's scanner reads them and fails.
    val fabricThin = project(":mc1201:fabric").tasks.named<AbstractArchiveTask>("remapThinJar")
    doFirst {
        JarFile(fabricThin.get().archiveFile.get().asFile).use { jar ->
            jar.manifest?.mainAttributes?.forEach { key, value ->
                val name = key.toString()
                if (name.startsWith("Fabric-")) manifest.attributes[name] = value.toString()
            }
        }
    }
}

val downgradeSingleJar = tasks.register<xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar>("downgradeSingleJar") {
    group = "build"
    description = "Rewrites every class in the merged jar to Java 8."
    dependsOn(singleShadowJar)
    inputFile.set(singleShadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    archiveClassifier.set("merged-java8")
    destinationDirectory.set(layout.buildDirectory.dir("single-jar"))
}

val shadeSingleJar = tasks.register<xyz.wagyourtail.jvmdg.gradle.task.ShadeJar>("shadeSingleJar") {
    group = "build"
    description = "Bundles the jvmdg runtime stubs the downgrade now references."
    inputFile.set(downgradeSingleJar.flatMap { it.archiveFile })
    shadePath.set({ _: String -> "com/crystalgraphics/shadow" })
    archiveClassifier.set("merged-java8-shaded")
    destinationDirectory.set(layout.buildDirectory.dir("single-jar"))
}

val singleJar = tasks.register<Copy>("singleJar") {
    group = "build"
    description = "The one jar every loader installs."
    dependsOn(shadeSingleJar)
    from(shadeSingleJar.map { it.archiveFile })
    into(layout.buildDirectory.dir("libs"))
    val fileName = singleJarFileName
    rename { fileName }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val checkSingleJar = tasks.register<cgbuildlogic.CheckSingleJar>("checkSingleJar") {
    dependsOn(singleJar)
    jar.set(layout.buildDirectory.file("libs/$singleJarFileName"))
    classMajorCeiling.set(52)
    forbiddenPrefixes.set(listOf("META-INF/versions/", "org/joml/", "de/javagl/", "com/fasterxml/"))
    // The `:core` MODULE's packages, which are not called `core`: api is the public surface, gl the
    // implementation, text the font stack.
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
}

tasks.named("assemble") { dependsOn(singleJar) }
tasks.register("checkSingle") {
    group = "verification"
    description = "Builds the single jar and asserts everything four loaders need of it."
    dependsOn(checkSingleJar)
}
