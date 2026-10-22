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
 * @param tessellation    {@code { mode, factor, tension, angle }}, null for Niagara's automatic
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

    /**
     * Quads between two linked particles, as Niagara's {@code CalculateTessellationFactor} answers them: 1 with
     * tessellation {@code disabled}, the {@code factor} (1 to 16) for {@code custom} at {@code angle} 0 (a constant
     * factor), 0 for {@code automatic} or none, the engine's own.
     *
     * <pre>{@code
     * CgVfxRenderers.ribbon("r1").material(STREAM).field("tessellation", Map.of("mode", "custom", "factor", 2)).build();
     * }</pre>
     */
    public int subSegments() {
        if (tessellation == null) return 0;
        return switch (String.valueOf(tessellation.getOrDefault("mode", "automatic"))) {
            case "disabled" -> 1;
            case "custom" -> Math.max(1, Math.min(16, tessellation.get("factor") instanceof Number n ? n.intValue() : 16));
            default -> 0;
        };
    }
}
