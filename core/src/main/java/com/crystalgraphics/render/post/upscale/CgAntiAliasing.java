package com.crystalgraphics.render.post.upscale;

/**
 * Which temporal step a view's post chain runs while its {@code temporalAA} show flag is on (Unreal's
 * {@code AntiAliasingMethod}): {@code view.antiAliasing(method)}.
 *
 * <pre>{@code
 * view.showFlags().temporalAA(true);                               // TSR, the default
 * view.antiAliasing(CgAntiAliasing.TSR).screenPercentage(67f);   // drawn at 67%, rebuilt to the full size
 * view.antiAliasing(CgAntiAliasing.TAA);                         // temporal AA at the size drawn
 * }</pre>
 */
public enum CgAntiAliasing {
    /**
     * Temporal anti-aliasing at the size the world draws at ({@code CgTemporalAA}); a view drawn below 100% is upscaled
     * spatially after its chain, by its {@code upscaleFilter}.
     */
    TAA,
    /**
     * Temporal super resolution ({@code CgTemporalSuperResolution}): the world drawn at the view's screen percentage
     * and rebuilt to its output size from its frames; at 100% a temporal anti-aliasing of its own.
     */
    TSR
}
