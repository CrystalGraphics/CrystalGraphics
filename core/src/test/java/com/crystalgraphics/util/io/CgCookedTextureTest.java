package com.crystalgraphics.util.io;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A cooked texture read and decoded matches the image it was cooked from. The fixture is cookTextures' output. */
public class CgCookedTextureTest {

    private static final String PNG = "crystalgraphics:textures/test/cooked_fixture.png";
    private static final String COOKED = "crystalgraphics:textures/test/cooked_fixture.cgtex";

    @Test
    public void readsEveryLevelOfTheChain() {
        CgCookedTexture cooked = CgCookedTexture.read(COOKED);
        assertNotNull(cooked);
        assertEquals(64, cooked.width());
        assertEquals(48, cooked.height());
        assertEquals(7, cooked.levels());          // 64x48 down to 1x1
        assertTrue(cooked.srgb());
        assertEquals(CgBc7.levelBytes(2, 1), cooked.level(5).remaining());   // one block for a 2x1 level
    }

    @Test
    public void decodedLevelZeroIsItsImageWithinBc7Error() {
        CgCookedTexture cooked = CgCookedTexture.read(COOKED);
        CgTextureIO.CgImageData image = CgTextureIO.load(PNG);
        assertNotNull(image);
        assertEquals(4, image.channels());
        ByteBuffer decoded = ByteBuffer.allocateDirect(4 * 64 * 48);
        CgBc7.decode(cooked.level(0), 64, 48, decoded);

        long sum = 0;
        int worst = 0;
        for (int i = 0; i < 4 * 64 * 48; i++) {
            int d = Math.abs((decoded.get(i) & 0xFF) - (image.pixels().get(i) & 0xFF));
            sum += d;
            worst = Math.max(worst, d);
        }
        double mean = sum / (4.0 * 64 * 48);
        // Rows in the same order (both bottom first) and BC7's error alone: a flipped or misread block is far past these.
        assertTrue("mean error " + mean, mean < 1.5);
        assertTrue("worst error " + worst, worst < 24);
    }

    @Test
    public void refusesAShortFile() {
        CgCookedTexture cooked = CgCookedTexture.read(COOKED);
        byte[] whole = new byte[16 + 8 * 7 + cooked.blockBytes()];
        ByteBuffer out = ByteBuffer.wrap(whole, 16 + 8 * 7, cooked.blockBytes());
        cooked.putBlocks(out);
        assertNull(CgCookedTexture.read("crystalgraphics:textures/test/missing.cgtex"));
        try {
            CgCookedTexture.parse(Arrays.copyOf(whole, 40));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("a 40-byte file parsed");
    }
}
