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
            "#domain surface",
            "Keywords { K0 (\"K0\")  K1 (\"K1\")  K2 (\"K2\")  K3 (\"K3\")  K4 (\"K4\")  K5 (\"K5\")  K6 (\"K6\")",
            "           K7 (\"K7\")  K8 (\"K8\")  K9 (\"K9\")  K10 (\"K10\") }",
            "Properties { _Color (\"Color\", color) = (1, 1, 1, 1) }",
            "void surface(in v2f i, inout CgSurface s) { s.baseColor = _Color.rgb; }");

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
