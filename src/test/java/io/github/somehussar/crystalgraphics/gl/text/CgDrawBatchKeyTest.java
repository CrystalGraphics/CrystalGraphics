package io.github.somehussar.crystalgraphics.gl.text;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link CgDrawBatchKey} — batch key for multi-page draw grouping.
 */
public class CgDrawBatchKeyTest {

    @Test
    public void equalityForIdenticalKeys() {
        CgDrawBatchKey a = new CgDrawBatchKey(false, 42, 0.0f);
        CgDrawBatchKey b = new CgDrawBatchKey(false, 42, 0.0f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void inequalityForDifferentMode() {
        CgDrawBatchKey bitmap = new CgDrawBatchKey(false, 42, 0.0f);
        CgDrawBatchKey msdf = new CgDrawBatchKey(true, 42, 4.0f);
        assertNotEquals(bitmap, msdf);
    }

    @Test
    public void inequalityForDifferentTextureId() {
        CgDrawBatchKey a = new CgDrawBatchKey(false, 1, 0.0f);
        CgDrawBatchKey b = new CgDrawBatchKey(false, 2, 0.0f);
        assertNotEquals(a, b);
    }

    @Test
    public void inequalityForDifferentPxRange() {
        CgDrawBatchKey a = new CgDrawBatchKey(true, 1, 4.0f);
        CgDrawBatchKey b = new CgDrawBatchKey(true, 1, 8.0f);
        assertNotEquals(a, b);
    }

    // ── Comparable ordering ───────────────────────────────────────────

    @Test
    public void bitmapSortsBeforeMsdf() {
        CgDrawBatchKey bitmap = new CgDrawBatchKey(false, 1, 0.0f);
        CgDrawBatchKey msdf = new CgDrawBatchKey(true, 1, 4.0f);
        assertTrue(bitmap.compareTo(msdf) < 0);
        assertTrue(msdf.compareTo(bitmap) > 0);
    }

    @Test
    public void withinSameModeSortsByTextureId() {
        CgDrawBatchKey a = new CgDrawBatchKey(true, 1, 4.0f);
        CgDrawBatchKey b = new CgDrawBatchKey(true, 5, 4.0f);
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
    }

    @Test
    public void withinSameTextureSortsByPxRange() {
        CgDrawBatchKey a = new CgDrawBatchKey(true, 1, 2.0f);
        CgDrawBatchKey b = new CgDrawBatchKey(true, 1, 8.0f);
        assertTrue(a.compareTo(b) < 0);
    }

    @Test
    public void equalKeysCompareToZero() {
        CgDrawBatchKey a = new CgDrawBatchKey(false, 42, 0.0f);
        CgDrawBatchKey b = new CgDrawBatchKey(false, 42, 0.0f);
        assertEquals(0, a.compareTo(b));
    }

    // ── Accessors ─────────────────────────────────────────────────────

    @Test
    public void accessorsReturnConstructorValues() {
        CgDrawBatchKey key = new CgDrawBatchKey(true, 99, 6.5f);
        assertTrue(key.isMsdf());
        assertEquals(99, key.getTextureId());
        assertEquals(6.5f, key.getPxRange(), 0.0f);
    }

    @Test
    public void toStringContainsAllFields() {
        CgDrawBatchKey key = new CgDrawBatchKey(true, 42, 4.0f);
        String s = key.toString();
        assertTrue(s.contains("msdf=true"));
        assertTrue(s.contains("textureId=42"));
        assertTrue(s.contains("pxRange=4.0"));
    }
}
