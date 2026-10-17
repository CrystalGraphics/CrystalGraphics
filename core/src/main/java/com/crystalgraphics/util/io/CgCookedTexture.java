package com.crystalgraphics.util.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A cooked texture file ({@code .cgtex}): BC7 blocks for every mip level, written by the {@code cookTextures} task and
 * read here. What {@code CgTexture2D} loads a path ending in {@code .cgtex} through.
 *
 * <pre>{@code
 * CgCookedTexture cooked = CgCookedTexture.read("mymod:cooked/fx/smoke.cgtex");   // null if it will not read
 * for (int level = 0; level < cooked.levels(); level++) {
 *     ByteBuffer blocks = cooked.level(level);           // CgBc7.levelBytes(width >> level, height >> level) bytes
 * }
 * }</pre>
 *
 * <pre>
 * "CGTX"  u16 version (1)  u16 format (1: BC7, 2: BC7 sRGB)  u16 width  u16 height  u32 levels
 * (u32 offset, u32 length)[levels]  then each level's blocks, rows top first as CgTextureIO reads a PNG; little-endian
 * </pre>
 *
 * <ul>
 *   <li>Levels run from the full size down, each half the last, at least 1, as GL's mip chain.</li>
 *   <li>Any thread: it reads the file and nothing else.</li>
 * </ul>
 */
public final class CgCookedTexture {

    private static final Logger LOGGER = Logger.getLogger(CgCookedTexture.class.getName());

    public static final int FORMAT_BC7 = 1, FORMAT_BC7_SRGB = 2;
    private static final int VERSION = 1, HEADER = 16;

    private final int format, width, height;
    private final int[] offsets, lengths;
    private final byte[] file;

    private CgCookedTexture(int format, int width, int height, int[] offsets, int[] lengths, byte[] file) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.offsets = offsets;
        this.lengths = lengths;
        this.file = file;
    }

    /** Whether {@code path} names a cooked texture. */
    public static boolean is(String path) {
        return path != null && path.endsWith(".cgtex");
    }

    /** Reads {@code path} through {@link CgIO#openStream}; null, logged, if it is missing or malformed. */
    public static CgCookedTexture read(String path) {
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) {
                LOGGER.log(Level.WARNING, "CgCookedTexture: no file at {0}", path);
                return null;
            }
            return parse(bytes(in));
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "CgCookedTexture: " + path + " will not read", e);
            return null;
        }
    }

    /** A cooked texture from its file's bytes; throws {@link IllegalArgumentException} naming what is wrong. */
    public static CgCookedTexture parse(byte[] file) {
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < HEADER || !"CGTX".equals(new String(file, 0, 4, StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("not a cooked texture");
        int version = b.getShort(4) & 0xFFFF, format = b.getShort(6) & 0xFFFF;
        int width = b.getShort(8) & 0xFFFF, height = b.getShort(10) & 0xFFFF, levels = b.getInt(12);
        if (version != VERSION) throw new IllegalArgumentException("version " + version + ", this engine reads " + VERSION);
        if (format != FORMAT_BC7 && format != FORMAT_BC7_SRGB) throw new IllegalArgumentException("format " + format);
        if (width < 1 || height < 1 || levels < 1 || levels > 32 - Integer.numberOfLeadingZeros(Math.max(width, height)))
            throw new IllegalArgumentException(width + "x" + height + " in " + levels + " levels");
        int[] offsets = new int[levels], lengths = new int[levels];
        for (int l = 0; l < levels; l++) {
            offsets[l] = b.getInt(HEADER + 8 * l);
            lengths[l] = b.getInt(HEADER + 8 * l + 4);
            int need = CgBc7.levelBytes(Math.max(1, width >> l), Math.max(1, height >> l));
            if (lengths[l] != need || offsets[l] < HEADER + 8 * levels || (long) offsets[l] + lengths[l] > file.length)
                throw new IllegalArgumentException("level " + l + ": " + lengths[l] + " bytes at " + offsets[l]
                        + ", " + need + " expected");
        }
        return new CgCookedTexture(format, width, height, offsets, lengths, file);
    }

    public int width() { return width; }

    public int height() { return height; }

    public int levels() { return offsets.length; }

    /** Whether its texels are sRGB colour rather than linear data. */
    public boolean srgb() { return format == FORMAT_BC7_SRGB; }

    /** Level {@code level}'s blocks: a view of the file, little-endian. */
    public ByteBuffer level(int level) {
        return ByteBuffer.wrap(file, offsets[level], lengths[level]).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Every level's blocks, in order and back to back: what one lease holds. */
    public int blockBytes() {
        int total = 0;
        for (int length : lengths) total += length;
        return total;
    }

    /** Copies every level's blocks back to back into {@code out}, from its position, which moves past them. */
    public void putBlocks(ByteBuffer out) {
        for (int l = 0; l < levels(); l++) out.put(file, offsets[l], lengths[l]);
    }

    private static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[1 << 16];
        for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
        return out.toByteArray();
    }
}
