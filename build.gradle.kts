
plugins {
    idea
    // One idea-ext for the whole build. gtnhgradle and ModDevGradle request it under different Maven
    // coordinates, so Gradle loads both classes and moddev's `hasPlugin(IdeaExtPlugin.class)` guard
    // misses, applying a second copy until the `settings` extension collides. Applying it at the root
    // puts one copy in the parent buildscript scope. IDE sync only -- a CLI build constructs no
    // IDEA model, so nothing but a sync can see the collision.
    id("org.jetbrains.gradle.plugin.idea-ext")
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

//
//// Umbrella task that extracts MC sources and resources for all mc1201 loader modules.
//// Run once after checkout or after toolchain version bumps. Each subproject's extractMcSources
//// task will trigger the appropriate toolchain download + decompile step as needed.
//tasks.register("extractAllMcSources") {
//    description = "Extracts MC sources and resources for all mc1201 loader modules. Run once after checkout."
//    group = "crystalgraphics"
//    // neoforge targets MC 1.20.4 (not 1.20.1 — NeoForge never published a stable 1.20.1 series).
//    dependsOn(
//        ":mc1201:neoforge:extractMcSources",
//        ":mc1201:forge:extractMcSources",
//        ":mc1201:fabric:extractMcSources"
//    )
//}
