package io.github.somehussar.crystalgraphics.text.atlas;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
        List<PackedRect> packed = new ArrayList<PackedRect>();

        for (int i = 0; i < 100; i++) {
            int w = 8 + rng.nextInt(57); // 8..64 inclusive
            int h = 8 + rng.nextInt(57);
            PackedRect rect = packer.insert(w, h, "glyph_" + i);
            if (rect != null) {
                packed.add(rect);
            }
        }

        assertTrue("Should pack at least some rects", packed.size() > 0);

        // Check all pairs for overlap
        for (int i = 0; i < packed.size(); i++) {
            PackedRect a = packed.get(i);
            // Verify within bin bounds
            assertTrue("Rect " + i + " x >= 0", a.getX() >= 0);
            assertTrue("Rect " + i + " y >= 0", a.getY() >= 0);
            assertTrue("Rect " + i + " right <= binWidth",
                    a.getX() + a.getWidth() <= 1024);
            assertTrue("Rect " + i + " bottom <= binHeight",
                    a.getY() + a.getHeight() <= 1024);

            for (int j = i + 1; j < packed.size(); j++) {
                PackedRect b = packed.get(j);
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
            PackedRect rect = packer.insert(w, h, "glyph_" + i);
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
            PackedRect r = packer.insert(1, 1, "cell_" + i);
            assertNotNull("Should pack rect " + i + " into 4x4 bin", r);
        }

        // Bin is now full (16 x 1x1 = 4x4)
        PackedRect overflow = packer.insert(1, 1, "overflow");
        assertNull("insert() should return null when bin is full", overflow);
    }

    // ---------------------------------------------------------------
    //  Test 4: Reuse after remove
    // ---------------------------------------------------------------

    /**
     * Inserts a 32×32 rect, removes it, then inserts another 32×32 rect.
     * The second insert must succeed (non-null).
     */
    @Test
    public void testReuseAfterRemove() {
        MaxRectsPacker packer = new MaxRectsPacker(64, 64);

        // Fill up most of the space to make the test meaningful
        PackedRect big = packer.insert(64, 32, "big");
        assertNotNull("Big rect should fit", big);

        PackedRect target = packer.insert(32, 32, "target");
        assertNotNull("Target rect should fit", target);

        PackedRect filler = packer.insert(32, 32, "filler");
        assertNotNull("Filler rect should fit (remaining 32x32)", filler);

        // Bin should now be full
        PackedRect shouldFail = packer.insert(32, 32, "shouldFail");
        assertNull("Bin should be full", shouldFail);

        // Remove the target rect
        packer.remove(target);

        // Insert same-sized rect — should succeed using freed space
        PackedRect reused = packer.insert(32, 32, "reused");
        assertNotNull("Second insert should succeed after remove()", reused);
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
        PackedRect r = packer.insert(16, 24, "test");

        assertNotNull("Should pack successfully", r);
        assertEquals("Width should match", 16, r.getWidth());
        assertEquals("Height should match", 24, r.getHeight());
        assertEquals("Id should match", "test", r.getId());
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
        PackedRect r = packer.insert(32, 32, "full");
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
        PackedRect r = packer.insert(33, 16, "tooBig");
        assertNull("Rect wider than bin should return null", r);
    }

    /**
     * Verifies multiple removes followed by re-inserts work correctly.
     */
    @Test
    public void testMultipleRemoveAndReinsert() {
        MaxRectsPacker packer = new MaxRectsPacker(64, 64);

        // Pack a grid of 16x16 rects (up to 16 can fit)
        List<PackedRect> rects = new ArrayList<PackedRect>();
        for (int i = 0; i < 16; i++) {
            PackedRect r = packer.insert(16, 16, "r" + i);
            if (r != null) {
                rects.add(r);
            }
        }
        assertEquals("Should pack 16 rects into 64x64", 16, rects.size());

        // Remove first 4
        for (int i = 0; i < 4; i++) {
            packer.remove(rects.get(i));
        }

        // Should be able to pack 4 more 16x16 rects
        int reinserted = 0;
        for (int i = 0; i < 4; i++) {
            PackedRect r = packer.insert(16, 16, "new_" + i);
            if (r != null) {
                reinserted++;
            }
        }
        assertEquals("Should reinsert 4 rects into freed space", 4, reinserted);
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
        List<PackedRect> packed = new ArrayList<PackedRect>();

        for (int i = 0; i < 500; i++) {
            int w = 4 + rng.nextInt(13); // 4..16
            int h = 4 + rng.nextInt(13);
            PackedRect r = packer.insert(w, h, i);
            if (r != null) {
                packed.add(r);
            }
        }

        assertTrue("Should pack many rects", packed.size() >= 400);

        for (int i = 0; i < packed.size(); i++) {
            PackedRect a = packed.get(i);
            for (int j = i + 1; j < packed.size(); j++) {
                PackedRect b = packed.get(j);
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
    private static boolean rectsOverlap(PackedRect a, PackedRect b) {
        int ax2 = a.getX() + a.getWidth();
        int ay2 = a.getY() + a.getHeight();
        int bx2 = b.getX() + b.getWidth();
        int by2 = b.getY() + b.getHeight();

        return a.getX() < bx2 && ax2 > b.getX()
                && a.getY() < by2 && ay2 > b.getY();
    }
}
