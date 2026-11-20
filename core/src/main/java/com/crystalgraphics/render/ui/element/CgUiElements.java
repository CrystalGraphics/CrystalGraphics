package com.crystalgraphics.render.ui.element;

import com.crystalgraphics.gpu.resource.CgBufferLifetime;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * The UI canvas's element record ({@link CgInstanceKind#ELEMENT}) and how to write one: a rect in its spatial node's
 * space, filled by colour, image, mask, gradient or a box shadow, rounded by elliptical radii and bordered per side.
 * Drawn by vertex pulling, six vertices a record, through {@code canvas/primitives/Element.primitive}.
 *
 * <pre>{@code
 * float[] r = new float[CgUiElements.FLOATS];
 * CgUiElements.rect(r, 0, x0, y0, x1, y1);
 * CgUiElements.fill(r, 0, CgUiElements.FILL_COLOR, 0xFF2060C0, 0);
 * CgUiElements.radii(r, 0, 6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f);
 * chunk.instances(r, 0, 1);
 * }</pre>
 *
 * <ul>
 *   <li>A zeroed record is a transparent rect at the origin: every field reads its neutral value at zero (opacity is
 *       stored as its fade, intensity as its boost over 1).</li>
 *   <li>Colours are {@code 0xAARRGGBB}, straight alpha.</li>
 *   <li>A draw holds at most {@link #MAX_PER_DRAW} records.</li>
 * </ul>
 */
public final class CgUiElements {

    /** The macro a shader reads a record through; the {@code element} engine buffer declares it. */
    public static final String MACRO_NAME = "ELEMENT_DATA";
    /** Where its shaders read it. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("element");
    /** Floats a record takes. */
    public static final int FLOATS = CgInstanceKind.ELEMENT.floats();
    /** Vertices a record draws: two triangles. */
    public static final int VERTICES = 6;
    /** Records one batch holds: what the shared vertex range covers. The range has no data, so it costs nothing. */
    public static final int MAX_PER_DRAW = 1 << 20;

    /** Fill kinds: {@code element.glsl}'s {@code CG_ELEMENT_*}. */
    public static final int FILL_COLOR = 0, FILL_TEXTURE = 1, FILL_MASK = 2, FILL_GRADIENT = 3, FILL_SHADOW = 4,
            FILL_INSET_SHADOW = 5, FILL_GRID = 6;
    /** Flags above the kind. */
    public static final int FLAG_ROUNDED = 16, FLAG_BORDER = 32, FLAG_PREMULTIPLIED = 64, FLAG_CHECKER = 128;
    /** A path's element: its coverage is the texture's alpha at {@code uv}, its paint mapped by {@link #paintFrame}. */
    public static final int FLAG_COVERAGE = 256;
    /**
     * Sides with no edge ramp and no pad, where the element meets another of the same brush: the rasterizer gives a
     * pixel on the shared edge to one of the two, so a seam inside a pixel is covered once. Left, top, right, bottom.
     */
    public static final int FLAG_HARD_LEFT = 512, FLAG_HARD_TOP = 1024, FLAG_HARD_RIGHT = 2048, FLAG_HARD_BOTTOM = 4096;
    /** An image or mask from a layer of a 2D array, {@code _MainArray} at {@link #ARRAY_SLOT}; the layer in {@code PAINT}. */
    public static final int FLAG_ARRAY = 8192;
    /** The primitive's texture slots: an image's, and a 2D array's. */
    public static final int IMAGE_SLOT = 0, ARRAY_SLOT = 1;

    /** Float offsets of the record's fields: std430, as {@link CgInstanceKind#ELEMENT}'s format lays them out. */
    public static final int RECT = 0, UV = 4, RADII = 8, BORDER_COLOR = 12, WIDTHS_TR = 16, WIDTHS_BL = 17, COLOR = 18,
            FLAGS = 19, NODE = 20, CLIP = 21, FADE_BOOST = 22, PAINT = 23;

    private CgUiElements() {
    }

    /** The {@code element} engine buffer's declaration: the frame's records are bound where it is. */
    public static CgShaderBuffer buffer() {
        return Holder.BUFFER;
    }

    private static final class Holder {
        static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get().getOrCreateInternal("CgUiElements",
                CgInstanceKind.ELEMENT.format(), SLOT, CgBufferLifetime.FRAME);
    }

    /** The rect, corners in its node's space. */
    public static void rect(float[] r, int at, float x0, float y0, float x1, float y1) {
        r[at + RECT] = x0;
        r[at + RECT + 1] = y0;
        r[at + RECT + 2] = x1;
        r[at + RECT + 3] = y1;
    }

    /**
     * The texture's region drawn over the rect; for an inset shadow the hole, in the rect's units from its corner;
     * for a grid its cell width and height and its line width and height.
     */
    public static void uv(float[] r, int at, float u0, float v0, float u1, float v1) {
        r[at + UV] = u0;
        r[at + UV + 1] = v0;
        r[at + UV + 2] = u1;
        r[at + UV + 3] = v1;
    }

    /**
     * How it is filled: {@code kind} one of {@code FILL_*}, {@code argb} its colour (a texture's tint), {@code paint} its
     * gradient's index in the recording's table, a shadow's sigma, or a grid's second colour as its bits.
     */
    public static void fill(float[] r, int at, int kind, int argb, float paint) {
        int flags = Float.floatToRawIntBits(r[at + FLAGS]) & ~15;
        r[at + FLAGS] = Float.intBitsToFloat(flags | kind);
        r[at + COLOR] = Float.intBitsToFloat(argb);
        r[at + PAINT] = kind == FILL_GRADIENT ? Float.intBitsToFloat((int) paint) : paint;
    }

    /** The image or mask fill at {@code at} read from layer {@code layer} of a 2D array. */
    public static void layer(float[] r, int at, int layer) {
        flag(r, at, FLAG_ARRAY, true);
        r[at + PAINT] = Float.intBitsToFloat(layer);
    }

    /** The fill kind of the record at {@code at}: one of {@code FILL_*}. */
    public static int kind(float[] r, int at) {
        return Float.floatToRawIntBits(r[at + FLAGS]) & 15;
    }

    /** Sets or clears flag bits. */
    public static void flag(float[] r, int at, int flag, boolean on) {
        int flags = Float.floatToRawIntBits(r[at + FLAGS]);
        r[at + FLAGS] = Float.intBitsToFloat(on ? flags | flag : flags & ~flag);
    }

    /** Its corners' radii, horizontal and vertical, in CSS order: top-left, top-right, bottom-right, bottom-left. */
    public static void radii(float[] r, int at, float tlx, float tly, float trx, float try_, float brx, float bry,
                             float blx, float bly) {
        r[at + RADII] = Float.intBitsToFloat(half2(tlx, tly));
        r[at + RADII + 1] = Float.intBitsToFloat(half2(trx, try_));
        r[at + RADII + 2] = Float.intBitsToFloat(half2(brx, bry));
        r[at + RADII + 3] = Float.intBitsToFloat(half2(blx, bly));
        flag(r, at, FLAG_ROUNDED, tlx > 0 || tly > 0 || trx > 0 || try_ > 0 || brx > 0 || bry > 0 || blx > 0 || bly > 0);
    }

    /**
     * A path element's paint frame, in the radii's place: the paint's 0 to 1 coordinate is
     * {@code (m00, m10) * x + (m01, m11) * y + (tx, ty)} of a pixel {@code (x, y)} from the element's corner, over a box
     * {@code width x height} (what a gradient's angle and extents are taken against). Sets {@link #FLAG_COVERAGE}.
     */
    public static void paintFrame(float[] r, int at, float m00, float m10, float m01, float m11, float tx, float ty,
                                  float width, float height) {
        r[at + RADII] = Float.intBitsToFloat(half2(m00, m10));
        r[at + RADII + 1] = Float.intBitsToFloat(half2(m01, m11));
        r[at + RADII + 2] = Float.intBitsToFloat(half2(tx, ty));
        r[at + RADII + 3] = Float.intBitsToFloat(half2(width, height));
        flag(r, at, FLAG_ROUNDED, false);
        flag(r, at, FLAG_COVERAGE, true);
    }

    /** A border, widths and colours per side: top, right, bottom, left. */
    public static void border(float[] r, int at, float top, float right, float bottom, float left,
                              int topArgb, int rightArgb, int bottomArgb, int leftArgb) {
        r[at + WIDTHS_TR] = Float.intBitsToFloat(half2(top, right));
        r[at + WIDTHS_BL] = Float.intBitsToFloat(half2(bottom, left));
        r[at + BORDER_COLOR] = Float.intBitsToFloat(topArgb);
        r[at + BORDER_COLOR + 1] = Float.intBitsToFloat(rightArgb);
        r[at + BORDER_COLOR + 2] = Float.intBitsToFloat(bottomArgb);
        r[at + BORDER_COLOR + 3] = Float.intBitsToFloat(leftArgb);
        flag(r, at, FLAG_BORDER, top > 0 || right > 0 || bottom > 0 || left > 0);
    }

    /** Its spatial and effect nodes, {@code CgPalette.pack}'d, and its clip-table entry (0 for none). */
    public static void nodes(float[] r, int at, float node, int clip) {
        r[at + NODE] = node;
        r[at + CLIP] = Float.intBitsToFloat(clip);
    }

    /** Its opacity, 0..1, and HDR intensity, 1 for none. */
    public static void opacity(float[] r, int at, float opacity, float intensity) {
        r[at + FADE_BOOST] = Float.intBitsToFloat(half2(1f - opacity, intensity - 1f));
    }

    /** Two floats as IEEE half precision, x in the low 16 bits: GLSL's {@code packHalf2x16}. */
    public static int half2(float x, float y) {
        return (half(y) << 16) | half(x);
    }

    /** A float as IEEE half precision, rounded to nearest even; past its range, infinity. */
    static int half(float f) {
        int bits = Float.floatToRawIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int biased = (bits >>> 23) & 0xff;
        int mant = bits & 0x7fffff;
        if (biased == 0xff) return sign | 0x7c00 | (mant != 0 ? 0x200 : 0);
        int exp = biased - 127 + 15;
        if (exp >= 31) return sign | 0x7c00;
        int h, rem, halfway;
        if (exp <= 0) {
            if (exp < -10) return sign;
            mant |= 0x800000;
            int shift = 14 - exp;
            h = mant >>> shift;
            rem = mant & ((1 << shift) - 1);
            halfway = 1 << (shift - 1);
        } else {
            h = (exp << 10) | (mant >>> 13);
            rem = mant & 0x1fff;
            halfway = 0x1000;
        }
        // A carry out of the mantissa raises the exponent, which is the right result
        if (rem > halfway || (rem == halfway && (h & 1) != 0)) h++;
        return sign | h;
    }
}
