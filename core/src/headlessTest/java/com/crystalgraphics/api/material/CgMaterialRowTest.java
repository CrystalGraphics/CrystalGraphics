package com.crystalgraphics.api.material;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A keyed material channel moves one draw's value without a new material; a track is refused on a non-row property. */
public class CgMaterialRowTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgMaterialDefinition glow() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("_Speed", 2.0);
        values.put("_Color", Arrays.asList(1.0, 0.5, 0.25, 1.0));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("shader", "test:shaders/fx.shader");
        tree.put("values", values);
        return CgMaterialDefinitions.compile(CgAssetData.of("test:glow", CgLookKinds.MATERIAL, tree, null),
                CgMaterialDefinitionsTest.ARCHETYPES);
    }

    @Test
    public void aKeyedChannelMovesOneDrawsValueWithoutANewMaterial() {
        CgMaterialDefinition d = glow();
        int color = d.requireRow("_Color", "track tint");
        CgMaterialRow a = new CgMaterialRow(d), b = new CgMaterialRow(d);
        assertEquals("a row starts at the material's constant", 0.5f, a.get(color, 1), 0f);

        a.set(color, 0.2f, 0.9f, 0.4f, 1f);
        assertEquals(0.9f, a.get(color, 1), 0f);
        assertEquals("the other draw keeps the constant", 0.5f, b.get(color, 1), 0f);
        assertTrue("both draws are rows of one material", a.definition() == b.definition());

        float[] rows = new float[2 * d.rows().vec4s() * 4];
        a.copyTo(rows, 0);
        b.copyTo(rows, d.rows().vec4s());
        assertEquals(0.9f, rows[color * 4 + 1], 0f);
        assertEquals(0.5f, rows[(d.rows().vec4s() + color) * 4 + 1], 0f);
    }

    @Test
    public void aTrackIsRefusedOnANonRowProperty() {
        CgMaterialDefinition d = glow();
        try {
            d.requireRow("_MainTex", "track flicker");
            fail("a sampler is no row property");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("track flicker") && e.getMessage().contains("_MainTex"));
        }
    }
}
