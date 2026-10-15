package com.crystalgraphics.vfx;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.element.CgVfxCharge;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Tweens and endings: values eased on the tick, what follows them following, and an effect gone when its ending is. */
public class CgVfxEndingTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:ending");
    private static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 2f);
    private static final CgVfxParam.Scalar GLOW = SCHEMA.length("glow", RADIUS, 3f);
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).build();
    private static final CgVfxEnding SHRINK = CgVfxEnding.over("test:ending/shrink", 0.5f)
            .animate(RADIUS, 0f, CgEasings.LINEAR).fadeOut(CgEasings.LINEAR).build();

    /** An effect that would play for ever, and hears its ending. */
    static final class Endless extends CgVfxEffect<Endless> {
        CgVfxEnding heard;

        Endless() {
            super(LOOK, 0, 64, 0);
        }

        @Override
        protected void tick(float dt) {
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }

        @Override
        protected void ending(CgVfxEnding ending) {
            heard = ending;
        }
    }

    /** Updates {@code vfx} at 60 frames a second from {@code from} to {@code to}; answers the time reached. */
    private static double run(CgVfxSystem vfx, double from, double to) {
        double t = from;
        vfx.update(t);
        while (t < to - 1e-9) vfx.update(t = Math.min(to, t + 1 / 60.0));
        return t;
    }

    @Test
    public void anEndingEasesItsValuesAndPresenceThenTheEffectDies() {
        CgVfxSystem vfx = new CgVfxSystem();
        Endless effect = vfx.play(new Endless());
        double t = run(vfx, 0.0, 0.1);
        effect.end(SHRINK);
        assertSame(SHRINK, effect.heard);
        assertSame(SHRINK, effect.ending());
        t = run(vfx, t, t + 0.25);
        assertEquals(1f, effect.get(RADIUS), 0.05f);
        assertEquals(3f, effect.get(GLOW), 0.15f);
        assertEquals(0.5f, effect.presence(), 0.05f);
        for (int f = 0; f < 60 && effect.state() != CgVfxEffect.State.DEAD; f++) vfx.update(t += 1 / 60.0);
        assertEquals(CgVfxEffect.State.DEAD, effect.state());
        assertEquals(0f, effect.get(RADIUS), 0f);
    }

    @Test
    public void aTweenMovesWhatFollowsAndASetStopsIt() {
        CgVfxSystem vfx = new CgVfxSystem();
        Endless effect = vfx.play(new Endless());
        double t = run(vfx, 0.0, 0.0);
        effect.animate(RADIUS, 4f, 1f, CgEasings.LINEAR);
        t = run(vfx, t, 0.5);
        assertEquals(3f, effect.get(RADIUS), 0.05f);
        assertEquals(9f, effect.get(GLOW), 0.15f);
        effect.set(RADIUS, 1f);
        run(vfx, t, 1.5);
        assertEquals(1f, effect.get(RADIUS), 0f);
    }

    @Test
    public void aWaveFadedWhileHittingThinsAwayWithoutABlast() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxLook look = CgEnergyWave.kamehameha().derive().set(CgVfxCharge.TIME, 0f).build();
        CgEnergyWave wave = vfx.play(new CgEnergyWave(look, 0, 64, 0));
        wave.aim(1f, 0f, 0f).target(8, 64, 0);
        double t = 0;
        for (int f = 0; f < 240 && !wave.impacting(); f++) vfx.update(t += 1 / 60.0);
        assertTrue("the beam reaches its target", wave.impacting());
        wave.end(CgEnergyWave.FADE);
        for (int f = 0; f < 60 && wave.state() != CgVfxEffect.State.DEAD; f++) vfx.update(t += 1 / 60.0);
        assertEquals(CgVfxEffect.State.DEAD, wave.state());
        assertFalse(wave.burst());
    }
}
