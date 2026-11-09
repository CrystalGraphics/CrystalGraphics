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
 * from a layer of a 2D array, a curve, and a box (rounded, bordered, textured or nine-sliced, by {@link CgShapeTable}).
 * Gradients and grids are {@link CgCanvasGradient} and {@link CgCanvasGrid}. Unreal's simple element shader: one look
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
 *   <li>Each is one shared instance: never set a property on it. A caller that does (a texture it keeps, a group's
 *       {@code _LayerOpacity}) makes its own with {@link #newBox()} or {@link #newCurve()}.</li>
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

    // Before SCREEN and WORLD: their constructors size by them.
    private static final CgBlendMode[] BLENDS = CgBlendMode.values();
    private static final int IMAGE = 0, MASK = 1, IMAGE_ARRAY = 2, MASK_ARRAY = 3, KINDS = 4;

    /** Over whatever is there: a GUI stage, an immediate canvas on the screen. */
    public static final CgCanvasMaterials SCREEN = new CgCanvasMaterials(true);
    /** Tested against the scene's depth, writing none: a world stage. */
    public static final CgCanvasMaterials WORLD = new CgCanvasMaterials(false);

    private final boolean screen;
    private final CgMaterial[] made = new CgMaterial[KINDS * BLENDS.length];
    private CgMaterial curve, box;
    /** The gradient and grid variants {@link #precache} keeps built; theirs, since a pipeline is a material's. */
    private final List<CgMaterial> probes = new ArrayList<>();

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
            curve = newCurve();
            curve.precache();
        }
        return curve;
    }

    /** Boxes by shape, premultiplied: {@code quad(box()).custom2(shape)}. @see CgShapeTable */
    public synchronized CgMaterial box() {
        if (box == null) {
            box = newBox();
            box.precache();
        }
        return box;
    }

    /** A {@link #box()} of the caller's own, for properties it sets: {@code _MainTex}, {@code _LayerOpacity}. */
    public CgMaterial newBox() {
        return CgMaterial.newInstance(BOX_SHADER, state(CgBlendMode.PREMULTIPLIED));
    }

    /** A {@link #curve()} of the caller's own, for properties it sets: {@code _LayerOpacity}. */
    public CgMaterial newCurve() {
        CgMaterial m = CgMaterial.newInstance(CURVE_SHADER, state(CgBlendMode.ALPHA));
        m.toggleKeyword("SCREEN", screen);
        return m;
    }

    CgMaterial newGradient() {
        return CgMaterial.newInstance(GRADIENT_SHADER, state(CgBlendMode.PREMULTIPLIED));
    }

    CgMaterial newGrid() {
        return CgMaterial.newInstance(GRID_SHADER, state(CgBlendMode.PREMULTIPLIED));
    }

    private synchronized CgMaterial get(int kind, CgBlendMode blend) {
        if (blend == CgBlendMode.OPAQUE) {
            throw new IllegalArgumentException("canvas materials blend: an image at full alpha under ALPHA covers");
        }
        int at = kind * BLENDS.length + blend.ordinal();
        CgMaterial m = made[at];
        if (m == null) {
            boolean array = kind == IMAGE_ARRAY || kind == MASK_ARRAY;
            m = CgMaterial.newInstance(array ? IMAGE_ARRAY_SHADER : IMAGE_SHADER, state(blend));
            m.toggleKeyword("MASK", kind == MASK || kind == MASK_ARRAY);
            m.toggleKeyword("SCREEN", screen);
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
        list.addPass(SCREEN.box(), CgInstanceKind.QUAD);
        for (CgMaterial probe : SCREEN.probes()) list.addPass(probe, CgInstanceKind.QUAD);
    }

    /** Every gradient and grid variant a screen draws: mask or none, linear or radial. */
    private synchronized List<CgMaterial> probes() {
        if (probes.isEmpty()) {
            for (int v = 0; v < 4; v++) {
                CgMaterial gradient = newGradient();
                gradient.toggleKeyword(CgCanvasGradient.WITH_MASK, (v & 1) != 0);
                gradient.toggleKeyword(CgCanvasGradient.RADIAL, (v & 2) != 0);
                probes.add(gradient);
            }
            for (int v = 0; v < 2; v++) {
                CgMaterial grid = newGrid();
                grid.toggleKeyword(CgCanvasGrid.WITH_MASK, v != 0);
                probes.add(grid);
            }
        }
        return probes;
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
        probes.clear();
    }
}
