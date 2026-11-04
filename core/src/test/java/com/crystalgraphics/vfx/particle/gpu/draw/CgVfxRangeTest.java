package com.crystalgraphics.vfx.particle.gpu.draw;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CgVfxRangeTest {

    private static final String RANGE = "crystalgraphics:shaders/env/compute/vfx/range.compute";
    private static final String LOOK = "crystalgraphics:shaders/env/compute/vfx/look.compute";

    @Test
    public void rangesKernelsParse() {
        CgCompute range = CgCompute.load(RANGE);
        assertNotNull(range.kernel("Key").decl());
        assertNotNull(range.kernel("Place").decl());
        assertNotNull(range.kernel("Objects").decl());
    }

    @Test
    public void rangeAndTheDrawsReadTheDrawnPartLookWrites() {
        assertTrue(CgIO.loadSource(LOOK).contains(CgVfxRecord.DRAWN_GLSL));
        assertTrue(CgIO.loadSource(RANGE).contains(CgVfxRecord.DRAWN_GLSL));
    }
}
