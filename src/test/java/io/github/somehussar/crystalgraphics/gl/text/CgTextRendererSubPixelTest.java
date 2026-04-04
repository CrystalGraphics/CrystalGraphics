package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgTextRendererSubPixelTest {

    @Test
    public void subPixelBucketZeroForLargeEffectiveSize() {
        int bucket = CgTextRenderer.selectSubPixelBucket(48, 0.3f);
        assertEquals(0, bucket);
    }

    @Test
    public void subPixelBucketZeroAtThreshold() {
        int bucket = CgTextRenderer.selectSubPixelBucket(CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX, 0.3f);
        assertEquals(0, bucket);
    }

    @Test
    public void subPixelBucket0ForSmallOffset() {
        int bucket = CgTextRenderer.selectSubPixelBucket(16, 0.05f);
        assertEquals(0, bucket);
    }

    @Test
    public void subPixelBucket1ForQuarterOffset() {
        int bucket = CgTextRenderer.selectSubPixelBucket(16, 0.25f);
        assertEquals(1, bucket);
    }

    @Test
    public void subPixelBucket2ForHalfOffset() {
        int bucket = CgTextRenderer.selectSubPixelBucket(16, 0.5f);
        assertEquals(2, bucket);
    }

    @Test
    public void subPixelBucket3ForThreeQuarterOffset() {
        int bucket = CgTextRenderer.selectSubPixelBucket(16, 0.75f);
        assertEquals(3, bucket);
    }

    @Test
    public void subPixelBucket0ForNearOneOffset() {
        int bucket = CgTextRenderer.selectSubPixelBucket(16, 0.9f);
        assertEquals(0, bucket);
    }

    @Test
    public void subPixelBucketUsesEffectiveNotBase() {
        // Effective size 48 >= threshold → always bucket 0 regardless of offset
        assertEquals(0, CgTextRenderer.selectSubPixelBucket(48, 0.25f));
        assertEquals(0, CgTextRenderer.selectSubPixelBucket(48, 0.5f));
        assertEquals(0, CgTextRenderer.selectSubPixelBucket(48, 0.75f));

        // Effective size 16 < threshold → uses fractional buckets
        assertEquals(1, CgTextRenderer.selectSubPixelBucket(16, 0.25f));
        assertEquals(2, CgTextRenderer.selectSubPixelBucket(16, 0.5f));
        assertEquals(3, CgTextRenderer.selectSubPixelBucket(16, 0.75f));
    }
}
