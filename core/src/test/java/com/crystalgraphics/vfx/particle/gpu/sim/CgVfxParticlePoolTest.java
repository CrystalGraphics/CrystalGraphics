package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CgVfxParticlePoolTest {

    /** A kind with one vec4 of numbers. */
    private static final class Pull implements CgVfxGpuModule {
        final float strength;

        Pull(float strength) {
            this.strength = strength;
        }

        public String gpuKind() {
            return "pull";
        }

        public boolean afterSolve() {
            return false;
        }

        public void writeParams(CgVfxWords out) {
            out.vec4(strength, 0f, 0f, 0f);
        }
    }

    /** A kind with two lanes worked out per instance, as Turbulence's lattice cell and fraction. */
    private static final class Swirl implements CgVfxGpuModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.IVEC4, CgVfxLane.VEC4};
        final double frequency;
        int writes = 2;

        Swirl(double frequency) {
            this.frequency = frequency;
        }

        public String gpuKind() {
            return "swirl";
        }

        public boolean afterSolve() {
            return false;
        }

        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        public void writeParams(CgVfxWords out) {
            out.vec4((float) frequency, 0f, 0f, 0f);
        }

        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            double x = instance.originX() * frequency;
            if (writes > 0) out.ivec4((int) Math.floor(x), 0, 0, 0);
            if (writes > 1) out.vec4((float) (x - Math.floor(x)), 0f, 0f, 0f);
        }
    }

    /** A kind after the solver taking the floor. */
    private static final class Floor implements CgVfxGpuModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};
        final boolean after;

        Floor(boolean after) {
            this.after = after;
        }

        public String gpuKind() {
            return "floor";
        }

        public boolean afterSolve() {
            return after;
        }

        public CgVfxWorldInput[] worldInputs() {
            return WORLD;
        }

        public void writeParams(CgVfxWords out) {
            out.vec4(0.5f, 0f, 0f, 0f);
        }
    }

    private static final class Def implements CgVfxGpuEmitter {
        final String name;
        final CgVfxEmitter.Renderer renderer;
        final List<CgVfxGpuModule> modules;

        Def(String name, CgVfxEmitter.Renderer renderer, CgVfxGpuModule... modules) {
            this.name = name;
            this.renderer = renderer;
            this.modules = Arrays.asList(modules);
        }

        public String name() {
            return name;
        }

        public CgVfxEmitter.Renderer renderer() {
            return renderer;
        }

        public List<CgVfxGpuModule> modules() {
            return modules;
        }

        public void writeSpawn(CgVfxWords out) {
            out.vec4(1f, 2f, 3f, 4f).vec4(5f, 6f, 7f, 8f).vec4(9f, 10f, 11f, 12f).vec4(13f, 14f, 0f, 0f);
        }

        public void writeCurves(float[] out, int at, int texels) {
            Arrays.fill(out, at, at + 2 * texels, 1f);
        }
    }

    private static final class At implements CgVfxInstanceView {
        final double x;
        final float time;

        At(double x, float time) {
            this.x = x;
            this.time = time;
        }

        public double originX() {
            return x;
        }

        public double originY() {
            return 0;
        }

        public double originZ() {
            return 0;
        }

        public float time() {
            return time;
        }

        public float sourceX() {
            return 1f;
        }

        public float sourceY() {
            return 2f;
        }

        public float sourceZ() {
            return 3f;
        }
    }

    @After
    public void forget() {
        CgVfxParticlePool.forgetAll();
    }

    @Test
    public void aShapeIsItsKindsAndCountsNeverItsNumbers() {
        CgVfxShape a = CgVfxShape.of(new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12)));
        CgVfxShape b = CgVfxShape.of(new Def("b", CgVfxEmitter.Renderer.QUADS, new Pull(1f), new Swirl(3.0)));
        assertEquals(a, b);
        assertEquals("QUADS|pull:1|swirl:1:IVEC4,VEC4", a.key());
        assertNotEquals(a, CgVfxShape.of(new Def("c", CgVfxEmitter.Renderer.QUADS, new Swirl(0.12), new Pull(9.8f))));
        assertNotEquals(a, CgVfxShape.of(new Def("d", CgVfxEmitter.Renderer.MESHES, new Pull(9.8f), new Swirl(0.12))));
        assertSame(CgVfxParticlePool.of(new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12))),
                CgVfxParticlePool.of(new Def("b", CgVfxEmitter.Renderer.QUADS, new Pull(1f), new Swirl(3.0))));
    }

    @Test
    public void aParameterRowIsTheSpawnNumbersThenEachModulesNumbers() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12));
        CgVfxParticlePool pool = CgVfxParticlePool.of(def);
        pool.open(def, 100);
        CgVfxShape shape = pool.shape();
        assertEquals(CgVfxGpuEmitter.SPAWN_VECTORS + 2, shape.paramRowVectors());
        int[] words = pool.paramWords();
        assertEquals(14f, Float.intBitsToFloat(words[13]), 0f);
        assertEquals(9.8f, Float.intBitsToFloat(words[shape.paramAt(0) * 4]), 0f);
        assertEquals(0.12f, Float.intBitsToFloat(words[shape.paramAt(1) * 4]), 0f);
        assertTrue(pool.paramsChanged());
    }

    @Test
    public void anInstanceRowIsItsHeaderThenEachModulesLanes() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.5));
        CgVfxParticlePool pool = CgVfxParticlePool.of(def);
        pool.open(def, 10);
        int slot = pool.open(def, 20);
        pool.beginStep(1f / 60f, 0f, 0f, 0f);
        pool.instance(0, 7, 1f, Float.NaN, new At(0, 0f));
        pool.instance(slot, 0x5EED, 0.5f, -2f, new At(1001.25, 3.5f));
        pool.endStep();
        CgVfxShape shape = pool.shape();
        int at = pool.stepInstanceAt(0) + slot * shape.instanceRowVectors() * 4;
        int[] w = pool.instanceWords();
        assertEquals(0, w[at]);                                         // both instances share definition row 0
        assertEquals(0x5EED, w[at + 1]);
        assertEquals(slot, w[at + 2]);
        assertEquals(3f, Float.intBitsToFloat(w[at + 6]), 0f);           // source z
        assertEquals(3.5f, Float.intBitsToFloat(w[at + 7]), 0f);         // time
        assertEquals(0.5f, Float.intBitsToFloat(w[at + 8]), 0f);         // share
        assertEquals(-2f, Float.intBitsToFloat(w[at + 9]), 0f);          // ground
        int lanes = at + shape.lanesAt(1) * 4;
        assertEquals(500, w[lanes]);                                    // 1001.25 * 0.5: the cell in doubles
        assertEquals(0.625f, Float.intBitsToFloat(w[lanes + 4]), 0f);    // and its fraction
    }

    @Test
    public void aKindWritingOtherThanItDeclaresIsNamed() {
        Swirl swirl = new Swirl(0.5);
        Def def = new Def("embers", CgVfxEmitter.Renderer.QUADS, swirl);
        CgVfxParticlePool pool = CgVfxParticlePool.of(def);
        int slot = pool.open(def, 10);
        swirl.writes = 1;
        pool.beginStep(0.01f, 0f, 0f, 0f);
        try {
            pool.instance(slot, 0, 1f, 0f, new At(0, 0f));
            fail("one lane of two");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("embers's fx_swirl wrote 1 of the 2"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void worldInputsBeforeTheSolverAreRefused() {
        CgVfxShape.of(new Def("a", CgVfxEmitter.Renderer.QUADS, new Floor(false)));
    }

    @Test
    public void slotsAreReusedAndListBasesFollowCapacities() {
        Def a = new Def("a", CgVfxEmitter.Renderer.MESHES, new Floor(true));
        Def b = new Def("b", CgVfxEmitter.Renderer.MESHES, new Floor(true));
        CgVfxParticlePool pool = CgVfxParticlePool.of(a);
        int s0 = pool.open(a, 100), s1 = pool.open(b, 50), s2 = pool.open(a, 25);
        assertEquals(175, pool.capacity());
        assertEquals(0, pool.listBase(s0));
        assertEquals(100, pool.listBase(s1));
        assertEquals(150, pool.listBase(s2));
        assertEquals(2, pool.paramRows());
        pool.close(s1);
        assertEquals(125, pool.capacity());
        assertEquals(100, pool.listBase(s2));
        assertEquals(s1, pool.open(a, 10));                             // the freed slot, and a's row again
        assertEquals(2, pool.paramRows());
        assertEquals(3, pool.open(b, 5));                               // b's row was freed: written again
        assertEquals(2, pool.paramRows());
    }

    @Test
    public void aStepNeedsEveryOpenSlotsRowAndCountsItsSpawns() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(1f));
        CgVfxParticlePool pool = CgVfxParticlePool.of(def);
        int s0 = pool.open(def, 100), s1 = pool.open(def, 100);
        pool.beginStep(0.01f, 1f, 0f, 0f);
        pool.instance(s0, 0, 1f, 0f, new At(0, 0f));
        try {
            pool.endStep();
            fail("slot 1 has no row");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("slot 1"));
        }
        assertEquals(0, pool.queuedSteps());
        pool.beginStep(0.01f, 1f, 0f, 0f);
        pool.instance(s0, 0, 1f, 0f, new At(0, 0f));
        pool.instance(s1, 0, 1f, 0f, new At(0, 0f));
        pool.spawn(s1, 40, 25);
        pool.spawn(s0, 0, 10);
        pool.endStep();
        assertEquals(1, pool.queuedSteps());
        assertEquals(2, pool.stepSpawnRows(0));
        assertEquals(35, pool.stepSpawned(0));
        int[] rows = pool.spawnWords();
        int at = pool.stepSpawnAt(0);
        assertEquals(Arrays.asList(s1, 40, 25, 0, s0, 0, 10, 25),
                Arrays.asList(rows[at], rows[at + 1], rows[at + 2], rows[at + 3], rows[at + 4], rows[at + 5], rows[at + 6],
                        rows[at + 7]));
        try {
            pool.open(def, 1);
            pool.beginStep(0.01f, 0f, 0f, 0f);
            pool.open(def, 1);
            fail("open mid-step");
        } catch (IllegalStateException expected) {
        }
    }
}
