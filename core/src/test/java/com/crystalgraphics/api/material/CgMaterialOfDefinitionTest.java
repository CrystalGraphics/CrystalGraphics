package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** A definition draws as one material: its state, keywords and constants, shared by every caller and draw keyword. */
public class CgMaterialOfDefinitionTest {

    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgMaterialDefinition sparks() {
        return CgMaterialDefinition.of("test:materials/sparks", SPRITE).usage(CgMaterialUsage.SPRITES).blend(CgBlendMode.ADDITIVE).twoSided()
                                   .color("_Color", 0xFFFF8040, 2f).value("_SoftDistance", 0.25f)
                                   .texture("_Sheet", "crystalgraphics:textures/vfx/smoke.png", CgSampler.Filter.BILINEAR,
                        CgSampler.Address.CLAMP)
                                   .build();
    }

    private static float[] value(CgMaterial m, String name) {
        for (CgMaterialProperty p : m.properties()) if (p.getName().equals(name)) return p.getFloatValue();
        throw new AssertionError(name + " is not a property of " + m);
    }

    @Test
    public void aDefinitionDrawsAsItsStateAndConstants() {
        CgMaterialDefinition d = sparks();
        CgMaterial m = CgMaterial.of(d);
        assertSame("one material a definition", m, CgMaterial.of(d));
        assertEquals(CgBlendMode.ADDITIVE, m.state().blend());
        assertEquals(d.state(), m.state());
        assertArrayEquals(new float[] {2f, 0x80 / 255f * 2f, 0x40 / 255f * 2f, 1f}, value(m, "_Color"), 1e-6f);
        assertArrayEquals(new float[] {0.25f}, value(m, "_SoftDistance"), 0f);
    }

    @Test
    public void aDrawKeywordIsItsOwnMaterialWhereTheShaderDeclaresIt() {
        CgMaterialDefinition d = sparks();
        CgMaterial plain = CgMaterial.of(d);
        List<String> declared = plain.declaredKeywords();
        String keyword = declared.isEmpty() ? "FX_ON_NOTHING" : declared.get(0);
        CgMaterial keyed = CgMaterialRegistry.get().getOrCreate(d, keyword);
        assertNotSame(plain, keyed);
        assertSame(keyed, CgMaterialRegistry.get().getOrCreate(d, keyword));
        assertEquals(declared.contains(keyword), keyed.isKeywordEnabled(keyword));
    }

    @Test
    public void aTextureOnAnUndeclaredSamplerFailsAtLoadNamingIt() {
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgMaterialDefinitions.derive(sparks(),
                Map.of("textures", Map.of("_Nope", Map.of("texture", "crystalgraphics:textures/vfx/smoke.png")))));
        assertTrue(e.getMessage(), e.getMessage().contains("_Nope"));
    }
}
