package com.crystalgraphics.vfx.particle.renderer;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * A strip through an emitter's particles: Niagara's {@code UNiagaraRibbonRendererProperties}.
 *
 * <pre>{@code
 * CgVfxRibbonRendererProperties stream = CgVfxRenderers.ribbon("r1").material("crystalgraphics:materials/ribbon")
 *         .field("link_order_by_id", true).build();
 * }</pre>
 *
 * @param facing          {@code ENiagaraRibbonFacingMode}
 * @param shape           {@code ENiagaraRibbonShapeMode}
 * @param widthSegments   segments across it, 0 for its shape's own
 * @param maxRibbons      the most ribbons it draws, 0 for no limit
 * @param uv0             its first UV channel's settings ({@code FNiagaraRibbonUVSettings}), null for Unreal's
 * @param uv1             its second, null for Unreal's
 * @param tessellation    {@code { mode, factor, tension, angle }}, null for none
 * @param linkOrderById   whether it joins particles by spawn id rather than spawn order
 * @param maxLinkGap      the most spawns two linked particles may be apart, 0 for any
 */
public record CgVfxRibbonRendererProperties(Common common, Map<String, Object> tree, Facing facing, Shape shape,
                                            int widthSegments, int maxRibbons, @Nullable Map<String, Object> uv0,
                                            @Nullable Map<String, Object> uv1,
                                            @Nullable Map<String, Object> tessellation, boolean linkOrderById,
                                            int maxLinkGap) implements CgVfxRendererProperties {

    /** {@code ENiagaraRibbonFacingMode}. */
    public enum Facing { SCREEN, CUSTOM, CUSTOM_SIDE_VECTOR }

    /** {@code ENiagaraRibbonShapeMode}. */
    public enum Shape { PLANE, MULTI_PLANE, TUBE, CUSTOM }
}
