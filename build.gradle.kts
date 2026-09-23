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
