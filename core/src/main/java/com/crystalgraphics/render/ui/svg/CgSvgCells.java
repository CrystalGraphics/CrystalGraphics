package com.crystalgraphics.render.ui.svg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgStrokeCap;
import com.crystalgraphics.api.vector.CgStrokeJoin;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.ui.path.CgPathCells;
import com.crystalgraphics.render.ui.path.CgPathFlattener;
import com.crystalgraphics.render.ui.path.CgPathStroker;

/**
 * An SVG op's coverage as the cell primitive's records ({@link CgInstanceKind#CURVE}), under an affine into a raster:
 * a fill's own cells, every edge an exact area so they sum; a stroke's outline, filled. What the canvas draws into a
 * coverage target, white under {@code CgCanvasPrimitives.coverage()} or in the op's colours under
 * {@code colourCoverage()}.
 *
 * <pre>{@code
 * CgSvgCells cells = new CgSvgCells();
 * int n = cells.op(op, coloured, -1f, a, b, c, d, tx, ty);   // records written
 * recorder.draw(CgInstanceKind.CURVE, coloured ? colourCoverage : coverage, ..., cells.records(), 0, n, ...);
 * }</pre>
 *
 * <p>Holds its record arrays, grown as needed: one per thread that rasterises.</p>
 */
public final class CgSvgCells {

    private static final CgBufferLayout FORMAT = CgInstanceKind.CURVE.format();
    private static final int FLOATS = FORMAT.getFloatCount();
    private static final int P0 = FORMAT.getField("p0").getFloatOffset(), P1 = FORMAT.getField("p1").getFloatOffset();
    private static final int P2 = FORMAT.getField("p2").getFloatOffset(), WIDTHS = FORMAT.getField("widths").getFloatOffset();
    private static final int COLOR0 = FORMAT.getField("color0").getFloatOffset(), COLOR1 = FORMAT.getField("color1").getFloatOffset();
    private static final int FLAGS = FORMAT.getField("flags").getFloatOffset(), GRADIENT = FORMAT.getField("gradient").getFloatOffset();

    /** The cell primitive's flags: a cell, a ramp along its axis, every edge exact (shifted in). Equal to {@code CgCurveBatch}'s. */
    private static final int FLAG_CELL = 256, FLAG_GRADIENT = 32, ALL_EDGES = 15 << 9;

    /** A stroke's flattening tolerance in raster pixels: {@code CgPathFlattener}'s. */
    private static final float TOLERANCE = CgPathFlattener.TOLERANCE;

    private final CgPathCells strokes = new CgPathCells();
    private float[] records = new float[FLOATS * 64];
    private float[] written = records;

    /**
     * Writes op {@code op}'s cells under the affine {@code x' = a·x + c·y + tx}, {@code y' = b·x + d·y + ty} from the
     * document's units into the raster's pixels.
     *
     * @param coloured   a fill in its own colours and ramps, for {@code colourCoverage()}; otherwise white
     * @param halfWidth  a stroke's half-width in raster pixels overriding the file's; {@code <= 0} keeps the file's
     * @return how many records {@link #records()} now holds
     */
    public int op(CgSvgOps.Op op, boolean coloured, float halfWidth, float a, float b, float c, float d, float tx, float ty) {
        if (!op.fill()) {
            int n = stroke(op, halfWidth, a, b, c, d, tx, ty);
            written = strokes.records();
            return n;
        }
        float[] q = op.data();
        int n = q.length / 8;
        if (records.length < n * FLOATS) records = Arrays.copyOf(records, Math.max(n * FLOATS, records.length * 2));
        Arrays.fill(records, 0, n * FLOATS, 0f);
        int[] start = coloured ? op.colours() : null, end = coloured ? op.coloursEnd() : null;
        float[] axes = coloured ? op.gradients() : null;
        boolean ramp = start != null && end != null && axes != null;
        // A ramp's direction is a covector: it maps by the inverse transpose, its origin as a point
        float det = a * d - b * c;
        float ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS, k = i * 8;
            corner(o + P0, q[k], q[k + 1], a, b, c, d, tx, ty);
            corner(o + P1, q[k + 2], q[k + 3], a, b, c, d, tx, ty);
            corner(o + P2, q[k + 4], q[k + 5], a, b, c, d, tx, ty);
            corner(o + WIDTHS, q[k + 6], q[k + 7], a, b, c, d, tx, ty);
            int argb0 = start == null ? 0xFFFFFFFF : start[i];
            colour(o + COLOR0, argb0);
            colour(o + COLOR1, ramp ? end[i] : argb0);
            int flags = FLAG_CELL | ALL_EDGES;
            if (ramp) {
                int g = i * 4;
                float ox = axes[g], oy = axes[g + 1], dx = axes[g + 2], dy = axes[g + 3];
                records[o + GRADIENT] = a * ox + c * oy + tx;
                records[o + GRADIENT + 1] = b * ox + d * oy + ty;
                records[o + GRADIENT + 2] = ia * dx + ib * dy;
                records[o + GRADIENT + 3] = ic * dx + id * dy;
                flags |= FLAG_GRADIENT;
            }
            records[o + FLAGS] = flags;
        }
        written = records;
        return n;
    }

    /** The records the last {@link #op} wrote. */
    public float[] records() {
        return written;
    }

    /**
     * A stroke as its outline, filled: the centrelines stroked in raster pixels with round joins, so the outline is the
     * union of the round-ended segments the file's stroke is, at exact area.
     */
    private int stroke(CgSvgOps.Op op, float halfWidth, float a, float b, float c, float d, float tx, float ty) {
        List<CgPathFlattener.Contour> device = new ArrayList<>(op.contours().size());
        for (CgSvgPath.Polyline line : op.contours()) {
            List<float[]> points = new ArrayList<>(line.points().size());
            for (float[] p : line.points()) points.add(new float[]{a * p[0] + c * p[1] + tx, b * p[0] + d * p[1] + ty});
            device.add(new CgPathFlattener.Contour(points, line.closed()));
        }
        float scale = (float) Math.sqrt(Math.abs(a * d - b * c));
        float half = halfWidth > 0f ? halfWidth : op.halfWidth() * scale;
        CgStroke pen = CgStroke.of(2f * half).cap(capOf(op.cap())).join(CgStrokeJoin.ROUND);
        return strokes.fill(CgPathStroker.outline(device, pen, TOLERANCE), false, 0f, 0f);
    }

    private static CgStrokeCap capOf(int cap) {
        return cap == CgSvgStyle.CAP_ROUND ? CgStrokeCap.ROUND : cap == CgSvgStyle.CAP_SQUARE ? CgStrokeCap.SQUARE : CgStrokeCap.BUTT;
    }

    private void corner(int at, float x, float y, float a, float b, float c, float d, float tx, float ty) {
        records[at] = a * x + c * y + tx;
        records[at + 1] = b * x + d * y + ty;
    }

    /** Straight {@code 0xAARRGGBB} as the record's four floats, as {@code CgBufferWriter.colorAt} writes it. */
    private void colour(int at, int argb) {
        records[at] = ((argb >>> 16) & 255) / 255f;
        records[at + 1] = ((argb >>> 8) & 255) / 255f;
        records[at + 2] = (argb & 255) / 255f;
        records[at + 3] = (argb >>> 24) / 255f;
    }
}
