package com.crystalgraphics.api.material;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.material.shader.CgShaderSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A kit's material on the archetype the kit ships, and one naming an archetype nobody ships. */
public class CgArchetypesTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void aKitsMaterialCompilesAgainstItsOwnArchetype() {
        CgShaderSpec effect = CgMaterialArchetypes.FILES.parse("cgtest:shaders/kit_effect.shader");
        assertTrue(effect.features().containsAll(Arrays.asList("SECOND_LAYER", "RIM_FALLOFF")));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("shader", "cgtest:shaders/kit_effect.shader");
        tree.put("state", map("BlendMode", "Additive", "Faces", "Both"));
        tree.put("keywords", Arrays.asList("SECOND_LAYER", "RIM_FALLOFF"));
        tree.put("values", map("_Glare", 0.5));
        tree.put("textures", map("_Tex0", map("texture", "cgtest:textures/a.png")));
        CgMaterialDefinition d = CgMaterialDefinitions.compile(
                CgAssetData.of("cgtest:fx/wave", CgLookKinds.MATERIAL, tree, null), CgMaterialArchetypes.FILES);
        assertEquals("cgtest:shaders/kit_effect.shader", d.permutation().shader());
        assertEquals(2, d.permutation().keywords().size());
        assertEquals(CgMaterialDomain.SURFACE, d.domain());
    }

    @Test
    public void anArchetypeNoKitShipsFailsNamingIt() {
        try {
            CgMaterialArchetypes.FILES.parse("nsuns4_kit:shaders/cc2_effect.shader");
            fail("the engine ships no kit's archetype");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nsuns4_kit:shaders/cc2_effect.shader"));
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
