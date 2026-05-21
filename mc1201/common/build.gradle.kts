// ⚠ TOOLCHAIN NOTE: NeoForge did not publish artifacts for Minecraft 1.20.1 (version 20.1.x).
// The NeoForge Maven shows the earliest available series as 20.2.x (MC 1.20.2). Therefore,
// ModDevGradle NeoForm mode for 1.20.1 cannot be used — there is no
// net.neoforged:neoform:1.20.1-* artifact. This subproject compiles as a plain Java-17
// library against the shared :platform and :core modules. When we upgrade to 1.20.2+,
// replace 'cg-mc1201-common' with the full ModDevGradle NeoForm setup.

plugins {
    id("cg-mc1201-common")
}

group = rootProject.properties["modGroup"] as String
version = rootProject.properties["modVersion"] as String
base { archivesName.set("crystalgraphics-mc1201-common") }
