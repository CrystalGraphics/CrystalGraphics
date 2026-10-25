package com.crystalgraphics.util.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An OpenEXR image ({@code .exr}) of half floats, read without a codec: signed linear values, alpha included, which a
 * vertex animation texture's offsets need and a Radiance image cannot keep. Read by the OpenEXR file layout
 * (openexr.com, "OpenEXR File Layout"): a single-part scanline image, uncompressed, its channels HALF.
 *
 * <pre>{@code
 * CgOpenExr.Image offsets = CgOpenExr.read("spzero_kit:textures/fx/T_ex_wave_EX.exr");
 * texture.upload(offsets.width(), offsets.height(), offsets.halves(), GL_RGBA, GL_HALF_FLOAT);   // bits untouched
 * }</pre>
 *
 * <ul>
 *   <li>Refused by name: tiled, multi-part or deep images, any compression, a channel that is not HALF or is
 *       subsampled, a channel other than R, G, B and A.</li>
 *   <li>A missing colour channel reads 0 and a missing alpha 1, as OpenEXR fills them.</li>
 *   <li>Rows top first (the data window's lowest y first), as {@code CgTextureIO} reads a PNG.</li>
 *   <li>Any thread: it reads the file and nothing else.</li>
 * </ul>
 */
public final class CgOpenExr {

    /** Its texels as RGBA binary16, two bytes a value in native order, in a direct buffer ready for an upload. */
    public record Image(int width, int height, ByteBuffer halves) {
    }

    private static final int MAGIC = 20000630, TILED = 0x200, DEEP = 0x800, MULTIPART = 0x1000, HALF = 1;
    private static final short ONE = 0x3C00;

    private CgOpenExr() {
    }

    /** Whether {@code path} names an OpenEXR image. */
    public static boolean is(String path) {
        return path != null && path.endsWith(".exr");
    }

    /** {@code path} through {@link CgIO#openStream}. @throws IOException missing, or not an image this reads */
    public static Image read(String path) throws IOException {
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IOException(path + " is missing");
            return read(in.readAllBytes(), path);
        }
    }

    /** {@code file}'s bytes; {@code who} names it in errors. */
    public static Image read(byte[] file, String who) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (b.getInt() != MAGIC) throw new IOException(who + " is not an OpenEXR image");
            int version = b.getInt();
            if ((version & 0xFF) != 2) throw new IOException(who + " is OpenEXR version " + (version & 0xFF) + ", not 2");
            if ((version & (TILED | DEEP | MULTIPART)) != 0) {
                throw new IOException(who + " is a tiled, deep or multi-part OpenEXR image: only one scanline part is read");
            }
            String channels = null;
            int compression = -1, x0 = 0, y0 = 0, x1 = -1, y1 = -1;
            int[] order = null;
            for (String name = string(b); !name.isEmpty(); name = string(b)) {
                string(b);   // its type: each attribute read is known by name
                int size = b.getInt(), end = b.position() + size;
                switch (name) {
                    case "compression" -> compression = b.get() & 0xFF;
                    case "dataWindow" -> {
                        x0 = b.getInt();
                        y0 = b.getInt();
                        x1 = b.getInt();
                        y1 = b.getInt();
                    }
                    case "channels" -> {
                        StringBuilder names = new StringBuilder();
                        int[] slots = new int[4];
                        int n = 0;
                        for (String c = string(b); !c.isEmpty(); c = string(b)) {
                            int type = b.getInt();
                            b.position(b.position() + 4);   // pLinear and its reserved bytes
                            int xs = b.getInt(), ys = b.getInt();
                            int slot = "RGBA".indexOf(c);
                            if (c.length() != 1 || slot < 0) throw new IOException(who + " has a channel " + c + ": only R, G, B and A are read");
                            if (type != HALF) throw new IOException(who + "'s channel " + c + " is not HALF (pixel type " + type + ")");
                            if (xs != 1 || ys != 1) throw new IOException(who + "'s channel " + c + " is subsampled");
                            if (n == 4) throw new IOException(who + " repeats a channel");
                            slots[n++] = slot;
                            names.append(c);
                        }
                        channels = names.toString();
                        order = Arrays.copyOf(slots, n);
                    }
                    default -> {
                    }
                }
                b.position(end);
            }
            if (compression != 0) throw new IOException(who + " is compressed (compression " + compression + "): only uncompressed images are read");
            if (order == null || order.length == 0) throw new IOException(who + " declares no channels");
            int width = x1 - x0 + 1, height = y1 - y0 + 1;
            if (width < 1 || height < 1 || (long) width * height > (1 << 26)) throw new IOException(who + " is " + width + "x" + height);
            ByteBuffer out = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder());
            for (int t = 0; t < width * height; t++) out.putShort(t * 8 + 6, ONE);
            long[] offsets = new long[height];
            for (int i = 0; i < height; i++) offsets[i] = b.getLong();
            for (long offset : offsets) {
                b.position(Math.toIntExact(offset));
                int row = b.getInt() - y0, bytes = b.getInt();
                if (row < 0 || row >= height || bytes != width * order.length * 2) {
                    throw new IOException(who + " has a scanline at y " + (row + y0) + " of " + bytes + " bytes, not one of its "
                            + width + " texels of " + channels);
                }
                for (int slot : order) {
                    for (int x = 0; x < width; x++) out.putShort(((row * width + x) * 4 + slot) * 2, b.getShort());
                }
            }
            out.position(0).limit(width * height * 8);
            return new Image(width, height, out);
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IOException(who + " ends early or points past its end", e);
        }
    }

    /** A null-terminated string at the buffer's position, the null consumed. */
    private static String string(ByteBuffer b) {
        int start = b.position();
        while (b.get() != 0) {
            // to the terminator
        }
        return new String(b.array(), start, b.position() - start - 1, StandardCharsets.UTF_8);
    }
}
