import cgbuildlogic.Licence
import cgbuildlogic.publishedModule
import cgbuildlogic.registerCheckAllTargets
plugins {
    idea
    // One idea-ext for the whole build. gtnhgradle and ModDevGradle request it under different Maven
    // coordinates, so Gradle loads both classes and moddev's `hasPlugin(IdeaExtPlugin.class)` guard
    // misses, applying a second copy until the `settings` extension collides. Applying it at the root
    // puts one copy in the parent buildscript scope. IDE sync only -- a CLI build constructs no
    // IDEA model, so nothing but a sync can see the collision.
    id("org.jetbrains.gradle.plugin.idea-ext")

    // What this mod says about itself, declared once and printed into every format the merged jar
    // needs. Applied to the ROOT because the merged descriptors describe every loader at once and
    // belong to no one of them.
    id("cg-descriptors")

    // The merge: four thin jars and one renderer into the artifact every loader installs.
    id("cg-single-jar")
}

// ── One compiler ─────────────────────────────────────────────────────────────────────────────────
//
// Every module compiles with ONE JDK, `dep.jdk.compiler`; its own --release or source/target still
// decides its bytecode, and its toolchain stays for launchers only -- so building the jars provisions
// no other JDK. The abstract modules are authored at this Java, and every consumer below it resolves
// their Java 8 copies (cgbuildlogic.abstractModule). :runtime:mc:1710 is left to GTNH's convention,
// which already compiles with 25.
val compilerJdk = providers.gradleProperty("dep.jdk.compiler").get().toInt()
subprojects {
    if (path == ":runtime:mc:1710") return@subprojects
    plugins.withType<JavaBasePlugin> {
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<JavaCompile>().configureEach {
            javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(compilerJdk)) })
        }
    }
}

// Published from here: the bindings also build standalone, where cgbuildlogic does not exist.
project(":freetype-msdfgen-harfbuzz-bindings") {
    pluginManager.withPlugin("java-library") {
        publishedModule("FreeType-MSDFgen-HarfBuzz Java Bindings",
            "JNI bindings for FreeType, msdfgen and HarfBuzz, with natives for Windows, Linux and macOS.", Licence.MIT)
    }
}

// IDEA triggers 'processIdeaSettings' on the root project during sync and gtnhconvention only
// registers it on subprojects, so this is the fallback. Guarded because idea-ext (applied above) now
// supplies the real one, and registering twice is a configuration failure. findByName is safe here:
// the plugins block has already run.
if (tasks.findByName("processIdeaSettings") == null) {
    tasks.register("processIdeaSettings") {
        group = "ide"
        description = "No-op fallback for IntelliJ IDEA Gradle sync when idea-ext is absent"
    }
}

// Every node of the 1.20.x tree compiled, every source set -- a change is compiled against every
// Minecraft version before it is committed, not only the IDE's active node. @see ModernConventions
registerCheckAllTargets()
