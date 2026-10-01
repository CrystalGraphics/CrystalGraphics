package com.crystalgraphics.render.property;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** What a palette places under a pass's view: a node's values move it, and a layer's owner carries its content. */
public class CgPaletteTest {

    private static final float[] BOX = {0f, 0f, 100f, 50f};

    @Test
    public void aRootScissorMovesWithItsNode() {
        CgSpatialTree tree = new CgSpatialTree();
        int scroll = tree.add(0, 10f, 20f, true);
        CgPropertyValues values = new CgPropertyValues();
        CgPalette palette = palette(tree, values);
        int[] rect = new int[4];

        palette.pass(0, 0f, 0f, 600f);
        palette.scissorOf(scroll, BOX, 0, rect);
        assertArrayEquals(new int[]{10, 530, 100, 50}, rect);

        values.translate(scroll, 0f, -30f);
        palette.pass(0, 0f, 0f, 600f);
        palette.scissorOf(scroll, BOX, 0, rect);
        assertArrayEquals(new int[]{10, 560, 100, 50}, rect);
    }

    /** A window's layer holds its content where it was drawn however the window moves; a scroll inside still moves. */
    @Test
    public void aLayerOwnerCarriesItsContent() {
        CgSpatialTree tree = new CgSpatialTree();
        int window = tree.add(0, 100f, 50f, true);
        int scroll = tree.add(window, 5f, 5f, true);
        CgPropertyValues values = new CgPropertyValues();
        CgPalette palette = palette(tree, values);
        float[] box = {0f, 0f, 20f, 10f};
        int[] rect = new int[4];

        palette.pass(window, 100f, 50f, 200f);
        palette.scissorOf(scroll, box, 0, rect);
        assertArrayEquals(new int[]{5, 185, 20, 10}, rect);

        values.translate(window, 300f, 0f);
        palette.pass(window, 100f, 50f, 200f);
        palette.scissorOf(scroll, box, 0, rect);
        assertArrayEquals(new int[]{5, 185, 20, 10}, rect);

        values.translate(scroll, 0f, -5f);
        palette.pass(window, 100f, 50f, 200f);
        palette.scissorOf(scroll, box, 0, rect);
        assertArrayEquals(new int[]{5, 190, 20, 10}, rect);
    }

    @Test
    public void aScaledNodeScissorsItsBounds() {
        CgSpatialTree tree = new CgSpatialTree();
        int zoom = tree.add(0, 2f, 0f, 0f, 2f, 10f, 10f, true);
        CgPalette palette = palette(tree, new CgPropertyValues());
        int[] rect = new int[4];

        palette.pass(0, 0f, 0f, 400f);
        palette.scissorOf(zoom, BOX, 0, rect);
        assertArrayEquals(new int[]{10, 290, 200, 100}, rect);
    }

    @Test
    public void packKeepsBothNodesExactly() {
        int[][] pairs = {{0, 0}, {1, 0}, {0, 1}, {4095, 4095}, {123, 4000}};
        for (int[] pair : pairs) {
            int packed = (int) CgPalette.pack(pair[0], pair[1]);
            assertEquals(pair[0], packed & 4095);
            assertEquals(pair[1], packed >> 12);
        }
    }

    private static CgPalette palette(CgSpatialTree tree, CgPropertyValues values) {
        CgPalette palette = new CgPalette();
        palette.copyFrom(tree, new CgEffectTree(), values);
        return palette;
    }
}
