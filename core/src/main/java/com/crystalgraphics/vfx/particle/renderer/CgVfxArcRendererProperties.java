package com.crystalgraphics.vfx.particle.renderer;

import java.util.Map;

/**
 * A stroke a particle, an arc round the emitter's source through the particle: ink streaks. Ours, with no Niagara
 * counterpart.
 *
 * <pre>{@code
 * CgVfxArcRendererProperties ink = CgVfxRenderers.arc("r1").material("crystalgraphics:materials/ink").build();
 * }</pre>
 *
 * @param subdivisions segments along each stroke, 0 for the shader's own
 */
public record CgVfxArcRendererProperties(Common common, Map<String, Object> tree, int subdivisions)
        implements CgVfxRendererProperties {
}
