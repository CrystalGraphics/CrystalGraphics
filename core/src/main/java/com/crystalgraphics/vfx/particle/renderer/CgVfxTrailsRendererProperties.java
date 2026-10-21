package com.crystalgraphics.vfx.particle.renderer;

import java.util.Map;

/**
 * A strip behind each particle through the positions its pool keeps: a spark's tail. Ours, with no Niagara
 * counterpart.
 *
 * <pre>{@code
 * CgVfxTrailsRendererProperties tails = CgVfxRenderers.trails("r1").material("crystalgraphics:materials/streaks")
 *         .field("points", 12).build();
 * }</pre>
 *
 * @param points       positions kept a particle, 2 to 64: its pool keeps that many a slot
 * @param subdivisions quads between two points, 0 for the shader's own
 * @param taper        how much it narrows to its tail, 0 to 1
 */
public record CgVfxTrailsRendererProperties(Common common, Map<String, Object> tree, int points, int subdivisions,
                                            float taper) implements CgVfxRendererProperties {
}
