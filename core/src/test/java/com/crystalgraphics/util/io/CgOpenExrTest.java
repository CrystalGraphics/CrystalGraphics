package com.crystalgraphics.util.io;

import com.crystalgraphics.util.CgHalfFloat;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** An uncompressed half-float OpenEXR read into RGBA halves: the sign and the alpha kept, the bits untouched. */
public class CgOpenExrTest {

    /** A {@code w x h} scanline image of channels A, B, G, R (OpenEXR's alphabetical order), texel t's RGBA at {@code rgba}. */
    private static byte[] exr(int w, int h, float[] rgba, int compression) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ByteBuffer ints = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        ints.putInt(20000630).putInt(2);
        header.writeBytes(ints.array());
        ByteBuffer ch = ByteBuffer.allocate(4 * 18 + 1).order(ByteOrder.LITTLE_ENDIAN);
        for (String c : new String[] {"A", "B", "G", "R"}) {
            ch.put(c.getBytes(StandardCharsets.US_ASCII)).put((byte) 0).putInt(1).putInt(0).putInt(1).putInt(1);
        }
        ch.put((byte) 0);
        attribute(header, "channels", "chlist", ch.array());
        attribute(header, "compression", "compression", new byte[] {(byte) compression});
        ByteBuffer box = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(w - 1).putInt(h - 1);
        attribute(header, "dataWindow", "box2i", box.array());
        attribute(header, "lineOrder", "lineOrder", new byte[] {0});
        header.write(0);
        int rowBytes = 8 + w * 4 * 2, start = header.size() + 8 * h;
        ByteBuffer body = ByteBuffer.allocate(8 * h + rowBytes * h).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) body.putLong(start + (long) y * rowBytes);
        int[] slot = {3, 2, 1, 0};   // A, B, G, R
        for (int y = 0; y < h; y++) {
            body.putInt(y).putInt(w * 4 * 2);
            for (int s : slot) for (int x = 0; x < w; x++) body.putShort(CgHalfFloat.toHalf(rgba[(y * w + x) * 4 + s]));
        }
        header.writeBytes(body.array());
        return header.toByteArray();
    }

    private static void attribute(ByteArrayOutputStream out, String name, String type, byte[] value) {
        out.writeBytes((name + "\0" + type + "\0").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.length).array());
        out.writeBytes(value);
    }

    @Test
    public void itsHalvesAreReadSignedWithTheirAlpha() throws IOException {
        float[] rgba = {-1.5f, 0.25f, 2f, 0.5f,   3f, -0.125f, 0f, 1f,
                        0f, 0f, -4f, 0f,          1f, 1f, 1f, 0.75f};
        CgOpenExr.Image image = CgOpenExr.read(exr(2, 2, rgba, 0), "test.exr");
        assertEquals(2, image.width());
        assertEquals(2, image.height());
        ByteBuffer halves = image.halves();
        for (int i = 0; i < rgba.length; i++) {
            assertEquals("value " + i, rgba[i], CgHalfFloat.toFloat(halves.getShort(i * 2)), 0f);
        }
    }

    @Test
    public void aCompressedOrForeignImageFailsNamingWhy() {
        IOException e = assertThrows(IOException.class, () -> CgOpenExr.read(exr(2, 2, new float[16], 3), "zip.exr"));
        assertTrue(e.getMessage(), e.getMessage().contains("zip.exr") && e.getMessage().contains("compress"));
        e = assertThrows(IOException.class, () -> CgOpenExr.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, "png.exr"));
        assertTrue(e.getMessage(), e.getMessage().contains("not an OpenEXR"));
    }
}
