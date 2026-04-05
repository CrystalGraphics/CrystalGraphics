package io.github.somehussar.crystalgraphics.text.atlas;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgGuillotinePacker}.
 *
 * <p>Validates the upstream-parity guillotine bin packing algorithm:
 * no-overlap placement, utilization targets, fit scoring, split strategy,
 * full-bin rejection, and packing density compared to MaxRects.</p>
 */
public class CgGuillotinePackerTest {

    @Test
    public void testSingleInsert_positionAndDimensions() {
        CgGuillotinePacker packer = new CgGuillotinePacker(256, 256);
        PackedRect r = packer.insert(16, 24, "test");

        assertNotNull(r);
        assertEquals(16, r.getWidth());
        assertEquals(24, r.getHeight());
        assertEquals("test", r.getId());
        assertTrue(r.getX() >= 0);
        assertTrue(r.getY() >= 0);
        assertTrue(r.getX() + r.getWidth() <= 256);
        assertTrue(r.getY() + r.getHeight() <= 256);
    }

    @Test
    public void testPack100Rects_noOverlaps() {
        CgGuillotinePacker packer = new CgGuillotinePacker(1024, 1024);
        Random rng = new Random(42);
        List<PackedRect> packed = new ArrayList<PackedRect>();

        for (int i = 0; i < 100; i++) {
            int w = 8 + rng.nextInt(57);
            int h = 8 + rng.nextInt(57);
            PackedRect rect = packer.insert(w, h, "g_" + i);
            if (rect != null) {
                packed.add(rect);
            }
        }

        assertTrue("Should pack at least some rects", packed.size() > 0);

        for (int i = 0; i < packed.size(); i++) {
            PackedRect a = packed.get(i);
            assertTrue(a.getX() >= 0);
            assertTrue(a.getY() >= 0);
            assertTrue(a.getX() + a.getWidth() <= 1024);
            assertTrue(a.getY() + a.getHeight() <= 1024);

            for (int j = i + 1; j < packed.size(); j++) {
                PackedRect b = packed.get(j);
                assertFalse("Rects " + i + " and " + j + " overlap",
                        rectsOverlap(a, b));
            }
        }
    }

    @Test
    public void testUtilization_atLeast55Percent() {
        CgGuillotinePacker packer = new CgGuillotinePacker(256, 256);
        Random rng = new Random(42);

        for (int i = 0; i < 100; i++) {
            int w = 8 + rng.nextInt(57);
            int h = 8 + rng.nextInt(57);
            packer.insert(w, h, "g_" + i);
        }

        float util = packer.utilization();
        assertTrue("Utilization should be >= 0.55, was " + util, util >= 0.55f);
    }

    @Test
    public void testFullBin_returnsNull() {
        CgGuillotinePacker packer = new CgGuillotinePacker(4, 4);

        // A 4x4 bin can hold one 4x4 rect exactly
        PackedRect r = packer.insert(4, 4, "full");
        assertNotNull(r);
        assertEquals(1.0f, packer.utilization(), 0.001f);

        PackedRect overflow = packer.insert(1, 1, "overflow");
        assertNull("insert() should return null when bin is full", overflow);
    }

    @Test
    public void testLargerThanBin_returnsNull() {
        CgGuillotinePacker packer = new CgGuillotinePacker(32, 32);
        PackedRect r = packer.insert(33, 16, "tooBig");
        assertNull(r);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInsert_zeroDimension_throws() {
        CgGuillotinePacker packer = new CgGuillotinePacker(64, 64);
        packer.insert(0, 10, "bad");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_negativeDimension_throws() {
        new CgGuillotinePacker(-1, 64);
    }

    @Test
    public void testEmptyBin_utilizationZero() {
        CgGuillotinePacker packer = new CgGuillotinePacker(128, 128);
        assertEquals(0.0f, packer.utilization(), 0.0001f);
        assertEquals(0, packer.getPackedCount());
    }

    @Test
    public void testIrregularBoxSizes_tightPacking() {
        CgGuillotinePacker packer = new CgGuillotinePacker(512, 512);

        // Simulate MSDF-like glyph boxes: varying widths and heights
        int[][] boxes = {
            {23, 45}, {12, 38}, {35, 42}, {18, 30}, {45, 50},
            {8, 15}, {60, 55}, {25, 28}, {15, 40}, {40, 20},
            {10, 10}, {55, 60}, {30, 35}, {22, 18}, {48, 52},
            {14, 44}, {36, 26}, {28, 48}, {42, 32}, {20, 22}
        };

        int packedCount = 0;
        List<PackedRect> packed = new ArrayList<PackedRect>();
        for (int i = 0; i < boxes.length; i++) {
            PackedRect r = packer.insert(boxes[i][0], boxes[i][1], "glyph_" + i);
            if (r != null) {
                packed.add(r);
                packedCount++;
            }
        }

        assertEquals("All irregular boxes should fit in 512x512", boxes.length, packedCount);

        // Verify no overlaps
        for (int i = 0; i < packed.size(); i++) {
            for (int j = i + 1; j < packed.size(); j++) {
                assertFalse("Overlap at " + i + "," + j, rectsOverlap(packed.get(i), packed.get(j)));
            }
        }
    }

    @Test
    public void testStress_500SmallRects_noOverlaps() {
        CgGuillotinePacker packer = new CgGuillotinePacker(512, 512);
        Random rng = new Random(1337);
        List<PackedRect> packed = new ArrayList<PackedRect>();

        for (int i = 0; i < 500; i++) {
            int w = 4 + rng.nextInt(13);
            int h = 4 + rng.nextInt(13);
            PackedRect r = packer.insert(w, h, i);
            if (r != null) {
                packed.add(r);
            }
        }

        assertTrue("Should pack many rects", packed.size() >= 300);

        for (int i = 0; i < packed.size(); i++) {
            PackedRect a = packed.get(i);
            for (int j = i + 1; j < packed.size(); j++) {
                PackedRect b = packed.get(j);
                assertFalse("Overlap at " + i + "," + j, rectsOverlap(a, b));
            }
        }
    }

    @Test
    public void testPerfectFit_shortCircuits() {
        CgGuillotinePacker packer = new CgGuillotinePacker(64, 64);

        // Pack a rect that leaves a 32x64 remainder
        PackedRect r1 = packer.insert(32, 64, "left");
        assertNotNull(r1);

        // The remaining space should be 32x64 — a perfect fit
        PackedRect r2 = packer.insert(32, 64, "right");
        assertNotNull(r2);

        assertEquals(1.0f, packer.utilization(), 0.001f);
    }

    @Test
    public void testPackedCount_incrementsCorrectly() {
        CgGuillotinePacker packer = new CgGuillotinePacker(128, 128);

        assertEquals(0, packer.getPackedCount());
        packer.insert(10, 10, "a");
        assertEquals(1, packer.getPackedCount());
        packer.insert(10, 10, "b");
        assertEquals(2, packer.getPackedCount());
    }

    @Test
    public void testBinDimensions() {
        CgGuillotinePacker packer = new CgGuillotinePacker(200, 300);
        assertEquals(200, packer.getBinWidth());
        assertEquals(300, packer.getBinHeight());
    }

    @Test
    public void testImplementsPackingStrategy() {
        CgGuillotinePacker packer = new CgGuillotinePacker(64, 64);
        assertTrue(packer instanceof CgPackingStrategy);
    }

    @Test
    public void testSpacingReservesAllocatorGap() {
        CgGuillotinePacker noSpacing = new CgGuillotinePacker(64, 64);
        CgGuillotinePacker withSpacing = new CgGuillotinePacker(64, 64);

        noSpacing.insert(10, 10, "a");
        noSpacing.insert(10, 10, "b");

        withSpacing.insert(10, 10, 2, "a");
        withSpacing.insert(10, 10, 2, "b");

        assertTrue("Spacing should consume additional allocator area",
                withSpacing.utilization() > noSpacing.utilization());
    }

    private static boolean rectsOverlap(PackedRect a, PackedRect b) {
        int ax2 = a.getX() + a.getWidth();
        int ay2 = a.getY() + a.getHeight();
        int bx2 = b.getX() + b.getWidth();
        int by2 = b.getY() + b.getHeight();
        return a.getX() < bx2 && ax2 > b.getX()
                && a.getY() < by2 && ay2 > b.getY();
    }
}
