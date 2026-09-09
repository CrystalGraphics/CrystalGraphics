plugins { id("cg-java17") }

// LWJGL 3.3.1 -- what MC 1.20.1 ships -- predates Java 21 and does not recognise its JNI version. It
// patches the JNIEnv function table on a guessed layout anyway; under a debugger's JVMTI agent that
// table is instrumented, so the write lands past it and the process dies with a native fail-fast
// (0xC0000409 on Windows) before the window opens.
//
// A dev run here is ALWAYS on Java 21: cg-java17 raises the toolchain to 21 so javac can read :core's
// v65 classes, and ModDevGradle takes the run JVM from the toolchain. So the client runs fine and
// cannot be debugged -- which reads as an IDE fault rather than a library one.
//
// 3.3.3 knows the version and uses the right layout. Dev runs only; nothing shipped resolves LWJGL.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("LWJGL 3.3.1 corrupts the JNIEnv table on Java 21 under a debugger")
        }
    }
}


repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

dependencies {
    // compileOnly: shadowJar bundles these manually (see each loader's build.gradle.kts).
    // runtimeOnly: picked up by Fabric/Loom dev runs via Gradle's standard runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath and instead use the
    // mods{} sourceSet declarations in each loader's build.gradle.kts.
    "compileOnly"(project(":mc1201:common"))
    "compileOnly"(project(":platform"))
    "compileOnly"(project(":core"))
    "compileOnly"(project(":freetype-msdfgen-harfbuzz-bindings"))
    "runtimeOnly"(project(":mc1201:common"))
    "runtimeOnly"(project(":platform"))
    "runtimeOnly"(project(":core"))
    // Fabric/Loom dev runs pick this up from runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs need it in the mods{} sourceSet block instead.
    "runtimeOnly"(project(":freetype-msdfgen-harfbuzz-bindings"))
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}")
    "annotationProcessor"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
}

// ── The thin jar (J1) ────────────────────────────────────────────────────────────────────────────
//
// One input to the single-jar merge: this loader's own classes and resources, plus :mc1201:common,
// and NOTHING else. The renderer, its SPI, the font bindings and JOML enter the merge once at the
// root; a copy here would ship three times over.
//
// `common` has to be relocated because the single jar carries THREE remapped copies of it -- SRG on
// Forge, official on NeoForge, intermediary on Fabric -- and three classes cannot share a name.
//
// THE PACKAGE IS MOVED, NOT ITS PARENT: relocating `com.crystalgraphics.mc` would rewrite this
// loader's own `com.crystalgraphics.mc.<loader>` too. `platform` keeps its leaf name under the new
// root, so `mc.platform.Lifecycle1201` becomes `mc.forge.common.platform.Lifecycle1201`.
val cgThinRoot = "com.crystalgraphics.mc.${project.name}.common"

val thinShadowJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("thinShadowJar") {
    group = "build"
    description = "This loader plus :mc1201:common, relocated -- the merge's input, before remapping."
    // DEV NAMES STILL. Forge reobfuscates this, Fabric remaps it, NeoForge ships it as it is.
    archiveClassifier.set("thin-dev")
    configurations = emptyList()
    from(sourceSets["main"].output)
    val commonJar = project(":mc1201:common").tasks.named<Jar>("jar")
    dependsOn(commonJar)
    from(commonJar.map { zipTree(it.archiveFile) })
    relocate("com.crystalgraphics.mc.platform", "$cgThinRoot.platform")
}

// Nothing in :mc1201:common may be NAMED from a descriptor or a service file.
//
// The relocation rewrites class references inside the jar; it cannot rewrite a name sitting in
// `mods.toml`, `fabric.mod.json` or `META-INF/services/...`, so such a name would point at a class
// that no longer exists under that spelling -- on three loaders, silently, at the moment something
// asks for it. The loader's OWN packages are fine: they are not relocated.
val checkDescriptorsNameNoCommon = tasks.register("checkDescriptorsNameNoCommon") {
    group = "verification"
    description = "Fails if a descriptor or service file names a class that the thin jar relocates."
    val resourceRoot = layout.projectDirectory.dir("src/main/resources").asFile
    val forbidden = listOf("com.crystalgraphics.mc.platform")
    inputs.dir(resourceRoot).optional(true).withPropertyName("resources")
    outputs.upToDateWhen { true }
    doLast {
        if (!resourceRoot.isDirectory) return@doLast
        val hits = resourceRoot.walkTopDown()
            .filter { it.isFile }
            .flatMap { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                forbidden.filter { text.contains(it) }.map { file.relativeTo(resourceRoot) to it }
            }
            .toList()
        if (hits.isNotEmpty()) {
            throw GradleException(
                "A descriptor or service file names a package the thin jar relocates, so the name "
                    + "will be wrong on every loader:\n"
                    + hits.joinToString("\n") { (path, pkg) -> "  $path  names  $pkg" })
        }
    }
}
tasks.named("check") { dependsOn(checkDescriptorsNameNoCommon) }
