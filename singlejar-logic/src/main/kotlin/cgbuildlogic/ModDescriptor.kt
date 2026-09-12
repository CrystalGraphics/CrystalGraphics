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
 * make the choice depend on declaration order. <b>The constructor refuses that</b> — a malformed
 * declaration fails at configuration time rather than shipping; {@code DescriptorModelTest} pins it.</p>
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
    /**
     * Loader family → the class that loader constructs, when that is a bootstrapper rather than the
     * variant's own entry.
     *
     * <p>Only Fabric needs this: its descriptor names entry points, while Forge, NeoForge and FML
     * all find theirs by scanning for an annotation, so moving the annotation is the whole of the
     * change there. A family absent from this map names its variants' own entries, which is what a
     * mod with one variant per loader still wants.</p>
     */
    val bootstrappers: Map<String, String> = emptyMap(),
) {
    init {
        val problems = validateVariants(variants)
        require(problems.isEmpty()) {
            "$id cannot ship as one jar:\n" + problems.joinToString("\n") { "  - $it" }
        }
    }

    /** The variants of one loader family, in declaration order. */
    fun variantsOf(vararg loaders: String): List<Variant> =
        variants.filter { loaders.contains(it.loader) }
}

/**
 * What stops a variant list being shippable, one sentence each — empty when nothing does.
 *
 * <p>Apart from the constructor so a test can read the reasons instead of parsing a thrown message.</p>
 */
internal fun validateVariants(variants: List<Variant>): List<String> {
    val problems = mutableListOf<String>()
    // A LIST, not a map keyed by Variant: `Variant` is a data class, so two identical declarations
    // would collapse into one key and the overlap they are would go unreported.
    val parsed = mutableListOf<Pair<Variant, McRange>>()
    for (v in variants) {
        try {
            parsed += v to McRange.parse(v.minecraft)
        } catch (e: IllegalArgumentException) {
            problems += "${v.loader} declares `${v.minecraft}`, which is not a version range: ${e.message}"
        }
    }
    parsed.groupBy { it.first.loader }.forEach { (loader, group) ->
        for (i in group.indices) {
            for (j in i + 1 until group.size) {
                if (group[i].second.overlaps(group[j].second)) {
                    problems += "$loader declares two variants over the same versions, " +
                        "${group[i].second} and ${group[j].second} — the bootstrapper takes the " +
                        "first match, so which one runs would depend on declaration order"
                }
            }
        }
    }
    return problems
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
        // ONE ENTRY POINT WHERE THERE IS A BOOTSTRAPPER. Fabric constructs every element of these
        // lists, so naming several variants' entries here would construct them all -- including the
        // ones compiled against a Minecraft that is not running. The bootstrapper is what picks.
        //
        // AND ONLY THE HALVES THAT EXIST. A bootstrapper named under `main` must implement
        // ModInitializer, so declaring one for a client-only mod -- the language stack has no common
        // entry at all -- fails the main entrypoint stage and takes the client down with it.
        val bootstrapper = d.bootstrappers["fabric"]
        val main = when {
            bootstrapper == null -> fabric.mapNotNull { it.commonEntry }
            fabric.any { it.commonEntry != null } -> listOf(bootstrapper)
            else -> emptyList()
        }
        val client = when {
            bootstrapper == null -> fabric.mapNotNull { it.clientEntry }
            fabric.any { it.clientEntry != null } -> listOf(bootstrapper)
            else -> emptyList()
        }
        val mixins = fabric.flatMap { it.mixinConfigs }.distinct()
        // ONE ENTRY PER ID, and several variants' constraints are OR-ed rather than overwritten.
        // `putAll` kept whichever variant was declared last, so a second Fabric row silently narrowed
        // the whole jar to one Minecraft version -- the loader would then refuse to load it on the
        // other, with a message about a dependency the descriptor does declare.
        val depends = LinkedHashMap<String, MutableList<String>>()
        fabric.forEach { variant ->
            variant.fabricDepends.forEach { (id, range) ->
                depends.getOrPut(id) { mutableListOf() }.let { if (range !in it) it += range }
            }
        }

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
        // An ARRAY is Fabric's spelling of OR for a dependency; one constraint stays a plain string
        // so the common case reads as it always has.
        entries.append(depends.entries.joinToString(",\n") { (id, ranges) ->
            val value = if (ranges.size == 1) quote(ranges[0])
            else ranges.joinToString(", ", "[", "]") { quote(it) }
            "    " + quote(id) + ": " + value
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
        return McRange.hull(variants.map { McRange.parse(it.minecraft) }).toString()
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
