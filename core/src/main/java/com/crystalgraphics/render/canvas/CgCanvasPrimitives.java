package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.canvas.CgCanvas;

import java.util.Arrays;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.engine.CgEngineMaterials;
import com.crystalgraphics.api.material.engine.CgPlacement;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.precache.CgPipelineList;

/**
 * The canvas's own drawing materials, the parts of how it draws rather than looks a caller picks: curves, boxes by
 * shape, and curves rastered into coverage. A caller's look is a {@link CgEngineMaterials} entry.
 *
 * <pre>{@code
 * canvas.curve(CgCanvasPrimitives.curve(CgPlacement.SCREEN)).line(x0, y0, x1, y1).width(1f).color(argb).submit();
 * canvas.quad(CgCanvasPrimitives.box()).custom2(shape).at(x, y).size(120, 10).submit();
 * canvas.cell(CgCanvasPrimitives.coverage()).points(...).submit();          // a fill's cells, into a cleared target
 * }</pre>
 *
 * <ul>
 *   <li>A box is premultiplied out; its quad names its shape with {@code custom2}, in the canvas's own table, so it
 *       draws only into a recording (a stage's canvas).</li>
 *   <li>Coverage rasters are the screen's alone: a fill's cells add their exact areas into alpha, a stroke's segments
 *       take the max, a fill with its own colours sums premultiplied colour times area (Skia's path atlas).</li>
 *   <li>Shared: never set a property on one.</li>
 * </ul>
 */
public final class CgCanvasPrimitives {

    private static final String CANVAS = "crystalgraphics:shaders/canvas/";

    private static final CgMaterial[] curves = new CgMaterial[CgPlacement.values().length];
    private static CgMaterial box, coverage, strokeCoverage, colourCoverage;

    private CgCanvasPrimitives() {
    }

    /** Lines, Bezier strokes, triangles and cells, alpha-blended, at {@code placement}. */
    public static synchronized CgMaterial curve(CgPlacement placement) {
        CgMaterial m = curves[placement.ordinal()];
        if (m == null) {
            m = CgMaterial.load(CANVAS + "curve.shader", state(CgBlendMode.ALPHA, placement))
                    .keyword("SCREEN", placement == CgPlacement.SCREEN);
            m.precache();
            curves[placement.ordinal()] = m;
        }
        return m;
    }

    /** Boxes by shape on the screen, premultiplied: {@code quad(box()).custom2(shape)}. @see CgShapeTable */
    public static synchronized CgMaterial box() {
        if (box == null) {
            box = CgMaterial.load(CANVAS + "box.shader", state(CgBlendMode.PREMULTIPLIED, CgPlacement.SCREEN));
            box.precache();
        }
        return box;
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

    /**
     * What the canvas draws ahead of its first frame: the screen's curve, box and coverage rasters, the world's curve,
     * every gradient and grid variant a screen draws, and glass in every set of its layers (its shader's permutation
     * domain: a pane's set follows its look, so each is drawn somewhere).
     */
    public static void precache(CgPipelineList list) {
        list.addPass(curve(CgPlacement.SCREEN), CgInstanceKind.CURVE);
        list.addPass(curve(CgPlacement.WORLD), CgInstanceKind.CURVE);
        list.addPass(coverage(), CgInstanceKind.CURVE);
        list.addPass(strokeCoverage(), CgInstanceKind.CURVE);
        list.addPass(colourCoverage(), CgInstanceKind.CURVE);
        list.addPass(box(), CgInstanceKind.QUAD);
        for (int v = 0; v < 4; v++) {
            list.addPass(CgEngineMaterials.GRADIENT.screen().keyword(CgCanvasGradient.WITH_MASK, (v & 1) != 0)
                    .keyword(CgCanvasGradient.RADIAL, (v & 2) != 0), CgInstanceKind.QUAD);
        }
        for (int v = 0; v < 2; v++) {
            list.addPass(CgEngineMaterials.GRID.screen().keyword(CgCanvasGrid.WITH_MASK, v != 0), CgInstanceKind.QUAD);
        }
        String[] layers = {CgCanvasGlass.WITH_REFRACTION, CgCanvasGlass.WITH_CHROMATIC, CgCanvasGlass.WITH_SPECULAR,
                CgCanvasGlass.WITH_NOISE};
        for (CgPlacement placement : CgPlacement.values()) {
            for (int set = 0; set < 1 << layers.length; set++) {
                CgMaterial glass = CgEngineMaterials.BACKDROP.material(placement);
                for (int k = 0; k < layers.length; k++) glass = glass.keyword(layers[k], (set & 1 << k) != 0);
                list.addPass(glass, CgInstanceKind.QUAD);
            }
        }
        CgCanvas.precache(list);
    }

    /** Forgets every material made, at a context's teardown: the material registry frees them. */
    public static synchronized void releaseAll() {
        Arrays.fill(curves, null);
        box = null;
        coverage = null;
        strokeCoverage = null;
        colourCoverage = null;
    }

    /** Its blend is the shader's own: none of CgBlendMode's sums alpha alone or takes a max. */
    private static CgMaterial raster(String shader) {
        CgMaterial m = CgMaterial.load(CANVAS + shader);
        m.precache();
        return m;
    }

    private static CgMaterialState state(CgBlendMode blend, CgPlacement placement) {
        CgMaterialState.Builder b = CgMaterialState.builder().blend(blend).twoSided();
        if (placement == CgPlacement.SCREEN) b.disableDepthTest();
        return b.build();
    }
}
