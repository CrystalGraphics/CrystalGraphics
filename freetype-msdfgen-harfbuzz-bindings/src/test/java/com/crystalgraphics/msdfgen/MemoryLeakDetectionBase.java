package com.crystalgraphics.msdfgen;

import org.junit.Before;
import org.junit.After;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.NativeLoader;
/**
 * Base class for memory leak detection tests. Provides common setup/teardown,
 * baseline measurement, and result recording. Subclasses implement specific
 * test scenarios.
 */
public abstract class MemoryLeakDetectionBase {

    protected static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded = false;
        try {
            NativeLoader.ensureLoaded();
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native library not available - memory tests will be skipped: " + e.getMessage());
        }
        NATIVE_AVAILABLE = loaded;
    }

    protected MemoryMetrics.MemorySnapshot baseline;
    protected final List<PlatformTools.TestResult> results = new ArrayList<PlatformTools.TestResult>();

    @Before
    public void setUp() {
        MemoryMetrics.forceGarbageCollection();
        baseline = MemoryMetrics.capture(true);
    }

    @After
    public void tearDown() {
        MemoryMetrics.forceGarbageCollection();
    }

    protected static void assumeNativeAvailable() {
        org.junit.Assert.assertTrue("Native library not available", NATIVE_AVAILABLE);
    }

    protected MSDFShape createSimpleSquare() {
        MSDFShape shape = MSDFShape.create();
        MSDFContour contour = shape.addContour();

        MSDFSegment top = MSDFSegment.createLinear();
        top.setPoint(0, 0.0, 1.0);
        top.setPoint(1, 1.0, 1.0);
        contour.addEdge(top);
        top.free();

        MSDFSegment right = MSDFSegment.createLinear();
        right.setPoint(0, 1.0, 1.0);
        right.setPoint(1, 1.0, 0.0);
        contour.addEdge(right);
        right.free();

        MSDFSegment bottom = MSDFSegment.createLinear();
        bottom.setPoint(0, 1.0, 0.0);
        bottom.setPoint(1, 0.0, 0.0);
        contour.addEdge(bottom);
        bottom.free();

        MSDFSegment left = MSDFSegment.createLinear();
        left.setPoint(0, 0.0, 0.0);
        left.setPoint(1, 0.0, 1.0);
        contour.addEdge(left);
        left.free();

        return shape;
    }

    /**
     * Creates a complex shape with multiple contours and segment types.
     * Includes linear, quadratic, and cubic segments for thorough testing.
     */
    protected MSDFShape createComplexShape() {
        MSDFShape shape = MSDFShape.create();

        // Outer contour with mixed segment types
        MSDFContour outer = shape.addContour();

        MSDFSegment s1 = MSDFSegment.createLinear();
        s1.setPoint(0, 0.0, 0.0);
        s1.setPoint(1, 10.0, 0.0);
        outer.addEdge(s1);
        s1.free();

        MSDFSegment s2 = MSDFSegment.createQuadratic();
        s2.setPoint(0, 10.0, 0.0);
        s2.setPoint(1, 12.0, 5.0);
        s2.setPoint(2, 10.0, 10.0);
        outer.addEdge(s2);
        s2.free();

        MSDFSegment s3 = MSDFSegment.createCubic();
        s3.setPoint(0, 10.0, 10.0);
        s3.setPoint(1, 7.0, 12.0);
        s3.setPoint(2, 3.0, 12.0);
        s3.setPoint(3, 0.0, 10.0);
        outer.addEdge(s3);
        s3.free();

        MSDFSegment s4 = MSDFSegment.createLinear();
        s4.setPoint(0, 0.0, 10.0);
        s4.setPoint(1, 0.0, 0.0);
        outer.addEdge(s4);
        s4.free();

        // Inner contour (hole)
        MSDFContour inner = shape.addContour();

        MSDFSegment h1 = MSDFSegment.createLinear();
        h1.setPoint(0, 3.0, 3.0);
        h1.setPoint(1, 7.0, 3.0);
        inner.addEdge(h1);
        h1.free();

        MSDFSegment h2 = MSDFSegment.createLinear();
        h2.setPoint(0, 7.0, 3.0);
        h2.setPoint(1, 7.0, 7.0);
        inner.addEdge(h2);
        h2.free();

        MSDFSegment h3 = MSDFSegment.createLinear();
        h3.setPoint(0, 7.0, 7.0);
        h3.setPoint(1, 3.0, 7.0);
        inner.addEdge(h3);
        h3.free();

        MSDFSegment h4 = MSDFSegment.createLinear();
        h4.setPoint(0, 3.0, 7.0);
        h4.setPoint(1, 3.0, 3.0);
        inner.addEdge(h4);
        h4.free();

        return shape;
    }

    protected PlatformTools.TestResult recordResult(String testName, boolean passed,
                                                     MemoryMetrics.MemorySnapshot before,
                                                     MemoryMetrics.MemorySnapshot peak,
                                                     MemoryMetrics.MemorySnapshot after,
                                                     long durationMs,
                                                     String notes) {
        long baselineKb = before.runtimeUsed / 1024;
        long peakKb = (peak != null ? peak.runtimeUsed : before.runtimeUsed) / 1024;
        long finalKb = after.runtimeUsed / 1024;
        long leakKb = (after.runtimeUsed - before.runtimeUsed) / 1024;
        PlatformTools.TestResult result = new PlatformTools.TestResult(
            testName, passed, baselineKb, peakKb, finalKb, leakKb, durationMs, notes
        );
        results.add(result);
        return result;
    }
}
