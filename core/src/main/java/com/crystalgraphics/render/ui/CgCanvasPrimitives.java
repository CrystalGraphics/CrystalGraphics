package com.crystalgraphics.render.ui;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.precache.CgPipelineList;

/**
 * The canvas's own path materials, the parts of how it draws rather than looks a caller picks: strokes and filled
 * triangles drawn by their distance, and paths rastered into coverage.
 *
 * <ul>
 *   <li>Coverage rasters: a fill's cells add their exact areas into alpha, a stroke's segments take the max, a fill with
 *       its own colours sums premultiplied colour times area (Skia's path atlas).</li>
 *   <li>Shared: never set a property on one.</li>
 * </ul>
 */
public final class CgCanvasPrimitives {

    private static final String UI = "crystalgraphics:shaders/ui/";

    private static CgMaterial curve, coverage, strokeCoverage, colourCoverage;

    private CgCanvasPrimitives() {
    }

    /** Lines, Bezier strokes, triangles and cells, alpha-blended, over what is there. */
    public static synchronized CgMaterial curve() {
        if (curve == null) {
            curve = CgMaterial.load(UI + "curve.shader",
                    CgMaterialState.builder().blend(CgBlendMode.ALPHA).twoSided().disableDepthTest().build());
            curve.precache();
        }
        return curve;
    }

    /** A fill's cells summed as exact coverage into a target cleared to transparent. */
    public static synchronized CgMaterial coverage() {
        if (coverage == null) coverage = raster("coverage.shader");
        return coverage;
    }

    /** A stroke's segments combined by max: they overlap at their joints, where a sum would bead. */
    public static synchronized CgMaterial strokeCoverage() {
        if (strokeCoverage == null) strokeCoverage = raster("coverage_max.shader");
        return strokeCoverage;
    }

    /** A fill with colours of its own: premultiplied colour times area, summed. */
    public static synchronized CgMaterial colourCoverage() {
        if (colourCoverage == null) colourCoverage = raster("coverage_colour.shader");
        return colourCoverage;
    }

    /** What the canvas draws ahead of its first frame: its curve and coverage rasters, and the canvas's own. */
    public static void precache(CgPipelineList list) {
        list.addPass(curve(), CgInstanceKind.CURVE);
        list.addPass(coverage(), CgInstanceKind.CURVE);
        list.addPass(strokeCoverage(), CgInstanceKind.CURVE);
        list.addPass(colourCoverage(), CgInstanceKind.CURVE);
        CgCanvas.precache(list);
    }

    /** Forgets every material made, the canvas's own too, at a context's teardown: the material registry frees them. */
    public static synchronized void releaseAll() {
        curve = null;
        coverage = null;
        strokeCoverage = null;
        colourCoverage = null;
        CgCanvas.releaseAll();
    }

    /** Its blend is the shader's own: none of CgBlendMode's sums alpha alone or takes a max. */
    private static CgMaterial raster(String shader) {
        CgMaterial m = CgMaterial.load(UI + shader);
        m.precache();
        return m;
    }
}
