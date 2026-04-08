package com.crystalgraphics.msdfgen;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Memory measurement utilities for leak detection tests.
 * Uses JVM ManagementFactory APIs to track heap and non-heap usage.
 * <p>
 * Java 8 compatible. No lambdas, no streams.
 *
 * @since 1.0.0
 */
public final class MemoryMetrics {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    /** Tolerance in bytes for "memory returned to baseline" checks (5KB). */
    public static final long DEFAULT_TOLERANCE_BYTES = 5 * 1024;

    /** Larger tolerance for stress tests (50KB). */
    public static final long STRESS_TOLERANCE_BYTES = 50 * 1024;

    /** Tolerance for high-volume tests with 1000+ allocations (512KB).
     *  JVM heap expansion and GC hysteresis make tighter thresholds flaky. */
    public static final long HIGH_VOLUME_TOLERANCE_BYTES = 512 * 1024;

    /** Tolerance ratio for platform variance checks (15%). */
    public static final double PLATFORM_VARIANCE_RATIO = 0.15;

    private MemoryMetrics() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Captures a snapshot of current memory usage.
     * Forces GC before capture for more stable readings.
     *
     * @param forceGc whether to force garbage collection before measuring
     * @return a snapshot of memory usage
     */
    public static MemorySnapshot capture(boolean forceGc) {
        if (forceGc) {
            forceGarbageCollection();
        }
        MemoryUsage heap = MEMORY_BEAN.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY_BEAN.getNonHeapMemoryUsage();
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long totalMemory = runtime.totalMemory();
        long usedRuntime = totalMemory - freeMemory;
        return new MemorySnapshot(
            heap.getUsed(),
            nonHeap.getUsed(),
            usedRuntime,
            System.nanoTime()
        );
    }

    /**
     * Convenience capture with forced GC.
     */
    public static MemorySnapshot capture() {
        return capture(true);
    }

    /**
     * Forces garbage collection as thoroughly as possible.
     * Calls System.gc() twice with a sleep to allow finalizers to run.
     */
    public static void forceGarbageCollection() {
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.runFinalization();
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Checks if memory returned to baseline within tolerance.
     *
     * @param baseline  memory snapshot before test
     * @param afterTest memory snapshot after test and cleanup
     * @param toleranceBytes acceptable difference in bytes
     * @return true if memory is within tolerance of baseline
     */
    public static boolean isWithinTolerance(MemorySnapshot baseline, MemorySnapshot afterTest,
                                            long toleranceBytes) {
        long heapDiff = afterTest.heapUsed - baseline.heapUsed;
        long runtimeDiff = afterTest.runtimeUsed - baseline.runtimeUsed;
        // Use runtime memory as primary indicator (more stable than MXBean for our purposes)
        return Math.abs(runtimeDiff) <= toleranceBytes;
    }

    /**
     * Checks if a series of memory measurements shows linear growth (leak indicator).
     * Uses simple linear regression on the measurements.
     *
     * @param measurements array of memory values at regular intervals
     * @return the slope of the linear regression (bytes per measurement); positive = growing
     */
    public static double calculateGrowthSlope(long[] measurements) {
        if (measurements.length < 2) {
            return 0.0;
        }
        int n = measurements.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = measurements[i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (Math.abs(denominator) < 1e-10) {
            return 0.0;
        }
        return (n * sumXY - sumX * sumY) / denominator;
    }

    /**
     * Determines if memory growth pattern indicates a leak.
     * A leak is indicated by consistent positive slope exceeding a threshold.
     *
     * @param measurements memory usage samples at regular intervals
     * @param maxSlopePerIteration maximum acceptable growth per iteration (bytes)
     * @return true if growth pattern indicates a leak
     */
    public static boolean detectsLeak(long[] measurements, double maxSlopePerIteration) {
        double slope = calculateGrowthSlope(measurements);
        return slope > maxSlopePerIteration;
    }

    /**
     * Calculates fragmentation estimate based on committed-but-unused memory
     * relative to the maximum heap size. This measures how much memory the JVM
     * has committed (reserved from the OS) beyond what is actually in use.
     * <p>
     * A high value means the JVM committed a lot of memory that GC has since
     * freed but not returned to the OS — this is normal JVM behavior, especially
     * after stress tests. We measure {@code (total - used) / max} to get the
     * fraction of the heap ceiling that is committed-but-idle.
     *
     * @return fragmentation ratio (0.0 = no wasted committed memory, 1.0 = all committed memory is idle)
     */
    public static double estimateFragmentation() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long max = runtime.maxMemory();
        long used = total - free;
        if (max <= 0) return 0.0;
        long committedButUnused = total - used;
        return (double) committedButUnused / (double) max;
    }

    /**
     * Immutable snapshot of memory state at a point in time.
     */
    public static final class MemorySnapshot {
        public final long heapUsed;
        public final long nonHeapUsed;
        public final long runtimeUsed;
        public final long timestampNanos;

        public MemorySnapshot(long heapUsed, long nonHeapUsed, long runtimeUsed, long timestampNanos) {
            this.heapUsed = heapUsed;
            this.nonHeapUsed = nonHeapUsed;
            this.runtimeUsed = runtimeUsed;
            this.timestampNanos = timestampNanos;
        }

        /**
         * Returns the difference in runtime memory usage from another snapshot.
         */
        public long runtimeDiffFrom(MemorySnapshot other) {
            return this.runtimeUsed - other.runtimeUsed;
        }

        public String toString() {
            return String.format("MemorySnapshot[heap=%dKB, nonHeap=%dKB, runtime=%dKB]",
                heapUsed / 1024, nonHeapUsed / 1024, runtimeUsed / 1024);
        }
    }

    /**
     * Tracks memory over multiple sample points for trend analysis.
     */
    public static final class MemoryTracker {
        private final long[] samples;
        private int count;
        private final int capacity;

        public MemoryTracker(int capacity) {
            this.capacity = capacity;
            this.samples = new long[capacity];
            this.count = 0;
        }

        /**
         * Records a memory sample (forces GC first).
         */
        public void record() {
            if (count < capacity) {
                MemorySnapshot snap = capture(true);
                samples[count] = snap.runtimeUsed;
                count++;
            }
        }

        /**
         * Records a specific value (no GC).
         */
        public void record(long value) {
            if (count < capacity) {
                samples[count] = value;
                count++;
            }
        }

        /**
         * Returns the recorded samples (trimmed to actual count).
         */
        public long[] getSamples() {
            long[] result = new long[count];
            System.arraycopy(samples, 0, result, 0, count);
            return result;
        }

        /**
         * Returns the growth slope.
         */
        public double getSlope() {
            return calculateGrowthSlope(getSamples());
        }

        /**
         * Checks if measurements indicate a leak.
         */
        public boolean detectsLeak(double maxSlopePerSample) {
            return MemoryMetrics.detectsLeak(getSamples(), maxSlopePerSample);
        }

        /**
         * Returns the peak memory value recorded.
         */
        public long getPeak() {
            long peak = Long.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                if (samples[i] > peak) {
                    peak = samples[i];
                }
            }
            return peak;
        }

        /**
         * Returns the minimum memory value recorded.
         */
        public long getMin() {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                if (samples[i] < min) {
                    min = samples[i];
                }
            }
            return min;
        }

        /**
         * Returns the count of recorded samples.
         */
        public int getCount() {
            return count;
        }

        /**
         * Generates a CSV-formatted string of all samples.
         */
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append("sample_index,memory_bytes\n");
            for (int i = 0; i < count; i++) {
                sb.append(i).append(',').append(samples[i]).append('\n');
            }
            return sb.toString();
        }
    }
}
