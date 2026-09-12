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
 * Every case here is one a J11 row can actually produce: the shipping Fabric variant claims
 * `[1.20.1,1.21)`, which already covers 1.20.4, so adding a 1.20.4 row without narrowing it is the
 * overlap this refuses.
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
}
