package com.crystalgraphics.api.material.engine;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.precache.CgPipelineList;

/**
 * The engine's ready materials, so no mod ships its own {@code image.shader}: an image, a mask, either from a layer of
 * a 2D array, a flat colour, a gradient, a grid, a pane of glass. Named by what they draw, never by where; each comes
 * in a screen form and a world form, in any blend it takes. Unreal's engine materials.
 *
 * <pre>{@code
 * CgMaterial icon = CgEngineMaterials.IMAGE.screen();                              // texture x colour, alpha-blended
 * CgMaterial glow = CgEngineMaterials.MASK.screen(CgBlendMode.ADDITIVE);           // the texture's shape, added
 * CgMaterial rune = CgEngineMaterials.IMAGE.world(CgBlendMode.ADDITIVE);           // depth-tested, in the world
 * CgMaterial digit = CgEngineMaterials.IMAGE_ARRAY.screen();                       // a layer of a 2D array
 *
 * canvas.quad(icon).texture(0, save).at(x, y).size(16, 16).submit();
 * canvas.quad(CgEngineMaterials.COLOR.screen()).at(x, y).size(w, h).color(0xC0101418).submit();
 * }</pre>
 *
 * <ul>
 *   <li>An image is its texture times the draw's colour; a mask the draw's colour times the texture's alpha alone. With
 *       no texture either draws the colour: that is {@link #COLOR}.</li>
 *   <li>Images and masks take {@code ALPHA}, {@code PREMULTIPLIED}, {@code ADDITIVE}, {@code MODULATE},
 *       {@code SUBTRACT}. Gradients and grids draw premultiplied, glass under its own program's blend.</li>
 *   <li>Every material here is shared: never set a property on one. A gradient, grid or pane is drawn through the
 *       value it paints ({@code CgGradient}, {@code CgGrid}, {@code CgBackdropFilter}).</li>
 * </ul>
 */
public final class CgEngineMaterials {

    private static final String CANVAS = "crystalgraphics:shaders/canvas/";

    /** A texture times the draw's colour. */
    public static final CgEngineMaterial IMAGE = new CgEngineMaterial("image", CANVAS + "image.shader", null, true,
            CgBlendMode.ALPHA);
    /** The draw's colour times the texture's alpha: a silhouette in any colour. */
    public static final CgEngineMaterial MASK = new CgEngineMaterial("mask", CANVAS + "image.shader", "MASK", true,
            CgBlendMode.ALPHA);
    /** {@link #IMAGE} from the layer of a 2D array the draw names. */
    public static final CgEngineMaterial IMAGE_ARRAY = new CgEngineMaterial("image array",
            CANVAS + "image_array.shader", null, true, CgBlendMode.ALPHA);
    /** {@link #MASK} from the layer of a 2D array the draw names. */
    public static final CgEngineMaterial MASK_ARRAY = new CgEngineMaterial("mask array",
            CANVAS + "image_array.shader", "MASK", true, CgBlendMode.ALPHA);
    /** The draw's colour alone: {@link #IMAGE} drawn with no texture. */
    public static final CgEngineMaterial COLOR = IMAGE;
    /** A {@code CgGradient}'s ramp over the draw, premultiplied. */
    public static final CgEngineMaterial GRADIENT = new CgEngineMaterial("gradient", CANVAS + "gradient.shader", null,
            false, CgBlendMode.PREMULTIPLIED);
    /** A {@code CgGrid}'s lines or checker over the draw, premultiplied. */
    public static final CgEngineMaterial GRID = new CgEngineMaterial("grid", CANVAS + "grid.shader", null, false,
            CgBlendMode.PREMULTIPLIED);
    /** A pane of glass over its captured backdrop: what a {@code CgBackdropFilter} draws through. */
    public static final CgEngineMaterial BACKDROP = new CgEngineMaterial("backdrop", CANVAS + "glass.shader", null,
            false, CgBlendMode.ALPHA);

    private static final CgEngineMaterial[] ALL = {IMAGE, MASK, IMAGE_ARRAY, MASK_ARRAY, GRADIENT, GRID, BACKDROP};

    private CgEngineMaterials() {
    }

    /** The screen's alpha and additive images and masks, built ahead so no first draw compiles them. */
    public static void precache(CgPipelineList list) {
        for (CgBlendMode blend : new CgBlendMode[] {CgBlendMode.ALPHA, CgBlendMode.ADDITIVE}) {
            list.addPass(IMAGE.screen(blend), CgInstanceKind.QUAD);
            list.addPass(MASK.screen(blend), CgInstanceKind.QUAD);
            list.addPass(IMAGE_ARRAY.screen(blend), CgInstanceKind.QUAD);
            list.addPass(MASK_ARRAY.screen(blend), CgInstanceKind.QUAD);
        }
    }

    /** Forgets every material made, at a context's teardown: the material registry frees them. */
    public static void releaseAll() {
        for (CgEngineMaterial m : ALL) m.forget();
    }
}
