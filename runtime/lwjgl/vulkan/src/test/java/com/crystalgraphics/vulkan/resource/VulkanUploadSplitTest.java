package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** R4: an upload past a staging chunk is cut into pieces under it that cover it exactly, in source order. */
public class VulkanUploadSplitTest {

    /** Walks every piece, checking each fits and follows the last; returns how many. */
    private static int cover(VulkanUploadSplit split, CgFormat format, CgTextureRegion region, long limit) {
        split.begin(format, region);
        long expected = 0;
        int pieces = 0;
        while (split.next()) {
            CgTextureRegion piece = split.region(region.mip());
            assertTrue("piece " + pieces + " fits a chunk", split.bytes() <= limit);
            assertEquals("pieces follow each other in the source", expected, split.sourceOffset());
            assertEquals(format.bytes(piece.width(), piece.height(), piece.depth()), split.bytes());
            assertEquals(region.x(), piece.x());
            assertEquals(region.width(), piece.width());
            expected += split.bytes();
            pieces++;
        }
        assertEquals("the pieces cover the upload", format.bytes(region.width(), region.height(), region.depth()), expected);
        return pieces;
    }

    @Test
    public void slicesGoWholeWhileTheyFit() {
        long limit = 64 * 64 * 4 * 3;   // three 64x64 RGBA8 slices
        VulkanUploadSplit split = new VulkanUploadSplit(limit);
        assertEquals(3, cover(split, CgFormat.RGBA8_UNORM, new CgTextureRegion(0, 0, 0, 2, 64, 64, 8), limit));
    }

    @Test
    public void aSliceTooLargeIsCutByRows() {
        long limit = 1000 * 4 * 300;   // 300 rows of 1000 texels
        VulkanUploadSplit split = new VulkanUploadSplit(limit);
        assertEquals(4, cover(split, CgFormat.RGBA8_UNORM, new CgTextureRegion(1, 5, 7, 0, 1000, 1000, 1), limit));
        split.begin(CgFormat.RGBA8_UNORM, new CgTextureRegion(1, 5, 7, 0, 1000, 1000, 1));
        split.next();
        split.next();
        assertEquals("the second piece starts 300 rows down", 7 + 300, split.region(1).y());
    }

    @Test
    public void blockFormatsAreCutOnBlockRows() {
        long limit = 1024 * 1024;   // a 4096-wide BC7 block row is 16 KB: 64 block rows a piece
        VulkanUploadSplit split = new VulkanUploadSplit(limit);
        int pieces = cover(split, CgFormat.BC7_RGBA_UNORM, new CgTextureRegion(0, 0, 0, 0, 4096, 4096, 1), limit);
        assertEquals(16, pieces);
        split.begin(CgFormat.BC7_RGBA_UNORM, new CgTextureRegion(0, 0, 0, 0, 4096, 4096, 1));
        while (split.next()) assertEquals(0, split.region(0).y() % 4);
    }

    @Test
    public void anUploadUnderTheLimitIsOnePiece() {
        VulkanUploadSplit split = new VulkanUploadSplit(VulkanStaging.CHUNK);
        assertFalse(split.needed(VulkanStaging.CHUNK));
        assertTrue(split.needed(VulkanStaging.CHUNK + 1));
        assertEquals(1, cover(split, CgFormat.RGBA8_UNORM, new CgTextureRegion(0, 0, 0, 0, 512, 512, 1), VulkanStaging.CHUNK));
    }

    @Test
    public void aBlockRowPastTheLimitThrows() {
        VulkanUploadSplit split = new VulkanUploadSplit(1024);
        assertThrows(IllegalArgumentException.class,
                () -> split.begin(CgFormat.RGBA32_FLOAT, new CgTextureRegion(0, 0, 0, 0, 4096, 4, 1)));
    }
}
