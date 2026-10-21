package com.crystalgraphics.vfx.particle.renderer;

import java.util.Map;

/**
 * A sequence a particle, on the particle's age times its rate, stopping with it: Niagara's component renderer playing
 * a sequence.
 *
 * <pre>{@code
 * CgVfxSequenceRendererProperties blades = CgVfxRenderers.sequence("r1", "sparking_zero:sequences/shuriken_blades")
 *         .field("play_rate", 1.5).build();
 * }</pre>
 *
 * @param sequence the sequence asset each particle plays
 * @param playRate its rate, times the particle's age
 */
public record CgVfxSequenceRendererProperties(Common common, Map<String, Object> tree, String sequence, float playRate)
        implements CgVfxRendererProperties {
}
