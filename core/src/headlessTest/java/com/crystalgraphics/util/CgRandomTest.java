package com.crystalgraphics.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.Assert.*;

public class CgRandomTest {

    private boolean before;

    @Before
    public void remember() {
        before = CgRandom.fixedSeed();
    }

    @After
    public void restore() {
        CgRandom.fixedSeed(before);
    }

    private static int[] draw(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = CgRandom.nextInt();
        return out;
    }

    @Test
    public void fixedSeedRepeatsWhenSetAgain() {
        CgRandom.fixedSeed(true);
        int[] first = draw(32);
        CgRandom.fixedSeed(true);
        assertArrayEquals(first, draw(32));
    }

    @Test
    public void fixedSeedGivesEveryThreadTheSameStream() throws InterruptedException {
        CgRandom.fixedSeed(true);
        int[] mine = draw(16);
        AtomicIntegerArray theirs = new AtomicIntegerArray(16);
        Thread t = new Thread(() -> {
            for (int i = 0; i < 16; i++) theirs.set(i, CgRandom.nextInt());
        });
        t.start();
        t.join();
        for (int i = 0; i < 16; i++) assertEquals(mine[i], theirs.get(i));
    }

    @Test
    public void unfixedStartsFromTheClock() throws InterruptedException {
        CgRandom.fixedSeed(false);
        int[] a = draw(8);
        CgRandom.fixedSeed(false);
        Thread.sleep(2);
        int[] b = draw(8);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    public void unitStaysInRange() {
        CgRandom.fixedSeed(true);
        for (int i = 0; i < 10000; i++) {
            float u = CgRandom.unit();
            assertTrue(u >= 0f && u < 1f);
        }
    }
}
