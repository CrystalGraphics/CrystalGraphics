package com.crystalgraphics.api.material;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.CgMaterialProperties;
import com.crystalgraphics.material.CgMaterialProperty;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class CgMaterialTest {

    private static CgMaterial mat() {
        return CgMaterial.forTest(CgRenderState.DEFAULT, 2000);
    }

    // ── getRenderQueue ────────────────────────────────────────────────────────

    @Test
    public void getRenderQueue_returnsConstructedValue() {
        CgMaterial m = CgMaterial.forTest(CgRenderState.DEFAULT, 3000);
        assertEquals(3000, CgMaterialProxy.of(m).getRenderQueue());
    }

    // ── T7: property defaults ─────────────────────────────────────────────────

    @Test
    public void freshMaterial_hasDefaultPropertyInUbo() {
        CgMaterialProperty prop = CgMaterialProperty.fromDecl("_Color", "_Color", "vec4", "(0.5, 0.5, 0.5, 1.0)");
        CgMaterialProperties props = new CgMaterialProperties(Collections.singletonList(prop));
        CgMaterial m = CgMaterial.forTest(CgRenderState.DEFAULT, 2000, props);

        float[] values = m.getPropertyForTest("_Color").getFloatValue();
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f, 1.0f}, values, 1e-4f);
    }

    @Test
    public void anInstanceSetsItsOwnValuesAndLeavesItsMaterials() {
        CgMaterialProperty prop = CgMaterialProperty.fromDecl("_Color", "_Color", "vec4", "(0.5, 0.5, 0.5, 1.0)");
        CgMaterialProperties props = new CgMaterialProperties(Collections.singletonList(prop));
        CgMaterial m = CgMaterial.forTest(CgRenderState.DEFAULT, 2000, props);
        CgMaterialInstance mine = m.instance().vec4("_Color", 1f, 0f, 0f, 1f);
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f}, value(mine), 1e-4f);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f, 1f}, m.getPropertyForTest("_Color").getFloatValue(), 1e-4f);
        assertArrayEquals("a second starts from the material's", new float[]{0.5f, 0.5f, 0.5f, 1f}, value(m.instance()), 1e-4f);
    }

    @Test
    public void anInstanceCopiesItsMaterialsValuesWhenMade() {
        CgMaterialProperty prop = CgMaterialProperty.fromDecl("_Color", "_Color", "vec4", "(0.5, 0.5, 0.5, 1.0)");
        CgMaterial m = CgMaterial.forTest(CgRenderState.DEFAULT, 2000, new CgMaterialProperties(Collections.singletonList(prop)));
        CgMaterialProxy.of(m).writer().vec4("_Color", 1f, 0f, 0f, 1f);
        CgMaterialInstance instance = m.instance();
        CgMaterialProxy.of(m).writer().vec4("_Color", 0f, 0f, 1f, 1f);
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f}, value(instance), 0f);
    }

    private static float[] value(CgMaterialInstance instance) {
        for (CgMaterialProperty p : instance.properties()) if (p.getName().equals("_Color")) return p.getFloatValue();
        throw new AssertionError("_Color not declared");
    }
}
