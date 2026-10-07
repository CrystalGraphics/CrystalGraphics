package com.crystalgraphics.vfx.particle.gpu.draw;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class CgVfxRangeTest {

    private static final String RANGE = "crystalgraphics:shaders/env/compute/vfx/range.compute";

    @Test
    public void rangesKernelsRunOnEveryTier() {
        CgCompute range = CgCompute.load(RANGE);
        range.kernel("Key").check();
        range.kernel("Place").check();
        range.kernel("Objects").check();
    }

    @Test
    public void rangeReadsTheRecordTheStepKernelWrites() {
        assertTrue(CgIO.loadSource(RANGE).contains(CgVfxRecord.GLSL));
    }
}
