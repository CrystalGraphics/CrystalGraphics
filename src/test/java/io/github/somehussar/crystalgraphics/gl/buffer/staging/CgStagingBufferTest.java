package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import org.junit.Test;

import static org.junit.Assert.*;

public class CgStagingBufferTest {

    @Test
    public void reserveAndZeroReturnsPriorCursorAndAdvances() {
        CgStagingBuffer buf = new CgStagingBuffer(16);
        buf.putFloat(1f);
        buf.putFloat(2f);
        buf.putFloat(3f);
        buf.putFloat(4f);
        assertEquals(4, buf.rawCursor());

        int start = buf.reserveAndZero(8);
        assertEquals(4, start);
        assertEquals(12, buf.rawCursor());
    }

    @Test
    public void reserveAndZeroFillsZeroes() {
        CgStagingBuffer buf = new CgStagingBuffer(16);
        int start = buf.reserveAndZero(8);
        float[] data = buf.rawData();
        for (int i = start; i < start + 8; i++) {
            assertEquals("slot " + i + " should be 0f", 0f, data[i], 0f);
        }
    }

    @Test
    public void setFloatAtWritesWithoutAdvancingCursor() {
        CgStagingBuffer buf = new CgStagingBuffer(16);
        buf.reserveAndZero(16);
        assertEquals(16, buf.rawCursor());

        buf.setFloatAt(5, 3.14f);
        assertEquals("cursor must not advance", 16, buf.rawCursor());
        assertEquals(3.14f, buf.rawData()[5], 0.0001f);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void setFloatAtOutOfBoundsThrows() {
        CgStagingBuffer buf = new CgStagingBuffer(16);
        buf.reserveAndZero(4);
        buf.setFloatAt(4, 1f); // index == cursor, out of reserved range
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void setFloatAtNegativeIndexThrows() {
        CgStagingBuffer buf = new CgStagingBuffer(8);
        buf.reserveAndZero(4);
        buf.setFloatAt(-1, 1f);
    }
}
