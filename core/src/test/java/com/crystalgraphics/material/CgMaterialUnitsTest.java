package com.crystalgraphics.material;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.render.shader.CgBindingModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** A material's textures against the GPU's units: refused past them where textures are bound, free under bindless. */
public class CgMaterialUnitsTest {

    /** 28 textures: one past the null device's 32 units less the view's. */
    private static final String SOURCE = """
            #domain global
            Material { Queue Geometry }
            Properties {
                _T0 ("t", sampler2D) = "white"
                _T1 ("t", sampler2D) = "white"
                _T2 ("t", sampler2D) = "white"
                _T3 ("t", sampler2D) = "white"
                _T4 ("t", sampler2D) = "white"
                _T5 ("t", sampler2D) = "white"
                _T6 ("t", sampler2D) = "white"
                _T7 ("t", sampler2D) = "white"
                _T8 ("t", sampler2D) = "white"
                _T9 ("t", sampler2D) = "white"
                _T10 ("t", sampler2D) = "white"
                _T11 ("t", sampler2D) = "white"
                _T12 ("t", sampler2D) = "white"
                _T13 ("t", sampler2D) = "white"
                _T14 ("t", sampler2D) = "white"
                _T15 ("t", sampler2D) = "white"
                _T16 ("t", sampler2D) = "white"
                _T17 ("t", sampler2D) = "white"
                _T18 ("t", sampler2D) = "white"
                _T19 ("t", sampler2D) = "white"
                _T20 ("t", sampler2D) = "white"
                _T21 ("t", sampler2D) = "white"
                _T22 ("t", sampler2D) = "white"
                _T23 ("t", sampler2D) = "white"
                _T24 ("t", sampler2D) = "white"
                _T25 ("t", sampler2D) = "white"
                _T26 ("t", sampler2D) = "white"
                _T27 ("t", sampler2D) = "white"
            }
            void vertex(out v2f o) { }
            void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
            """;

    private CgNullRHI rhi;

    @Before
    public void resetRegistry() {
        CgMaterialShaderRegistry.resetForTest();
    }

    @After
    public void uninstall() {
        rhi.close();
        CgRHI.install(null);
    }

    private CgMaterialShader load(CgNullRHI device) {
        rhi = device.withoutLog();
        CgRHI.install(rhi);
        return CgMaterialShaderRegistry.get().getOrCreateGenerated(SOURCE);
    }

    @Test
    public void aMaterialPastTheUnitsIsRefusedWhereTexturesAreBound() {
        CgMaterialShader shader = load(new CgNullRHI(64, 64).withoutBindless());
        assertEquals("the fixture is one past the room", 28,
                rhi.globals().limits().maxTextureUnits() - CgBindingModel.VIEW_TEXTURES + 1);
        assertNull("refused at its parse", shader.ensureParsed());
    }

    @Test
    public void bindlessTakesNoUnitSoTheSameMaterialLoads() {
        assertNotNull("read by index: no unit to run out of", load(new CgNullRHI(64, 64)).ensureParsed());
    }
}
