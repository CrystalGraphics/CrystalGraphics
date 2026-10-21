package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModuleTypes;
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CgVfxParticlePoolTest {

    private static final Object OWNER = new Object();

    /** A kind with one vec4 of numbers. */
    private static final class Pull implements CgVfxGpuModule {
        static {
            CgVfxModuleTypes.declare("pull", Pull.class, t -> t.property("strength", CgValueType.FLOAT),
                    (m, in) -> in.f("strength", m.strength));
        }

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
    }

    /** A kind with two lanes worked out per instance, as Turbulence's lattice cell and fraction. */
    private static final class Swirl implements CgVfxGpuModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.IVEC4, CgVfxLane.VEC4};
        static {
            CgVfxModuleTypes.declare("swirl", Swirl.class, t -> t.property("frequency", CgValueType.FLOAT),
                    (m, in) -> in.f("frequency", (float) m.frequency));
        }

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

        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            double x = instance.originX() * frequency;
            if (writes > 0) out.ivec4((int) Math.floor(x), 0, 0, 0);
            if (writes > 1) out.vec4((float) (x - Math.floor(x)), 0f, 0f, 0f);
        }
    }

    /** A kind after the solver taking the floor. */
    private static final class Floor implements CgVfxGpuModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};
        static {
            CgVfxModuleTypes.declare("floor", Floor.class, t -> t.property("rest", CgValueType.FLOAT), (m, in) -> in.f("rest", 0.5f));
        }

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
    }

    private static final class Def implements CgVfxGpuEmitter {
        final String name;
        final CgVfxEmitter.Renderer renderer;
        final List<CgVfxGpuModule> modules;
        final int[] moduleWords;
        int trailPoints;

        Def(String name, CgVfxEmitter.Renderer renderer, CgVfxGpuModule... modules) {
            this.name = name;
            this.renderer = renderer;
            this.modules = Arrays.asList(modules);
            this.moduleWords = CgVfxModuleTypes.words(this.modules);
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

        public int[] moduleWords() {
            return moduleWords;
        }

        public void writeSpawn(CgVfxWords out) {
            out.vec4(1f, 2f, 3f, 4f).vec4(5f, 6f, 7f, 8f).vec4(9f, 10f, 11f, 12f).vec4(13f, 14f, 0f, 0f);
            for (int i = 4; i < CgVfxGpuEmitter.SPAWN_VECTORS; i++) out.vec4(0f, 0f, 0f, 0f);
        }

        public void writeCurves(float[] out, int at, int texels) {
            Arrays.fill(out, at, at + 4 * (2 * texels + CgVfxGpuEmitter.CURVE_STARTS), 1f);
        }

        public int trailPoints() {
            return trailPoints;
        }

        Def trail(int points) {
            trailPoints = points;
            return this;
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
        assertSame(CgVfxParticlePool.of(OWNER, new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12))),
                CgVfxParticlePool.of(OWNER, new Def("b", CgVfxEmitter.Renderer.QUADS, new Pull(1f), new Swirl(3.0))));
        // Another simulation steps its own: a step moves every particle of a pool.
        assertNotSame(CgVfxParticlePool.of(OWNER, new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12))),
                CgVfxParticlePool.of(new Object(), new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12))));
    }

    @Test
    public void aParameterRowIsTheSpawnNumbersThenEachModulesNumbers() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.12));
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, def);
        pool.open(def, 100);
        CgVfxShape shape = pool.shape();
        // The spawn numbers, a vec4 each module, then the roll keys a word each.
        assertEquals(CgVfxGpuEmitter.SPAWN_VECTORS + 2 + (CgVfxGpuEmitter.ROLL_KEYS + 3) / 4, shape.paramRowVectors());
        int[] words = pool.paramWords();
        assertEquals(def.rollKeys()[CgVfxGpuEmitter.KEY_LIFE], words[shape.keysAt() * 4 + CgVfxGpuEmitter.KEY_LIFE]);
        assertEquals(14f, Float.intBitsToFloat(words[13]), 0f);
        assertEquals(9.8f, Float.intBitsToFloat(words[shape.paramAt(0) * 4]), 0f);
        assertEquals(0.12f, Float.intBitsToFloat(words[shape.paramAt(1) * 4]), 0f);
        assertTrue(pool.paramsChanged());
    }

    @Test
    public void anInstanceRowIsItsHeaderThenEachModulesLanes() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(9.8f), new Swirl(0.5));
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, def);
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
        assertEquals(1f, Float.intBitsToFloat(w[at + 10]), 0f);          // step scale
        int lanes = at + shape.lanesAt(1) * 4;
        assertEquals(500, w[lanes]);                                    // 1001.25 * 0.5: the cell in doubles
        assertEquals(0.625f, Float.intBitsToFloat(w[lanes + 4]), 0f);    // and its fraction
    }

    @Test
    public void aKindWritingOtherThanItDeclaresIsNamed() {
        Swirl swirl = new Swirl(0.5);
        Def def = new Def("embers", CgVfxEmitter.Renderer.QUADS, swirl);
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, def);
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
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, a);
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
    public void trailRingsAreASlotsOwnAndGivenBackWhenItCloses() {
        Def kept = new Def("kept", CgVfxEmitter.Renderer.QUADS, new Pull(1f)).trail(8);
        Def plain = new Def("plain", CgVfxEmitter.Renderer.QUADS, new Pull(1f));
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, kept);
        int a = pool.open(kept, 100), b = pool.open(plain, 50), c = pool.open(kept, 20);
        assertEquals(0, pool.ringFirst(a));
        assertEquals(8, pool.ringPoints(a));
        assertEquals(0, pool.ringPoints(b));
        assertEquals(800, pool.ringFirst(c));                          // a ring a particle of a's capacity
        pool.close(a);
        int d = pool.open(kept, 30);
        assertEquals(0, pool.ringFirst(d));                            // into a's run, first fit
        int e = pool.open(kept, 200);
        assertEquals(960, pool.ringFirst(e));                          // too big for what is left of it: past the end
        // A draw submitted now reads the point the queued steps will have stored last: one each TRAIL_INTERVAL of
        // particle time, whatever the frame rate.
        pool.beginStep(0.01f, 0f, 0f, 0f);
        for (int s : new int[]{b, c, d, e}) pool.instance(s, 0, 1f, 0f, new At(0, 0f));
        pool.endStep();
        assertEquals(-1, pool.lastStep());
        assertEquals(0.01f, pool.trailSince(), 1e-6f);
        pool.beginStep(0.01f, 0f, 0f, 0f);
        for (int s : new int[]{b, c, d, e}) pool.instance(s, 0, 1f, 0f, new At(0, 0f));
        pool.endStep();
        assertEquals(0, pool.lastStep());
        assertEquals(0.02f - CgVfxParticlePool.TRAIL_INTERVAL, pool.trailSince(), 1e-6f);
    }

    @Test
    public void aStepNeedsEveryOpenSlotsRowAndCountsItsSpawns() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(1f));
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, def);
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
        int at = pool.stepSpawnAt(0), next = at + CgVfxParticlePool.SPAWN_ROW_WORDS;
        assertEquals(Arrays.asList(s1, 40, 25, 0, s0, 0, 10, 25),
                Arrays.asList(rows[at], rows[at + 1], rows[at + 2], rows[at + 3], rows[next], rows[next + 1], rows[next + 2],
                        rows[next + 3]));
        // A row is one tick's: born at its start, its bursts all of it here, from and to the instance row's source.
        assertEquals(0f, Float.intBitsToFloat(rows[at + 4]), 0f);
        assertEquals(25f, Float.intBitsToFloat(rows[at + 6]), 0f);
        assertEquals(3f, Float.intBitsToFloat(rows[at + 10]), 0f);
        assertEquals(3f, Float.intBitsToFloat(rows[at + 14]), 0f);
        try {
            pool.open(def, 1);
            pool.beginStep(0.01f, 0f, 0f, 0f);
            pool.open(def, 1);
            fail("open mid-step");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void oneStepAFrameHoldsEachTicksSpawnsAtTheirOffsets() {
        Def def = new Def("a", CgVfxEmitter.Renderer.QUADS, new Pull(1f));
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, def);
        int slot = pool.open(def, 100);
        float tick = 1f / 120f;
        pool.beginStep(3 * tick, 0f, 0f, 0f);
        pool.instance(slot, 0, 1f, 0f, new At(0, 0f));
        pool.spawn(slot, 0, 4, 1, 0f, tick, 0f, 0f, 0f, 1f, 0f, 0f, 0f, tick);
        pool.spawn(slot, 4, 2, 0, 2 * tick, tick, 1f, 0f, 0f, 2f, 0f, 0f, 2 * tick, 3 * tick);
        pool.endStep();
        int[] rows = pool.spawnWords();
        int second = pool.stepSpawnAt(0) + CgVfxParticlePool.SPAWN_ROW_WORDS;
        assertEquals(4, rows[second + 3]);                                       // after the first tick's candidates
        assertEquals(2 * tick, Float.intBitsToFloat(rows[second + 4]), 0f);       // born from its tick's offset
        assertEquals(3 * tick, Float.intBitsToFloat(rows[second + 5]), 1e-7f);   // to its end
        assertEquals(2f, Float.intBitsToFloat(rows[second + 12]), 0f);           // the source at its end
        assertEquals(2 * tick, Float.intBitsToFloat(rows[second + 11]), 0f);      // its loop's seconds at its start
        assertEquals(3 * tick, Float.intBitsToFloat(rows[second + 15]), 0f);      // and at its end
        String glsl = CgVfxEmitterCompiler.source(pool.shape());
        assertTrue(glsl.contains("s.dt = max(_Step.x - born, 0.0)"));
        assertTrue(glsl.contains("born = mix(when.x, when.y, along);"));
    }
}
