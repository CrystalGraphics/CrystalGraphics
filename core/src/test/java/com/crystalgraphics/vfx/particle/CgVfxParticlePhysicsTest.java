package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.CgVfxSystem;
import com.sun.management.ThreadMXBean;

import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The particle engine's physics against what it claims: terminal speeds, quadratic braking, debris settling, a
 * divergence-free turbulence field, determinism per seed, and no allocation per tick.
 */
public class CgVfxParticlePhysicsTest {

    private static final float TICK = CgVfxSystem.TICK;

    @Test
    public void linearDragSettlesAtGravityOverDrag() {
        CgVfxEmitterInstance ember = run(CgVfxEmitter.builder("ember").capacity(1).burst(0f, 1).life(60f, 60f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Drag(4f, 0f)).build(), 5f);
        assertEquals(-9.8f / 4f, ember.particles().vy[0], 0.02f);
    }

    @Test
    public void quadraticDragBrakesAFastBurstAsItsClosedFormDoes() {
        // v(t) = v0 / (1 + c v0 t) for pure quadratic drag
        CgVfxEmitterInstance billow = run(CgVfxEmitter.builder("billow").capacity(1).burst(0f, 1).life(60f, 60f)
                .launch(0f, 0f, 1f).speed(20f, 20f).module(new CgVfxModule.Drag(0f, 0.12f)).build(), 1f);
        CgVfxParticleSet p = billow.particles();
        float speed = (float) Math.sqrt(p.vx[0] * p.vx[0] + p.vz[0] * p.vz[0]);
        assertEquals(20f / (1f + 0.12f * 20f * 1f), speed, 0.15f);
    }

    @Test
    public void debrisComesToRestOnTheGround() {
        CgVfxEmitter debris = CgVfxEmitter.builder("debris").capacity(200).burst(0f, 200).life(60f, 60f)
                .launch(0.2f, 1f, 1f).speed(8f, 26f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Drag(0.25f, 0f))
                .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f)).build();
        CgVfxEmitterInstance run = new CgVfxEmitterInstance(debris, 0.37f);
        run.start(0f, 2f, 0f);
        run.ground(0f);
        tick(run, 12f);
        CgVfxParticleSet p = run.particles();
        for (int i = 0; i < p.count(); i++) {
            assertEquals("resting " + i, 1f, p.resting[i], 0f);
            assertEquals("on the ground " + i, 0f, p.y[i], 1.0e-4f);
        }
    }

    @Test
    public void curlNoiseHasNoDivergence() {
        float[] a = new float[3], b = new float[3];
        float h = 1.0e-2f, worst = 0f, magnitude = 0f;
        // Near the origin: far out, a float's place within its lattice cell is too coarse for a finite difference.
        for (int n = 0; n < 200; n++) {
            float x = (n * 0.731f) % 8f - 4f, y = (n * 0.317f) % 8f - 4f, z = (n * 0.173f) % 8f - 4f;
            CgVfxCurlNoise.sample(x, y, z, a);
            magnitude = Math.max(magnitude, Math.abs(a[0]) + Math.abs(a[1]) + Math.abs(a[2]));
            float div = 0f;
            for (int axis = 0; axis < 3; axis++) {
                CgVfxCurlNoise.sample(x + (axis == 0 ? h : 0f), y + (axis == 1 ? h : 0f), z + (axis == 2 ? h : 0f), b);
                CgVfxCurlNoise.sample(x - (axis == 0 ? h : 0f), y - (axis == 1 ? h : 0f), z - (axis == 2 ? h : 0f), a);
                div += (b[axis] - a[axis]) / (2f * h);
            }
            worst = Math.max(worst, Math.abs(div));
        }
        assertTrue("the field is not trivial: " + magnitude, magnitude > 0.5f);
        assertTrue("divergence " + worst, worst < 0.05f);
    }

    @Test
    public void oneSeedGivesOneRun() {
        CgVfxEmitter embers = embers(300);
        CgVfxEmitterInstance a = run(embers, 3f), b = run(embers, 3f);
        CgVfxParticleSet pa = a.particles(), pb = b.particles();
        assertEquals(pa.count(), pb.count());
        for (int i = 0; i < pa.count(); i++) {
            assertEquals(pa.x[i], pb.x[i], 0f);
            assertEquals(pa.y[i], pb.y[i], 0f);
            assertEquals(pa.z[i], pb.z[i], 0f);
        }
    }

    @Test
    public void aTickAllocatesNothing() {
        CgVfxEmitterInstance run = new CgVfxEmitterInstance(embers(500), 0.5f);
        run.start(0f, 0f, 0f);
        CgVfxAir air = new CgVfxAir();
        for (int t = 0; t < 240; t++) run.tick(TICK, air, 0.0, 0.0, 0.0);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        for (int t = 0; t < 600; t++) {
            air.tick(t * TICK);
            run.tick(TICK, air, 0.0, 0.0, 0.0);
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue("allocated " + allocated + " bytes over 600 ticks", allocated < 4096);
    }

    /** Not a gate: what 2,000 particles with every force cost a tick, printed for the plan's budget. */
    @Test
    public void measureTwoThousandParticles() {
        CgVfxEmitterInstance run = new CgVfxEmitterInstance(embers(2000), 0.9f);
        run.start(0f, 0f, 0f);
        run.ground(-2f);
        CgVfxAir air = new CgVfxAir();
        for (int t = 0; t < 240; t++) run.tick(TICK, air, 0.0, 0.0, 0.0);
        long start = System.nanoTime();
        int ticks = 1200;
        for (int t = 0; t < ticks; t++) run.tick(TICK, air, 0.0, 0.0, 0.0);
        double perTick = (System.nanoTime() - start) / 1.0e6 / ticks;
        System.out.printf("[vfx.sim] %d particles, every module: %.3f ms a tick, %.2f ms a 60 Hz frame (2 ticks)%n",
                run.particles().count(), perTick, perTick * 2);
    }

    /** Embers with every kind of force on: the most expensive stack. */
    private static CgVfxEmitter embers(int count) {
        return CgVfxEmitter.builder("embers").capacity(count).burst(0f, count).life(30f, 40f)
                .launch(-0.1f, 1f, 0.6f).speed(6f, 18f).size(0.05f, 0.25f, 2f).heat(1f).spin(1f, 6f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(4f, 0.01f))
                .module(new CgVfxModule.Buoyancy(16f, 0.9f))
                .module(new CgVfxModule.Turbulence(8f, 0.12f, 0.6f))
                .module(new CgVfxModule.Wind(1f))
                .module(new CgVfxModule.Updraft(12f, 4f, 10f, 2f))
                .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.4f))
                .module(new CgVfxModule.Spin(0.5f))
                .build();
    }

    private static CgVfxEmitterInstance run(CgVfxEmitter emitter, float seconds) {
        CgVfxEmitterInstance run = new CgVfxEmitterInstance(emitter, 0.21f);
        run.start(0f, 0f, 0f);
        tick(run, seconds);
        return run;
    }

    private static void tick(CgVfxEmitterInstance run, float seconds) {
        CgVfxAir air = new CgVfxAir().wind(0f, 0f, 0f);
        int ticks = Math.round(seconds / TICK);
        for (int t = 0; t < ticks; t++) {
            air.tick(t * TICK);
            run.tick(TICK, air, 0.0, 0.0, 0.0);
        }
    }
}
