package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
        assertTrue(glsl, glsl.contains("fx_ground(p, s, step_param(row + " + shape.paramAt(ground) + "), step_floor(p, inst));"));
        // a world input reads the voxel window, so the kernel declares it
        assertTrue(shape.readsWorld());
        assertTrue(glsl.contains("lib/vfx/sim/fx_world_at.glsl") && glsl.contains("_WorldLive") && glsl.contains("float step_floor("));
    }

    /** A kind given as text, bouncing on the window's distance field, its impacts and a rate as events. */
    private static final class Bounce implements CgVfxGpuModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.WORLD_DISTANCE};
        public String gpuKind() { return "test_bounce"; }
        public boolean afterSolve() { return true; }
        public CgVfxWorldInput[] worldInputs() { return WORLD; }
        public void writeParams(CgVfxWords out) { out.vec4(0.5f, 1f, 0f, 0f); }
        public String gpuSource() {
            return """
                    void fx_test_bounce(inout FxParticle p, FxStep s, vec4 m, FxWorld world) {
                        vec3 g;
                        if (fx_world_sdf(world, p.position, g) > 0.25 || !fx_world_solid(world, p.position)) return;
                        vec3 n = length(g) > 0.0 ? normalize(g) : vec3(0.0, 1.0, 0.0);
                        if (-dot(p.velocity, n) >= m.y) fx_hit(p, n);
                        p.velocity = reflect(p.velocity, n) * m.x;
                    }
                    """;
        }
    }

    private record Hooked(CgVfxEmitter of, List<CgVfxGpuModule> modules, List<CgVfxEvent> events) implements CgVfxGpuEmitter {
        public String name() { return of.name(); }
        public CgVfxEmitter.Renderer renderer() { return of.renderer(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
    }

    @Test
    public void aCustomKindOnTheDistanceFieldFiresCollisionsAndARate() {
        List<CgVfxGpuModule> modules = new ArrayList<>(EVERY_KIND.modules());
        modules.add(new Bounce());
        CgVfxGpuEmitter hooked = new Hooked(EVERY_KIND, modules,
                List.of(CgVfxEvent.onCollision().spawn(EVERY_KIND, 2), CgVfxEvent.every(0.1f).readback(8)));
        CgVfxShape shape = CgVfxShape.of(hooked);
        String glsl = CgVfxEmitterCompiler.source(shape);
        assertTrue(shape.usesDistance() && shape.readsWorld());
        assertEquals(1, count(glsl, "void fx_test_bounce("));                                   // inlined, once
        assertTrue(glsl.contains("fx_test_bounce(p, s, step_param(row + " + shape.paramAt(modules.size() - 1) + "), step_world(inst));"));
        assertTrue(glsl.contains("if (p.hit.w > 0.0)") && glsl.contains("p.collisions);"));
        assertTrue(glsl.contains("floor((p.age - s.dt) / step_param(row + " + shape.eventsAt() + ")[1])"));
        CgVfxEmitterCompiler.compile(shape).kernel("Step").check();
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
