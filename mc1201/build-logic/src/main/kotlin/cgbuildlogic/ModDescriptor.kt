package cgbuildlogic

/**
 * What a mod says about itself, once, for every format that has to say it.
 *
 * <p>A single jar carries one {@code fabric.mod.json}, one {@code mods.toml} and one
 * {@code mcmod.info} between them describing four loaders, and each loader reads only its own. Four
 * hand-written files cannot be kept in step — a version bumped in three of them is a mod whose
 * dependency range is satisfiable on some loaders and not others, and nothing reports it. So the
 * fields are declared here and the formats are printed from them.</p>
 *
 * <pre>
 * val descriptor = ModDescriptor(
 *     id = "crystalgui", name = "CrystalGUI", version = "1.0.0",
 *     description = "UI engine library for Minecraft mods.",
 *     license = "LGPL-3.0-or-later",
 *     dependencies = listOf(Dependency("crystalgraphics", "[1.0.0,)", ordering = Ordering.AFTER)),
 *     variants = listOf(Variant(loader = "forge", minecraft = "[1.20.1,1.21)", ...)))
 * println(ForgeModsToml.merged(descriptor))
 * </pre>
 *
 * <p>Easy to get wrong: a {@link Variant}'s {@code minecraft} range is what the merged descriptor
 * unions and what the bootstrapper matches on, so two variants of one loader with overlapping ranges
 * make the choice arbitrary. {@code DescriptorModelTest} refuses that rather than letting it ship.</p>
 */
data class ModDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val license: String,
    /** Jar-relative, or null when the mod ships no icon. */
    val icon: String? = null,
    val homepage: String? = null,
    val sources: String? = null,
    val issues: String? = null,
    val environment: Side = Side.BOTH,
    val dependencies: List<Dependency> = emptyList(),
    val variants: List<Variant> = emptyList(),
) {
    /** The variants of one loader family, in declaration order. */
    fun variantsOf(vararg loaders: String): List<Variant> =
        variants.filter { loaders.contains(it.loader) }
}

enum class Side { CLIENT, SERVER, BOTH }

enum class Ordering { NONE, BEFORE, AFTER }

data class Dependency(
    val id: String,
    /** A Maven range. Fabric's own grammar is different and [FabricModJson] converts it. */
    val range: String,
    val required: Boolean = true,
    val ordering: Ordering = Ordering.NONE,
    val side: Side = Side.BOTH,
)

/**
 * One (loader, Minecraft range) the jar carries.
 *
 * @param loader one of `fabric`, `forge`, `neoforge`, `fml1710`, `fml1122` — the vocabulary
 *        `LoaderProbe` answers in
 * @param era which source tree built it; documentation today, a target-list key later
 * @param commonEntry the class constructed on both sides, or null when the loader has none
 * @param clientEntry the class constructed on clients only, or null
 */
data class Variant(
    val loader: String,
    val minecraft: String,
    val era: String,
    val commonEntry: String? = null,
    val clientEntry: String? = null,
    val mixinConfigs: List<String> = emptyList(),
    val packFormat: Int = 15,
    /** The loader's own version range, where the format asks for one. */
    val loaderRange: String? = null,
    /** Fabric's `depends` block, whose grammar is not Maven's. */
    val fabricDepends: Map<String, String> = emptyMap(),
)

/** Shared by the printers: a TOML/JSON string literal. */
private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

object FabricModJson {

    /** The one descriptor a merged jar carries for Fabric: every Fabric variant's entrypoints. */
    fun merged(d: ModDescriptor): String {
        val fabric = d.variantsOf("fabric")
        val main = fabric.mapNotNull { it.commonEntry }
        val client = fabric.mapNotNull { it.clientEntry }
        val mixins = fabric.flatMap { it.mixinConfigs }.distinct()
        val depends = LinkedHashMap<String, String>()
        fabric.forEach { depends.putAll(it.fabricDepends) }

        val entries = StringBuilder()
        entries.append("{\n")
        entries.append("  \"schemaVersion\": 1,\n")
        entries.append("  \"id\": ").append(quote(d.id)).append(",\n")
        entries.append("  \"version\": ").append(quote(d.version)).append(",\n")
        entries.append("  \"name\": ").append(quote(d.name)).append(",\n")
        entries.append("  \"description\": ").append(quote(d.description)).append(",\n")
        entries.append("  \"license\": ").append(quote(d.license)).append(",\n")
        d.icon?.let { entries.append("  \"icon\": ").append(quote(it)).append(",\n") }
        entries.append("  \"environment\": ").append(quote(environmentOf(d.environment))).append(",\n")
        entries.append("  \"entrypoints\": {\n")
        entries.append("    \"main\": [").append(main.joinToString(", ") { quote(it) }).append("],\n")
        entries.append("    \"client\": [").append(client.joinToString(", ") { quote(it) }).append("]\n")
        entries.append("  },\n")
        if (mixins.isNotEmpty()) {
            entries.append("  \"mixins\": [")
                .append(mixins.joinToString(", ") { quote(it) }).append("],\n")
        }
        entries.append("  \"depends\": {\n")
        entries.append(depends.entries.joinToString(",\n") {
            "    " + quote(it.key) + ": " + quote(it.value)
        })
        entries.append("\n  }\n}\n")
        return entries.toString()
    }

    private fun environmentOf(side: Side) = when (side) {
        Side.CLIENT -> "client"
        Side.SERVER -> "server"
        Side.BOTH -> "*"
    }
}

object ForgeModsToml {

    /**
     * The one `mods.toml` a merged jar carries, for Forge AND NeoForge alike.
     *
     * <p>Three things make one file serve both, and each was measured rather than assumed:
     * `loaderVersion="[1,)"` satisfies every javafml either has shipped; the `minecraft` dependency
     * carries **both** `mandatory` (Forge's spelling) and `type` (NeoForge's), which each loader
     * reads while ignoring the other; and there is **no** `forge` or `neoforge` row, because a
     * required dependency on a mod the other loader does not have is a refusal to load.</p>
     */
    fun merged(d: ModDescriptor): String {
        val forgeFamily = d.variantsOf("forge", "neoforge")
        val mixins = forgeFamily.flatMap { it.mixinConfigs }.distinct()
        val out = StringBuilder()
        out.append("modLoader = \"javafml\"\n")
        out.append("loaderVersion = \"[1,)\"\n")
        out.append("license = ").append(quote(d.license)).append("\n")
        d.issues?.let { out.append("issueTrackerURL = ").append(quote(it)).append("\n") }
        out.append("\n")
        mixins.forEach { out.append("[[mixins]]\n    config = ").append(quote(it)).append("\n\n") }
        out.append("[[mods]]\n")
        out.append("    modId = ").append(quote(d.id)).append("\n")
        out.append("    version = \"\${file.jarVersion}\"\n")
        out.append("    displayName = ").append(quote(d.name)).append("\n")
        d.homepage?.let { out.append("    displayURL = ").append(quote(it)).append("\n") }
        d.icon?.let { out.append("    logoFile = ").append(quote(it)).append("\n") }
        out.append("    description = ").append(quote(d.description)).append("\n")
        out.append("\n")
        out.append("[[dependencies.").append(d.id).append("]]\n")
        out.append("    modId = \"minecraft\"\n")
        out.append("    mandatory = true\n")
        out.append("    type = \"required\"\n")
        out.append("    versionRange = ").append(quote(minecraftUnion(forgeFamily))).append("\n")
        out.append("    ordering = \"NONE\"\n")
        out.append("    side = \"BOTH\"\n")
        d.dependencies.forEach { dep ->
            out.append("\n[[dependencies.").append(d.id).append("]]\n")
            out.append("    modId = ").append(quote(dep.id)).append("\n")
            out.append("    mandatory = ").append(dep.required).append("\n")
            out.append("    type = ").append(quote(if (dep.required) "required" else "optional")).append("\n")
            out.append("    versionRange = ").append(quote(dep.range)).append("\n")
            out.append("    ordering = ").append(quote(dep.ordering.name)).append("\n")
            out.append("    side = ").append(quote(dep.side.name)).append("\n")
        }
        return out.toString()
    }

    /**
     * The widest range the Forge-family variants cover.
     *
     * <p>A union rather than a list because `mods.toml` takes one range per dependency. It is a hull,
     * not a set: a gap between two variants is included, and the bootstrapper is what refuses a
     * version inside the hull that no variant claims — with a message naming what is supported,
     * which is a better failure than the loader's own "incompatible" screen.</p>
     */
    fun minecraftUnion(variants: List<Variant>): String {
        if (variants.isEmpty()) return "[1.20.1,)"
        val lows = variants.map { it.minecraft.substringAfter('[').substringAfter('(').substringBefore(',') }
        val highs = variants.map { it.minecraft.substringAfterLast(',').substringBefore(']').substringBefore(')') }
        val openEnded = variants.any { it.minecraft.trimEnd().endsWith(")") && it.minecraft.substringAfterLast(',').substringBefore(')').isBlank() }
        val low = lows.minWithOrNull(::compareVersions) ?: "1.20.1"
        val high = if (openEnded) "" else highs.maxWithOrNull(::compareVersions).orEmpty()
        return "[" + low + "," + high + ")"
    }

    /** 1.20.1 before 1.20.4 before 1.21 — numeric per segment, so "1.9" does not beat "1.20". */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)?.toIntOrNull() ?: 0
            val r = right.getOrNull(i)?.toIntOrNull() ?: 0
            if (l != r) return l - r
        }
        return 0
    }
}

object McmodInfo {

    /** FML 1.7.10 and 1.12 read this one; it names the oldest era the jar carries. */
    fun merged(d: ModDescriptor): String {
        val oldest = d.variantsOf("fml1710", "fml1122").firstOrNull()
        val mcVersion = oldest?.minecraft?.trim('[', ']') ?: "1.7.10"
        val out = StringBuilder()
        out.append("[\n  {\n")
        out.append("    \"modid\": ").append(quote(d.id)).append(",\n")
        out.append("    \"name\": ").append(quote(d.name)).append(",\n")
        out.append("    \"description\": ").append(quote(d.description)).append(",\n")
        out.append("    \"version\": ").append(quote(d.version)).append(",\n")
        out.append("    \"mcversion\": ").append(quote(mcVersion)).append(",\n")
        out.append("    \"url\": ").append(quote(d.homepage.orEmpty())).append(",\n")
        out.append("    \"authorList\": [],\n")
        out.append("    \"credits\": \"\",\n")
        out.append("    \"logoFile\": ").append(quote(d.icon.orEmpty())).append(",\n")
        out.append("    \"screenshots\": [],\n")
        out.append("    \"dependencies\": [")
        out.append(d.dependencies.joinToString(", ") { quote(it.id) })
        out.append("]\n  }\n]\n")
        return out.toString()
    }
}

object PackMcmeta {

    /** The newest era's format: an older number can be refused outright, a newer one only warns. */
    fun merged(d: ModDescriptor): String {
        val format = d.variants.maxOfOrNull { it.packFormat } ?: 15
        return "{\n  \"pack\": {\n    \"description\": " + quote(d.name) +
            ",\n    \"pack_format\": " + format + "\n  }\n}\n"
    }
}
