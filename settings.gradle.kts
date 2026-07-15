
rootProject.name = "CrystalGraphics"

// JNI bindings subproject (standalone Java library, not Minecraft mod)
include("freetype-msdfgen-harfbuzz-bindings")

// Platform split subprojects (plain java-library, no gtnhconvention)
include(":core")
include(":platform")
//
//// MC version subprojects (each applies gtnhconvention)
//include(":mc1710")

//// Standalone GL debug harness (no Minecraft/Forge)
//if (file("gl-debug-harness").exists())
//    include(":gl-debug-harness")

//// mc1201 subprojects — nested under mc1201/
//include(":mc1201:common")
//include(":mc1201:neoforge")
//include(":mc1201:fabric")
//include(":mc1201:forge")
//
