package com.crystalgraphics.api.material;

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
        CgMaterial m = CgMaterial.fromSource(SOURCE);
        for (int k = 0; k <= 10; k += 2) m.enableKeyword("K" + k);
        CgPipeline a = m.pipeline(CgInstanceKind.OBJECT);
        assertSame(a, m.pipeline(CgInstanceKind.OBJECT));
        m.enableKeyword("K9");
        CgPipeline b = m.pipeline(CgInstanceKind.OBJECT);
        assertNotSame(a, b);
        m.disableKeyword("K9");
        assertSame(a, m.pipeline(CgInstanceKind.OBJECT));
        CgAllocationProof.assertZero("CgMaterial.pipeline, 11 keywords", 2000, 20000,
                () -> m.pipeline(CgInstanceKind.OBJECT));
    }
}
