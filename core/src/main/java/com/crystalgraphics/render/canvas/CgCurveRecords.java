package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.draw.CgInstanceKind;

/**
 * The canvas's curve record ({@link CgInstanceKind#CURVE}) and how to write one: a spline (a quadratic Bezier stroke,
 * its half-widths, colours and caps at each end) or a filled triangle, both drawn by the curve primitive's distance
 * to them. A cubic is split into quadratics first, more of them the larger it is drawn.
 *
 * <pre>{@code
 * float[] r = new float[CgCurveRecords.FLOATS];
 * int flags = CgCurveRecords.flags(CgCurveRecords.CAP_ROUND, CgCurveRecords.CAP_ROUND, false, false);
 * CgCurveRecords.write(r, 0, x0, y0, cx, cy, x1, y1, 1f, 1f, argb, argb, CgCurveRecords.ANTIALIAS, flags, clip, node, 0f);
 * }</pre>
 *
 * <ul>
 *   <li>Points are in the record's spatial node's space; widths are half-widths in the same units.</li>
 *   <li>Colours are {@code 0xAARRGGBB}, straight alpha.</li>
 * </ul>
 */
public final class CgCurveRecords {

    /** Floats a record takes. */
    public static final int FLOATS = CgInstanceKind.CURVE.floats();
    /** Quadratics a cubic splits into at most: {@link #splitCubic}'s output holds this many, 9 floats each. */
    public static final int MAX_PIECES = CgCurveSplitter.MAX_CUBIC_SEGMENTS;
    /** The edge's antialiasing feather, in the record's units at one per pixel. */
    public static final float ANTIALIAS = 1.5f;
    /** End caps: the curve primitive's codes. */
    public static final int CAP_BUTT = CgCurveBatch.CAP_BUTT, CAP_ROUND = CgCurveBatch.CAP_ROUND,
            CAP_SQUARE = CgCurveBatch.CAP_SQUARE, CAP_ARROW = CgCurveBatch.CAP_ARROW;

    private static final CgBufferLayout FORMAT = CgInstanceKind.CURVE.format();
    private static final int P0 = offset("p0"), FADE = offset("fade"), P1 = offset("p1"), P2 = offset("p2"),
            COLOR0 = offset("color0"), COLOR1 = offset("color1"), WIDTHS = offset("widths"), FEATHER = offset("feather"),
            FLAGS = offset("flags"), GRADIENT = offset("gradient");
    /** Where a record names its clip entry (an int's value) and its packed spatial and effect nodes. */
    public static final int CLIP = offset("clip"), NODE = offset("node");

    private CgCurveRecords() {
    }

    private static int offset(String field) {
        return FORMAT.getField(field).getFloatOffset();
    }

    /**
     * Splits a cubic into quadratics into {@code out}, 9 floats each (x, y, z of the start, control and end), as many
     * as keep it within tolerance drawn at {@code scale} pixels a unit; answers how many.
     */
    public static int splitCubic(float x0, float y0, float c1x, float c1y, float c2x, float c2y, float x1, float y1,
                                 float[] out, float scale) {
        return CgCurveSplitter.splitCubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1, out, scale);
    }

    /** A record's flags: its caps, and which ends join another piece of the same curve (cut hard, no cap). */
    public static int flags(int capStart, int capEnd, boolean jointStart, boolean jointEnd) {
        int flags = CgCurveSplitter.packCaps(capStart, capEnd);
        if (jointStart) flags |= CgCurveBatch.FLAG_JOINT_START;
        if (jointEnd) flags |= CgCurveBatch.FLAG_JOINT_END;
        return flags;
    }

    /** The colour {@code t} of the way from {@code from} to {@code to}, per channel. */
    public static int lerp(int from, int to, float t) {
        return CgCurveSplitter.lerpArgb(from, to, t);
    }

    /** Writes one record at {@code at}: the quadratic {@code a b c}, its half-widths and colours at each end. */
    public static void write(float[] r, int at, float ax, float ay, float bx, float by, float cx, float cy,
                             float halfStart, float halfEnd, int argbStart, int argbEnd, float feather, int flags,
                             int clip, float node, float fade) {
        r[at + P0] = ax;
        r[at + P0 + 1] = ay;
        r[at + P0 + 2] = 0f;
        r[at + FADE] = fade;
        r[at + P1] = bx;
        r[at + P1 + 1] = by;
        r[at + P1 + 2] = 0f;
        r[at + P2] = cx;
        r[at + P2 + 1] = cy;
        r[at + P2 + 2] = 0f;
        color(r, at + COLOR0, argbStart);
        color(r, at + COLOR1, argbEnd);
        r[at + WIDTHS] = halfStart;
        r[at + WIDTHS + 1] = halfEnd;
        r[at + FEATHER] = feather;
        r[at + FLAGS] = flags;
        r[at + GRADIENT] = r[at + GRADIENT + 1] = r[at + GRADIENT + 2] = r[at + GRADIENT + 3] = 0f;
        r[at + CLIP] = clip;
        r[at + NODE] = node;
    }

    /** A triangle's flags: whether it ramps, and its one antialiased edge ({@code EDGE_*}), the others hard seams. */
    public static int triangleFlags(boolean ramps, int edge) {
        return (ramps ? CgCurveBatch.FLAG_FILL | CgCurveBatch.FLAG_GRADIENT : CgCurveBatch.FLAG_FILL)
                | (edge << CgCurveBatch.FILL_EDGE_SHIFT);
    }

    /** Every edge antialiased; {@code EDGE_P1_P2} or {@code EDGE_P2_P0} antialias that edge alone. */
    public static final int EDGE_ALL = CgCurveBatch.EDGE_NONE, EDGE_P1_P2 = CgCurveBatch.EDGE_P1_P2,
            EDGE_P2_P0 = CgCurveBatch.EDGE_P2_P0;

    /**
     * Writes one filled triangle at {@code at}: its colour, or a ramp from {@code argbStart} to {@code argbEnd} where
     * {@code t = dot(p - origin, direction)} runs 0 to 1, its corners rounded by {@code radius}.
     */
    public static void writeTriangle(float[] r, int at, float ax, float ay, float bx, float by, float cx, float cy,
                                     int argbStart, int argbEnd, float radius, float feather, int flags, float originX,
                                     float originY, float directionX, float directionY, int clip, float node,
                                     float fade) {
        write(r, at, ax, ay, bx, by, cx, cy, radius, radius, argbStart, argbEnd, feather, flags, clip, node, fade);
        r[at + GRADIENT] = originX;
        r[at + GRADIENT + 1] = originY;
        r[at + GRADIENT + 2] = directionX;
        r[at + GRADIENT + 3] = directionY;
    }

    private static void color(float[] r, int o, int argb) {
        r[o] = ((argb >>> 16) & 0xFF) / 255f;
        r[o + 1] = ((argb >>> 8) & 0xFF) / 255f;
        r[o + 2] = (argb & 0xFF) / 255f;
        r[o + 3] = ((argb >>> 24) & 0xFF) / 255f;
    }
}
