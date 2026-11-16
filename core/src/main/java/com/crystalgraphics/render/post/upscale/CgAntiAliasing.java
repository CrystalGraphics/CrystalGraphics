package com.crystalgraphics.render.post.upscale;

/**
 * Which temporal step a view's post chain runs while its {@code temporalAA} show flag is on (Unreal's
 * {@code AntiAliasingMethod}): {@code view.antiAliasing(method)}.
 *
 * <pre>{@code
 * view.showFlags().temporalAA(true);
 * view.antiAliasing(CgAntiAliasing.TEMPORAL_AA);
 * }</pre>
 */
public enum CgAntiAliasing {
    /**
     * Temporal anti-aliasing at the size the world draws at ({@code CgTemporalAA}); a view drawn below 100% is upscaled
     * spatially after its chain, by its {@code upscaleFilter}.
     */
    TEMPORAL_AA
}
