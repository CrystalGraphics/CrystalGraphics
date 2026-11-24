package com.crystalgraphics.util.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A gray PNG decodes to its stored values, as Minecraft's decoder reads it: 26.x's slot sprites are gray with tRNS. */
public class CgTextureIOGrayTest {

    @Test
    public void aGrayPngWithATransparentGrayKeepsItsValues() throws IOException {
        // Two pixels: the transparent gray 0, then 85 (#555555, a slot outline)
        CgTextureIO.CgImageData image = CgTextureIO.convertNative(ImageIO.read(new ByteArrayInputStream(
                grayPng(new int[] {0, 85}, 0))));

        assertEquals(4, image.channels());
        assertEquals("transparent", 0, image.pixels().get(3) & 0xFF);
        assertEquals("85 as stored, not as linear gray made sRGB (156)", 85, image.pixels().get(4) & 0xFF);
        assertEquals(255, image.pixels().get(7) & 0xFF);
    }

    @Test
    public void aGrayPngWithoutAlphaIsOneChannel() throws IOException {
        CgTextureIO.CgImageData image = CgTextureIO.convertNative(ImageIO.read(new ByteArrayInputStream(
                grayPng(new int[] {85, 200}, -1))));

        assertEquals(1, image.channels());
        assertEquals(85, image.pixels().get(0) & 0xFF);
        assertEquals(200, image.pixels().get(1) & 0xFF);
    }

    // An 8-bit gray PNG one row high; a tRNS chunk naming `transparent` unless it is negative
    private static byte[] grayPng(int[] row, int transparent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(ihdr);
        h.writeInt(row.length);
        h.writeInt(1);
        h.write(new byte[] {8, 0, 0, 0, 0});   // 8 bits, gray, deflate, no filter method, no interlace
        chunk(out, "IHDR", ihdr.toByteArray());
        if (transparent >= 0) chunk(out, "tRNS", new byte[] {0, (byte) transparent});
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(0);   // filter: none
        for (int v : row) raw.write(v);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(packed)) {
            z.write(raw.toByteArray());
        }
        chunk(out, "IDAT", packed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(data.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        d.write(name);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
