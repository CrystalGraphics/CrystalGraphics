package com.crystalgraphics.trace;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Garbage-collection pauses with their cause, and direct memory, from the JVM's management beans.
 *
 * <p>Not public; {@link CgTrace} reads. A pause's notification arrives on a JMX thread after the pause, so pauses are
 * kept by their start time and attributed to a frame when read: a notification that comes after its frame committed
 * still lands on it. Collectors that run beside the program (G1's and ZGC's concurrent cycles) are not pauses and are
 * left out.</p>
 *
 * <p>A pause's start is its notification's arrival minus its duration. The JVM stamps pauses in milliseconds on its
 * own clock, and mapping that to {@code nanoTime} through the uptime put a pause about 20 ms before it began on
 * HotSpot 25; the notification follows its pause within a millisecond.</p>
 */
final class CgTraceGc {

    private static final int CAPACITY = 256;

    private static final Object LOCK = new Object();
    private static final CgTraceSnapshot.GcPauseView[] RING = new CgTraceSnapshot.GcPauseView[CAPACITY];
    private static long written;
    private static final AtomicLong PENDING = new AtomicLong();

    private static final BufferPoolMXBean DIRECT = directPool();

    private CgTraceGc() {
    }

    /** Registers the listeners, once. A JVM that offers no notifications records no pauses. */
    static void install() {
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = bean.getName();
                if (name.contains("Concurrent") || name.endsWith("Cycles") || !(bean instanceof NotificationEmitter)) continue;
                ((NotificationEmitter) bean).addNotificationListener(CgTraceGc::onNotification, null, null);
            }
        } catch (Throwable absent) {
            // No com.sun.management here: pauses stay unrecorded and the report says nothing of them.
        }
    }

    private static void onNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
        long arrived = System.nanoTime();
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        long duration = info.getGcInfo().getDuration() * 1_000_000L;
        CgTraceSnapshot.GcPauseView pause = new CgTraceSnapshot.GcPauseView(info.getGcName(), info.getGcAction(),
                info.getGcCause(), arrived - duration, duration);
        synchronized (LOCK) {
            RING[(int) (written++ % CAPACITY)] = pause;
        }
        PENDING.incrementAndGet();
    }

    /** Pauses that began inside {@code frame}, by start. */
    static List<CgTraceSnapshot.GcPauseView> in(CgFrameRecord frame) {
        List<CgTraceSnapshot.GcPauseView> out = new ArrayList<>();
        for (CgTraceSnapshot.GcPauseView pause : all()) if (frame.contains(pause.startNanos())) out.add(pause);
        return out;
    }

    /** Every pause held, by start. */
    static List<CgTraceSnapshot.GcPauseView> all() {
        List<CgTraceSnapshot.GcPauseView> out = new ArrayList<>();
        synchronized (LOCK) {
            for (long slot = Math.max(0L, written - CAPACITY); slot < written; slot++) out.add(RING[(int) (slot % CAPACITY)]);
        }
        out.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
        return out;
    }

    static long pending() {
        return PENDING.get();
    }

    static void clear() {
        synchronized (LOCK) {
            written = 0L;
        }
        PENDING.set(0L);
    }

    private static BufferPoolMXBean directPool() {
        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) return pool;
            }
        } catch (Throwable absent) {
            // a JVM without the buffer-pool beans
        }
        return null;
    }

    static boolean directAvailable() {
        return DIRECT != null;
    }

    /** Bytes of direct buffers in use, or {@link CgFrameRecord#ABSENT}. */
    static long directBytes() {
        return DIRECT == null ? CgFrameRecord.ABSENT : DIRECT.getMemoryUsed();
    }
}
