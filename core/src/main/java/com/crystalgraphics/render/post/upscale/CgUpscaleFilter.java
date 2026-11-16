package com.crystalgraphics.render.post.upscale;

/**
 * How a view drawn below 100% is upscaled into its target when no temporal upscaler rebuilds it (Unreal's
 * {@code r.Upscale.Quality}): {@code view.upscaleFilter(filter)}.
 *
 * <pre>{@code
 * viewport.screenPercentage(50f).upscaleFilter(CgUpscaleFilter.EASU);
 * }</pre>
 */
public enum CgUpscaleFilter {
    /** Four texels, linearly: the softest and cheapest. */
    BILINEAR(null),
    /** The 4x4 Catmull-Rom cubic (Unreal's default, its 5-tap form approximating Lanczos 2). */
    CATMULL_ROM("CATMULL_ROM"),
    /** The 6x6 Lanczos 3: sharpest, a little ringing at hard edges. */
    LANCZOS("LANCZOS"),
    /** AMD FidelityFX FSR 1's edge-adaptive upscale (EASU): edges stay clean where a fixed kernel stair-steps them. */
    EASU("EASU");

    private final String keyword;

    CgUpscaleFilter(String keyword) {
        this.keyword = keyword;
    }

    /** Its keyword in {@code spatial_upscale.shader}; null for bilinear, the shader's default. */
    public String keyword() {
        return keyword;
    }
}
