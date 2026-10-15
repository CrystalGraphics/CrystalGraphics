package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An effect started at an age is where one played from zero is at that age: its phase and its instance's books, its
 * particles prewarmed in at most {@code PREWARM_STEPS} steps that hold every other effect's particles still.
 */
public class CgVfxStartAtTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:start_at")).build();
    /** A rate for 4 s and a burst at 1 s: both the books and what is alive depend on the whole past. */
    private static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("start_at_embers").capacity(2000)
            .rate(150f, 0f, 4f).burst(1f, 60).life(0.5f, 1.2f).speed(1f, 3f)
            .module(new CgVfxModule.Gravity(9.8f)).build();
    /**
     * Frames of the from-zero run: a sixtieth of a second each, two ticks; fewer particle steps than a system queues
     * before it waits for the world to record them.
     */
    private static final int FRAMES = 24;

    /** An effect whose phase accumulates every tick, so a jump in age would show. */
    static final class Phased extends CgVfxEffect {
        final CgVfxEmitterInstance embers = new CgVfxEmitterInstance(EMBERS, 0.37f);
        float phase;
        int ticks;

        Phased() {
            super(LOOK, 0, 64, 0);
            embers.start(0f, 1f, 0f);
        }

        @Override
        protected void tick(float dt) {
            ticks++;
            phase += dt * (1f + age);
            tick(embers, dt);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void anEffectStartedAtAnAgeMatchesOnePlayedFromZero() {
        CgVfxSystem fromZero = new CgVfxSystem();
        Phased played = fromZero.play(new Phased());
        for (int f = 0; f <= FRAMES; f++) fromZero.update(f / 60.0);

        CgVfxSystem late = new CgVfxSystem();
        Phased started = late.play(new Phased());
        started.startAt(played.age());
        late.update(0.0);
        late.update(0.0);

        assertEquals(played.ticks, started.ticks);
        assertEquals(played.age(), started.age(), 0f);
        assertEquals(played.phase, started.phase, 0f);
        assertEquals(played.embers.time(), started.embers.time(), 0.2f);
    }

    @Test
    public void itsParticlesArriveInACappedPrewarm() {
        CgVfxSystem vfx = new CgVfxSystem();
        Phased early = vfx.play(new Phased());
        for (int f = 0; f <= 6; f++) vfx.update(f / 60.0);
        float earlyTime = early.embers.time();
        CgVfxParticlePool pool = CgVfxParticlePool.of(vfx.gpuSteps(), EMBERS);
        int queued = pool.queuedSteps();

        Phased late = vfx.play(new Phased());
        late.startAt(3f);
        vfx.update(6 / 60.0);

        assertEquals(Math.round(3f / CgVfxSystem.TICK), late.ticks);
        assertEquals(3f, late.age(), 1e-3f);
        assertEquals(3f, late.embers.time(), 0.2f);
        assertTrue("a slot for the late effect", vfx.gpuSteps().of(late.embers) != null);
        int prewarm = pool.queuedSteps() - queued;
        assertTrue("prewarmed in " + prewarm + " steps", prewarm > 0 && prewarm <= 16);
        // Held still through the prewarm: not coasted, its books untouched.
        assertEquals(earlyTime, early.embers.time(), 0f);
    }

    @Test
    public void pastTheCapItsAgeJumps() {
        CgVfxSystem vfx = new CgVfxSystem();
        Phased late = vfx.play(new Phased());
        late.startAt(CgVfxSystem.MAX_START_SECONDS + 20f);
        vfx.update(0.0);
        vfx.update(0.0);
        assertEquals(Math.round(CgVfxSystem.MAX_START_SECONDS / CgVfxSystem.TICK), late.ticks);
        assertEquals(CgVfxSystem.MAX_START_SECONDS + 20f, late.age(), 0f);
    }
}
