package com.crystalgraphics.render.ui.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.draw.CgInstanceKind;

/**
 * A fill's cells as the cell primitive's records ({@link CgInstanceKind#CURVE}): white, each antialiasing the edges on
 * the outline, summed by the coverage material into exact area. What a path's coverage raster draws.
 *
 * <pre>{@code
 * CgPathCells cells = new CgPathCells();
 * int n = cells.fill(deviceContours, evenOdd, -originX, -originY);   // records written, placed in the raster
 * recorder.draw(CgInstanceKind.CURVE, CgCanvasPrimitives.coverage(), null, null, null, 0, cells.records(), 0, n, ...);
 * }</pre>
 *
 * <p>Holds its record array, grown as needed: one per thread that rasterises.</p>
 */
public final class CgPathCells {

    private static final CgBufferLayout FORMAT = CgInstanceKind.CURVE.format();
    private static final int FLOATS = FORMAT.getFloatCount();
    private static final int P0 = FORMAT.getField("p0").getFloatOffset(), P1 = FORMAT.getField("p1").getFloatOffset();
    private static final int P2 = FORMAT.getField("p2").getFloatOffset(), COLOR0 = FORMAT.getField("color0").getFloatOffset();
    private static final int COLOR1 = FORMAT.getField("color1").getFloatOffset();
    private static final int WIDTHS = FORMAT.getField("widths").getFloatOffset();
    private static final int FLAGS = FORMAT.getField("flags").getFloatOffset();

    /** The cell primitive's flags: a cell, its outline edges shifted in. Equal to {@code CgCurveBatch}'s. */
    private static final int FLAG_CELL = 256, EDGE_SHIFT = 9;

    private float[] records = new float[FLOATS * 64];

    /**
     * Writes the cells filling {@code contours} under the fill rule, moved by ({@code dx}, {@code dy}).
     *
     * @return how many records {@link #records()} now holds
     */
    public int fill(List<CgPathFlattener.Contour> contours, boolean evenOdd, float dx, float dy) {
        List<List<float[]>> rings = new ArrayList<>(contours.size());
        for (CgPathFlattener.Contour c : contours) rings.add(c.points());
        CgPathTriangulator.Fill fill = CgPathTriangulator.fill(rings, evenOdd, 0f, 0f);
        float[] cells = fill.cells();
        int n = fill.slice().length;
        if (records.length < n * FLOATS) records = Arrays.copyOf(records, Math.max(n * FLOATS, records.length * 2));
        Arrays.fill(records, 0, n * FLOATS, 0f);
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS, c = i * 8;
            records[o + P0] = cells[c] + dx;
            records[o + P0 + 1] = cells[c + 1] + dy;
            records[o + P1] = cells[c + 2] + dx;
            records[o + P1 + 1] = cells[c + 3] + dy;
            records[o + P2] = cells[c + 4] + dx;
            records[o + P2 + 1] = cells[c + 5] + dy;
            records[o + WIDTHS] = cells[c + 6] + dx;
            records[o + WIDTHS + 1] = cells[c + 7] + dy;
            for (int k = 0; k < 4; k++) {
                records[o + COLOR0 + k] = 1f;
                records[o + COLOR1 + k] = 1f;
            }
            records[o + FLAGS] = FLAG_CELL | (fill.edges()[i] & 15) << EDGE_SHIFT;
        }
        return n;
    }

    /** The records the last {@link #fill} wrote. */
    public float[] records() {
        return records;
    }
}
