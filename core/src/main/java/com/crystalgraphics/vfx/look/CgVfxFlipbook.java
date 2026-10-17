package com.crystalgraphics.vfx.look;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Which frame of a sheet a sprite particle shows: Niagara's Sub UV Animation over its sprite renderer's sub-images. The
 * sub-images are a grid, frame 0 at the top left and rows going down, or a table of rectangles anywhere in the image;
 * the frame runs over the particle's life, loops at a rate, or is drawn at random.
 *
 * <pre>{@code
 * CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/sprite.shader").on(PUFFS).texture("_Sheet", SMOKE)
 *         .flipbook(CgVfxFlipbook.grid(8, 8))                     // 64 frames over each puff's life, blended
 *         .build();
 * }</pre>
 *
 * <pre>{@code
 * CgVfxFlipbook.grid(4, 4).loop(24f).randomStart()                // flames: 24 frames a second, each its own phase
 * CgVfxFlipbook.grid(4, 4).random(8f)                             // a new frame 8 times a second, never blended
 * CgVfxFlipbook.grid(8, 8).frames(16, 31)                         // rows 2 and 3 alone
 * CgVfxFlipbook.rects(0f, 0f, 0.5f, 0.25f,  0.5f, 0f, 1f, 0.25f)  // two rects: u0, v0, u1, v1 each, v down from the top
 *         .loop(30f).blend(false)                                  // CC2's billboard: rect k at age k/30 s
 * }</pre>
 *
 * <ul>
 *   <li>Each call answers a new flipbook; a flipbook is a value, shared by any number of layers.</li>
 *   <li>A rect table is a texture made at its first draw and kept for the session, one per {@link #rects} call
 *       however many flipbooks are derived from it; a server never makes it.</li>
 *   <li>The layer's shader reads {@code _SubImage}, {@code _SubUV} and {@code _SubImages}: {@code sprite} and
 *       {@code sprite_glow} do, through {@code fx_particle.glsl}'s {@code fx_subuv_frames}.</li>
 * </ul>
 */
public final class CgVfxFlipbook {

    /** How the frame is picked: Niagara's {@code ENSMSubUVAnimation_Mode}, less DirectSet. */
    public enum Mode {
        /** First to last over the particle's life. */
        LINEAR,
        /** Round the range at a number of frames a second. */
        LOOP,
        /** A whole frame of the range, drawn again at a number of times a second. */
        RANDOM
    }

    /** A rect table's texels to a row: {@code FX_SUBIMAGE_ROW}. */
    static final int TABLE_ROW = 1024;

    private static final CgTextureSpec TABLE_SPEC = CgTextureSpec.builder().type(CgTextureType.RGBA32F)
            .minFilter(CgGL.GL_NEAREST).magFilter(CgGL.GL_NEAREST).build();

    private final int columns, rows;
    private final Table table;
    private final Mode mode;
    private final int first, last;
    private final float rate;
    private final boolean blend, randomStart;

    private CgVfxFlipbook(int columns, int rows, Table table, Mode mode, int first, int last, float rate,
                          boolean blend, boolean randomStart) {
        this.columns = columns;
        this.rows = rows;
        this.table = table;
        this.mode = mode;
        this.first = first;
        this.last = last;
        this.rate = rate;
        this.blend = blend;
        this.randomStart = randomStart;
    }

    /** A grid of {@code columns} by {@code rows} frames over the whole image, played linearly and blended. */
    public static CgVfxFlipbook grid(int columns, int rows) {
        if (columns < 1 || rows < 1) throw new IllegalArgumentException("a " + columns + "x" + rows + " grid");
        return new CgVfxFlipbook(columns, rows, null, Mode.LINEAR, 0, -1, 0f, true, false);
    }

    /**
     * A frame a rect, four floats each: {@code u0, v0, u1, v1} in the image, v down from its top. Played linearly and
     * blended unless told otherwise.
     */
    public static CgVfxFlipbook rects(float... rects) {
        if (rects.length < 4 || rects.length % 4 != 0) {
            throw new IllegalArgumentException("four floats a rect, got " + rects.length);
        }
        return new CgVfxFlipbook(rects.length / 4, 0, new Table(rects.clone()), Mode.LINEAR, 0, -1, 0f, true, false);
    }

    /** First to last over each particle's life: frame {@code first} at birth, {@code last} at death. The default. */
    public CgVfxFlipbook linear() {
        return new CgVfxFlipbook(columns, rows, table, Mode.LINEAR, first, last, 0f, blend, randomStart);
    }

    /** Round the range at {@code framesPerSecond}, wrapping from the last frame to the first. */
    public CgVfxFlipbook loop(float framesPerSecond) {
        if (!(framesPerSecond > 0f)) throw new IllegalArgumentException("a loop of " + framesPerSecond + " frames a second");
        return new CgVfxFlipbook(columns, rows, table, Mode.LOOP, first, last, framesPerSecond, blend, randomStart);
    }

    /** A whole frame of the range at random, drawn again {@code changesPerSecond} times a second; 0 keeps the first. */
    public CgVfxFlipbook random(float changesPerSecond) {
        if (!(changesPerSecond >= 0f)) throw new IllegalArgumentException(changesPerSecond + " changes a second");
        return new CgVfxFlipbook(columns, rows, table, Mode.RANDOM, first, last, changesPerSecond, blend, randomStart);
    }

    /** Plays frames {@code first} to {@code last} alone, both included: Niagara's start and end frame overrides. */
    public CgVfxFlipbook frames(int first, int last) {
        if (first < 0 || last < first || last >= frames()) {
            throw new IllegalArgumentException("frames " + first + " to " + last + " of " + frames());
        }
        return new CgVfxFlipbook(columns, rows, table, mode, first, last, rate, blend, randomStart);
    }

    /** Whether a frame fades into the next as it plays: Niagara's sub UV blending. On unless set; random never blends. */
    public CgVfxFlipbook blend(boolean on) {
        return new CgVfxFlipbook(columns, rows, table, mode, first, last, rate, on, randomStart);
    }

    /** Each particle's loop starts at a frame of its own, from its seed: flames that do not flicker in step. */
    public CgVfxFlipbook randomStart() {
        return new CgVfxFlipbook(columns, rows, table, mode, first, last, rate, blend, true);
    }

    /** How many frames its grid or table holds. */
    public int frames() {
        return rows == 0 ? columns : columns * rows;
    }

    public Mode mode() {
        return mode;
    }

    /** Whether its frames are a rect table rather than a grid. */
    public boolean isTable() {
        return table != null;
    }

    /**
     * What a shader's {@code _SubImage} holds: columns (a table's frames), rows (0 for a table), blends, random start.
     * {@link CgVfxLayer.Builder#flipbook} sets it; a material of your own sets it by hand.
     */
    public float[] subImage() {
        return new float[] {columns, rows, blend ? 1f : 0f, randomStart ? 1f : 0f};
    }

    /** What a shader's {@code _SubUV} holds: mode, first frame, last frame (-1 the last), rate. */
    public float[] subUv() {
        return new float[] {mode.ordinal(), first, last, rate};
    }

    /** The rect table's texture, a rect a texel in rows of {@link #TABLE_ROW}: made at the first call, on any thread. */
    public CgTexture2D table() {
        if (table == null) throw new IllegalStateException("a grid has no table");
        return table.texture();
    }

    private static final class Table {
        private final float[] rects;
        private CgTexture2D texture;

        Table(float[] rects) {
            this.rects = rects;
        }

        synchronized CgTexture2D texture() {
            if (texture != null) return texture;
            int count = rects.length / 4;
            int width = Math.min(count, TABLE_ROW), height = (count + TABLE_ROW - 1) / TABLE_ROW;
            ByteBuffer texels = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder());
            for (float f : rects) texels.putFloat(f);
            while (texels.hasRemaining()) texels.putFloat(0f);
            texels.flip();
            texture = CgTexture2D.createFromPixels(width, height, texels, TABLE_SPEC);
            return texture;
        }
    }
}
