package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.world.CgPipelineList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The canvas's default materials, so a HUD, a marker or a whole UI needs no shader of its own: an image, a mask, either
 * from a layer of a 2D array, a curve, a box (rounded, bordered, textured or nine-sliced, by {@link CgShapeTable}), and
 * curves rastered into a texture as exact coverage.
 * Gradients, grids and glass are {@link CgCanvasGradient}, {@link CgCanvasGrid} and {@link CgCanvasGlass}. Unreal's simple element shader: one look
 * per kind of item, varied by blend mode.
 *
 * <pre>{@code
 * CgCanvas canvas = frame.canvas();
 * canvas.quad(CgCanvasMaterials.SCREEN.image()).texture(0, icon).at(x, y).size(32, 32).submit();
 * canvas.quad(CgCanvasMaterials.SCREEN.image()).at(x, y).size(w, h).color(0xC0101418).submit();   // a filled box
 * canvas.quad(CgCanvasMaterials.SCREEN.imageArray(CgBlendMode.ALPHA)).texture(0, digits).atlasLayer(d)
 *         .at(x, y).size(w, h).submit();
 * canvas.quad(CgCanvasMaterials.SCREEN.maskArray(CgBlendMode.ADDITIVE)).texture(0, digits).atlasLayer(d)
 *         .at(x, y).size(w, h).color(0xFFFFE080).submit();                                          // a glow of its shape
 * canvas.curve(CgCanvasMaterials.SCREEN.curve()).line(x0, y0, x1, y1).width(1f).color(argb).submit();
 *
 * int bar = canvas.shapes().begin(CgShapeTable.FLAT, 120, 10).radii(5, 5, 5, 5, 5, 5, 5, 5)   // a rounded health bar
 *         .border(1, 1, 1, 1, 0xFF000000).fill(0xFF3FBF4F).end();
 * canvas.quad(CgCanvasMaterials.SCREEN.box()).custom2(bar).at(x, y).size(120, 10).submit();
 *
 * frame.canvas().quad(CgCanvasMaterials.WORLD.image()).world(x, y, z).pose(facing)   // depth-tested, in the world
 *         .at(-0.5f, -0.5f).size(1f, 1f).texture(0, icon).submit();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #SCREEN} draws over whatever is there, in submit order: GUI stages and immediate canvases.
 *       {@link #WORLD} is tested against the scene's depth and writes none: world stages.</li>
 *   <li>An image is its texture times the item's colour; a mask tints the item's colour by the texture's alpha alone.
 *       With no texture bound either draws the colour alone.</li>
 *   <li>Blends: {@code ALPHA}, {@code PREMULTIPLIED} (the texture premultiplied), {@code ADDITIVE} (weighed by alpha),
 *       {@code MODULATE}, {@code SUBTRACT}. Not {@code OPAQUE}: an image at full alpha under {@code ALPHA} covers.</li>
 *   <li>A box is premultiplied out, under {@code PREMULTIPLIED}; its quad names its shape with {@code custom2}, in the
 *       canvas's own table, so it draws only into a recording (a stage's canvas). With no shape it is
 *       {@link CgShapeTable#PLAIN}: the texture times the colour.</li>
 *   <li>Each is one shared instance: never set a property on it. A caller that does (a texture it keeps) makes its
 *       own with {@link #newBox()} or {@link #newCurve()}. A fade is per item, never a property: {@code opacity(f)}
 *       on the quad or curve, which every material here but the coverage rasters reads.</li>
 *   <li>On the screen every item antialiases its own edges when rotated, and an image filters its texels; in the
 *       world the scene's own antialiasing does. A screen curve takes each fragment's point from the window position,
 *       exact for a 2D camera, so a fill tessellated into cells partitions its pixels.</li>
 *   <li>Edges antialias over one unit of the canvas's space: a window pixel on {@code frame.canvas()}, softer by the
 *       scale on {@code guiScaledCanvas()}.</li>
 *   <li>Each is made at its first ask and precached then; the screen alpha and additive ones, the screen box, curve,
 *       gradients and grids are built at load.</li>
 * </ul>
 */
public final class CgCanvasMaterials {

    public static final String IMAGE_SHADER = "crystalgraphics:shaders/canvas/image.shader";
    public static final String IMAGE_ARRAY_SHADER = "crystalgraphics:shaders/canvas/image_array.shader";
    public static final String CURVE_SHADER = "crystalgraphics:shaders/canvas/curve.shader";
    public static final String BOX_SHADER = "crystalgraphics:shaders/canvas/box.shader";
    public static final String GRADIENT_SHADER = "crystalgraphics:shaders/canvas/gradient.shader";
    public static final String GRID_SHADER = "crystalgraphics:shaders/canvas/grid.shader";
    public static final String GLASS_SHADER = "crystalgraphics:shaders/canvas/glass.shader";
    public static final String COVERAGE_SHADER = "crystalgraphics:shaders/canvas/coverage.shader";
    public static final String STROKE_COVERAGE_SHADER = "crystalgraphics:shaders/canvas/coverage_max.shader";
    public static final String COLOUR_COVERAGE_SHADER = "crystalgraphics:shaders/canvas/coverage_colour.shader";

    // Before SCREEN and WORLD: their constructors size by them.
    private static final CgBlendMode[] BLENDS = CgBlendMode.values();
    private static final int IMAGE = 0, MASK = 1, IMAGE_ARRAY = 2, MASK_ARRAY = 3, KINDS = 4;

    /** Over whatever is there: a GUI stage, an immediate canvas on the screen. */
    public static final CgCanvasMaterials SCREEN = new CgCanvasMaterials(true);
    /** Tested against the scene's depth, writing none: a world stage. */
    public static final CgCanvasMaterials WORLD = new CgCanvasMaterials(false);

    private final boolean screen;
    private final CgMaterial[] made = new CgMaterial[KINDS * BLENDS.length];
    private CgMaterial curve, box, coverage, strokeCoverage, colourCoverage;
    /** The gradient and grid variants {@link #precache} keeps built; theirs, since a pipeline is a material's. */
    private final List<CgMaterial> probes = new ArrayList<>(), glassProbes = new ArrayList<>();

    private CgCanvasMaterials(boolean screen) {
        this.screen = screen;
    }

    /** The image under {@code ALPHA}: a texture, or with none the item's colour, over what is behind. */
    public CgMaterial image() {
        return image(CgBlendMode.ALPHA);
    }

    /** The item's texture times its colour, under {@code blend}. */
    public CgMaterial image(CgBlendMode blend) {
        return get(IMAGE, blend);
    }

    /** The item's colour, its alpha times the texture's: a silhouette in any colour. */
    public CgMaterial mask(CgBlendMode blend) {
        return get(MASK, blend);
    }

    /** {@link #image(CgBlendMode)} from the layer of a 2D array the item's {@code atlasLayer} names. */
    public CgMaterial imageArray(CgBlendMode blend) {
        return get(IMAGE_ARRAY, blend);
    }

    /** {@link #mask(CgBlendMode)} from the layer of a 2D array the item's {@code atlasLayer} names. */
    public CgMaterial maskArray(CgBlendMode blend) {
        return get(MASK_ARRAY, blend);
    }

    /** Lines, Bezier strokes, triangles and cells, alpha-blended: {@code canvas.curve(...)}, {@code triangle}, {@code cell}. */
    public synchronized CgMaterial curve() {
        if (curve == null) {
            curve = CgMaterial.load(CURVE_SHADER, state(CgBlendMode.ALPHA)).keyword("SCREEN", screen);
            curve.precache();
        }
        return curve;
    }

    /**
     * Curves rastered into a texture as coverage, on the screen's canvas only: a fill's cells add their exact areas
     * into alpha, (1, 1, 1, coverage), so a tessellated shape is exact and the texture draws later as a tinted image
     * (Skia's path atlas). Draw into a target cleared to transparent.
     *
     * <pre>{@code
     * canvas.cell(CgCanvasMaterials.SCREEN.coverage()).points(...).submit();          // a fill's cells
     * canvas.curve(CgCanvasMaterials.SCREEN.strokeCoverage()).line(...).submit();   // a stroke's segments
     * canvas.cell(CgCanvasMaterials.SCREEN.colourCoverage()).points(...).submit();    // a fill with colours of its own
     * }</pre>
     */
    public synchronized CgMaterial coverage() {
        if (coverage == null) coverage = rasterMaterial(COVERAGE_SHADER);
        return coverage;
    }

    /** {@link #coverage()} combined by max: a stroke's segments overlap at their joints, where a sum would bead. */
    public synchronized CgMaterial strokeCoverage() {
        if (strokeCoverage == null) strokeCoverage = rasterMaterial(STROKE_COVERAGE_SHADER);
        return strokeCoverage;
    }

    /** {@link #coverage()} for a fill with colours of its own: premultiplied colour times area, summed. */
    public synchronized CgMaterial colourCoverage() {
        if (colourCoverage == null) colourCoverage = rasterMaterial(COLOUR_COVERAGE_SHADER);
        return colourCoverage;
    }

    /** Its blend is the shader's own: none of CgBlendMode's sums alpha alone or takes a max. */
    private CgMaterial rasterMaterial(String shader) {
        if (!screen) throw new IllegalStateException("coverage rasters on the screen's canvas: CgCanvasMaterials.SCREEN");
        CgMaterial m = CgMaterial.load(shader);
        m.precache();
        return m;
    }

    /** Boxes by shape, premultiplied: {@code quad(box()).custom2(shape)}. @see CgShapeTable */
    public synchronized CgMaterial box() {
        if (box == null) {
            box = CgMaterial.load(BOX_SHADER, state(CgBlendMode.PREMULTIPLIED));
            box.precache();
        }
        return box;
    }

    /** Gradients' material with no keyword on: {@link CgCanvasGradient}'s variants are of it. */
    CgMaterial gradient() {
        return CgMaterial.load(GRADIENT_SHADER, state(CgBlendMode.PREMULTIPLIED));
    }

    /** Grids' material with no keyword on. */
    CgMaterial grid() {
        return CgMaterial.load(GRID_SHADER, state(CgBlendMode.PREMULTIPLIED));
    }

    /** Glass's material with no layer on. */
    CgMaterial glass() {
        return CgMaterial.load(GLASS_SHADER, state(CgBlendMode.ALPHA));
    }

    private synchronized CgMaterial get(int kind, CgBlendMode blend) {
        if (blend == CgBlendMode.OPAQUE) {
            throw new IllegalArgumentException("canvas materials blend: an image at full alpha under ALPHA covers");
        }
        int at = kind * BLENDS.length + blend.ordinal();
        CgMaterial m = made[at];
        if (m == null) {
            boolean array = kind == IMAGE_ARRAY || kind == MASK_ARRAY;
            m = CgMaterial.load(array ? IMAGE_ARRAY_SHADER : IMAGE_SHADER, state(blend))
                    .keyword("MASK", kind == MASK || kind == MASK_ARRAY).keyword("SCREEN", screen);
            m.precache();
            made[at] = m;
        }
        return m;
    }

    private CgMaterialState state(CgBlendMode blend) {
        CgMaterialState.Builder b = CgMaterialState.builder().blend(blend).twoSided();
        if (screen) b.disableDepthTest();
        return b.build();
    }

    /** The screen's alpha and additive images and masks, its box, gradients and grids, and both curves. */
    public static void precache(CgPipelineList list) {
        for (CgBlendMode blend : new CgBlendMode[] {CgBlendMode.ALPHA, CgBlendMode.ADDITIVE}) {
            list.addPass(SCREEN.image(blend), CgInstanceKind.QUAD);
            list.addPass(SCREEN.mask(blend), CgInstanceKind.QUAD);
            list.addPass(SCREEN.imageArray(blend), CgInstanceKind.QUAD);
            list.addPass(SCREEN.maskArray(blend), CgInstanceKind.QUAD);
        }
        list.addPass(SCREEN.curve(), CgInstanceKind.CURVE);
        list.addPass(WORLD.curve(), CgInstanceKind.CURVE);
        list.addPass(SCREEN.coverage(), CgInstanceKind.CURVE);
        list.addPass(SCREEN.strokeCoverage(), CgInstanceKind.CURVE);
        list.addPass(SCREEN.colourCoverage(), CgInstanceKind.CURVE);
        list.addPass(SCREEN.box(), CgInstanceKind.QUAD);
        for (CgMaterial probe : SCREEN.probes()) list.addPass(probe, CgInstanceKind.QUAD);
        for (CgMaterial glass : WORLD.glassProbes()) list.addPass(glass, CgInstanceKind.QUAD);
    }

    /** Every gradient and grid variant a screen draws, mask or none, linear or radial, and every set of glass. */
    private synchronized List<CgMaterial> probes() {
        if (probes.isEmpty()) {
            for (int v = 0; v < 4; v++) {
                probes.add(gradient().keyword(CgCanvasGradient.WITH_MASK, (v & 1) != 0)
                        .keyword(CgCanvasGradient.RADIAL, (v & 2) != 0));
            }
            for (int v = 0; v < 2; v++) {
                probes.add(grid().keyword(CgCanvasGrid.WITH_MASK, v != 0));
            }
            probes.addAll(glassProbes());
        }
        return probes;
    }

    /**
     * Glass in every set of its layers, its shader's permutation domain: a pane's set follows its look (frost with noise
     * alone is CrystalGUI's desktop), so each is drawn somewhere. Made on the glass material, whose blend is a program of
     * its own.
     */
    private synchronized List<CgMaterial> glassProbes() {
        if (glassProbes.isEmpty()) {
            String[] layers = {CgCanvasGlass.WITH_REFRACTION, CgCanvasGlass.WITH_CHROMATIC, CgCanvasGlass.WITH_SPECULAR,
                    CgCanvasGlass.WITH_NOISE};
            for (int set = 0; set < 1 << layers.length; set++) {
                CgMaterial glass = glass();
                for (int k = 0; k < layers.length; k++) glass = glass.keyword(layers[k], (set & 1 << k) != 0);
                glassProbes.add(glass);
            }
        }
        return glassProbes;
    }

    /** Forgets every instance, at a context's teardown: the material registry frees them. */
    public static void releaseAll() {
        SCREEN.forget();
        WORLD.forget();
    }

    private synchronized void forget() {
        Arrays.fill(made, null);
        curve = null;
        box = null;
        coverage = null;
        strokeCoverage = null;
        colourCoverage = null;
        probes.clear();
        glassProbes.clear();
    }
}
