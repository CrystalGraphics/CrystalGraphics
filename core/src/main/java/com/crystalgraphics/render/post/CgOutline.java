package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.precache.CgPipelinePrecache;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPostVolume;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The engine's toon outline: a post material at {@code SURFACES}, after lighting and before translucency, finding depth,
 * normal and material-ID edges round what is drawn into custom depth with one stencil value
 * ({@code crystalgraphics:shaders/post/outline.shader}, the technique of Dragon Ball Z: Kakarot's battle outline). Unreal
 * has no outline of its own; games put this post material in a volume over custom depth and stencil, and so does this.
 *
 * <pre>{@code
 * CgMaterialInstance look = CgOutline.material(1);                   // the engine's outline round stencil 1
 * CgPostVolume outline = scene.postVolume(CgOutline.settings(look)).spawn();
 * goku.customDepth(1);                                               // a skeletal mesh component: outlined
 * goku.scalar("OutlineColorType", 1f);                               // its materials write outline type 1...
 * CgOutline.type(1).set(0xFFFFB030, 2.5f);                           // ...drawn in this colour
 * outline.weight(0.5f);                                              // half drawn; 0 not at all
 * outline.remove();                                                  // off
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
 * CgPostVolume battle = scene.postVolume(CgOutline.settings(CgOutline.material(CgMaterial.of(battleOutlineDefinition)))).spawn();
 * }</pre>
 *
 * <p>Several owners outlining one stencil in one scene share one outline: each holds it, and the last to let go
 * removes it. The first holder of a stencil decides its look.</p>
 *
 * <pre>{@code
 * CgOutline.Hold held = CgOutline.hold(scene, 1, null);              // the engine's look, added with the first holder
 * CgOutline.Hold kit = CgOutline.hold(scene, 1, battleOutline);      // shares it: stencil 1 is drawn already
 * held.close();                                                      // removed with the last
 * }</pre>
 *
 * <ul>
 *   <li>Each {@link #material} is its own instance: a value set on it changes that outline alone.</li>
 *   <li>The type colours are one collection for every outline ({@code crystalgraphics:outline}), so a sequence keys
 *       them with a global track: {@code { kind: global, collection: crystalgraphics:outline, sections: [ { colours:
 *       { Type1: ... } } ] }}.</li>
 *   <li>It draws before every other effect at {@code SURFACES} ({@link #PRIORITY}), the height fog included.</li>
 * </ul>
 */
public final class CgOutline {

    /** The outline's shader. */
    public static final String SHADER = "crystalgraphics:shaders/post/outline.shader";
    /** Its priority among the effects at {@code SURFACES}: before all of them. */
    public static final int PRIORITY = -1_000_000;
    /** The type colours' collection: {@code CG_GLOBAL_outline_Type1..4}, the asset {@code crystalgraphics:outline}. */
    public static final String TYPES = "outline";
    private static final int TYPE_COUNT = 4;
    private static final float[][] TYPE_DEFAULTS = {{2.5f, 1.73f, 0.195f, 1f}, {2f, 2f, 2f, 1f}, {2.5f, 1.73f, 0.195f, 1f}, {1f, 1f, 1f, 1f}};

    private static final Map<Key, Shared> SHARED = new HashMap<>();

    private record Key(CgScene scene, int stencil) {
    }

    private static final class Shared {
        final CgPostVolume volume;
        int holders;

        Shared(CgPostVolume volume) {
            this.volume = volume;
        }
    }

    /** A hold on the shared outline of one stencil in one scene: closing it lets go, once. */
    public static final class Hold implements AutoCloseable {
        private final Key key;
        private boolean closed;

        private Hold(Key key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Shared s = SHARED.get(key);
            if (s == null || --s.holders > 0) return;
            SHARED.remove(key);
            s.volume.remove();
        }
    }

    private CgOutline() {
    }

    /**
     * A hold on the outline round {@code stencil} in {@code scene}, shared by every holder of that stencil there: its
     * volume spawned with the first, removed when the last closes. Drawn in {@code look} (a material on the outline
     * shader, its {@code _Stencil} set to {@code stencil}) where this is the first holder; null for the engine's look.
     * Frame thread.
     */
    public static Hold hold(CgScene scene, int stencil, @Nullable CgMaterialInterface look) {
        Key key = new Key(Objects.requireNonNull(scene, "scene"), stencil);
        Shared s = SHARED.get(key);
        if (s == null) {
            CgMaterialInstance m = look == null ? material(stencil) : material(look).set1i("_Stencil", stencil);
            SHARED.put(key, s = new Shared(scene.postVolume(settings(m)).spawn()));
        }
        s.holders++;
        return new Hold(key);
    }

    /** The engine's outline round stencil value {@code stencil} (0 to 255), an instance of its own. */
    public static CgMaterialInstance material(int stencil) {
        if (stencil < 0 || stencil > 255) throw new IllegalArgumentException("stencil " + stencil + " is not 0 to 255");
        types();
        return CgMaterial.load(SHADER).instance().set1i("_Stencil", stencil);
    }

    /**
     * An outline drawn by {@code material}, a material on the outline shader: itself where it is an instance, else a new
     * instance of it, so its values are this outline's.
     */
    public static CgMaterialInstance material(CgMaterialInterface material) {
        Objects.requireNonNull(material, "material");
        types();
        return material instanceof CgMaterialInstance instance ? instance : material.material().instance();
    }

    /** Settings drawing {@code outline} at full weight: what a post volume of it is spawned from. */
    public static CgPostSettings settings(CgMaterialInstance outline) {
        return new CgPostSettings().blendable(outline, CgPostPoint.SURFACES, 1f, PRIORITY);
    }

    /** Which of {@code outline}'s edge tests draw: the depth, normal and material-ID edges, all on by default. */
    public static void tests(CgMaterialInstance outline, boolean depth, boolean normal, boolean id) {
        outline.set1i("Tests", (depth ? 1 : 0) | (normal ? 2 : 0) | (id ? 4 : 0));
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
