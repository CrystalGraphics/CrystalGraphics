package com.crystalgraphics.render.world;

import javax.annotation.Nullable;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.stage.CgFrameKey;

/**
 * A firing's separate translucency: Unreal's {@code PostDOFTranslucency}. Under the HDR scene a translucent draw whose
 * material says {@code TranslucencyPass Separate} draws here instead of into the scene, and the post stack composites it
 * after its {@code BEFORE_SEPARATE_TRANSLUCENCY} effects ({@code CgTranslucencyComposition}), so a colour region grades
 * the scene behind it and never the effect, as Unreal's {@code PrePostProcessPass} runs before
 * {@code FTranslucencyComposition}.
 *
 * <pre>{@code
 * CgSeparateTranslucency apart = post.resources().get(CgSeparateTranslucency.KEY);
 * if (apart != null && apart.target() != null) over(scene, apart.target());     // scene * (1 - a) + rgb
 * for (int k = 0; k < CgGradeLayers.MAX; k++) {
 *     if (apart.layer(k) != null) over(layers.layer(k), apart.layer(k));        // its graded share, the same way
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Each target is RGBA16F, cleared to 0: rgb the light its draws add, premultiplied; a how much of what is behind
 *       they cover.</li>
 *   <li>A graded look's separate draws are in {@link #layer} too, laid over that look's layer as over the scene.</li>
 *   <li>A draw whose blend multiplies has no form here and draws into the scene, warned once by its renderer.</li>
 * </ul>
 */
public final class CgSeparateTranslucency {

    /** Where the world renderer publishes it, in a transparent firing under the HDR scene with a separate draw. */
    public static final CgFrameKey<CgSeparateTranslucency> KEY =
            CgFrameKey.of("crystalgraphics:separate_translucency", CgSeparateTranslucency.class);

    CgGraphTexture target;
    final CgGraphTexture[] layers = new CgGraphTexture[CgGradeLayers.MAX];

    /** Every separate draw's light and coverage; null where none drew. */
    @Nullable
    public CgGraphTexture target() {
        return target;
    }

    /** The separate draws of graded layer {@code k}'s look; null where none drew. */
    @Nullable
    public CgGraphTexture layer(int k) {
        return layers[k];
    }

    void reset() {
        target = null;
        for (int k = 0; k < layers.length; k++) layers[k] = null;
    }

    boolean any() {
        if (target != null) return true;
        for (CgGraphTexture l : layers) if (l != null) return true;
        return false;
    }
}
