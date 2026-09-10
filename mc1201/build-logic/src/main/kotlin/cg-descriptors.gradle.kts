import cgbuildlogic.Dependency
import cgbuildlogic.ModDescriptor
import cgbuildlogic.Variant
import cgbuildlogic.registerDescriptorTasks
import cgbuildlogic.registerDescriptorTasks

// ── What this mod says about itself, once (J3) ───────────────────────────────────────────────────
//
// The merged jar carries one fabric.mod.json, one mods.toml and one mcmod.info between them
// describing four loaders. Hand-writing four files that have to agree is how a version gets bumped in
// three of them; these are printed from the declaration below instead.
//
// The per-loader descriptors under each module's src/main/resources are still what those modules
// ship, and `checkDescriptorsAgree` is what stops them drifting from this.

val cgDescriptor = ModDescriptor(
    id = "crystalgraphics",
    name = "CrystalGraphics",
    version = providers.gradleProperty("modVersion").orElse("1.0.0").get(),
    description = "Rendering engine library for Minecraft mods.",
    license = "LGPL-3.0-or-later",
    dependencies = emptyList(),
    variants = listOf(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgraphics.CrystalGraphics",
            mixinConfigs = listOf("mixins.crystalgraphics.json"),
            packFormat = 1,
        ),
        Variant(
            loader = "forge", minecraft = "[1.20.1,1.21)", era = "modern",
            commonEntry = "com.crystalgraphics.mc.forge.CrystalGraphicsForge",
            packFormat = 15,
        ),
        Variant(
            loader = "neoforge", minecraft = "[1.20.4,1.21)", era = "modern",
            commonEntry = "com.crystalgraphics.mc.neoforge.CrystalGraphicsNeoForge",
            packFormat = 22,
        ),
        Variant(
            loader = "fabric", minecraft = "[1.20.1,1.21)", era = "modern",
            commonEntry = "com.crystalgraphics.mc.fabric.CrystalGraphicsFabricCommon",
            clientEntry = "com.crystalgraphics.mc.fabric.CrystalGraphicsFabric",
            fabricDepends = linkedMapOf(
                "fabricloader" to ">=0.15.0",
                "minecraft" to "~1.20.1",
                "fabric-api" to "*",
            ),
            packFormat = 15,
        ),
    ),
)

registerDescriptorTasks(cgDescriptor, "cg")
