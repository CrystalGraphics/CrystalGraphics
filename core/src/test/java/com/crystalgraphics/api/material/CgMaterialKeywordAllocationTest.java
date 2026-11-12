package com.crystalgraphics.api.material;

import com.crystalgraphics.material.CgMaterialProxy;
import org.junit.Test;

import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgAllocationProof;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** A material past eight keywords resolves its variants from a cache: once warm, no allocation. */
public class CgMaterialKeywordAllocationTest {

    private static final String SOURCE = String.join(System.lineSeparator(),
            "#type spatial",
            "#pragma cg_feature K0", "#pragma cg_feature K1", "#pragma cg_feature K2", "#pragma cg_feature K3",
            "#pragma cg_feature K4", "#pragma cg_feature K5", "#pragma cg_feature K6", "#pragma cg_feature K7",
            "#pragma cg_feature K8", "#pragma cg_feature K9", "#pragma cg_feature K10",
            "Properties { _Color (\"Color\", color) = (1, 1, 1, 1) }",
            "Pass {",
            "    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }",
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = _Color; }",
            "}");

    @Test
    public void aVariantPastEightKeywordsResolvesWithoutAllocating() {
        CgMaterial even = CgMaterial.fromSource(SOURCE);
        for (int k = 0; k <= 10; k += 2) even = even.keyword("K" + k, true);
        CgMaterial m = even;
        CgPipeline a = CgMaterialProxy.of(m).pipeline(CgInstanceKind.OBJECT);
        assertSame(a, CgMaterialProxy.of(m).pipeline(CgInstanceKind.OBJECT));
        CgPipeline b = CgMaterialProxy.of(m.keyword("K9", true)).pipeline(CgInstanceKind.OBJECT);
        assertNotSame(a, b);
        assertSame(m, m.keyword("K9", true).keyword("K9", false));
        CgAllocationProof.assertZero("CgMaterial.pipeline, 11 keywords", 2000, 20000,
                () -> CgMaterialProxy.of(m).pipeline(CgInstanceKind.OBJECT));
        CgAllocationProof.assertZero("CgMaterial.keyword, a variant made", 2000, 20000, () -> m.keyword("K9", true));
    }
}
