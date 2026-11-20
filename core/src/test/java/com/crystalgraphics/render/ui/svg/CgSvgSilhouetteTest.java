package com.crystalgraphics.render.ui.svg;

import com.crystalgraphics.render.ui.path.CgPathTriangulator;

import com.crystalgraphics.render.ui.path.CgCurveRecords;

import org.junit.Test;

import java.util.List;

import static com.crystalgraphics.render.ui.path.CgPathTriangulator.BOTTOM;
import static com.crystalgraphics.render.ui.path.CgPathTriangulator.LEFT;
import static com.crystalgraphics.render.ui.path.CgPathTriangulator.RIGHT;
import static com.crystalgraphics.render.ui.path.CgPathTriangulator.TOP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Which cell edges are on the outline, on the one shape that has every case: an L, whose upper cell has
 * a bottom that is half seam and half silhouette.
 */
public class CgSvgSilhouetteTest {

    private static final List<List<float[]>> L = List.of(List.of(
            new float[]{0, 0}, new float[]{4, 0}, new float[]{4, 2},
            new float[]{2, 2}, new float[]{2, 4}, new float[]{0, 4}));

    @Test
    public void aRectangleIsOneCellWithEveryEdgeOnTheOutline() {
        CgPathTriangulator.Fill fill = CgPathTriangulator.fill(List.of(List.of(
                new float[]{0, 0}, new float[]{3, 0}, new float[]{3, 2}, new float[]{0, 2})), false, 0f, 0f);
        assertEquals(1, fill.count());
        assertEquals(TOP | RIGHT | BOTTOM | LEFT, fill.edges()[0]);
    }

    @Test
    public void aStepSplitsTheCellWhereTheShapeStopsContinuing() {
        CgPathTriangulator.Fill fill = CgPathTriangulator.fill(L, false, 0f, 0f);
        assertEquals("upper band in two pieces, lower band in one", 3, fill.count());

        // Upper band, left piece: sits on the lower band, so its bottom is a seam.
        assertArrayEquals(new float[]{0, 0, 2, 0, 2, 2, 0, 2}, cell(fill, 0), 0f);
        assertEquals(TOP | LEFT, fill.edges()[0]);
        // Upper band, right piece: nothing below it.
        assertArrayEquals(new float[]{2, 0, 4, 0, 4, 2, 2, 2}, cell(fill, 1), 0f);
        assertEquals(TOP | RIGHT | BOTTOM, fill.edges()[1]);
        // Lower band: its top is the seam.
        assertArrayEquals(new float[]{0, 2, 2, 2, 2, 4, 0, 4}, cell(fill, 2), 0f);
        assertEquals(RIGHT | BOTTOM | LEFT, fill.edges()[2]);
    }

    /**
     * The mask travels from the tessellator into {@code CgCurveRecords.CELL_*} untranslated,
     * and the tessellator may not import the renderer to say so — this is where the two are held equal.
     */
    @Test
    public void theEdgeBitsAreTheRenderersEdgeBits() {
        assertEquals(CgCurveRecords.CELL_TOP, TOP);
        assertEquals(CgCurveRecords.CELL_RIGHT, RIGHT);
        assertEquals(CgCurveRecords.CELL_BOTTOM, BOTTOM);
        assertEquals(CgCurveRecords.CELL_LEFT, LEFT);
    }

    @Test
    public void thePiecesOfASplitCellKeepItsSliceTag() {
        CgPathTriangulator.Fill fill = CgPathTriangulator.fill(L, false, 0f, 0f);
        assertEquals(fill.slice()[0], fill.slice()[1]);
    }

    private static float[] cell(CgPathTriangulator.Fill fill, int index) {
        float[] out = new float[8];
        System.arraycopy(fill.cells(), index * 8, out, 0, 8);
        return out;
    }
}
