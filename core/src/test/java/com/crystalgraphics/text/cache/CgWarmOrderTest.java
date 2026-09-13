package com.crystalgraphics.text.cache;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A warm queue is FIFO, so its order is the order glyphs become drawable.</b>
 *
 * <p>{@code warmAscii} used to walk 0x20..0x7E, which puts every lowercase letter in the last third
 * behind punctuation and digits most labels never contain, so the text lab's specimen was warmed
 * almost last: of "Handgloves", nine of ten characters are lowercase.</p>
 */
public class CgWarmOrderTest {

    /** The specimen the text lab draws, and the case this ordering exists for. */
    private static final String FIRST_WORD = "Handgloves";

    @Test
    public void everyPrintableAsciiIsWarmedExactlyOnce() {
        Set<Integer> seen = new LinkedHashSet<>();
        for (int codePoint : CgFontWarmer.ASCII_ORDER) {
            assertTrue("warmed something outside printable ASCII: " + codePoint,
                    codePoint >= 0x20 && codePoint <= 0x7E);
            assertTrue("warmed " + (char) codePoint + " twice", seen.add(codePoint));
        }
        assertEquals("the warm must still cover the whole printable range, however the preferred "
                + "prefix is edited", 0x7E - 0x20 + 1, seen.size());
    }

    @Test
    public void theWordOnScreenIsWarmedEarlyRatherThanLast() {
        int worstNew = 0;
        int worstOld = 0;
        for (char c : FIRST_WORD.toCharArray()) {
            worstNew = Math.max(worstNew, indexIn(CgFontWarmer.ASCII_ORDER, c));
            worstOld = Math.max(worstOld, c - 0x20);   // where codepoint order put it
        }
        System.out.println("[warm] last letter of '" + FIRST_WORD + "' is job #" + worstNew
                + " by frequency, was #" + worstOld + " by codepoint");

        assertTrue("codepoint order is supposed to be the bad case here; if it is not, this test is "
                + "measuring the wrong thing", worstOld > 60);
        assertTrue("the word on screen still finishes late: job #" + worstNew,
                worstNew < worstOld / 2);
    }

    private static int indexIn(int[] order, int codePoint) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == codePoint) return i;
        }
        throw new AssertionError("not warmed at all: " + (char) codePoint);
    }
}
