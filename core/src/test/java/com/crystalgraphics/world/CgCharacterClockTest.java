package com.crystalgraphics.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A character's clock: ticks, hitstop, rate, and a freeze heard late. */
public class CgCharacterClockTest {

    @Test
    public void aFrozenTickIsSpentNotRun() {
        CgCharacterClock clock = new CgCharacterClock();
        assertTrue(clock.advance());
        clock.freeze(2);
        assertFalse(clock.advance());
        assertFalse(clock.advance());
        assertTrue(clock.advance());
        assertEquals(2, clock.ticks());
        assertEquals(0.1, clock.exact(0f), 1e-9);
    }

    @Test
    public void aSecondFreezeLastsToTheLaterEndNeverTheSum() {
        CgCharacterClock clock = new CgCharacterClock().freeze(6);
        clock.advance();
        clock.freeze(4);
        assertEquals(5, clock.frozenTicks());
        clock.freeze(8);
        assertEquals(8, clock.frozenTicks());
    }

    @Test
    public void thePartialTickIsHeldWhileFrozen() {
        CgCharacterClock clock = new CgCharacterClock();
        clock.advance();
        assertEquals(0.075, clock.exact(0.5f), 1e-9);
        clock.freeze(3);
        assertEquals(0.05, clock.exact(0.5f), 1e-9);
        assertEquals(0f, clock.pace(0.5f), 0f);
    }

    @Test
    public void changingTheRateLateNeverJumpsTheTime() {
        CgCharacterClock clock = new CgCharacterClock();
        for (int i = 0; i < 1200; i++) clock.advance();
        double before = clock.exact(0f);
        clock.rate(0.5f);
        assertEquals(before, clock.exact(0f), 1e-9);
        clock.advance();
        assertEquals(before + 0.025, clock.exact(0f), 1e-9);
    }

    @Test
    public void aFreezeHeardLateHoldsPresentedTimeAndNeverStepsBack() {
        CgCharacterClock clock = new CgCharacterClock();
        for (int i = 0; i < 10; i++) clock.advance();
        double shown = clock.now(0.5f);
        // The server's freeze began 3 ticks ago: this copy's exact time steps back to tick 7.
        clock.restore(7, 7 * CgCharacterClock.TICK_SECONDS, 3, 1f);
        assertEquals(shown, clock.now(0.5f), 0.0);
        clock.restore(7, 7 * CgCharacterClock.TICK_SECONDS, 0, 1f);
        assertEquals(0f, clock.pace(0.1f), 0f);
        assertEquals(shown, clock.now(0.1f), 0.0);
        clock.restore(11, 11 * CgCharacterClock.TICK_SECONDS, 0, 1f);
        assertEquals(1f, clock.pace(0f), 0f);
        assertTrue(clock.now(0f) > shown);
    }
}
