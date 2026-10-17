package com.crystalgraphics.api.material;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CgParameterCollectionTest {

    @Test
    public void scalarsPackByFourThenVectorsAsUnrealLaysThemOut() {
        CgParameterCollection skill = CgParameterCollection.of("layoutSkill");
        CgParameterCollection.Scalar[] s = new CgParameterCollection.Scalar[5];
        for (int i = 0; i < s.length; i++) s[i] = skill.scalar("s" + i, i);
        CgParameterCollection.Vector tint = skill.vector("tint", 0xFF804020);
        CgParameterCollection.Vector wind = skill.vector("wind", 1f, 2f, 3f, 4f);
        CgParameterCollection next = CgParameterCollection.of("layoutNext");
        CgParameterCollection.Scalar after = next.scalar("after", 7f);

        int base = s[0].offset();
        assertEquals("a collection starts on a vec4", 0, base % 4);
        for (int i = 0; i < s.length; i++) assertEquals(base + i, s[i].offset());
        assertEquals("five scalars take two vec4s, then the vectors", base + 8, tint.offset());
        assertEquals(base + 12, wind.offset());
        assertEquals("the next collection follows, in the order defined", base + 16, after.offset());

        assertEquals(3f, s[3].get(), 0f);
        assertArrayEquals(new float[] {128 / 255f, 64 / 255f, 32 / 255f, 1f}, tint.get(new float[4]), 1e-6f);
        s[3].set(0.25f);
        wind.set(0xFFFFFFFF, 2f);
        assertEquals(0.25f, s[3].get(), 0f);
        assertArrayEquals(new float[] {2f, 2f, 2f, 1f}, wind.get(new float[4]), 0f);
        skill.reset();
        assertEquals(3f, s[3].get(), 0f);
        assertArrayEquals(new float[] {1f, 2f, 3f, 4f}, wind.get(new float[4]), 0f);
    }

    @Test
    public void aShaderReadsEachParameterByNamespaceAndName() {
        CgParameterCollection stage = CgParameterCollection.of("glslStage");
        CgParameterCollection.Scalar darken = stage.scalar("darken", 0f);
        CgParameterCollection.Scalar wet = stage.scalar("wet", 0f);
        CgParameterCollection.Vector tint = stage.vector("tint", 0xFFFFFFFF);
        String glsl = CgParameterCollection.glsl();
        int v = darken.offset() / 4;
        assertTrue(glsl, glsl.contains("#define CG_GLOBAL_glslStage_darken (CG_GLOBAL_VEC4(" + v + ").x)\n"));
        assertTrue(glsl, glsl.contains("#define CG_GLOBAL_glslStage_wet (CG_GLOBAL_VEC4(" + v + ").y)\n"));
        assertTrue(glsl, glsl.contains("#define CG_GLOBAL_glslStage_tint (CG_GLOBAL_VEC4(" + (v + 1) + "))\n"));
        assertEquals(tint.offset(), (v + 1) * 4);
    }

    @Test
    public void aPlacedCollectionTakesNoMoreParametersAndNamesAreChecked() {
        CgParameterCollection used = CgParameterCollection.of("placedOnce");
        used.scalar("a", 1f).get();
        assertTrue(used.placed());
        assertThrows(IllegalStateException.class, () -> used.scalar("b", 0f));
        assertThrows(IllegalStateException.class, () -> CgParameterCollection.of("placedOnce"));

        CgParameterCollection named = CgParameterCollection.of("namesChecked");
        named.scalar("x", 0f);
        assertThrows(IllegalStateException.class, () -> named.vector("x", 0xFF000000));
        assertThrows(IllegalArgumentException.class, () -> named.scalar("two__underscores", 0f));
        assertThrows(IllegalArgumentException.class, () -> named.scalar("1st", 0f));
        assertThrows(IllegalArgumentException.class, () -> CgParameterCollection.of("has-dash"));
    }
}
