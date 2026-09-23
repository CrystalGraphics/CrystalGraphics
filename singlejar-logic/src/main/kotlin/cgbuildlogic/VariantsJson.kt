package cgbuildlogic

/**
 * The variant table a merged jar carries, and the one file that decides which entry class runs.
 *
 * <p>A single jar holds several loaders' entry classes and, from J11, several versions' worth per
 * loader. A loader can only be told one thing — construct this class — so the thing it constructs is
 * a bootstrapper that reads this table and picks. Written to {@code META-INF/<modid>/variants.json};
 * read by {@code Variants} in `runtime/mc/shared`.</p>
 *
 * <pre>
 * VariantsJson.merged(descriptor)   // the whole table, variants in declaration order
 * </pre>
 *
 * <p>Order is part of the contract: the bootstrapper takes the <b>first</b> variant whose loader
 * matches and whose range contains the running version. {@link ModDescriptor} refuses overlapping
 * ranges for one loader, so that first match is also the only match — order decides what is read
 * first, never which variant is correct.</p>
 */
object VariantsJson {

    /** The format number the reader checks. Bump only for a change a reader cannot skip past. */
    const val FORMAT = 1

    /** The shipped table: every variant, entries at their relocated names. */
    fun merged(d: ModDescriptor): String = table(d.id, d.variants.map { it.asShipped() })

    /**
     * One node's table for its DEV run, at source names: a dev run loads the classes from source-set
     * directories, unrelocated, so the shipped names would not resolve there.
     */
    fun dev(d: ModDescriptor, variant: Variant): String = table(d.id, listOf(variant))

    private fun table(modId: String, variants: List<Variant>): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"format\": ").append(FORMAT).append(",\n")
        out.append("  \"mod\": ").append(jsonQuote(modId)).append(",\n")
        out.append("  \"variants\": [\n")
        // ONE VARIANT PER LINE: the reader is a strict parser for this exact shape, and the file is
        // read on every boot of every loader, so it is written to be diffable rather than pretty.
        out.append(variants.joinToString(",\n") { v ->
            val fields = mutableListOf(
                "\"loader\": " + jsonQuote(v.loader),
                "\"minecraft\": " + jsonQuote(v.minecraft),
                "\"era\": " + jsonQuote(v.era),
            )
            v.commonEntry?.let { fields += "\"common\": " + jsonQuote(it) }
            v.clientEntry?.let { fields += "\"client\": " + jsonQuote(it) }
            fields += "\"mixins\": [" + v.mixinConfigs.joinToString(", ") { jsonQuote(it) } + "]"
            "    {" + fields.joinToString(", ") + "}"
        })
        out.append("\n  ]\n}\n")
        return out.toString()
    }
}

/** Separate from ModDescriptor.kt's own `quote`, which is private to that file. */
internal fun jsonQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
