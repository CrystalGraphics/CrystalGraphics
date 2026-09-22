package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** How much the ring holds, what it keeps when full, and when it stops by itself. */
public class CgTraceRetentionTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("retention.test");
    private static final long MS = 1_000_000L;

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.setEnabled(CHANNEL, true);
    }

    @After
    public void tearDown() {
        CgTrace.stopAfterHitch(0L, 0);
        CgTrace.configure(0, 600, 256);
        CgTrace.resetForTesting();
    }

    /** Frames of {@code millis} each, back to back from t=0; the last is committed by one more begin. */
    private static void frames(long... millis) {
        long at = 1_000L;
        for (long each : millis) {
            CgTrace.frameBegin(at);
            at += each * MS;
        }
        CgTrace.frameBegin(at);
    }

    @Test
    public void aGrownArenaKeepsItsEarlyZones() {
        CgTrace.configure(0, 8, 4096);
        CgTrace.setEnabled(CHANNEL, true);
        CgTrace.frameBegin(0L);
        for (int i = 0; i < 10_000; i++) CgTrace.zoneDone(CHANNEL, "ret:zone", 10L + i, 11L + i);
        CgTrace.frameBegin(1_000_000L);

        List<CgTraceSnapshot.ZoneView> zones = CgTrace.zonesBetween(0L, 1_000_000L);
        assertEquals("an arena growing past its first store lost zones", 10_000, zones.size());
        assertEquals("the earliest zone did not survive the growth", 10L, zones.get(0).startNanos());
    }

    @Test
    public void keepFirstStopsWhenFullAndStaysFull() {
        CgTrace.configure(5, 0, 256);
        CgTrace.setEnabled(CHANNEL, true);
        frames(1, 1, 1, 1, 1, 1, 1, 1);

        assertFalse("a full keep-first ring is still recording", CgTrace.isRecording());
        assertTrue(CgTrace.isFull());
        assertNotNull(CgTrace.stopReason());
        assertTrue("the channels to put back were not kept", CgTrace.stoppedChannels().contains(CHANNEL.name()));

        // Switched back on without a clear: the first frames must not be overwritten.
        CgTrace.setEnabled(CHANNEL, true);
        frames(1, 1, 1);
        List<CgFrameRecord> kept = CgTrace.frames();
        assertEquals(5, kept.size());
        assertEquals("the first frame was overwritten", 0L, kept.get(0).index());
        assertFalse(CgTrace.isRecording());
    }

    /**
     * The first frames stay for good and the newest roll behind them — the start of a recording is still
     * there, zones and counters too, long after a ring alone would have overwritten it.
     */
    @Test
    public void theFirstFramesSurviveBehindARollingRing() {
        CgTrace.configure(3, 4, 256);
        CgTrace.setEnabled(CHANNEL, true);
        long at = 1_000L;
        for (int frame = 0; frame < 12; frame++) {
            CgTrace.frameBegin(at);
            CgTrace.zoneDone(CHANNEL, "ret:frame" + frame, at + 10L, at + 20L);
            CgTrace.counter(CHANNEL, "ret:count", frame);
            // ENOUGH ZONES TO WRAP the ring arena many times over: the first frames' must not be among
            // what it overwrites.
            for (int i = 0; i < 2_000; i++) CgTrace.zoneDone(CHANNEL, "ret:filler", at + 30L + i, at + 31L + i);
            at += MS;
        }
        CgTrace.frameBegin(at);

        List<CgFrameRecord> kept = CgTrace.frames();
        assertEquals("not the first three and the newest four",
                List.of(0L, 1L, 2L, 8L, 9L, 10L, 11L), kept.stream().map(CgFrameRecord::index).toList());
        assertTrue("recording stopped although the newest frames are kept", CgTrace.isRecording());

        CgFrameRecord first = kept.get(0);
        assertTrue("frame #0's zones were overwritten by the ring",
                CgTrace.zonesIn(first).stream().anyMatch(zone -> zone.name().equals("ret:frame0")));
        assertEquals("frame #0's counter was overwritten by the ring",
                0L, CgTrace.countersIn(first).get(0).value());
    }

    @Test
    public void aHitchStopsRecordingTheStatedFramesLater() {
        CgTrace.stopAfterHitch(10 * MS, 2);
        frames(1, 1, 20, 1, 1, 1, 1);

        assertFalse("recording carried on past the frames after the hitch", CgTrace.isRecording());
        assertEquals(2L, CgTrace.hitchFrame());
        assertEquals("the hitch and the two frames after it are not the last recorded",
                5, CgTrace.frames().size());
        assertNotNull(CgTrace.stopReason());
    }

    @Test
    public void newestOverwritesTheOldestAndNeverStops() {
        CgTrace.configure(0, 4, 256);
        CgTrace.setEnabled(CHANNEL, true);
        frames(1, 1, 1, 1, 1, 1);

        assertTrue(CgTrace.isRecording());
        assertNull(CgTrace.stopReason());
        assertEquals(2L, CgTrace.frames().get(0).index());
    }

    /** Enabling at launch names owners whose channels have not registered yet. */
    @Test
    public void aPrefixEnabledEarlyTakesChannelsRegisteredLater() {
        CgTrace.enable("retention.late");
        CgTraceChannel late = CgTrace.channel("retention.late.channel");
        assertTrue("a channel registered after its prefix was enabled is not recording", CgTrace.isEnabled(late));

        CgTrace.disableAll();
        CgTraceChannel later = CgTrace.channel("retention.late.second");
        assertFalse("disableAll left the prefix standing", CgTrace.isEnabled(later));
    }

    /**
     * A clear from another thread must not leave this one writing an arena nobody can read.
     *
     * <p>It did: the clear dropped every arena from the registry and gave only the CLEARING thread a new
     * one, so every other thread's zones vanished until the process restarted.</p>
     */
    @Test
    public void aClearOnAnotherThreadLeavesThisThreadVisible() throws InterruptedException {
        CgTrace.zoneDone(CHANNEL, "ret:before", 5L, 6L);

        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                CgTrace.clear();
            } catch (Throwable e) {
                failed.set(e);
            }
        });
        other.start();
        other.join();
        assertNull(failed.get());

        CgTrace.zoneDone(CHANNEL, "ret:after", 50L, 60L);
        List<CgTraceSnapshot.ZoneView> zones = CgTrace.zonesBetween(0L, 100L);
        assertEquals("this thread's zones after another thread's clear were invisible", 1, zones.size());
        assertEquals("ret:after", zones.get(0).name());
    }
}
