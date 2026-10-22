package com.crystalgraphics.util.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A Radiance HDR image ({@code .hdr}, RGBE): linear RGB past 1, decoded to floats. What a float texture's file is.
 * Ported from stb_image's {@code stbi__hdr_load} (MIT, Sean Barrett): its header, its run-length scanlines and its
 * RGBE to float.
 *
 * <pre>{@code
 * CgRadianceHdr.Image sky = CgRadianceHdr.read("kakarot_kit:textures/Art/BG/Cubemap/T_Window_Cubemap.hdr");
 * float red = sky.rgba()[(y * sky.width() + x) * 4];   // rows top first, as CgTextureIO reads a PNG
 * }</pre>
 *
 * <ul>
 *   <li>Only {@code -Y h +X w} images, the orientation every writer uses; another throws, naming it.</li>
 *   <li>Alpha is 1: RGBE has none.</li>
 *   <li>Any thread: it reads the file and nothing else.</li>
 * </ul>
 */
public final class CgRadianceHdr {

    /** Linear RGBA, four floats a texel, rows top first. */
    public record Image(int width, int height, float[] rgba) {
    }

    private CgRadianceHdr() {
    }

    /** Whether {@code path} names a Radiance image. */
    public static boolean is(String path) {
        return path != null && path.endsWith(".hdr");
    }

    /** {@code path} through {@link CgIO#openStream}. @throws IOException missing, or not a Radiance image */
    public static Image read(String path) throws IOException {
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IOException(path + " is missing");
            return read(in.readAllBytes(), path);
        }
    }

    /** {@code file}'s bytes; {@code who} names it in errors. */
    public static Image read(byte[] file, String who) throws IOException {
        Reader r = new Reader(file, who);
        String magic = r.line();
        if (!magic.equals("#?RADIANCE") && !magic.equals("#?RGBE")) throw new IOException(who + " is not a Radiance image");
        boolean rgbe = false;
        for (String line = r.line(); !line.isEmpty(); line = r.line()) {
            if (line.equals("FORMAT=32-bit_rle_rgbe")) rgbe = true;
        }
        if (!rgbe) throw new IOException(who + " has no FORMAT=32-bit_rle_rgbe");
        String[] size = r.line().trim().split("\\s+");
        if (size.length != 4 || !size[0].equals("-Y") || !size[2].equals("+X")) {
            throw new IOException(who + "'s size line is not -Y h +X w: " + String.join(" ", size));
        }
        int height = Integer.parseInt(size[1]), width = Integer.parseInt(size[3]);
        if (width < 1 || height < 1 || (long) width * height > (1 << 26)) throw new IOException(who + " is " + width + "x" + height);
        float[] out = new float[width * height * 4];
        byte[] scanline = new byte[width * 4];
        // Too narrow or wide to run-length encode: every texel flat.
        if (width < 8 || width >= 32768) {
            for (int t = 0; t < width * height; t++) texel(r.u8(), r.u8(), r.u8(), r.u8(), out, t);
            return new Image(width, height, out);
        }
        for (int y = 0; y < height; y++) {
            int c1 = r.u8(), c2 = r.u8(), len = r.u8();
            if (c1 != 2 || c2 != 2 || (len & 0x80) != 0) {
                // Not run-length encoded: this and every texel after it flat, the four bytes read its first.
                texel(c1, c2, len, r.u8(), out, y * width);
                for (int t = y * width + 1; t < width * height; t++) texel(r.u8(), r.u8(), r.u8(), r.u8(), out, t);
                return new Image(width, height, out);
            }
            len = (len << 8) | r.u8();
            if (len != width) throw new IOException(who + " has a scanline of " + len + " texels in a " + width + " wide image");
            for (int k = 0; k < 4; k++) {
                int i = 0;
                while (i < width) {
                    int count = r.u8();
                    if (count > 128) {
                        count -= 128;
                        if (count > width - i) throw new IOException(who + "'s scanline " + y + " runs past its width");
                        byte value = (byte) r.u8();
                        for (int z = 0; z < count; z++) scanline[(i++) * 4 + k] = value;
                    } else {
                        if (count == 0 || count > width - i) throw new IOException(who + "'s scanline " + y + " is corrupt");
                        for (int z = 0; z < count; z++) scanline[(i++) * 4 + k] = (byte) r.u8();
                    }
                }
            }
            for (int x = 0; x < width; x++) {
                texel(scanline[x * 4] & 0xFF, scanline[x * 4 + 1] & 0xFF, scanline[x * 4 + 2] & 0xFF,
                        scanline[x * 4 + 3] & 0xFF, out, y * width + x);
            }
        }
        return new Image(width, height, out);
    }

    /** One RGBE texel into {@code out} at texel {@code t}: each channel times 2^(e - 136), black where e is 0. */
    private static void texel(int r, int g, int b, int e, float[] out, int t) {
        float f = e == 0 ? 0f : Math.scalb(1f, e - (128 + 8));
        out[t * 4] = r * f;
        out[t * 4 + 1] = g * f;
        out[t * 4 + 2] = b * f;
        out[t * 4 + 3] = 1f;
    }

    private static final class Reader {
        private final byte[] file;
        private final String who;
        private int at;

        Reader(byte[] file, String who) {
            this.file = file;
            this.who = who;
        }

        int u8() throws IOException {
            if (at >= file.length) throw new IOException(who + " ends early");
            return file[at++] & 0xFF;
        }

        String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int c = u8(); c != '\n'; c = u8()) if (c != '\r') line.write(c);   // a header checked out with CRLF
            return line.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
