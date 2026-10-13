package com.crystalgraphics.job.progress;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The rate window, the estimate's refresh, and the readout. */
public class CgProgressTest {

    private static final long START = 1_000_000L;

    @Test
    public void aSteadyRateReadsBackAsItself() {
        CgRateEstimator rate = new CgRateEstimator(START);
        for (int second = 1; second <= 10; second++) rate.increment(1_000_000, START + second * 1000L);
        assertEquals(1_000_000L, rate.countPerSecond(START + 10_000));
    }

    @Test
    public void anEarlyReadingIsNotDividedByTheWholeWindow() {
        CgRateEstimator rate = new CgRateEstimator(START);
        rate.increment(5_000_000, START + 200);
        assertEquals(5_000_000L, rate.countPerSecond(START + 900));
    }

    @Test
    public void theWindowForgetsWhatFellOutOfIt() {
        CgRateEstimator rate = new CgRateEstimator(START);
        for (int second = 1; second <= 10; second++) rate.increment(10_000_000, START + second * 1000L);
        for (int second = 11; second <= 20; second++) rate.increment(1_000_000, START + second * 1000L);
        assertEquals(1_000_000L, rate.countPerSecond(START + 20_000));
    }

    @Test
    public void aLongPauseOrABackwardClockStartsAgain() {
        CgRateEstimator rate = new CgRateEstimator(START);
        for (int second = 1; second <= 10; second++) rate.increment(10_000_000, START + second * 1000L);
        assertEquals(0L, rate.countPerSecond(START + 120_000));
        rate.increment(2_000_000, START + 120_500);
        assertEquals(2_000_000L, rate.countPerSecond(START + 120_900));
        rate.increment(1_000_000, START - 50_000);
        assertTrue(rate.countPerSecond(START - 49_000) >= 0);
    }

    @Test
    public void theEstimateChangesAtMostOnceASecond() {
        long[] now = {START};
        CgProgressTracker progress = new CgProgressTracker(() -> now[0]);
        progress.begin("Downloading", 100L << 20, CgProgress.Unit.BYTES);
        now[0] += 500;
        progress.advance(1L << 20);
        assertEquals("no estimate off half a second", -1L, progress.state().secondsRemaining());
        now[0] += 500;
        progress.advance(2L << 20);
        long first = progress.state().secondsRemaining();
        assertTrue(first > 0);
        now[0] += 100;
        progress.advance(10L << 20);
        assertEquals("held until a second has passed", first, progress.state().secondsRemaining());
    }

    @Test
    public void theReadoutIsForBytesOnly() {
        assertNull(new CgProgressState("Files", "", 412, 1178, 0, 0, CgProgress.Unit.ITEMS, -1).summary());
        assertEquals("12.4 MB of 48.9 MB · 30s left",
                new CgProgressState("Download", "", (long) (12.4 * (1 << 20)), (long) (48.9 * (1 << 20)), 0, 0,
                        CgProgress.Unit.BYTES, 30).summary());
        assertEquals("4.0 MB of 512 MB · 4m left",
                new CgProgressState("x", "", 4L << 20, 512L << 20, 0, 0, CgProgress.Unit.BYTES, 240).summary());
    }
}
