package com.crystalgraphics.api.canvas;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgNineSlice;
import com.crystalgraphics.api.paint.brush.CgTiling;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

public class CgCanvasTilesTest {

    private static CgTexture texture(int w, int h) {
        return new CgTexture() {
            @Override
            public CgTextureView rhiView() {
                return null;
            }

            @Override
            public int getWidth() {
                return w;
            }

            @Override
            public int getHeight() {
                return h;
            }
        };
    }

    private static List<float[]> lay(CgBrush brush, float w, float h) {
        List<float[]> pieces = new ArrayList<>();
        CgCanvasTiles.of(brush, 0, 0, w, h, 10_000, (x, y, pw, ph, u0, v0, u1, v1) -> pieces.add(new float[] {x, y, pw, ph, u0, v0, u1, v1}));
        return pieces;
    }

    private static float area(List<float[]> pieces) {
        float a = 0;
        for (float[] p : pieces) a += p[2] * p[3];
        return a;
    }

    @Test
    public void nineSliceCoversTheRectWithCornersAtTheirSize() {
        CgBrush brush = CgBrush.texture(texture(32, 32)).nineSlice(CgNineSlice.uniform(8));
        List<float[]> pieces = lay(brush, 100, 40);
        assertEquals(9, pieces.size());
        assertEquals(100 * 40, area(pieces), 1e-3);
        float[] topLeft = pieces.get(0);
        assertEquals(8, topLeft[2], 1e-6);
        assertEquals(8, topLeft[3], 1e-6);
        assertEquals(0.25f, topLeft[6], 1e-6);   // u1: 8 of 32 texels
    }

    @Test
    public void cornersShrinkTogetherWhereTheRectIsSmall() {
        CgBrush brush = CgBrush.texture(texture(32, 32)).nineSlice(CgNineSlice.uniform(8));
        List<float[]> pieces = lay(brush, 10, 40);
        assertEquals(5, pieces.get(0)[2], 1e-6);
        assertEquals(10 * 40, area(pieces), 1e-3);
    }

    @Test
    public void repeatTilesFromTheCornerAndCutsTheLast() {
        CgBrush brush = CgBrush.texture(texture(16, 16)).tiling(CgTiling.REPEAT);
        List<float[]> pieces = lay(brush, 40, 16);
        assertEquals(3, pieces.size());
        float[] last = pieces.get(2);
        assertEquals(8, last[2], 1e-6);
        assertEquals(0.5f, last[6], 1e-6);       // half the tile, half its texels
    }

    @Test
    public void roundFitsAWholeCountAndSpaceLeavesGaps() {
        List<float[]> round = lay(CgBrush.texture(texture(16, 16)).tiling(CgTiling.ROUND), 40, 16);
        assertEquals(3, round.size());           // 40 / 16 = 2.5 -> 3 tiles of 13.33
        assertEquals(40f / 3, round.get(0)[2], 1e-4);
        List<float[]> space = lay(CgBrush.texture(texture(16, 16)).tiling(CgTiling.SPACE), 40, 16);
        assertEquals(2, space.size());
        assertEquals(24, space.get(1)[0], 1e-4); // the gap pushes the second to the far edge
    }
}
