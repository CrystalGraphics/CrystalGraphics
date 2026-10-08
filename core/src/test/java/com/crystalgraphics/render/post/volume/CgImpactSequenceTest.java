package com.crystalgraphics.render.post.volume;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CgImpactSequenceTest {

    private static final CgImpactSequence HIT = CgImpactSequence.at(24f)
            .beat(CgImpact.SUBJECT, 2).beat(CgImpact.SUBJECT_INVERTED, 1).beat(CgImpact.SUBJECT_LINES, 2).build();

    @Test
    public void eachBeatHoldsItsWholeFrames_thenCuts() {
        float frame = 1f / 24f;
        assertEquals(CgImpact.SUBJECT, HIT.look(0f));
        assertEquals(CgImpact.SUBJECT, HIT.look(1.9f * frame));
        assertEquals(CgImpact.SUBJECT_INVERTED, HIT.look(2.1f * frame));
        assertEquals(CgImpact.SUBJECT_LINES, HIT.look(3.1f * frame));
        assertEquals(CgImpact.SUBJECT_LINES, HIT.look(4.9f * frame));
        assertNull(HIT.look(5.1f * frame));
    }

    @Test
    public void nothingBeforeItStarts_andItsLengthIsItsFrames() {
        assertNull(HIT.look(-0.01f));
        assertNull(HIT.look(Float.NaN));
        assertEquals(5f / 24f, HIT.seconds(), 1e-6f);
    }
}
