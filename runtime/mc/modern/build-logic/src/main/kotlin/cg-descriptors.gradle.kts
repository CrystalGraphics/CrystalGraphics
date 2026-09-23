import cgbuildlogic.Dependency
import cgbuildlogic.LoaderEntries
import cgbuildlogic.ModDescriptor
import cgbuildlogic.Variant
import cgbuildlogic.modernVariants
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
    // 1.7.10 by hand; every 1.20.x variant is a NODE of the tree, whose range and pack format are its
    // own pins (`variant.minecraft`, `variant.packFormat`) -- so adding a version adds its variant.
    variants = listOf(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgraphics.mc.v1710.CrystalGraphics",
            mixinConfigs = listOf("mixins.crystalgraphics.json"),
            packFormat = 1,
        ),
    ) + modernVariants(project, mapOf(
        "forge" to LoaderEntries("com.crystalgraphics.mc.modern.forge",
            common = "com.crystalgraphics.mc.modern.forge.CrystalGraphicsForge"),
        "neoforge" to LoaderEntries("com.crystalgraphics.mc.modern.neoforge",
            common = "com.crystalgraphics.mc.modern.neoforge.CrystalGraphicsNeoForge"),
        "fabric" to LoaderEntries("com.crystalgraphics.mc.modern.fabric",
            common = "com.crystalgraphics.mc.modern.fabric.CrystalGraphicsFabricCommon",
            client = "com.crystalgraphics.mc.modern.fabric.CrystalGraphicsFabric",
            fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "fabric-api" to "*")),
    )),
    // WHAT THE LOADER CONSTRUCTS, where that is not the variant itself. Fabric constructs EVERY entry
    // point its descriptor names, so with more than one variant it would construct them all --
    // including the one compiled against a Minecraft that is not running. Forge, NeoForge and FML need
    // no entry here: they find their entry by scanning for @Mod, so moving the annotation is the whole
    // of the change.
    bootstrappers = mapOf("fabric" to "com.crystalgraphics.mc.modern.fabric.FabricBootstrap"),
)

registerDescriptorTasks(cgDescriptor, "cg")

// Read by every loader node (its dev run's own variant table) and by cg-single-jar (the entries the
// merged jar must carry) -- neither can see this script's vals.
extra["cgModDescriptors"] = mapOf("main" to cgDescriptor)
