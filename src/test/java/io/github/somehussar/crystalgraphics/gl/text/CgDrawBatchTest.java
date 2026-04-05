package io.github.somehussar.crystalgraphics.gl.text;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link CgDrawBatch} — contiguous quad range for one draw call.
 */
public class CgDrawBatchTest {

    @Test
    public void constructorStoresFields() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        CgDrawBatch batch = new CgDrawBatch(key, 5, 10);

        assertSame(key, batch.getKey());
        assertEquals(5, batch.getStartQuad());
        assertEquals(10, batch.getQuadCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullKey() {
        new CgDrawBatch(null, 0, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeStartQuad() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        new CgDrawBatch(key, -1, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeQuadCount() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        new CgDrawBatch(key, 0, -1);
    }

    @Test
    public void iboByteOffsetComputation() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        // startQuad=3 → offset = 3 * 6 indices * 2 bytes = 36
        CgDrawBatch batch = new CgDrawBatch(key, 3, 5);
        assertEquals(36L, batch.getIboByteOffset());
    }

    @Test
    public void iboByteOffsetZeroForFirstBatch() {
        CgDrawBatchKey key = new CgDrawBatchKey(true, 1, 4.0f);
        CgDrawBatch batch = new CgDrawBatch(key, 0, 10);
        assertEquals(0L, batch.getIboByteOffset());
    }

    @Test
    public void indexCountComputation() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        CgDrawBatch batch = new CgDrawBatch(key, 0, 7);
        // 7 quads * 6 indices = 42
        assertEquals(42, batch.getIndexCount());
    }

    @Test
    public void isEmptyForZeroQuads() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        CgDrawBatch batch = new CgDrawBatch(key, 0, 0);
        assertTrue(batch.isEmpty());
    }

    @Test
    public void isNotEmptyForPositiveQuads() {
        CgDrawBatchKey key = new CgDrawBatchKey(false, 1, 0.0f);
        CgDrawBatch batch = new CgDrawBatch(key, 0, 1);
        assertFalse(batch.isEmpty());
    }

    @Test
    public void toStringContainsQuadInfo() {
        CgDrawBatchKey key = new CgDrawBatchKey(true, 42, 4.0f);
        CgDrawBatch batch = new CgDrawBatch(key, 10, 20);
        String s = batch.toString();
        assertTrue(s.contains("startQuad=10"));
        assertTrue(s.contains("quadCount=20"));
    }
}
