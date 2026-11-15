package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import java.nio.ByteBuffer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A sheet of equal tiles cut into a slice or layer a tile, in reading order from the top left. */
public class CgTileSheetTest {

    /** A 6x4 two-channel sheet whose every texel holds its own x and y. */
    private static CgImageData sheet() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(6 * 4 * 2);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 6; x++) pixels.put((byte) x).put((byte) y);
        }
        pixels.flip();
        return new CgImageData(pixels, 6, 4, 2);
    }

    @Test
    public void eachTileHoldsItsOwnTexelsInReadingOrder() {
        ByteBuffer[] tiles = CgTileSheet.cut(sheet(), 3, 2);   // 2x2 tiles
        assertEquals(6, tiles.length);
        for (int t = 0; t < tiles.length; t++) {
            int x0 = (t % 3) * 2, y0 = (t / 3) * 2;
            assertEquals(2 * 2 * 2, tiles[t].remaining());
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    int at = (y * 2 + x) * 2;
                    assertEquals("tile " + t + " x", x0 + x, tiles[t].get(at));
                    assertEquals("tile " + t + " y", y0 + y, tiles[t].get(at + 1));
                }
            }
        }
    }

    @Test
    public void aSheetThatDoesNotDivideIsRefused() {
        assertTrue(CgTileSheet.divides(sheet(), 3, 2));
        assertFalse(CgTileSheet.divides(sheet(), 4, 2));
        assertFalse(CgTileSheet.divides(sheet(), 3, 3));
    }
}
