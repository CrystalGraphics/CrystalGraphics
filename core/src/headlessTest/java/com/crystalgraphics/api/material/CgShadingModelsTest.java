package com.crystalgraphics.api.material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Shading models by registry, a masked material's threshold, and domains checked at load. */
public class CgShadingModelsTest {

    private static final String DECAL = "test:shaders/decal.shader", FX = "test:shaders/fx.shader";

    @BeforeClass
    public static void kit() {
        CgAssetKinds.register();
        // Kakarot's kit declares its models at its game's numbers, carried by the archetypes' CELL_TONE keyword.
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("cell_tone", map("index", 13, "base", "lit", "keyword", "CELL_TONE"));
        declared.put("cell_shade", map("index", 10, "base", "lit"));
        List<CgShadingModel> made = CgShadingModels.registerKit("kakarot", declared);
        assertEquals(2, made.size());
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static CgMaterialDefinition compile(String id, Object... kv) {
        Map<String, Object> tree = map(kv);
        tree.put("_v", 1);
        return CgMaterialDefinitions.compile(CgAssetData.of(id, CgLookKinds.MATERIAL, tree, null),
                CgMaterialDefinitionsTest.ARCHETYPES);
    }

    @Test
    public void cellToneRegistersFromItsKitAndCarriesItsKeyword() {
        CgShadingModel cell = CgShadingModels.get("kakarot:cell_tone");
        assertEquals("CELL_TONE", cell.keyword());
        CgMaterialDefinition d = compile("test:crack", "shader", DECAL,
                "state", map("blend", "translucent", "shading", "kakarot:cell_tone"));
        assertSame(cell, d.shading());
        assertEquals(Collections.singletonList("CELL_TONE"), d.permutation().keywords());
        assertEquals("kakarot:cell_tone", d.permutation().shading());
        assertSame(CgShadingModels.LIT, CgShadingModels.get("lit"));
    }

    @Test
    public void anUnknownShadingModelFailsAtLoad() {
        fails("kakarot:cel_tone", () -> compile("test:typo", "shader", DECAL, "state", map("shading", "kakarot:cel_tone")));
    }

    @Test
    public void aBaseMustBeTheEngines() {
        try {
            CgShadingModels.register(new CgShadingModel("mymod:x", 40, "toon", null, null));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("toon"));
        }
    }

    @Test
    public void anIndexIsTheGamesAndHeldOnce() {
        assertSame(CgShadingModels.get("kakarot:cell_tone"), CgShadingModels.at(13));
        assertEquals(1, CgShadingModels.LIT.index());
        assertEquals("13 needs four bits", 4, CgShadingModels.indexBits());
        IllegalArgumentException clash = assertThrows(IllegalArgumentException.class,
                () -> CgShadingModels.register(new CgShadingModel("mymod:clash", 13, "lit", null, null)));
        assertTrue(clash.getMessage(), clash.getMessage().contains("kakarot:cell_tone"));
        assertThrows(IllegalArgumentException.class,
                () -> CgShadingModels.register(new CgShadingModel("mymod:far", 256, "lit", null, null)));
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> CgShadingModels.registerKit("mymod", map("toon", map("base", "lit"))));
        assertTrue(none.getMessage(), none.getMessage().contains("index"));
    }

    @Test
    public void maskClipIsTheMaskedThreshold() {
        CgMaterialDefinition d = compile("test:masked", "shader", DECAL, "state", map("blend", "masked", "mask_clip", 0.33));
        assertTrue(d.permutation().clip());
        assertArrayEquals(new float[] {0.33f}, d.constants().get("_Clip"), 1e-6f);
        CgMaterialDefinition set = compile("test:masked_set", "shader", DECAL,
                "state", map("blend", "masked", "mask_clip", 0.33), "values", map("_Clip", 0.7));
        assertArrayEquals("an explicit value wins", new float[] {0.7f}, set.constants().get("_Clip"), 1e-6f);
        fails("_Clip", () -> compile("test:no_clip", "shader", FX, "state", map("blend", "masked", "mask_clip", 0.5)));
    }

    @Test
    public void aWrongDomainFailsAtLoad() {
        CgMaterialDefinition decal = compile("test:decal", "shader", DECAL);
        assertEquals(CgMaterialDomain.DECAL, decal.domain());
        decal.requireDomain(CgMaterialDomain.DECAL, "a decal spawnable");
        fails("decal", () -> decal.requireDomain(CgMaterialDomain.SURFACE, "renderer sprite"));
        fails("post", () -> compile("test:not_post", "shader", FX, "post", map("priority", 1)));
        assertEquals(CgMaterialDomain.SURFACE, CgMaterialDomain.ofSource("Tags { \"RenderType\" = \"Opaque\" }"));
        assertEquals(CgMaterialDomain.POST, CgMaterialDomain.ofSource("Tags { \"Domain\" = \"Post\" }"));
    }

    private static void fails(String naming, Runnable r) {
        try {
            r.run();
            fail("did not fail naming " + naming);
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(naming));
        }
    }
}
