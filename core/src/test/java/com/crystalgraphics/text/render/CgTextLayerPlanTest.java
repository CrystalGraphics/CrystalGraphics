package com.crystalgraphics.text.render;

import com.crystalgraphics.text.style.CgTextLayer;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static com.crystalgraphics.text.render.CgTextBatch.STEP_OVER;
import static com.crystalgraphics.text.render.CgTextBatch.STEP_UNDER;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The order a style's layers paint in, where its decorations go, and which outlines are rings. */
public class CgTextLayerPlanTest {

    private static final CgTextLayer SHADOW = CgTextLayer.shadow("shadow").offset(2f, 2f).paint(0x80000000).build();
    private static final CgTextLayer OUTLINE = CgTextLayer.outline("outline").width(2f).paint(0xFF000000).build();
    private static final CgTextLayer FILL = CgTextLayer.fill("fill").build();

    @Test
    public void slatesOrderDecoratesUnderTheOutlineAndOverTheFill() {
        assertArrayEquals(new int[]{0, STEP_UNDER, 1, 2, STEP_OVER}, steps(SHADOW, OUTLINE, FILL));
    }

    @Test
    public void aStrokeOverItsFillComesAfterTheLineThrough() {
        assertArrayEquals(new int[]{0, STEP_UNDER, 1, STEP_OVER, 2}, steps(SHADOW, FILL, OUTLINE));
    }

    @Test
    public void hollowTextDecoratesAroundItsOutline() {
        assertArrayEquals(new int[]{STEP_UNDER, 0, STEP_OVER}, steps(OUTLINE));
    }

    @Test
    public void shadowsAloneStillDrawTheirDecorationsOverThem() {
        assertArrayEquals(new int[]{0, STEP_UNDER, STEP_OVER}, steps(SHADOW));
    }

    @Test
    public void anOutlineUnderAFillIsTheGrownGlyphAndOverItTheRing() {
        assertFalse("Slate's: the fill covers its inside", CgTextBatch.ring(List.of(SHADOW, OUTLINE, FILL), 1));
        assertTrue("CSS's stroke over its fill", CgTextBatch.ring(List.of(FILL, OUTLINE), 1));
        assertTrue("nothing fills it", CgTextBatch.ring(List.of(OUTLINE), 0));
    }

    private static int[] steps(CgTextLayer... layers) {
        int[] out = new int[layers.length + 2];
        return Arrays.copyOf(out, CgTextBatch.steps(List.of(layers), out));
    }
}
