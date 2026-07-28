package com.crystalgraphics.text.atlas;

import com.crystalgraphics.text.atlas.packing.MaxRectsPacker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MaxRectsPacker}.
 *
 * <p>Verifies the MaxRects BSSF bin packing algorithm: no-overlap packing,
 * utilization targets, full-bin rejection, and space reuse after removal.</p>
 */
public class MaxRectsPackerTest {

    // ---------------------------------------------------------------
    //  Test 1: Pack 100 random rects — no overlaps
    // ---------------------------------------------------------------

    /**
     * Packs 100 rectangles of random sizes (8–64px) into a 1024×1024 bin
     * and asserts that no two packed rectangles overlap.
     */
    @Test
    public void testPack100Rects_noOverlaps() {
        MaxRectsPacker packer = new MaxRectsPacker(1024, 1024);
        Random rng = new Random(42); // deterministic seed
        List<MaxRectsPacker.PackedRect> packed = new ArrayList<MaxRectsPacker.PackedRect>();

        for (int i = 0; i < 100; i++) {
            int w = 8 + rng.nextInt(57); // 8..64 inclusive
            int h = 8 + rng.nextInt(57);
            MaxRectsPacker.PackedRect rect = packer.insert(w, h, "glyph_" + i);
            if (rect != null) {
                packed.add(rect);
            }
        }

        assertTrue("Should pack at least some rects", packed.size() > 0);

        // Check all pairs for overlap
        for (int i = 0; i < packed.size(); i++) {
            MaxRectsPacker.PackedRect a = packed.get(i);
            // Verify within bin bounds
            assertTrue("Rect " + i + " x >= 0", a.x() >= 0);
            assertTrue("Rect " + i + " y >= 0", a.y() >= 0);
            assertTrue("Rect " + i + " right <= binWidth",
                    a.x() + a.width() <= 1024);
            assertTrue("Rect " + i + " bottom <= binHeight",
                    a.y() + a.height() <= 1024);

            for (int j = i + 1; j < packed.size(); j++) {
                MaxRectsPacker.PackedRect b = packed.get(j);
                assertFalse("Rects " + i + " and " + j + " must not overlap",
                        rectsOverlap(a, b));
            }
        }
    }

    // ---------------------------------------------------------------
    //  Test 2: Utilization >= 60% after 100 random rects
    // ---------------------------------------------------------------

    /**
     * Packs rectangles of random sizes (8–64px) until the bin is sufficiently
     * full, then asserts utilization is at least 60%.
     *
     * <p>Uses a 256×256 bin so that 100 random rects (avg ~36×36 each) will
     * densely pack the bin and exercise the BSSF heuristic under pressure.</p>
     */
    @Test
    public void testUtilization_atLeast60Percent() {
        MaxRectsPacker packer = new MaxRectsPacker(256, 256);
        Random rng = new Random(42);

        int insertedCount = 0;
        for (int i = 0; i < 100; i++) {
            int w = 8 + rng.nextInt(57); // 8..64 inclusive
            int h = 8 + rng.nextInt(57);
            MaxRectsPacker.PackedRect rect = packer.insert(w, h, "glyph_" + i);
            if (rect != null) {
                insertedCount++;
            }
        }

        assertTrue("Should pack a significant number of rects", insertedCount > 0);
        float util = packer.utilization();
        assertTrue("Utilization should be >= 0.60, was " + util + " with " + insertedCount + " rects",
                util >= 0.60f);
    }

    // ---------------------------------------------------------------
    //  Test 3: Full bin rejection — insert returns null when full
    // ---------------------------------------------------------------

    /**
     * Fills a small bin completely with 1×1 rects, then asserts that one
     * more insert returns null.
     */
    @Test
    public void testFullBin_returnsNull() {
        // Use a 4x4 bin, fill with 16 individual 1x1 rects
        MaxRectsPacker packer = new MaxRectsPacker(4, 4);

        for (int i = 0; i < 16; i++) {
            MaxRectsPacker.PackedRect r = packer.insert(1, 1, "cell_" + i);
            assertNotNull("Should pack rect " + i + " into 4x4 bin", r);
        }

        // Bin is now full (16 x 1x1 = 4x4)
        MaxRectsPacker.PackedRect overflow = packer.insert(1, 1, "overflow");
        assertNull("insert() should return null when bin is full", overflow);
    }

    // ---------------------------------------------------------------
    //  Additional: dimension and position correctness
    // ---------------------------------------------------------------

    /**
     * Verifies that packed rect dimensions match the requested dimensions.
     */
    @Test
    public void testPackedRect_dimensionsCorrect() {
        MaxRectsPacker packer = new MaxRectsPacker(256, 256);
        MaxRectsPacker.PackedRect r = packer.insert(16, 24, "test");

        assertNotNull("Should pack successfully", r);
        assertEquals("Width should match", 16, r.width());
        assertEquals("Height should match", 24, r.height());
        assertEquals("Id should match", "test", r.id());
    }

    /**
     * Verifies utilization returns 0.0 for an empty bin.
     */
    @Test
    public void testUtilization_emptyBin() {
        MaxRectsPacker packer = new MaxRectsPacker(128, 128);
        assertEquals("Empty bin utilization", 0.0f, packer.utilization(), 0.0001f);
    }

    /**
     * Verifies utilization returns 1.0 for a completely filled bin.
     */
    @Test
    public void testUtilization_fullBin() {
        MaxRectsPacker packer = new MaxRectsPacker(32, 32);
        MaxRectsPacker.PackedRect r = packer.insert(32, 32, "full");
        assertNotNull("Should fit exactly", r);
        assertEquals("Full bin utilization", 1.0f, packer.utilization(), 0.0001f);
    }

    /**
     * Verifies that inserting zero or negative dimensions throws.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInsert_zeroDimension_throws() {
        MaxRectsPacker packer = new MaxRectsPacker(64, 64);
        packer.insert(0, 10, "bad");
    }

    /**
     * Verifies that negative bin dimensions throw.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_negativeDimension_throws() {
        new MaxRectsPacker(-1, 64);
    }

    /**
     * Verifies that a rect larger than the bin returns null.
     */
    @Test
    public void testInsert_largerThanBin_returnsNull() {
        MaxRectsPacker packer = new MaxRectsPacker(32, 32);
        MaxRectsPacker.PackedRect r = packer.insert(33, 16, "tooBig");
        assertNull("Rect wider than bin should return null", r);
    }

    // ---------------------------------------------------------------
    //  Stress test: deterministic packing with many small rects
    // ---------------------------------------------------------------

    /**
     * Packs 500 small rects (4–16px) into 512×512, checks no overlaps.
     */
    @Test
    public void testStress_500SmallRects_noOverlaps() {
        MaxRectsPacker packer = new MaxRectsPacker(512, 512);
        Random rng = new Random(1337);
        List<MaxRectsPacker.PackedRect> packed = new ArrayList<MaxRectsPacker.PackedRect>();

        for (int i = 0; i < 500; i++) {
            int w = 4 + rng.nextInt(13); // 4..16
            int h = 4 + rng.nextInt(13);
            MaxRectsPacker.PackedRect r = packer.insert(w, h, i);
            if (r != null) {
                packed.add(r);
            }
        }

        assertTrue("Should pack many rects", packed.size() >= 400);

        for (int i = 0; i < packed.size(); i++) {
            MaxRectsPacker.PackedRect a = packed.get(i);
            for (int j = i + 1; j < packed.size(); j++) {
                MaxRectsPacker.PackedRect b = packed.get(j);
                assertFalse("Overlap at " + i + "," + j, rectsOverlap(a, b));
            }
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    /**
     * Returns true if two PackedRects overlap (share any interior area).
     */
    private static boolean rectsOverlap(MaxRectsPacker.PackedRect a, MaxRectsPacker.PackedRect b) {
        int ax2 = a.x() + a.width();
        int ay2 = a.y() + a.height();
        int bx2 = b.x() + b.width();
        int by2 = b.y() + b.height();

        return a.x() < bx2 && ax2 > b.x()
                && a.y() < by2 && ay2 > b.y();
    }

    // ---------------------------------------------------------------
    //  Query surface: mayFit / countFreeRegionsFitting / describeFreeRegions
    //
    //  These had no coverage at all, and mayFit is load-bearing: CgGlyphAtlas uses it to
    //  reject hopeless pages cheaply while scanning oldest-first, so a mayFit that returns
    //  false for something placeable would silently strand atlas space forever with no
    //  visible symptom beyond a slowly growing page count.
    // ---------------------------------------------------------------

    /** mayFit must never reject something insert() would actually place. */
    @Test
    public void testMayFit_neverRejectsAPlaceableRect() {
        MaxRectsPacker packer = new MaxRectsPacker(256, 256);
        Random random = new Random(20260728L);

        for (int i = 0; i < 400; i++) {
            int w = 4 + random.nextInt(40);
            int h = 4 + random.nextInt(40);

            boolean mayFit = packer.mayFit(w, h);
            MaxRectsPacker.PackedRect placed = packer.insert(w, h, "r" + i);

            if (placed != null) {
                assertTrue("mayFit(" + w + "x" + h + ") returned false but insert() succeeded — "
                        + "a false negative strands space permanently", mayFit);
            }
        }
    }

    /** An empty bin may fit anything up to its own size, and nothing beyond it. */
    @Test
    public void testMayFit_boundsOfEmptyBin() {
        MaxRectsPacker packer = new MaxRectsPacker(64, 32);
        assertTrue(packer.mayFit(64, 32));
        assertTrue(packer.mayFit(1, 1));
        assertFalse("wider than bin", packer.mayFit(65, 32));
        assertFalse("taller than bin", packer.mayFit(64, 33));
    }

    /** A full bin must report that nothing more may fit. */
    @Test
    public void testMayFit_falseOnceBinIsFull() {
        MaxRectsPacker packer = new MaxRectsPacker(16, 16);
        assertNotNull(packer.insert(16, 16, "fill"));
        assertFalse("nothing can fit a fully consumed bin", packer.mayFit(1, 1));
    }

    /** countFreeRegionsFitting is exact, unlike mayFit — zero means genuinely unplaceable. */
    @Test
    public void testCountFreeRegionsFitting_agreesWithInsert() {
        MaxRectsPacker packer = new MaxRectsPacker(64, 64);
        assertNotNull(packer.insert(40, 40, "a"));

        assertEquals("a full-bin-sized rect cannot fit any remaining region",
                0, packer.countFreeRegionsFitting(64, 64));
        assertTrue("some region must still accept a small rect",
                packer.countFreeRegionsFitting(8, 8) > 0);
        assertNotNull("and insert must agree with that", packer.insert(8, 8, "b"));
    }

    /** describeFreeRegions reports real, in-bounds regions ordered largest-area first. */
    @Test
    public void testDescribeFreeRegions_shapeAndOrdering() {
        MaxRectsPacker packer = new MaxRectsPacker(128, 128);
        assertNotNull(packer.insert(50, 20, "a"));
        assertNotNull(packer.insert(30, 60, "b"));

        int[][] regions = packer.describeFreeRegions();
        assertTrue("a partially filled bin must report free regions", regions.length > 0);

        long previousArea = Long.MAX_VALUE;
        for (int[] r : regions) {
            assertEquals("each region is {x, y, w, h}", 4, r.length);
            assertTrue("width positive", r[2] > 0);
            assertTrue("height positive", r[3] > 0);
            assertTrue("region stays inside the bin", r[0] >= 0 && r[1] >= 0);
            assertTrue("region does not overrun the bin", r[0] + r[2] <= 128 && r[1] + r[3] <= 128);

            long area = (long) r[2] * r[3];
            assertTrue("regions must be ordered largest-area first", area <= previousArea);
            previousArea = area;
        }
    }

    // ---------------------------------------------------------------
    //  Spacing-aware insert
    // ---------------------------------------------------------------

    /**
     * Spacing is reserved by the allocator but must NOT appear in the returned rect: UVs and
     * plane bounds are derived from it and have to describe the visible glyph box, not the
     * padded footprint.
     */
    @Test
    public void testInsertWithSpacing_reportsVisibleSizeNotPaddedSize() {
        MaxRectsPacker packer = new MaxRectsPacker(128, 128);
        MaxRectsPacker.PackedRect r = packer.insert(20, 10, 4, "spaced");

        assertNotNull(r);
        assertEquals("width must be the requested width, not width+spacing", 20, r.width());
        assertEquals("height must be the requested height, not height+spacing", 10, r.height());
    }

    /** Spacing genuinely consumes bin area, so it must reduce how much still fits. */
    @Test
    public void testInsertWithSpacing_reservesTheExtraArea() {
        MaxRectsPacker tight = new MaxRectsPacker(32, 32);
        assertNotNull("32x32 fits exactly with no spacing", tight.insert(32, 32, 0, "exact"));

        MaxRectsPacker spaced = new MaxRectsPacker(32, 32);
        assertNull("32x32 plus spacing cannot fit a 32x32 bin",
                spaced.insert(32, 32, 1, "tooBigWithSpacing"));
    }

    /** Negative spacing is a caller error, not something to silently absorb. */
    @Test
    public void testInsert_negativeSpacing_throws() {
        MaxRectsPacker packer = new MaxRectsPacker(64, 64);
        try {
            packer.insert(8, 8, -1, "bad");
            fail("expected IllegalArgumentException for negative spacing");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
