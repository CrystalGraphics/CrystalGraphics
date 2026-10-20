package com.crystalgraphics.vfx;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.world.CgCharacterClock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Effects run on their owner's clock: a frozen character's hold while another's play on, and the world dilates all. */
public class CgVfxClockTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:clock")).build();

    static final class Ticker extends CgVfxEffect<Ticker> {
        int ticks;

        Ticker() {
            super(LOOK, 0, 64, 0);
        }

        @Override
        protected void tick(float dt) {
            ticks++;
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    private static void run(CgVfxSystem vfx, int frames) {
        for (int f = 0; f <= frames; f++) vfx.update(f / 60.0);
    }

    @Test
    public void aFrozenCharactersEffectsHoldWhileAnotherPlaysOn() {
        CgCharacterClock frozen = new CgCharacterClock().freeze(1000);
        CgCharacterClock free = new CgCharacterClock();
        CgVfxSystem vfx = new CgVfxSystem();
        Ticker held = vfx.play(new Ticker().clock(frozen));
        Ticker playing = vfx.play(new Ticker().clock(free));
        Ticker world = vfx.play(new Ticker());
        run(vfx, 60);
        assertEquals(0, held.ticks);
        assertEquals(0f, held.age(), 0f);
        assertTrue(playing.ticks > 100);
        assertEquals(world.ticks, playing.ticks);
    }

    @Test
    public void aCharactersRateSlowsItsEffectsAges() {
        CgCharacterClock slow = new CgCharacterClock().rate(0.5f);
        CgVfxSystem vfx = new CgVfxSystem();
        Ticker half = vfx.play(new Ticker().clock(slow));
        Ticker full = vfx.play(new Ticker());
        run(vfx, 120);
        assertEquals(full.age() * 0.5f, half.age(), 0.02f);
        assertEquals(full.ticks, half.ticks);
    }

    @Test
    public void aThawedEffectPlaysOnFromWhereItHeld() {
        CgCharacterClock clock = new CgCharacterClock();
        CgVfxSystem vfx = new CgVfxSystem();
        Ticker effect = vfx.play(new Ticker().clock(clock));
        for (int f = 0; f <= 30; f++) vfx.update(f / 60.0);
        float age = effect.age();
        clock.freeze(1);
        for (int f = 31; f <= 60; f++) vfx.update(f / 60.0);
        assertEquals(age, effect.age(), 0.02f);
        clock.advance();
        for (int f = 61; f <= 90; f++) vfx.update(f / 60.0);
        assertTrue(effect.age() > age + 0.4f);
    }

    @Test
    public void theWorldsDilationScalesEverything() {
        CgWorld preview = new CgWorld();
        CgVfxSystem vfx = new CgVfxSystem().world(preview);
        Ticker effect = vfx.play(new Ticker());
        preview.timeDilation(0.25f);
        run(vfx, 120);
        assertEquals(0.5f, effect.age(), 0.03f);
        preview.timeDilation(0f);
        float held = effect.age();
        for (int f = 121; f <= 150; f++) vfx.update(f / 60.0);
        assertEquals(held, effect.age(), 0f);
    }
}
