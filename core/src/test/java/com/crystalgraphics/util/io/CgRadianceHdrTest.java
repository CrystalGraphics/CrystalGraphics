package com.crystalgraphics.util.io;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Radiance images decode as stb_image decodes them: flat and run-length scanlines, RGBE to linear floats. */
public class CgRadianceHdrTest {

    private static ByteArrayOutputStream header(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\nEXPOSURE=1\n\n-Y " + height + " +X " + width + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        return out;
    }

    @Test
    public void aNarrowImageIsFlatRgbe() throws IOException {
        ByteArrayOutputStream out = header(2, 1);
        out.write(new byte[] {(byte) 128, 64, 32, (byte) 136});    // 2^(136-136) = 1: 128, 64, 32
        out.write(new byte[] {(byte) 200, 0, 0, 0});               // e 0: black
        CgRadianceHdr.Image image = CgRadianceHdr.read(out.toByteArray(), "flat");
        assertEquals(2, image.width());
        assertArrayEquals(new float[] {128f, 64f, 32f, 1f, 0f, 0f, 0f, 1f}, image.rgba(), 0f);
    }

    @Test
    public void runLengthScanlinesDecodeChannelByChannel() throws IOException {
        int width = 8;
        ByteArrayOutputStream out = header(width, 2);
        for (int y = 0; y < 2; y++) {
            out.write(new byte[] {2, 2, 0, (byte) width});
            out.write(new byte[] {(byte) (128 + width), (byte) (10 + y)});           // red: one run
            out.write(width);                                                          // green: literals 0..7
            for (int x = 0; x < width; x++) out.write(x);
            out.write(new byte[] {(byte) (128 + 4), 1, (byte) (128 + 4), 2});        // blue: two runs
            out.write(new byte[] {(byte) (128 + width), (byte) 137});                // e 137: times 2
        }
        CgRadianceHdr.Image image = CgRadianceHdr.read(out.toByteArray(), "rle");
        float[] p = image.rgba();
        assertEquals(22f, p[(8 + 3) * 4], 0f);          // row 1: red 11 x 2
        assertEquals(6f, p[3 * 4 + 1], 0f);             // green 3 x 2
        assertEquals(2f, p[2 * 4 + 2], 0f);             // blue's first run, 1 x 2
        assertEquals(4f, p[6 * 4 + 2], 0f);             // its second, 2 x 2
        assertEquals(1f, p[7 * 4 + 3], 0f);
    }

    @Test
    public void aFileThatIsNotRadianceIsRefused() {
        IOException e = assertThrows(IOException.class,
                () -> CgRadianceHdr.read("P6\n1 1\n255\n".getBytes(StandardCharsets.US_ASCII), "a.ppm"));
        assertTrue(e.getMessage(), e.getMessage().contains("a.ppm"));
    }
}
