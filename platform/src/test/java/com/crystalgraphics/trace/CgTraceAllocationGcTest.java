package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Allocated bytes per group and thread, GC pauses with their cause, and direct memory, each per frame and each shown
 * by the report.
 */
public class CgTraceAllocationGcTest {

    private static final CgTraceChannel CH = CgTrace.channel("t1gate");
    private static final String GROUP = "t1gate:frame.EFFECTS";
    private static final int MB = 1024 * 1024;

    /** Kept reachable so the allocation under test cannot be elided. */
    private static volatile Object sink;

    @Before
    public void on() {
        CgTrace.resetForTesting();
        CgTrace.enable("t1gate");
    }

    @After
    public void off() {
        sink = null;
        CgTrace.resetForTesting();
    }

    @Test
    public void theCounterIsTheCurrentThreadsAndAvailableOnTheTestJvm() {
        // The test JVM is HotSpot: an unavailable counter here is a broken probe, not a configuration.
        assertTrue("thread allocation counting must be available on HotSpot", CgThreadAllocation.available());
        long before = CgThreadAllocation.current();
        sink = new byte[MB];
        assertTrue(CgThreadAllocation.current() - before >= MB);
    }

    @Test
    public void aGroupsAllocationIsRecordedForItsFrameAndThread() throws InterruptedException {
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();

        long mark = CgTrace.allocationBegin(CH);
        sink = new byte[2 * MB];
        CgTrace.allocationEnd(CH, GROUP, mark);

        Thread worker = new Thread(() -> {
            long m = CgTrace.allocationBegin(CH);
            sink = new byte[MB];
            CgTrace.allocationEnd(CH, GROUP, m);
        }, "t1gate-worker");
        worker.start();
        worker.join();

        CgTrace.frameEnd();
        CgTrace.frameBegin();   // commits the frame
        CgTrace.frameBegin();   // and flushes every thread's totals for it

        CgFrameRecord record = CgTrace.frame(frame);
        assertNotNull(record);
        List<CgTraceSnapshot.AllocationView> views = CgTrace.allocationsIn(record);

        long mine = bytes(views, GROUP, Thread.currentThread().getName());
        long theirs = bytes(views, GROUP, "t1gate-worker");
        assertTrue("the frame thread's group: " + mine, mine >= 2L * MB && mine < 3L * MB);
        assertTrue("the worker's group: " + theirs, theirs >= MB && theirs < 2L * MB);

        // The frame thread's whole frame, read once a frame: at least what its group allocated.
        assertNotEquals(CgFrameRecord.ABSENT, record.allocatedBytes());
        assertTrue(record.allocatedBytes() >= 2L * MB);
    }

    @Test
    public void aDisabledChannelRecordsNothing() {
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        CgTrace.disable("t1gate");
        CgTrace.enable("t1gate.other");

        long mark = CgTrace.allocationBegin(CH);
        sink = new byte[MB];
        CgTrace.allocationEnd(CH, GROUP, mark);

        CgTrace.frameBegin();
        CgTrace.frameBegin();
        CgFrameRecord record = CgTrace.frame(frame);
        if (record != null) assertEquals(0L, bytes(CgTrace.allocationsIn(record), GROUP, Thread.currentThread().getName()));
    }

    @Test
    public void aCollectionIsRecordedWithItsCauseOnItsFrameAndShownByTheReport() throws InterruptedException {
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        Thread.sleep(20);   // a pause's start is reported in whole milliseconds since JVM start
        System.gc();
        CgTrace.frameEnd();
        // GC notifications arrive on a JMX thread, after the pause: the frame stays open until it has.
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline && CgTrace.pendingGcNotifications() == 0) Thread.sleep(5);
        Thread.sleep(50);
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        CgFrameRecord record = CgTrace.frame(frame);
        assertNotNull(record);
        assertTrue(record.hadGc());
        List<CgTraceSnapshot.GcPauseView> pauses = CgTrace.gcPausesIn(record);
        assertFalse("the System.gc() pause lands on the frame that ran it", pauses.isEmpty());
        CgTraceSnapshot.GcPauseView pause = pauses.get(0);
        assertEquals("System.gc()", pause.cause());
        assertFalse(pause.collector().isEmpty());
        assertTrue(pause.durationNanos() >= 0L);
        assertTrue(record.contains(pause.startNanos()));

        String text = CgTraceReport.of(CgTrace.snapshot()).frame(frame);
        assertTrue("the report names the pause's cause:\n" + text, text.contains("System.gc()"));
    }

    @Test
    public void directMemoryIsRecordedPerFrame() {
        Assume.assumeTrue(CgTrace.directMemoryAvailable());
        CgTrace.frameBegin();
        long first = CgTrace.currentFrameIndex();
        CgTrace.frameBegin();
        long second = CgTrace.currentFrameIndex();
        ByteBuffer held = ByteBuffer.allocateDirect(4 * MB);
        sink = held;
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        long a = CgTrace.frame(first).directBytes();
        long b = CgTrace.frame(second).directBytes();
        assertNotEquals(CgFrameRecord.ABSENT, a);
        assertTrue("direct memory rose by the buffer: " + a + " -> " + b, b - a >= 4L * MB);
    }

    @Test
    public void theReportShowsAFramesAllocationByGroup() {
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        long mark = CgTrace.allocationBegin(CH);
        sink = new byte[MB];
        CgTrace.allocationEnd(CH, GROUP, mark);
        CgTrace.frameEnd();
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        String text = CgTraceReport.of(CgTrace.snapshot()).frame(frame);
        assertTrue("the report lists the group's allocation:\n" + text, text.contains(GROUP));
    }

    private static long bytes(List<CgTraceSnapshot.AllocationView> views, String group, String thread) {
        long total = 0L;
        for (CgTraceSnapshot.AllocationView v : views) {
            if (v.group().equals(group) && v.thread().equals(thread)) total += v.bytes();
        }
        return total;
    }
}
