package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A reader on another thread, while the owner records into an arena small enough to lap constantly: every zone
 * it is handed must be one the owner really wrote, never a slot read before its contents landed or while the
 * ring overwrote it.
 */
public class CgTraceZonesConcurrentReadTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("zones.concurrent.test");

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.configure(0, 2, 16);   // the smallest arena, so the writer laps it every few hundred zones
        CgTrace.setEnabled(CHANNEL, true);
    }

    @After
    public void tearDown() {
        CgTrace.configure(0, 600, 256);
        CgTrace.resetForTesting();
    }

    @Test
    public void aWorkerNeverReadsATornZone() throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                try (CgTrace.Zone outer = CgTrace.zone(CHANNEL, "outer")) {
                    try (CgTrace.Zone inner = CgTrace.zone(CHANNEL, "inner")) {
                        Thread.onSpinWait();
                    }
                }
            }
        }, "zones-writer");
        CgTraceSnapshot.BACKSTOP_DROPPED.set(0L);
        writer.start();
        long began = System.nanoTime();
        long reads = 0;
        long seen = 0;
        try {
            while (System.nanoTime() - began < 1_500_000_000L) {
                List<CgTraceSnapshot.ZoneView> zones = CgTrace.zonesBetween(0L, Long.MAX_VALUE);
                for (CgTraceSnapshot.ZoneView zone : zones) {
                    if (!"zones-writer".equals(zone.thread())) continue;
                    seen++;
                    if (zone.startNanos() < began - 1_000_000_000L) {
                        fail("a zone from before the writer started: " + zone);
                    }
                    if (!zone.isOpen() && zone.endNanos() < zone.startNanos()) {
                        fail("a zone that ends before it starts: " + zone);
                    }
                }
                reads++;
            }
        } finally {
            stop.set(true);
            writer.join();
        }
        assertTrue("the reader never saw the writer's zones", seen > 0);
        // The backstop hides a torn zone from the reader; it must never have had one to hide.
        assertEquals("torn zones passed the watermark check", 0L, CgTraceSnapshot.BACKSTOP_DROPPED.get());
        assertTrue(reads > 0);
    }
}
