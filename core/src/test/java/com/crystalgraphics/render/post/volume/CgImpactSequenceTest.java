package com.crystalgraphics.render.post.volume;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class CgImpactSequenceTest {

    private static final CgImpactFrame BOILING = CgImpactFrame.drawn().lines(1f).boil(2).build();
    private static final CgImpactSequence HIT = CgImpactSequence.at(24f)
            .beat(CgImpact.SUBJECT, 2).beat(CgImpact.SUBJECT_INVERTED, 1).beat(BOILING, 4).build();
    private static final float FRAME = 1f / 24f;

    @Test
    public void eachBeatHoldsItsWholeFrames_thenCuts() {
        assertSame(CgImpact.SUBJECT.frame(), HIT.look(0f));
        assertSame(CgImpact.SUBJECT.frame(), HIT.look(1.9f * FRAME));
        assertSame(CgImpact.SUBJECT_INVERTED.frame(), HIT.look(2.1f * FRAME));
        assertSame(BOILING, HIT.look(3.1f * FRAME));
        assertSame(BOILING, HIT.look(6.9f * FRAME));
        assertNull(HIT.look(7.1f * FRAME));
    }

    @Test
    public void theSeedChangesEachBeat_andEveryBoilInsideOne() {
        assertEquals(HIT.seed(0.1f * FRAME), HIT.seed(1.9f * FRAME));       // no boil: one drawing the beat
        assertNotEquals(HIT.seed(1.9f * FRAME), HIT.seed(2.1f * FRAME));
        assertEquals(HIT.seed(3.1f * FRAME), HIT.seed(4.9f * FRAME));       // on twos
        assertNotEquals(HIT.seed(4.9f * FRAME), HIT.seed(5.1f * FRAME));
        assertEquals(0, HIT.seed(7.1f * FRAME));
    }

    @Test
    public void aFade_holdsTheLastBeatPastTheEnd_steppingDownAFrameAtATime() {
        CgImpactSequence faded = CgImpactSequence.at(24f).beat(CgImpact.SUBJECT, 2).beat(CgImpact.WHITE, 1).fadeOut(3).build();
        assertEquals(3f / 24f, faded.seconds(), 1e-6f);                    // the hitstop holds for the beats alone
        assertEquals(1f, faded.amount(2.5f * FRAME), 0f);
        assertSame(CgImpact.WHITE.frame(), faded.look(3.5f * FRAME));
        assertEquals(0.75f, faded.amount(3.5f * FRAME), 1e-6f);
        assertEquals(0.25f, faded.amount(5.5f * FRAME), 1e-6f);
        assertEquals(faded.seed(2.5f * FRAME), faded.seed(5.5f * FRAME));   // the same drawing, fading
        assertNull(faded.look(6.1f * FRAME));
        assertEquals(0f, faded.amount(6.1f * FRAME), 0f);
    }

    @Test
    public void nothingBeforeItStarts_andItsLengthIsItsFrames() {
        assertNull(HIT.look(-0.01f));
        assertNull(HIT.look(Float.NaN));
        assertEquals(7f / 24f, HIT.seconds(), 1e-6f);
    }
}
