package com.crystalgraphics.api.material;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.gl.material.parse.CgParsedShader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The engine's archetypes, found by the paths kits name them by, and a kit-shaped material compiling against each. */
public class CgArchetypesTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void aKitsShaderPathIsTheEnginesArchetype() {
        assertEquals("crystalgraphics:shaders/vfx/archetype/cc2_effect.shader",
                CgMaterialArchetypes.FILES.resolve("nsuns4_kit:shaders/cc2_effect.shader"));
        CgParsedShader effect = CgMaterialArchetypes.FILES.parse("nsuns4_kit:shaders/cc2_effect.shader");
        assertEquals(11, effect.featureNames().size());
        assertTrue(effect.featureNames().containsAll(Arrays.asList("SECOND_LAYER", "RIM_FALLOFF", "SCREEN_TEXTURE_2",
                "LIGHTMAP_BLEND", "SOFT_PARTICLE")));
    }

    @Test
    public void aCc2EffectMaterialCompiles() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("shader", "nsuns4_kit:shaders/cc2_effect.shader");
        tree.put("state", map("blend", "additive", "two_sided", true));
        tree.put("keywords", Arrays.asList("SECOND_LAYER", "RIM_FALLOFF", "SCREEN_TEXTURE", "SCREEN_TEXTURE_2"));
        tree.put("values", map("_Glare", 0.5, "_UV0Window", Arrays.asList(0.0, 0.0, 1.0, 1.0), "_OutlineID", 1.0,
                "_BlendRate1", 0.5, "_BlendType", Arrays.asList(1.0, 0.0, 0.0, 0.0), "_UvScaleScreen",
                Arrays.asList(2.0, 2.0, 1.0, 1.0), "_Falloff", 0.25, "_Clip", 0.01));
        tree.put("textures", map("_Tex0", map("texture", "nsuns4_kit:textures/a.png"),
                "_Tex4", map("texture", "nsuns4_kit:textures/b.png")));
        CgMaterialDefinition d = CgMaterialDefinitions.compile(
                CgAssetData.of("nsuns4_kit:fx/wave", CgLookKinds.MATERIAL, tree, null), CgMaterialArchetypes.FILES);
        assertEquals("crystalgraphics:shaders/vfx/archetype/cc2_effect.shader", d.permutation().shader());
        assertEquals(4, d.permutation().keywords().size());
        assertEquals(CgMaterialDomain.SURFACE, d.domain());
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
