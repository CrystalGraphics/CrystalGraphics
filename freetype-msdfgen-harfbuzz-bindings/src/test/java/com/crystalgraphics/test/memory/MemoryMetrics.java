package com.crystalgraphics.test.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Memory measurement utilities for leak detection tests.
 * All values in bytes. Thread-safe for use in multi-threaded tests.
 *
 * <p>Uses JVM heap + estimates of native memory by observing JVM-reported
 * values before and after GC. For precise native leak detection, use
 * platform tools (Valgrind, Dr.Memory, leaks) via {@link PlatformTools}.</p>
 */
public final class MemoryMetrics {

    /**
     * Tolerance for "returned to baseline" checks: 50KB.
     * <p>JVM memory measurements include noise from JIT compilation, class
     * loading, and GC overhead that can easily fluctuate by 10-30KB between
     * snapshots. A 50KB tolerance catches real native memory leaks (which
     * tend to be 100KB+ per iteration) while tolerating JVM-level noise.</p>
     */
    public static final long BASELINE_TOLERANCE_BYTES = 50 * 1024;

    /** Number of GC cycles to force before measurement */
    private static final int GC_CYCLES = 3;

    /** Sleep after GC to allow finalizers to run (ms) */
    private static final int GC_SLEEP_MS = 200;

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private MemoryMetrics() {
    }

    /**
     * Snapshot of memory state at a point in time.
     */
    public static final class Snapshot {
        public final long heapUsed;
        public final long heapCommitted;
        public final long nonHeapUsed;
        public final long timestamp;
        public final long freeMemory;
        public final long totalMemory;

        Snapshot(long heapUsed, long heapCommitted, long nonHeapUsed,
                 long freeMemory, long totalMemory) {
            this.heapUsed = heapUsed;
            this.heapCommitted = heapCommitted;
            this.nonHeapUsed = nonHeapUsed;
            this.freeMemory = freeMemory;
            this.totalMemory = totalMemory;
            this.timestamp = System.nanoTime();
        }

        /**
         * Returns estimated total memory in use (heap + non-heap).
         */
        public long totalUsed() {
            return heapUsed + nonHeapUsed;
        }

        /**
         * Returns the difference from another snapshot (this - other).
         * Positive means this snapshot uses MORE memory.
         */
        public long diffFrom(Snapshot baseline) {
            return this.totalUsed() - baseline.totalUsed();
        }

        /**
         * Returns heap usage difference from baseline.
         */
        public long heapDiffFrom(Snapshot baseline) {
            return this.heapUsed - baseline.heapUsed;
        }

        /**
         * Checks if memory returned to baseline within tolerance.
         */
        public boolean isWithinBaselineOf(Snapshot baseline, long toleranceBytes) {
            long diff = Math.abs(this.heapUsed - baseline.heapUsed);
            return diff <= toleranceBytes;
        }

        @Override
        public String toString() {
            return String.format("Memory[heap=%dKB, nonHeap=%dKB, total=%dKB]",
                    heapUsed / 1024, nonHeapUsed / 1024, totalUsed() / 1024);
        }
    }

    /**
     * Forces garbage collection and takes a memory snapshot.
     * Runs multiple GC cycles with sleep to allow finalizers to complete.
     */
    public static Snapshot takeSnapshot() {
        forceGC();
        return takeSnapshotNoGC();
    }

    /**
     * Takes a memory snapshot WITHOUT forcing GC first.
     */
    public static Snapshot takeSnapshotNoGC() {
        MemoryUsage heap = MEMORY_BEAN.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY_BEAN.getNonHeapMemoryUsage();
        Runtime rt = Runtime.getRuntime();
        return new Snapshot(
                heap.getUsed(),
                heap.getCommitted(),
                nonHeap.getUsed(),
                rt.freeMemory(),
                rt.totalMemory()
        );
    }

    /**
     * Forces GC with multiple cycles and sleep to allow finalizers.
     */
    public static void forceGC() {
        for (int i = 0; i < GC_CYCLES; i++) {
            System.gc();
            try {
                Thread.sleep(GC_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.runFinalization();
        try {
            Thread.sleep(GC_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Measures memory growth trend over iterations.
     * Returns average bytes growth per iteration.
     * A positive trend indicates a leak.
     */
    public static double measureGrowthTrend(long[] measurements) {
        if (measurements.length < 2) return 0;

        // Simple linear regression
        int n = measurements.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += measurements[i];
            sumXY += (double) i * measurements[i];
            sumXX += (double) i * i;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0) return 0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    /**
     * Reads all bytes from an InputStream (Java 8 compatible).
     */
    public static byte[] readAllBytes(InputStream is) {
        if (is == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stream", e);
        }
    }

    /**
     * Extracts a classpath resource to a temporary file and returns its path.
     */
    public static String extractResourceToTempFile(Class<?> clazz, String resourcePath, String suffix) {
        InputStream is = clazz.getResourceAsStream(resourcePath);
        if (is == null) return null;
        try {
            java.io.File tempFile = java.io.File.createTempFile("test-font-", suffix);
            tempFile.deleteOnExit();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.close();
            is.close();
            return tempFile.getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract resource", e);
        }
    }

    /**
     * Formats a byte count as a human-readable string.
     */
    public static String formatBytes(long bytes) {
        if (Math.abs(bytes) < 1024) return bytes + " B";
        if (Math.abs(bytes) < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
