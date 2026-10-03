package com.crystalgraphics.compute.ops;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@code cg_rng} is {@code cg_rng4(...).x}; the Java twin computes it apart, so the two must not drift. */
public class CgRngTest {

    @Test
    public void rng_isTheFirstWordOfRng4() {
        int[] four = new int[4];
        for (int i = 0; i < 2000; i++) {
            int seed = i * 0x9E3779B9, element = i * 7 - 300, step = i >>> 3, stream = i % 5;
            assertEquals(CgRng.rng4(seed, element, step, stream, four)[0], CgRng.rng(seed, element, step, stream));
        }
    }

    @Test
    public void unit_staysBelowOne() {
        assertEquals(0f, CgRng.unit(0), 0f);
        float top = CgRng.unit(0xFFFFFFFF);
        assertTrue(top < 1f);
        assertEquals(1f - 1f / 16777216f, top, 0f);
    }
}
