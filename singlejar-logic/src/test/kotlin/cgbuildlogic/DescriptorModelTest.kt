package cgbuildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusal `ModDescriptor`'s own javadoc promises, and the merges a second variant per loader
 * depends on.
 *
 * Every case here is one a J11 row can actually produce: a Fabric node claiming `[1.20.1,1.21)` already
 * covers 1.20.4, so adding a 1.20.4 node without narrowing it is the overlap this refuses -- and two
 * nodes of one loader can only share a jar because each ships its classes under its own package.
 */
class DescriptorModelTest {

    private fun variant(
        loader: String,
        range: String,
        common: String? = null,
        client: String? = null,
        depends: Map<String, String> = emptyMap(),
    ) = Variant(
        loader = loader, minecraft = range, era = "test",
        commonEntry = common, clientEntry = client, fabricDepends = depends,
    )

    private fun descriptor(vararg variants: Variant) = ModDescriptor(
        id = "crystalgui", name = "CrystalGUI", version = "1.0.0",
        description = "a UI engine", license = "LGPL-3.0-or-later",
        variants = variants.toList(),
    )

    // ── McRange ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a half-open range contains its low bound and not its high`() {
        val r = McRange.parse("[1.20.1,1.21)")
        assertTrue(r.contains("1.20.1"))
        assertTrue(r.contains("1.20.4"))
        assertFalse(r.contains("1.21"))
        assertFalse(r.contains("1.20"))
    }

    @Test
    fun `a bare version is exact`() {
        val r = McRange.parse("[1.7.10]")
        assertTrue(r.contains("1.7.10"))
        assertFalse(r.contains("1.7.9"))
        assertEquals("[1.7.10]", r.toString())
    }

    @Test
    fun `an open high bound has no top`() {
        val r = McRange.parse("[1.20.1,)")
        assertTrue(r.contains("1.99.0"))
        assertFalse(r.contains("1.20"))
    }

    @Test
    fun `versions compare per segment, so 1 dot 9 is below 1 dot 20`() {
        assertTrue(compareVersions("1.9", "1.20") < 0)
        assertTrue(McRange.parse("[1.16,1.21)").contains("1.9") == false)
    }

    @Test
    fun `adjacent ranges do not overlap`() {
        val below = McRange.parse("[1.20,1.20.2)")
        val above = McRange.parse("[1.20.2,1.21)")
        assertFalse(below.overlaps(above))
        assertFalse(above.overlaps(below))
    }

    @Test
    fun `ranges sharing a closed bound do overlap`() {
        assertTrue(McRange.parse("[1.20,1.20.2]").overlaps(McRange.parse("[1.20.2,1.21)")))
    }

    @Test
    fun `a malformed range is rejected rather than half-parsed`() {
        assertThrows(IllegalArgumentException::class.java) { McRange.parse("1.20.1") }
        assertThrows(IllegalArgumentException::class.java) { McRange.parse("[1.20.1,1.21") }
        assertThrows(IllegalArgumentException::class.java) { McRange.parse("[,)") }
    }

    @Test
    fun `a hull spans every range and keeps the gap between them`() {
        val hull = McRange.hull(listOf(McRange.parse("[1.16,1.17)"), McRange.parse("[1.20,1.21)")))
        assertEquals("[1.16,1.21)", hull.toString())
        assertTrue(hull.contains("1.18"))       // the gap is inside — the bootstrapper refuses it
    }

    // ── the refusal ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two variants of one loader over the same versions are refused`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            descriptor(
                variant("fabric", "[1.20.1,1.21)"),
                variant("fabric", "[1.20.4,1.21)"),
            )
        }
        val message = thrown.message.orEmpty()
        assertTrue(message, message.contains("fabric"))
        assertTrue(message, message.contains("declaration order"))
    }

    @Test
    fun `two variants of one loader over adjacent versions are fine`() {
        val d = descriptor(
            variant("fabric", "[1.20.1,1.20.2)"),
            variant("fabric", "[1.20.2,1.21)"),
        )
        assertEquals(2, d.variantsOf("fabric").size)
    }

    @Test
    fun `different loaders may claim the same versions`() {
        val d = descriptor(
            variant("forge", "[1.20.1,1.21)"),
            variant("fabric", "[1.20.1,1.21)"),
        )
        assertEquals(2, d.variants.size)
    }

    @Test
    fun `an unparseable range is refused by the constructor`() {
        assertThrows(IllegalArgumentException::class.java) { descriptor(variant("fabric", "1.20.1")) }
    }

    @Test
    fun `the shipping declaration is accepted unchanged`() {
        val d = descriptor(
            variant("fml1710", "[1.7.10]"),
            variant("forge", "[1.20.1,1.21)"),
            variant("neoforge", "[1.20.4,1.21)"),
            variant("fabric", "[1.20.1,1.21)"),
        )
        assertEquals(4, d.variants.size)
    }

    // ── the merges a second variant depends on ──────────────────────────────────────────────────

    @Test
    fun `every fabric variant's entry points reach the merged descriptor`() {
        val json = FabricModJson.merged(descriptor(
            variant("fabric", "[1.20.1,1.20.2)", common = "a.Common", client = "a.Client"),
            variant("fabric", "[1.20.2,1.21)", common = "b.Common", client = "b.Client"),
        ))
        assertTrue(json, json.contains("\"a.Common\""))
        assertTrue(json, json.contains("\"b.Common\""))
        assertTrue(json, json.contains("\"a.Client\""))
        assertTrue(json, json.contains("\"b.Client\""))
    }

    /**
     * A client-only mod gets no `main` entrypoint, even with a bootstrapper declared.
     *
     * A bootstrapper named under `main` must implement `ModInitializer`; the language stack's only
     * implements `ClientModInitializer`, so naming it there failed the main entrypoint stage and took
     * the whole client down — measured on an installed 1.20.1 Fabric client, 2026-09-12.
     */
    @Test
    fun `a bootstrapper is named only for the halves its variants actually have`() {
        val clientOnly = ModDescriptor(
            id = "crystalgui_language", name = "L", version = "1", description = "d", license = "l",
            variants = listOf(variant("fabric", "[1.20.1,1.21)", client = "a.Client")),
            bootstrappers = mapOf("fabric" to "a.Boot"),
        )
        val json = FabricModJson.merged(clientOnly)
        assertTrue(json, json.contains("\"client\": [\"a.Boot\"]"))
        assertTrue(json, json.contains("\"main\": []"))
    }

    @Test
    fun `a mod with both halves names the bootstrapper for both`() {
        val json = FabricModJson.merged(ModDescriptor(
            id = "crystalgui", name = "C", version = "1", description = "d", license = "l",
            variants = listOf(variant("fabric", "[1.20.1,1.21)", common = "a.Common", client = "a.Client")),
            bootstrappers = mapOf("fabric" to "a.Boot"),
        ))
        assertTrue(json, json.contains("\"main\": [\"a.Boot\"]"))
        assertTrue(json, json.contains("\"client\": [\"a.Boot\"]"))
    }

    @Test
    fun `differing constraints on one dependency are OR-ed, not overwritten`() {
        val json = FabricModJson.merged(descriptor(
            variant("fabric", "[1.20.1,1.20.2)", depends = mapOf("minecraft" to "~1.20.1")),
            variant("fabric", "[1.20.2,1.21)", depends = mapOf("minecraft" to "~1.20.4")),
        ))
        assertTrue(json, json.contains("[\"~1.20.1\", \"~1.20.4\"]"))
    }

    @Test
    fun `one constraint stays a plain string`() {
        val json = FabricModJson.merged(descriptor(
            variant("fabric", "[1.20.1,1.21)", depends = mapOf("fabricloader" to ">=0.15.0")),
        ))
        assertTrue(json, json.contains("\"fabricloader\": \">=0.15.0\""))
    }

    @Test
    fun `an identical constraint declared twice is not duplicated`() {
        val json = FabricModJson.merged(descriptor(
            variant("fabric", "[1.20.1,1.20.2)", depends = mapOf("fabricloader" to ">=0.15.0")),
            variant("fabric", "[1.20.2,1.21)", depends = mapOf("fabricloader" to ">=0.15.0")),
        ))
        assertTrue(json, json.contains("\"fabricloader\": \">=0.15.0\""))
        // This dependency's own value, not the document: `entrypoints` is always an array.
        assertFalse(json, json.contains("\"fabricloader\": ["))
    }

    // ── the variant table the bootstrapper reads ────────────────────────────────────────────────

    @Test
    fun `the variant table carries every row with the fields the reader requires`() {
        val json = VariantsJson.merged(descriptor(
            variant("fabric", "[1.20.1,1.20.2)", common = "a.Common", client = "a.Client"),
            variant("forge", "[1.20.1,1.21)", common = "b.Common"),
        ))
        assertTrue(json, json.contains("\"format\": 1"))
        assertTrue(json, json.contains("\"mod\": \"crystalgui\""))
        assertTrue(json, json.contains("\"loader\": \"fabric\""))
        assertTrue(json, json.contains("\"minecraft\": \"[1.20.1,1.20.2)\""))
        assertTrue(json, json.contains("\"common\": \"a.Common\""))
        assertTrue(json, json.contains("\"client\": \"a.Client\""))
        assertTrue(json, json.contains("\"loader\": \"forge\""))
    }

    /** An absent client entry is omitted, so the reader sees null rather than an empty class name. */
    @Test
    fun `a variant with no client entry omits the key`() {
        val json = VariantsJson.merged(descriptor(variant("forge", "[1.20.1,1.21)", common = "b.Common")))
        assertFalse(json, json.contains("\"client\""))
    }

    @Test
    fun `variants keep declaration order, which is the order the bootstrapper reads`() {
        val json = VariantsJson.merged(descriptor(
            variant("fabric", "[1.20.1,1.20.2)", common = "first.Entry"),
            variant("fabric", "[1.20.2,1.21)", common = "second.Entry"),
        ))
        assertTrue(json, json.indexOf("first.Entry") < json.indexOf("second.Entry"))
    }

    @Test
    fun `the forge family's Minecraft range is the hull of its variants`() {
        val d = descriptor(
            variant("forge", "[1.20.1,1.21)"),
            variant("neoforge", "[1.20.4,1.21)"),
        )
        assertEquals("[1.20.1,1.21)", ForgeModsToml.minecraftUnion(d.variantsOf("forge", "neoforge")))
    }

    @Test
    fun `one open-ended variant opens the whole hull`() {
        val d = descriptor(
            variant("forge", "[1.20.1,1.21)"),
            variant("neoforge", "[1.21,)"),
        )
        assertEquals("[1.20.1,)", ForgeModsToml.minecraftUnion(d.variantsOf("forge", "neoforge")))
    }

    // ── a node's relocated names (J11.1b) ───────────────────────────────────────────────────────

    /** Two nodes of one loader, each shipping its classes under its own package. */
    private fun twoFabricNodes() = descriptor(
        variant("fabric", "[1.20.1,1.20.2)", common = "m.fabric.Common", client = "m.fabric.Client")
            .copy(relocation = "m.fabric" to nodePackage("m.fabric", "1.20.1")),
        variant("fabric", "[1.20.4,1.20.5)", common = "m.fabric.Common", client = "m.fabric.Client")
            .copy(relocation = "m.fabric" to nodePackage("m.fabric", "1.20.4")),
    )

    @Test
    fun `a node's package is the loader's plus its version digits`() {
        assertEquals("m.fabric.v1204", nodePackage("m.fabric", "1.20.4"))
        assertEquals("m.fabric.v121", nodePackage("m.fabric", "1.21"))
    }

    /** The shipped table is what the merged jar's classes are called, so two nodes never share a name. */
    @Test
    fun `the merged table names each node's entries at its own package`() {
        val json = VariantsJson.merged(twoFabricNodes())
        assertTrue(json, json.contains("\"common\": \"m.fabric.v1201.Common\""))
        assertTrue(json, json.contains("\"common\": \"m.fabric.v1204.Common\""))
        assertFalse(json, json.contains("\"m.fabric.Common\""))
    }

    /** A dev run loads the classes unrelocated, so its table must keep the source names. */
    @Test
    fun `a node's dev table keeps source names and holds that node alone`() {
        val d = twoFabricNodes()
        val json = VariantsJson.dev(d, d.variants[1])
        assertTrue(json, json.contains("\"common\": \"m.fabric.Common\""))
        assertTrue(json, json.contains("\"minecraft\": \"[1.20.4,1.20.5)\""))
        assertFalse(json, json.contains("[1.20.1,1.20.2)"))
    }

    @Test
    fun `required entries are the shipped names of every node`() {
        assertEquals(
            listOf("m/fabric/v1201/Common.class", "m/fabric/v1201/Client.class",
                "m/fabric/v1204/Common.class", "m/fabric/v1204/Client.class"),
            twoFabricNodes().shippedEntryPaths())
    }

    /** A name outside the relocated package is left alone -- a relocation is a prefix, not a rename. */
    @Test
    fun `only names under the relocated package are rewritten`() {
        val v = variant("fabric", "[1.20.1,1.20.2)").copy(relocation = "m.fabric" to "m.fabric.v1201")
        assertEquals("m.fabricated.X", v.shipped("m.fabricated.X"))
        assertEquals("m.fabric.v1201.lang.X", v.shipped("m.fabric.lang.X"))
    }

    @Test
    fun `a range becomes fabric's predicate`() {
        assertEquals(">=1.20.4 <1.20.5", McRange.parse("[1.20.4,1.20.5)").toFabricPredicate())
        assertEquals("1.7.10", McRange.parse("[1.7.10]").toFabricPredicate())
        assertEquals(">=1.20.1", McRange.parse("[1.20.1,)").toFabricPredicate())
    }
}
