
plugins {
    idea
}

// IntelliJ IDEA triggers 'processIdeaSettings' on the root project during Gradle sync.
// gtnhconvention only registers this task on subprojects that apply it, so register a
// no-op here to prevent "task not found" errors during IDEA sync.
tasks.register("processIdeaSettings") {
    group = "ide"
    description = "No-op task for IntelliJ IDEA Gradle sync compatibility"
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
