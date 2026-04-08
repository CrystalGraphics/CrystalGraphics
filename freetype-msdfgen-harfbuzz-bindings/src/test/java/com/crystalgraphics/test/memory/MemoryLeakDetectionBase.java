package com.crystalgraphics.test.memory;

import com.crystalgraphics.NativeLoader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public abstract class MemoryLeakDetectionBase {

    @Rule
    public TestName testName = new TestName();

    protected static final long TOLERANCE = MemoryMetrics.BASELINE_TOLERANCE_BYTES;
    protected static final String TEST_FONT_RESOURCE = "/test-font.ttf";

    private static Boolean nativesAvailable;

    @Before
    public void ensureNativesAvailable() {
        if (nativesAvailable == null) {
            try {
                NativeLoader.ensureLoaded();
                nativesAvailable = Boolean.TRUE;
            } catch (UnsatisfiedLinkError e) {
                nativesAvailable = Boolean.FALSE;
            }
        }
        Assert.assertTrue("Native library not available", nativesAvailable);
    }

    protected byte[] loadTestFontData() {
        byte[] data = MemoryMetrics.readAllBytes(
                getClass().getResourceAsStream(TEST_FONT_RESOURCE));
        Assert.assertNotNull("Test font not found on classpath", data);
        return data;
    }

    protected String extractTestFontToFile() {
        String path = MemoryMetrics.extractResourceToTempFile(
                getClass(), TEST_FONT_RESOURCE, ".ttf");
        Assert.assertNotNull("Test font not found on classpath", path);
        return path;
    }

    protected void logTestStart() {
        System.out.println("\n=== TEST: " + testName.getMethodName() + " ===");
    }

    protected void logMemory(String label, MemoryMetrics.Snapshot snapshot) {
        System.out.println("  " + label + ": " + snapshot);
    }

    protected void logResult(String label, long valueBytes) {
        System.out.println("  " + label + ": " + MemoryMetrics.formatBytes(valueBytes));
    }

    protected void assertMemoryReturnsToBaseline(
            MemoryMetrics.Snapshot baseline,
            MemoryMetrics.Snapshot afterCleanup) {
        long diff = Math.abs(afterCleanup.heapDiffFrom(baseline));
        System.out.println("  Memory diff from baseline: " + MemoryMetrics.formatBytes(diff)
                + " (tolerance: " + MemoryMetrics.formatBytes(TOLERANCE) + ")");
        if (diff > TOLERANCE) {
            org.junit.Assert.fail(
                    "Memory did not return to baseline. Diff: "
                    + MemoryMetrics.formatBytes(diff) + ", tolerance: "
                    + MemoryMetrics.formatBytes(TOLERANCE)
                    + "\n  Baseline: " + baseline
                    + "\n  After cleanup: " + afterCleanup);
        }
    }

    protected void assertNoMemoryGrowthTrend(long[] measurements, double maxBytesPerIteration) {
        double trend = MemoryMetrics.measureGrowthTrend(measurements);
        System.out.println("  Memory growth trend: " + String.format("%.2f", trend)
                + " bytes/iteration (max: " + String.format("%.2f", maxBytesPerIteration) + ")");
        if (trend > maxBytesPerIteration) {
            org.junit.Assert.fail(
                    "Memory growth trend detected: " + String.format("%.2f", trend)
                    + " bytes/iteration exceeds threshold of "
                    + String.format("%.2f", maxBytesPerIteration));
        }
    }
}
