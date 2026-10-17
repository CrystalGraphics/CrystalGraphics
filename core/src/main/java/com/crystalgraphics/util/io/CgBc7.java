package com.crystalgraphics.util.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decodes BC7 blocks to RGBA8: what a cooked texture becomes on a context without BPTC (macOS's GL 4.1).
 *
 * <pre>{@code
 * ByteBuffer blocks = ...;                                    // ceil(w/4) * ceil(h/4) blocks of 16 bytes
 * ByteBuffer rgba = ByteBuffer.allocateDirect(4 * w * h);
 * CgBc7.decode(blocks, w, h, rgba);                          // rows in the blocks' order, tightly packed
 * }</pre>
 *
 * <ul>
 *   <li>The output's position is left alone; it needs {@code 4 * w * h} bytes from there. Texels past the image's
 *       edge in its last row or column of blocks are dropped.</li>
 *   <li>sRGB or linear is the caller's: the bytes are the same, and the texture's format decides how they are read.</li>
 *   <li>A reserved mode decodes as transparent black, as the format defines.</li>
 * </ul>
 *
 * <p>Ported from bcdec's {@code bcdec_bc7} (github.com/iOrange/bcdec, Copyright (c) 2022 Sergii Kudlai, MIT).</p>
 */
public final class CgBc7 {

    /** Bytes in one 4x4 block. */
    public static final int BLOCK_BYTES = 16;

    // Bits per RGB and alpha endpoint component by mode, before its P-bit.
    private static final int[] COLOUR_BITS = {4, 6, 5, 7, 5, 7, 7, 5};
    private static final int[] ALPHA_BITS = {0, 0, 0, 0, 6, 8, 7, 5};
    /** Modes with a P-bit per endpoint: 0, 1 (shared per subset), 3, 6 and 7. */
    private static final int MODE_HAS_P_BITS = 0b11001011;

    private static final int[] WEIGHT2 = {0, 21, 43, 64};
    private static final int[] WEIGHT3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] WEIGHT4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    // Each texel's subset, row by row, for the 64 shapes of two and three subsets; 128 marks a subset's fix-up texel.
    private static final int[] TWO = {
            128, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,  // 0
            128, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 129,  // 1
            128, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 129,  // 2
            128, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 129,  // 3
            128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 129,  // 4
            128, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,  // 5
            128, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,  // 6
            128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 129,  // 7
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 129,  // 8
            128, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,  // 9
            128, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 129,  // 10
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 129,  // 11
            128, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,  // 12
            128, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 129,  // 13
            128, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,  // 14
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 129,  // 15
            128, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 129,  // 16
            128, 1, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,  // 17
            128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 1, 0,  // 18
            128, 1, 129, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0,  // 19
            128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,  // 20
            128, 0, 0, 0, 1, 0, 0, 0, 129, 1, 0, 0, 1, 1, 1, 0,  // 21
            128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,  // 22
            128, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 129,  // 23
            128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0,  // 24
            128, 0, 0, 0, 1, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,  // 25
            128, 1, 129, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0,  // 26
            128, 0, 129, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0,  // 27
            128, 0, 0, 1, 0, 1, 1, 1, 129, 1, 1, 0, 1, 0, 0, 0,  // 28
            128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 0, 0, 0, 0,  // 29
            128, 1, 129, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0,  // 30
            128, 0, 129, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0,  // 31
            128, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 129,  // 32
            128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 129,  // 33
            128, 1, 0, 1, 1, 0, 129, 0, 0, 1, 0, 1, 1, 0, 1, 0,  // 34
            128, 0, 1, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 1, 0, 0,  // 35
            128, 0, 129, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0,  // 36
            128, 1, 0, 1, 0, 1, 0, 1, 129, 0, 1, 0, 1, 0, 1, 0,  // 37
            128, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 129,  // 38
            128, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 129,  // 39
            128, 1, 129, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 0,  // 40
            128, 0, 0, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 0, 0, 0,  // 41
            128, 0, 129, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0,  // 42
            128, 0, 129, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0,  // 43
            128, 1, 129, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0,  // 44
            128, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 129,  // 45
            128, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 129,  // 46
            128, 0, 0, 0, 0, 1, 129, 0, 0, 1, 1, 0, 0, 0, 0, 0,  // 47
            128, 1, 0, 0, 1, 1, 129, 0, 0, 1, 0, 0, 0, 0, 0, 0,  // 48
            128, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0,  // 49
            128, 0, 0, 0, 0, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0,  // 50
            128, 0, 0, 0, 0, 1, 0, 0, 129, 1, 1, 0, 0, 1, 0, 0,  // 51
            128, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 129,  // 52
            128, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 129,  // 53
            128, 1, 129, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0,  // 54
            128, 0, 129, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0,  // 55
            128, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 129,  // 56
            128, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 129,  // 57
            128, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 129,  // 58
            128, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 129,  // 59
            128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,  // 60
            128, 0, 129, 1, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0,  // 61
            128, 0, 129, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0,  // 62
            128, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 129,  // 63
    };
    private static final int[] THREE = {
            128, 0, 1, 129, 0, 0, 1, 1, 0, 2, 2, 1, 2, 2, 2, 130,  // 0
            128, 0, 0, 129, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 2, 1,  // 1
            128, 0, 0, 0, 2, 0, 0, 1, 130, 2, 1, 1, 2, 2, 1, 129,  // 2
            128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 1, 0, 1, 1, 129,  // 3
            128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 2, 2, 1, 1, 2, 130,  // 4
            128, 0, 1, 129, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 2, 130,  // 5
            128, 0, 2, 130, 0, 0, 2, 2, 1, 1, 1, 1, 1, 1, 1, 129,  // 6
            128, 0, 1, 1, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 1, 129,  // 7
            128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 1, 1, 2, 2, 2, 130,  // 8
            128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 2, 2, 2, 130,  // 9
            128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 2, 2, 2, 2, 2, 130,  // 10
            128, 0, 1, 2, 0, 0, 129, 2, 0, 0, 1, 2, 0, 0, 1, 130,  // 11
            128, 1, 1, 2, 0, 1, 129, 2, 0, 1, 1, 2, 0, 1, 1, 130,  // 12
            128, 1, 2, 2, 0, 129, 2, 2, 0, 1, 2, 2, 0, 1, 2, 130,  // 13
            128, 0, 1, 129, 0, 1, 1, 2, 1, 1, 2, 2, 1, 2, 2, 130,  // 14
            128, 0, 1, 129, 2, 0, 0, 1, 130, 2, 0, 0, 2, 2, 2, 0,  // 15
            128, 0, 0, 129, 0, 0, 1, 1, 0, 1, 1, 2, 1, 1, 2, 130,  // 16
            128, 1, 1, 129, 0, 0, 1, 1, 130, 0, 0, 1, 2, 2, 0, 0,  // 17
            128, 0, 0, 0, 1, 1, 2, 2, 129, 1, 2, 2, 1, 1, 2, 130,  // 18
            128, 0, 2, 130, 0, 0, 2, 2, 0, 0, 2, 2, 1, 1, 1, 129,  // 19
            128, 1, 1, 129, 0, 1, 1, 1, 0, 2, 2, 2, 0, 2, 2, 130,  // 20
            128, 0, 0, 129, 0, 0, 0, 1, 130, 2, 2, 1, 2, 2, 2, 1,  // 21
            128, 0, 0, 0, 0, 0, 129, 1, 0, 1, 2, 2, 0, 1, 2, 130,  // 22
            128, 0, 0, 0, 1, 1, 0, 0, 130, 2, 129, 0, 2, 2, 1, 0,  // 23
            128, 1, 2, 130, 0, 129, 2, 2, 0, 0, 1, 1, 0, 0, 0, 0,  // 24
            128, 0, 1, 2, 0, 0, 1, 2, 129, 1, 2, 2, 2, 2, 2, 130,  // 25
            128, 1, 1, 0, 1, 2, 130, 1, 129, 2, 2, 1, 0, 1, 1, 0,  // 26
            128, 0, 0, 0, 0, 1, 129, 0, 1, 2, 130, 1, 1, 2, 2, 1,  // 27
            128, 0, 2, 2, 1, 1, 0, 2, 129, 1, 0, 2, 0, 0, 2, 130,  // 28
            128, 1, 1, 0, 0, 129, 1, 0, 2, 0, 0, 2, 2, 2, 2, 130,  // 29
            128, 0, 1, 1, 0, 1, 2, 2, 0, 1, 130, 2, 0, 0, 1, 129,  // 30
            128, 0, 0, 0, 2, 0, 0, 0, 130, 2, 1, 1, 2, 2, 2, 129,  // 31
            128, 0, 0, 0, 0, 0, 0, 2, 129, 1, 2, 2, 1, 2, 2, 130,  // 32
            128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 2, 0, 0, 1, 129,  // 33
            128, 0, 1, 129, 0, 0, 1, 2, 0, 0, 2, 2, 0, 2, 2, 130,  // 34
            128, 1, 2, 0, 0, 129, 2, 0, 0, 1, 130, 0, 0, 1, 2, 0,  // 35
            128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 130, 2, 0, 0, 0, 0,  // 36
            128, 1, 2, 0, 1, 2, 0, 1, 130, 0, 129, 2, 0, 1, 2, 0,  // 37
            128, 1, 2, 0, 2, 0, 1, 2, 129, 130, 0, 1, 0, 1, 2, 0,  // 38
            128, 0, 1, 1, 2, 2, 0, 0, 1, 1, 130, 2, 0, 0, 1, 129,  // 39
            128, 0, 1, 1, 1, 1, 130, 2, 2, 2, 0, 0, 0, 0, 1, 129,  // 40
            128, 1, 0, 129, 0, 1, 0, 1, 2, 2, 2, 2, 2, 2, 2, 130,  // 41
            128, 0, 0, 0, 0, 0, 0, 0, 130, 1, 2, 1, 2, 1, 2, 129,  // 42
            128, 0, 2, 2, 1, 129, 2, 2, 0, 0, 2, 2, 1, 1, 2, 130,  // 43
            128, 0, 2, 130, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 1, 129,  // 44
            128, 2, 2, 0, 1, 2, 130, 1, 0, 2, 2, 0, 1, 2, 2, 129,  // 45
            128, 1, 0, 1, 2, 2, 130, 2, 2, 2, 2, 2, 0, 1, 0, 129,  // 46
            128, 0, 0, 0, 2, 1, 2, 1, 130, 1, 2, 1, 2, 1, 2, 129,  // 47
            128, 1, 0, 129, 0, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 130,  // 48
            128, 2, 2, 130, 0, 1, 1, 1, 0, 2, 2, 2, 0, 1, 1, 129,  // 49
            128, 0, 0, 2, 1, 129, 1, 2, 0, 0, 0, 2, 1, 1, 1, 130,  // 50
            128, 0, 0, 0, 2, 129, 1, 2, 2, 1, 1, 2, 2, 1, 1, 130,  // 51
            128, 2, 2, 2, 0, 129, 1, 1, 0, 1, 1, 1, 0, 2, 2, 130,  // 52
            128, 0, 0, 2, 1, 1, 1, 2, 129, 1, 1, 2, 0, 0, 0, 130,  // 53
            128, 1, 1, 0, 0, 129, 1, 0, 0, 1, 1, 0, 2, 2, 2, 130,  // 54
            128, 0, 0, 0, 0, 0, 0, 0, 2, 1, 129, 2, 2, 1, 1, 130,  // 55
            128, 1, 1, 0, 0, 129, 1, 0, 2, 2, 2, 2, 2, 2, 2, 130,  // 56
            128, 0, 2, 2, 0, 0, 1, 1, 0, 0, 129, 1, 0, 0, 2, 130,  // 57
            128, 0, 2, 2, 1, 1, 2, 2, 129, 1, 2, 2, 0, 0, 2, 130,  // 58
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 129, 1, 130,  // 59
            128, 0, 0, 130, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 129,  // 60
            128, 2, 2, 2, 1, 2, 2, 2, 0, 2, 2, 2, 129, 2, 2, 130,  // 61
            128, 1, 0, 129, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 130,  // 62
            128, 1, 1, 129, 2, 0, 1, 1, 130, 2, 0, 1, 2, 2, 2, 0,  // 63
    };

    private CgBc7() {}

    /** The bytes of a {@code width} x {@code height} level in blocks. */
    public static int levelBytes(int width, int height) {
        return ((width + 3) >> 2) * ((height + 3) >> 2) * BLOCK_BYTES;
    }

    /** Decodes {@code blocks}, from its position, into {@code out} as RGBA8 rows from its position. Neither moves. */
    public static void decode(ByteBuffer blocks, int width, int height, ByteBuffer out) {
        ByteBuffer in = blocks.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = out.position();
        int across = (width + 3) >> 2, down = (height + 3) >> 2;
        byte[] texels = new byte[64];
        int[][] endpoints = new int[6][4];
        int[] indices = new int[16];
        Bits bits = new Bits();
        for (int by = 0; by < down; by++) {
            for (int bx = 0; bx < across; bx++) {
                int at = in.position() + (by * across + bx) * BLOCK_BYTES;
                block(bits.of(in.getLong(at), in.getLong(at + 8)), texels, endpoints, indices);
                int rows = Math.min(4, height - by * 4), columns = Math.min(4, width - bx * 4);
                for (int i = 0; i < rows; i++) {
                    int to = base + ((by * 4 + i) * width + bx * 4) * 4;
                    for (int c = 0; c < columns * 4; c++) out.put(to + c, texels[i * 16 + c]);
                }
            }
        }
    }

    /** One block's 16 texels into {@code texels}, RGBA row by row. */
    static void block(Bits bits, byte[] texels, int[][] endpoints, int[] indices) {
        int mode = 0;
        while (mode < 8 && bits.read(1) == 0) mode++;
        if (mode >= 8) {
            Arrays.fill(texels, (byte) 0);
            return;
        }

        int partition = 0, subsets = 1, rotation = 0, indexSelection = 0;
        if (mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7) {
            subsets = mode == 0 || mode == 2 ? 3 : 2;
            partition = bits.read(mode == 0 ? 4 : 6);
        }
        int count = subsets * 2;
        if (mode == 4 || mode == 5) {
            rotation = bits.read(2);
            if (mode == 4) indexSelection = bits.read(1);
        }

        for (int c = 0; c < 3; c++) for (int e = 0; e < count; e++) endpoints[e][c] = bits.read(COLOUR_BITS[mode]);
        if (ALPHA_BITS[mode] > 0) for (int e = 0; e < count; e++) endpoints[e][3] = bits.read(ALPHA_BITS[mode]);

        if (mode == 0 || mode == 1 || mode == 3 || mode == 6 || mode == 7) {
            for (int e = 0; e < count; e++) for (int c = 0; c < 4; c++) endpoints[e][c] <<= 1;
            if (mode == 1) {
                int p0 = bits.read(1), p1 = bits.read(1);
                for (int c = 0; c < 3; c++) {
                    endpoints[0][c] |= p0;
                    endpoints[1][c] |= p0;
                    endpoints[2][c] |= p1;
                    endpoints[3][c] |= p1;
                }
            } else if ((MODE_HAS_P_BITS & (1 << mode)) != 0) {
                for (int e = 0; e < count; e++) {
                    int p = bits.read(1);
                    for (int c = 0; c < 4; c++) endpoints[e][c] |= p;
                }
            }
        }

        int pBit = (MODE_HAS_P_BITS >> mode) & 1;
        for (int e = 0; e < count; e++) {
            int precision = COLOUR_BITS[mode] + pBit;
            for (int c = 0; c < 3; c++) {
                endpoints[e][c] = endpoints[e][c] << (8 - precision);
                endpoints[e][c] = endpoints[e][c] | (endpoints[e][c] >> precision);
            }
            precision = ALPHA_BITS[mode] + pBit;
            endpoints[e][3] = endpoints[e][3] << (8 - precision);
            endpoints[e][3] = endpoints[e][3] | (endpoints[e][3] >> precision);
        }
        if (ALPHA_BITS[mode] == 0) for (int e = 0; e < count; e++) endpoints[e][3] = 0xFF;

        int indexBits = mode == 0 || mode == 1 ? 3 : mode == 6 ? 4 : 2;
        int indexBits2 = mode == 4 ? 3 : mode == 5 ? 2 : 0;
        int[] weights = indexBits == 2 ? WEIGHT2 : indexBits == 3 ? WEIGHT3 : WEIGHT4;
        int[] weights2 = indexBits2 == 2 ? WEIGHT2 : WEIGHT3;

        for (int t = 0; t < 16; t++) {
            int subset = subset(subsets, partition, t);
            // A fix-up index is one bit shorter: its top bit is known to be 0.
            indices[t] = bits.read((subset & 0x80) != 0 ? indexBits - 1 : indexBits);
        }

        for (int t = 0; t < 16; t++) {
            int s = (subset(subsets, partition, t) & 0x03) * 2;
            int[] e0 = endpoints[s], e1 = endpoints[s + 1];
            int index = indices[t];
            int r, g, b, a;
            if (indexBits2 == 0) {
                r = interpolate(e0[0], e1[0], weights, index);
                g = interpolate(e0[1], e1[1], weights, index);
                b = interpolate(e0[2], e1[2], weights, index);
                a = interpolate(e0[3], e1[3], weights, index);
            } else {
                int index2 = bits.read(t != 0 ? indexBits2 : indexBits2 - 1);
                if (indexSelection == 0) {
                    r = interpolate(e0[0], e1[0], weights, index);
                    g = interpolate(e0[1], e1[1], weights, index);
                    b = interpolate(e0[2], e1[2], weights, index);
                    a = interpolate(e0[3], e1[3], weights2, index2);
                } else {
                    r = interpolate(e0[0], e1[0], weights2, index2);
                    g = interpolate(e0[1], e1[1], weights2, index2);
                    b = interpolate(e0[2], e1[2], weights2, index2);
                    a = interpolate(e0[3], e1[3], weights, index);
                }
            }
            int swap;
            switch (rotation) {
                case 1: swap = a; a = r; r = swap; break;
                case 2: swap = a; a = g; g = swap; break;
                case 3: swap = a; a = b; b = swap; break;
                default: break;
            }
            texels[t * 4] = (byte) r;
            texels[t * 4 + 1] = (byte) g;
            texels[t * 4 + 2] = (byte) b;
            texels[t * 4 + 3] = (byte) a;
        }
    }

    /** Texel {@code t}'s subset, with 128 set on a fix-up texel. */
    private static int subset(int subsets, int partition, int t) {
        if (subsets == 1) return t == 0 ? 128 : 0;
        return (subsets == 2 ? TWO : THREE)[partition * 16 + t];
    }

    private static int interpolate(int a, int b, int[] weights, int index) {
        return (a * (64 - weights[index]) + b * weights[index] + 32) >> 6;
    }

    /** A block's 128 bits, read from the lowest. */
    static final class Bits {
        private long low, high;

        Bits of(long low, long high) {
            this.low = low;
            this.high = high;
            return this;
        }

        int read(int n) {
            long mask = (1L << n) - 1;
            int bits = (int) (low & mask);
            low = (low >>> n) | ((high & mask) << (64 - n));
            high >>>= n;
            return bits;
        }
    }
}
