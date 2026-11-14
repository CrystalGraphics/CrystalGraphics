package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgPipelinePrecache;

import java.util.Objects;

/**
 * A toon outline round what is drawn into custom depth with one stencil value: one fullscreen pass after lighting and
 * before translucency, finding depth, normal and material-ID edges ({@code crystalgraphics:shaders/post/outline.shader},
 * the technique of Dragon Ball Z: Kakarot's battle outline). Unreal has no outline of its own; games add this post
 * material over custom depth and stencil, and this is the engine's.
 *
 * <pre>{@code
 * CgOutline outline = CgOutline.add(1);                        // the engine's outline round stencil 1
 * goku.customDepth(1);                                          // a skeletal mesh component: outlined
 * goku.scalar("OutlineColorType", 1f);                          // its materials write outline type 1...
 * CgOutline.type(1).set(0xFFFFB030, 2.5f);                      // ...drawn in this colour
 * outline.close();                                              // off
 * }</pre>
 *
 * <p>A look of your own: a material asset naming the outline shader with its values, as a kit tunes it.</p>
 *
 * <pre>{@code
 * # assets/mymod/materials/battle_outline.yaml
 * _v: 1
 * shader: crystalgraphics:shaders/post/outline.shader
 * values: { _Stencil: 1, Intensity: 0.35, Thin_Width: 2 }
 *
 * CgOutline battle = CgOutline.add(CgMaterial.of(battleOutlineDefinition));
 * }</pre>
 *
 * <ul>
 *   <li>Each outline is its own instance: {@link #material()} changes this one alone, its edge tests with
 *       {@link #tests}.</li>
 *   <li>The type colours are one collection for every outline ({@code crystalgraphics:outline}), so a sequence keys
 *       them with a global track: {@code { kind: global, collection: crystalgraphics:outline, sections: [ { colours:
 *       { Type1: ... } } ] }}.</li>
 *   <li>It draws before every other post material at its point, as a game's outline draws after lighting.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgOutline implements AutoCloseable {

    /** The outline's shader. */
    public static final String SHADER = "crystalgraphics:shaders/post/outline.shader";
    /** Its order among the post materials at its point: before all of them. */
    public static final int ORDER = -1_000_000;
    /** The type colours' collection: {@code CG_GLOBAL_outline_Type1..4}, the asset {@code crystalgraphics:outline}. */
    public static final String TYPES = "outline";
    private static final int TYPE_COUNT = 4;
    private static final float[][] TYPE_DEFAULTS = {{2.5f, 1.73f, 0.195f, 1f}, {2f, 2f, 2f, 1f}, {2.5f, 1.73f, 0.195f, 1f}, {1f, 1f, 1f, 1f}};

    private final CgMaterialInstance material;
    private final CgPostMaterial post;
    private final CgRenderStage.Registration registration;

    private CgOutline(CgMaterialInstance material) {
        this.material = material;
        types();
        this.post = CgPostMaterial.of(material, CgPostPoint.BEFORE_TRANSLUCENCY, ORDER);
        this.registration = CgPostStack.get().add(post);
    }

    /** The engine's outline round stencil value {@code stencil} (0 to 255). */
    public static CgOutline add(int stencil) {
        if (stencil < 0 || stencil > 255) throw new IllegalArgumentException("stencil " + stencil + " is not 0 to 255");
        return new CgOutline(CgMaterial.load(SHADER).instance().set1i("_Stencil", stencil));
    }

    /**
     * An outline drawn by {@code material}, a material on the outline shader: its own instance where it is one, else a
     * new instance of it, so its values are this outline's.
     */
    public static CgOutline add(CgMaterialInterface material) {
        Objects.requireNonNull(material, "material");
        return new CgOutline(material instanceof CgMaterialInstance instance ? instance : material.material().instance());
    }

    /** Its material: change a value and this outline alone changes. */
    public CgMaterialInstance material() {
        return material;
    }

    /** Which edge tests draw: the depth, normal and material-ID edges, all on by default. A check's switch. */
    public CgOutline tests(boolean depth, boolean normal, boolean id) {
        material.set1i("Tests", (depth ? 1 : 0) | (normal ? 2 : 0) | (id ? 4 : 0));
        return this;
    }

    /** How much it is drawn, 0 (not at all) to 1 (the default): a fade in or out. */
    public CgOutline weight(float weight) {
        post.weight(weight);
        return this;
    }

    /** Takes it off the post stack. */
    @Override
    public void close() {
        registration.close();
    }

    /**
     * Type {@code n}'s colour (1 to 4), linear rgb and alpha, HDR allowed: what a pixel whose material writes outline
     * type {@code n} is outlined in, by every outline.
     */
    public static CgParameterCollection.Vector type(int n) {
        if (n < 1 || n > TYPE_COUNT) throw new IllegalArgumentException("outline type " + n + " is not 1 to " + TYPE_COUNT);
        return types().vectorNamed("Type" + n);
    }

    /**
     * The engine's, at start, before any material compiles: the type colours' collection declared (shaders read it)
     * and the outline's pass precached with the engine's own, so the first outline compiles nothing.
     */
    public static void install() {
        types();
        CgPipelinePrecache.register("crystalgraphics:outline", list -> list.addPass(SHADER));
    }

    /** The type colours' collection, defined here the first time, or the one its asset defined first. */
    private static CgParameterCollection types() {
        synchronized (CgParameterCollection.class) {
            CgParameterCollection c = CgParameterCollection.named(TYPES);
            if (c != null) return c;
            c = CgParameterCollection.of(TYPES);
            for (int n = 0; n < TYPE_COUNT; n++) {
                float[] d = TYPE_DEFAULTS[n];
                c.vector("Type" + (n + 1), d[0], d[1], d[2], d[3]);
            }
            return c;
        }
    }
}
