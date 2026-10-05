package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgVfxEmitterCompilerTest {

    /** Every module kind, the ground the one after the solver, and drag twice. */
    private static final CgVfxEmitter EVERY_KIND = CgVfxEmitter.builder("every").renderer(CgVfxEmitter.Renderer.QUADS)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(0.3f, 0.15f))
            .module(new CgVfxModule.Wind(0.2f))
            .module(new CgVfxModule.Turbulence(1f, 0.06f, 0.15f))
            .module(new CgVfxModule.Buoyancy(1.2f, 2.5f))
            .module(new CgVfxModule.Updraft(4f, 2f, 6f, 1f))
            .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f))
            .module(new CgVfxModule.Spin(0.5f))
            .module(new CgVfxModule.Drag(0.1f, 0f))
            .build();

    @Test
    public void modulesRunInStackOrderAroundTheSolver() {
        String glsl = CgVfxEmitterCompiler.source(CgVfxShape.of(EVERY_KIND));
        int solve = glsl.indexOf("CgVfxEmitterInstance.solve");
        String[] before = {"fx_gravity(p, f, s", "fx_drag(p, f, s", "fx_wind(p, f, s", "fx_turbulence(p, f, s",
                "fx_buoyancy(p, f, s", "fx_updraft(p, f, s", "fx_spin(p, f, s"};
        int at = -1;
        for (String call : before) {
            int next = glsl.indexOf(call, at + 1);
            assertTrue(call + " in order, before the solver", next > at && next < solve);
            at = next;
        }
        assertTrue(glsl.indexOf("fx_drag(p, f, s", at) > at);                                // the second drag, last
        assertTrue(glsl.indexOf("fx_ground(p, s") > solve);
        assertEquals(1, count(glsl, "#include \"crystalgraphics:shaders/lib/vfx/sim/fx_drag.glsl\""));
    }

    @Test
    public void aModuleTakesItsRowsNumbersItsInstanceLanesAndItsWorldInputs() {
        CgVfxShape shape = CgVfxShape.of(EVERY_KIND);
        String glsl = CgVfxEmitterCompiler.source(shape);
        int turbulence = 3, ground = 6;
        int p = shape.paramAt(turbulence), l = shape.lanesAt(turbulence);
        assertTrue(glsl, glsl.contains("fx_turbulence(p, f, s, step_param(row + " + p + "), ivec4(INSTANCES(inst + " + l
                + ")), step_instance(inst + " + (l + 1) + "), ivec4(INSTANCES(inst + " + (l + 2) + ")), step_instance(inst + "
                + (l + 3) + "));"));
        assertTrue(glsl, glsl.contains("fx_ground(p, s, step_param(row + " + shape.paramAt(ground) + "), ground);"));
    }

    @Test
    public void theStepKernelRunsOnEveryTier() {
        CgVfxEmitterCompiler.compile(CgVfxShape.of(EVERY_KIND)).kernel("Step").check();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }
}
