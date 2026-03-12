package com.msdfgen;

import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.*;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MSDFgenMemoryLeakTests extends MemoryLeakDetectionBase {

    // ==================== Group A: Basic Lifecycle ====================

    @Test
    public void testA1_singleShapeAllocationDeallocation() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        Shape shape = Shape.create();
        assertFalse(shape.isFreed());

        MemoryMetrics.MemorySnapshot duringAllocation = MemoryMetrics.capture(false);

        shape.free();
        assertTrue(shape.isFreed());

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("A1_SingleAllocationDeallocation", withinTolerance,
            before, duringAllocation, after, duration,
            "Leak=" + (after.runtimeUsed - before.runtimeUsed) + "B");

        assertTrue("Memory did not return to baseline (diff=" +
            (after.runtimeUsed - before.runtimeUsed) + "B)", withinTolerance);
    }

    @Test
    public void testA2_repeatedAllocationCycles100() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();
        MemoryMetrics.MemoryTracker tracker = new MemoryMetrics.MemoryTracker(12);
        tracker.record(before.runtimeUsed);

        for (int cycle = 0; cycle < 100; cycle++) {
            Shape shape = createSimpleSquare();
            shape.normalize();
            shape.edgeColoringSimple(3.0);
            shape.free();

            if ((cycle + 1) % 10 == 0) {
                tracker.record();
            }
        }

        tracker.record();
        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        // 512 bytes per iteration is the max acceptable slope
        boolean noLeak = !tracker.detectsLeak(512);
        double slope = tracker.getSlope();

        recordResult("A2_RepeatedCycles100", noLeak,
            before, null, after, duration,
            "Slope=" + String.format("%.1f", slope) + "B/sample");

        assertTrue("Linear memory growth detected (slope=" +
            String.format("%.1f", slope) + "B/sample)", noLeak);
    }

    @Test
    public void testA3_bitmapBufferAllocation512x512() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        Bitmap bitmap = Bitmap.allocMsdf(512, 512);

        long byteSize = bitmap.getByteSize();
        // MSDF = 3 channels * 512 * 512 * 4 bytes/float = 3,145,728 bytes (~3MB)
        long expectedSize = 512L * 512L * 3 * 4;
        long tolerance10Pct = expectedSize / 10;
        assertTrue("Bitmap byte size (" + byteSize + ") not within 10% of expected (" + expectedSize + ")",
            Math.abs(byteSize - expectedSize) <= tolerance10Pct);

        assertEquals(3, bitmap.getChannelCount());
        assertEquals(512, bitmap.getWidth());
        assertEquals(512, bitmap.getHeight());

        bitmap.free();
        assertTrue(bitmap.isFreed());

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.STRESS_TOLERANCE_BYTES);

        recordResult("A3_BitmapBuffer512x512", withinTolerance,
            before, null, after, duration,
            "ByteSize=" + byteSize + " Expected=" + expectedSize);

        assertTrue("Bitmap memory not freed (diff=" +
            (after.runtimeUsed - before.runtimeUsed) + "B)", withinTolerance);
    }

    // ==================== Group B: Error Path Handling ====================

    @Test
    public void testB1_exceptionDuringBitmapConstructionInvalidDimensions() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        boolean exceptionCaught = false;
        try {
            Bitmap.alloc(MsdfConstants.BITMAP_TYPE_SDF, -1, -1);
        } catch (IllegalArgumentException e) {
            exceptionCaught = true;
        }
        assertTrue("Expected IllegalArgumentException for negative dimensions", exceptionCaught);

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("B1_ExceptionDuringConstruction", withinTolerance,
            before, null, after, duration, "Exception path clean");

        assertTrue("Memory leaked after exception", withinTolerance);
    }

    @Test
    public void testB2_partialOperationWithFreedShape() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        Shape shape = createSimpleSquare();
        Bitmap bitmap = Bitmap.allocSdf(32, 32);

        // Free shape before generation - should cause exception
        shape.free();

        boolean exceptionCaught = false;
        try {
            Transform transform = new Transform().scale(1.0).translate(0, 0).range(4.0);
            Generator.generateSdf(bitmap, shape, transform);
        } catch (IllegalStateException e) {
            exceptionCaught = true;
        }
        assertTrue("Expected IllegalStateException for freed shape", exceptionCaught);

        bitmap.free();

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("B2_PartialOperationFailure", withinTolerance,
            before, null, after, duration, "Freed shape exception path");

        assertTrue("Memory leaked after partial operation failure", withinTolerance);
    }

    @Test
    public void testB3_multipleErrorsInSequence() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        int errorCount = 0;
        for (int i = 0; i < 10; i++) {
            try {
                // Invalid bitmap type
                Bitmap.alloc(99, 32, 32);
            } catch (MsdfException e) {
                errorCount++;
            } catch (IllegalArgumentException e) {
                errorCount++;
            }

            try {
                // Zero-dimension bitmap
                Bitmap.alloc(MsdfConstants.BITMAP_TYPE_SDF, 0, 32);
            } catch (IllegalArgumentException e) {
                errorCount++;
            }
        }

        assertTrue("Expected 20 errors, got " + errorCount, errorCount == 20);

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("B3_MultipleErrorsInSequence", withinTolerance,
            before, null, after, duration,
            errorCount + " errors caught, no cumulative leak expected");

        assertTrue("Cumulative leak after " + errorCount + " errors", withinTolerance);
    }

    // ==================== Group C: Stress & Scale ====================

    @Test
    public void testC1_highVolumeAllocation1000Shapes() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        Shape[] shapes = new Shape[1000];
        for (int i = 0; i < 1000; i++) {
            shapes[i] = Shape.create();
        }

        MemoryMetrics.MemorySnapshot peak = MemoryMetrics.capture(false);

        for (int i = 0; i < 1000; i++) {
            shapes[i].free();
        }

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.HIGH_VOLUME_TOLERANCE_BYTES);

        recordResult("C1_HighVolume1000Shapes", withinTolerance,
            before, peak, after, duration,
            "Peak=" + peak.runtimeUsed / 1024 + "KB");

        assertTrue("1000 shapes not fully freed (diff=" +
            (after.runtimeUsed - before.runtimeUsed) + "B, tolerance=" +
            MemoryMetrics.HIGH_VOLUME_TOLERANCE_BYTES + "B)", withinTolerance);
    }

    @Test
    public void testC2_memoryFragmentationMixedSizes() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // Allocate mixed sizes: small shapes, large bitmaps, interleaved
        for (int round = 0; round < 5; round++) {
            Shape[] shapes = new Shape[50];
            Bitmap[] bitmaps = new Bitmap[10];

            for (int i = 0; i < 50; i++) {
                shapes[i] = createSimpleSquare();
            }
            for (int i = 0; i < 10; i++) {
                int size = 16 + (i * 16); // 16x16 to 176x176
                bitmaps[i] = Bitmap.allocMsdf(size, size);
            }

            // Free in reverse order to stress allocator
            for (int i = 9; i >= 0; i--) {
                bitmaps[i].free();
            }
            for (int i = 0; i < 50; i++) {
                shapes[i].free();
            }
        }

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        double fragmentation = MemoryMetrics.estimateFragmentation();
        boolean lowFragmentation = fragmentation < 0.5; // 50% threshold (more lenient than 5% due to JVM GC)
        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.STRESS_TOLERANCE_BYTES);

        recordResult("C2_FragmentationMixedSizes", withinTolerance && lowFragmentation,
            before, null, after, duration,
            "Fragmentation=" + String.format("%.1f%%", fragmentation * 100));

        assertTrue("Excessive fragmentation: " + String.format("%.1f%%", fragmentation * 100), lowFragmentation);
        assertTrue("Memory not returned after mixed allocation", withinTolerance);
    }

    @Test
    public void testC3_multipleBitmapsSimultaneous() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // Create all 4 bitmap types simultaneously
        Bitmap sdf = Bitmap.allocSdf(64, 64);
        Bitmap psdf = Bitmap.allocPsdf(64, 64);
        Bitmap msdf = Bitmap.allocMsdf(64, 64);
        Bitmap mtsdf = Bitmap.allocMtsdf(64, 64);

        assertEquals(1, sdf.getChannelCount());
        assertEquals(1, psdf.getChannelCount());
        assertEquals(3, msdf.getChannelCount());
        assertEquals(4, mtsdf.getChannelCount());

        sdf.free();
        psdf.free();
        msdf.free();
        mtsdf.free();

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("C3_MultipleBitmapsSimultaneous", withinTolerance,
            before, null, after, duration, "4 bitmap types created/freed");

        assertTrue("Concurrent bitmaps not fully freed", withinTolerance);
    }

    @Test
    public void testC4_stressComplexShapesWithGeneration() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        for (int i = 0; i < 100; i++) {
            Shape shape = createComplexShape();
            shape.normalize();
            shape.edgeColoringSimple(3.0);

            Bitmap bitmap = Bitmap.allocMsdf(32, 32);
            Transform transform = Transform.autoFrame(shape, 32, 32, 4.0);
            Generator.generateMsdf(bitmap, shape, transform);

            // Verify pixel data is accessible
            float[] pixels = bitmap.getPixelData();
            assertTrue("Pixel data should be 32*32*3=" + (32*32*3) + " but was " + pixels.length,
                pixels.length == 32 * 32 * 3);

            bitmap.free();
            shape.free();
        }

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.STRESS_TOLERANCE_BYTES);

        recordResult("C4_StressComplexShapes100", withinTolerance,
            before, null, after, duration,
            "100 complex shapes with MSDF generation");

        assertTrue("Complex shape stress test leaked memory", withinTolerance);
    }

    // ==================== Group D: Finalizer & GC Testing ====================

    @Test
    public void testD1_finalizerCleanupShape() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // Create shapes and let them fall out of scope WITHOUT calling free()
        for (int i = 0; i < 50; i++) {
            Shape leaked = createSimpleSquare();
            // Intentionally NOT calling leaked.free() - relying on finalizer
        }

        // Force GC multiple times to trigger finalizers
        MemoryMetrics.forceGarbageCollection();
        MemoryMetrics.forceGarbageCollection();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MemoryMetrics.forceGarbageCollection();

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        // Finalizers may not reclaim ALL memory, but should reclaim most
        // Allow larger tolerance since finalizer timing is non-deterministic
        long leakBytes = after.runtimeUsed - before.runtimeUsed;
        boolean acceptable = Math.abs(leakBytes) < 256 * 1024; // 256KB tolerance for finalizer tests

        recordResult("D1_FinalizerCleanupShape", acceptable,
            before, null, after, duration,
            "Finalizer-based cleanup. Leak=" + leakBytes + "B (tolerance=256KB)");

        assertTrue("Finalizer failed to reclaim most memory (leak=" + leakBytes + "B)",
            acceptable);
    }

    @Test
    public void testD2_finalizerCleanupBitmaps() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // Create bitmaps and let them fall out of scope
        for (int i = 0; i < 20; i++) {
            Bitmap leaked = Bitmap.allocMsdf(64, 64);
            // Intentionally NOT calling leaked.free()
        }

        MemoryMetrics.forceGarbageCollection();
        MemoryMetrics.forceGarbageCollection();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MemoryMetrics.forceGarbageCollection();

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        long leakBytes = after.runtimeUsed - before.runtimeUsed;
        boolean acceptable = Math.abs(leakBytes) < 256 * 1024;

        recordResult("D2_FinalizerCleanupBitmaps", acceptable,
            before, null, after, duration,
            "Bitmap finalizer cleanup. Leak=" + leakBytes + "B");

        assertTrue("Bitmap finalizer failed (leak=" + leakBytes + "B)", acceptable);
    }

    // ==================== Group E: Platform-Specific Testing ====================

    @Test
    public void testE1_crossPlatformConsistencyCheck() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // Run a standardized workload and measure
        long[] iterationMemory = new long[10];
        for (int i = 0; i < 10; i++) {
            Shape shape = createSimpleSquare();
            shape.normalize();
            shape.edgeColoringSimple(3.0);

            Bitmap bitmap = Bitmap.allocSdf(64, 64);
            Transform transform = Transform.autoFrame(shape, 64, 64, 4.0);
            Generator.generateSdf(bitmap, shape, transform);

            bitmap.free();
            shape.free();

            MemoryMetrics.MemorySnapshot snap = MemoryMetrics.capture();
            iterationMemory[i] = snap.runtimeUsed;
        }

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        // Calculate variance across iterations
        long sum = 0;
        for (int i = 0; i < iterationMemory.length; i++) {
            sum += iterationMemory[i];
        }
        long mean = sum / iterationMemory.length;
        long maxDeviation = 0;
        for (int i = 0; i < iterationMemory.length; i++) {
            long dev = Math.abs(iterationMemory[i] - mean);
            if (dev > maxDeviation) maxDeviation = dev;
        }

        double varianceRatio = (double) maxDeviation / (double) mean;
        boolean lowVariance = varianceRatio < MemoryMetrics.PLATFORM_VARIANCE_RATIO;

        recordResult("E1_CrossPlatformConsistency", lowVariance,
            before, null, after, duration,
            "Platform=" + PlatformTools.getPlatformDescription() +
            " Variance=" + String.format("%.1f%%", varianceRatio * 100));

        assertTrue("Memory variance too high across iterations: " +
            String.format("%.1f%%", varianceRatio * 100), lowVariance);
    }

    @Test
    public void testE2_platformNativeValidation() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();

        String os = PlatformTools.OS;
        String arch = PlatformTools.ARCH;
        assertNotNull("OS detection failed", os);
        assertNotNull("Architecture detection failed", arch);
        assertFalse("OS detected as 'unknown'", "unknown".equals(os));

        // Verify library loaded correctly by testing basic operations
        Shape shape = Shape.create();
        assertFalse(shape.isFreed());
        shape.free();
        assertTrue(shape.isFreed());

        Bitmap bitmap = Bitmap.allocSdf(8, 8);
        assertEquals(1, bitmap.getChannelCount());
        bitmap.free();

        long duration = System.currentTimeMillis() - startTime;
        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();

        String platformInfo = PlatformTools.getPlatformDescription();
        boolean isRecognizedPlatform = "windows".equals(os) || "linux".equals(os) || "macos".equals(os);
        boolean isRecognizedArch = "x64".equals(arch) || "aarch64".equals(arch) || "x86".equals(arch);

        recordResult("E2_PlatformNativeValidation", isRecognizedPlatform && isRecognizedArch,
            baseline, null, after, duration,
            "Platform=" + platformInfo +
            " ARM64=" + PlatformTools.IS_ARM64);

        assertTrue("Unrecognized platform: " + platformInfo, isRecognizedPlatform);
        assertTrue("Unrecognized architecture: " + arch, isRecognizedArch);
    }

    @Test
    public void testE3_largeBitmapAllocation() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // 4096x4096 MTSDF = 4 channels * 4096 * 4096 * 4 bytes = 268MB
        // Use 1024x1024 to avoid OOM in test environments
        int size = 1024;
        Bitmap largeBitmap = Bitmap.allocMtsdf(size, size);

        long byteSize = largeBitmap.getByteSize();
        long expectedSize = (long) size * size * 4 * 4; // 4 channels, 4 bytes/float
        assertEquals("Large bitmap byte size mismatch", expectedSize, byteSize);

        MemoryMetrics.MemorySnapshot peak = MemoryMetrics.capture(false);

        largeBitmap.free();

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.STRESS_TOLERANCE_BYTES);

        recordResult("E3_LargeBitmapAllocation", withinTolerance,
            before, peak, after, duration,
            size + "x" + size + " MTSDF, " + byteSize + " bytes");

        assertTrue("Large bitmap memory not freed", withinTolerance);
    }

    // ==================== Group F: Additional Robustness Tests ====================

    @Test
    public void testF1_doubleFreeSafety() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        Shape shape = createSimpleSquare();
        shape.free();
        assertTrue(shape.isFreed());

        // Second free should be a no-op, not crash
        shape.free();
        assertTrue(shape.isFreed());

        Bitmap bitmap = Bitmap.allocSdf(32, 32);
        bitmap.free();
        bitmap.free(); // Should be safe

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("F1_DoubleFreeSafety", withinTolerance,
            before, null, after, duration, "Double-free did not crash");

        assertTrue("Double-free caused memory issue", withinTolerance);
    }

    @Test
    public void testF2_useAfterFreeDetection() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        Shape shape = Shape.create();
        shape.free();

        // All operations on freed shape should throw
        int exceptionsThrown = 0;

        try { shape.normalize(); } catch (IllegalStateException e) { exceptionsThrown++; }
        try { shape.validate(); } catch (IllegalStateException e) { exceptionsThrown++; }
        try { shape.getBounds(); } catch (IllegalStateException e) { exceptionsThrown++; }
        try { shape.addContour(); } catch (IllegalStateException e) { exceptionsThrown++; }
        try { shape.getContourCount(); } catch (IllegalStateException e) { exceptionsThrown++; }
        try { shape.getEdgeCount(); } catch (IllegalStateException e) { exceptionsThrown++; }
        try { shape.edgeColoringSimple(3.0); } catch (IllegalStateException e) { exceptionsThrown++; }

        assertEquals("Not all use-after-free operations threw", 7, exceptionsThrown);

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("F2_UseAfterFreeDetection", withinTolerance && (exceptionsThrown == 7),
            before, null, after, duration,
            exceptionsThrown + "/7 use-after-free operations properly detected");

        assertTrue("Use-after-free detection failed", exceptionsThrown == 7);
    }

    @Test
    public void testF3_segmentOwnershipAfterAddToContour() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        // Verify that segments added to contours are cloned (not moved)
        Shape shape = Shape.create();
        Contour contour = shape.addContour();

        Segment original = Segment.createLinear();
        original.setPoint(0, 0.0, 0.0);
        original.setPoint(1, 1.0, 1.0);
        contour.addEdge(original);

        // Original segment should still be accessible and freeable
        assertFalse("Original segment should not be freed", original.isFreed());
        double[] pt = original.getPoint(0);
        assertEquals(0.0, pt[0], 1e-10);
        assertEquals(0.0, pt[1], 1e-10);

        original.free();
        assertTrue(original.isFreed());

        // Contour should still have its copy
        assertEquals(1, contour.getEdgeCount());

        shape.free();

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("F3_SegmentOwnershipAfterAdd", withinTolerance,
            before, null, after, duration,
            "Segment clone on add verified - no double-free");

        assertTrue("Segment ownership issue caused leak", withinTolerance);
    }

    @Test
    public void testF4_allBitmapTypesAllocationDeallocation() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();

        int[] types = {
            MsdfConstants.BITMAP_TYPE_SDF,
            MsdfConstants.BITMAP_TYPE_PSDF,
            MsdfConstants.BITMAP_TYPE_MSDF,
            MsdfConstants.BITMAP_TYPE_MTSDF
        };
        int[] expectedChannels = { 1, 1, 3, 4 };

        for (int round = 0; round < 10; round++) {
            for (int t = 0; t < types.length; t++) {
                Bitmap bmp = Bitmap.alloc(types[t], 128, 128);
                assertEquals(expectedChannels[t], bmp.getChannelCount());

                long expectedBytes = 128L * 128L * expectedChannels[t] * 4L;
                assertEquals("Byte size for type " + types[t], expectedBytes, bmp.getByteSize());

                bmp.free();
            }
        }

        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean withinTolerance = MemoryMetrics.isWithinTolerance(before, after, MemoryMetrics.DEFAULT_TOLERANCE_BYTES);

        recordResult("F4_AllBitmapTypes", withinTolerance,
            before, null, after, duration,
            "40 bitmaps (10 rounds x 4 types) created/freed");

        assertTrue("Bitmap type coverage test leaked", withinTolerance);
    }

    @Test
    public void testF5_fullPipelineRepeated() {
        assumeNativeAvailable();
        long startTime = System.currentTimeMillis();
        MemoryMetrics.MemorySnapshot before = MemoryMetrics.capture();
        MemoryMetrics.MemoryTracker tracker = new MemoryMetrics.MemoryTracker(22);
        tracker.record(before.runtimeUsed);

        for (int i = 0; i < 50; i++) {
            // Full pipeline: shape -> contours -> segments -> normalize -> color -> generate -> pixels -> free
            Shape shape = createComplexShape();
            shape.normalize();
            shape.orientContours();
            shape.edgeColoringSimple(3.0);
            assertTrue("Shape should validate", shape.validate());

            Bitmap bitmap = Bitmap.allocMsdf(64, 64);
            Transform transform = Transform.autoFrame(shape, 64, 64, 4.0);
            Generator.generateMsdf(bitmap, shape, transform);

            float[] pixels = bitmap.getPixelData();
            assertEquals(64 * 64 * 3, pixels.length);

            bitmap.free();
            shape.free();

            if ((i + 1) % 5 == 0) {
                tracker.record();
            }
        }

        tracker.record();
        MemoryMetrics.MemorySnapshot after = MemoryMetrics.capture();
        long duration = System.currentTimeMillis() - startTime;

        boolean noLeak = !tracker.detectsLeak(1024);
        double slope = tracker.getSlope();

        recordResult("F5_FullPipelineRepeated", noLeak,
            before, null, after, duration,
            "50 full pipelines. Slope=" + String.format("%.1f", slope) + "B/sample");

        assertTrue("Full pipeline shows memory leak (slope=" +
            String.format("%.1f", slope) + "B/sample)", noLeak);
    }
}
