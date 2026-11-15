package com.crystalgraphics.api.material;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Every blend family and shading base of the base surface material loads, each from its own shader. */
public class CgSurfaceMaterialTest {

    @Test
    public void everyBlendAndShadingLoads_eachFromTheShaderThatNamesItsOutputs() {
        for (CgShadingModel shading : new CgShadingModel[] {CgShadingModels.LIT, CgShadingModels.UNLIT}) {
            String unlit = shading == CgShadingModels.UNLIT ? "_unlit" : "";
            assertEquals("opaque" + unlit, shader(CgSurfaceMaterial.of("test:a" + unlit).shading(shading)));
            assertEquals("masked" + unlit, shader(CgSurfaceMaterial.of("test:b" + unlit).shading(shading).masked(0.1f).twoSided()));
            for (CgBlendMode blend : new CgBlendMode[] {CgBlendMode.ALPHA, CgBlendMode.ADDITIVE, CgBlendMode.MODULATE}) {
                assertEquals("translucent" + unlit, shader(CgSurfaceMaterial.of("test:c" + blend + unlit).shading(shading)
                        .blend(blend).separateTranslucency().emissive(2f).tint(0x80FF0000)));
            }
            assertEquals("decal" + unlit, shader(CgSurfaceMaterial.decal("test:d" + unlit).shading(shading)));
        }
    }

    @Test
    public void whatABaseDoesNotHaveIsRefusedAtTheCall() {
        assertThrows(IllegalStateException.class, () -> CgSurfaceMaterial.decal("test:d").twoSided());
        assertThrows(IllegalStateException.class, () -> CgSurfaceMaterial.of("test:e").separateTranslucency().definition());
    }

    @Test
    public void maskedAndABlendAreOneChoice_theLastCallWins() {
        assertEquals("translucent", shader(CgSurfaceMaterial.of("test:f").masked(0.5f).blend(CgBlendMode.ALPHA)));
        assertEquals("masked", shader(CgSurfaceMaterial.of("test:g").blend(CgBlendMode.ALPHA).masked(0.5f)));
    }

    /** The base shader {@code b}'s definition draws with: its file's name; building it loads its archetype. */
    private static String shader(CgSurfaceMaterial.Builder b) {
        String path = b.definition().permutation().shader();
        assertTrue(path, path.startsWith("crystalgraphics:shaders/materials/surface/"));
        return path.substring(path.lastIndexOf('/') + 1, path.length() - ".shader".length());
    }
}
