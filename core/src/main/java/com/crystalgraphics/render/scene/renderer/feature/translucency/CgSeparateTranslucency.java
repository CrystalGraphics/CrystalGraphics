package com.crystalgraphics.render.scene.renderer.feature.translucency;

import javax.annotation.Nullable;

import com.crystalgraphics.render.look.CgLookChannels;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.view.CgViewKey;

/**
 * A firing's separate translucency: Unreal's {@code TPT_TranslucencyAfterDOF} and
 * {@code TPT_TranslucencyAfterMotionBlur}. Under the HDR scene a translucent draw whose material's {@code TranslucencyPass}
 * is {@code AfterDOF} draws into {@link #target} instead of the scene, laid over after depth of field
 * ({@code CgTranslucencyComposition}, or TSR itself), so a colour region grades the scene behind it and never the
 * effect; one {@code AfterMotionBlur} draws into {@link #afterMotionBlur}, laid over after motion blur (or the temporal
 * step, with motion blur off), unsmeared.
 *
 * <pre>{@code
 * CgSeparateTranslucency apart = post.view().read(CgSeparateTranslucency.KEY);
 * if (apart != null && apart.target() != null) over(scene, apart.target());     // scene * (1 - a) + rgb
 * for (int k = 0; k < CgLookChannels.MAX; k++) {
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

    /** Where the scene renderer publishes it in its view, a frame under the HDR scene with a separate draw. */
    public static final CgViewKey<CgSeparateTranslucency> KEY = new CgViewKey<>("crystalgraphics:separate_translucency");

    CgGraphTexture target, afterMotionBlur;
    final CgGraphTexture[] layers = new CgGraphTexture[CgLookChannels.MAX];

    /** Every {@code AfterDOF} draw's light and coverage; null where none drew. */
    @Nullable
    public CgGraphTexture target() {
        return target;
    }

    /** Every {@code AfterMotionBlur} draw's light and coverage, at the size the world draws at; null where none drew. */
    @Nullable
    public CgGraphTexture afterMotionBlur() {
        return afterMotionBlur;
    }

    /** The separate draws of graded layer {@code k}'s look; null where none drew. */
    @Nullable
    public CgGraphTexture layer(int k) {
        return layers[k];
    }

    void reset() {
        target = afterMotionBlur = null;
        for (int k = 0; k < layers.length; k++) layers[k] = null;
    }

    boolean any() {
        if (target != null || afterMotionBlur != null) return true;
        for (CgGraphTexture l : layers) if (l != null) return true;
        return false;
    }
}
