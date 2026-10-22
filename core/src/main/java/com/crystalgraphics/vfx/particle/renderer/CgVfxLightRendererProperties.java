package com.crystalgraphics.vfx.particle.renderer;

import java.util.Map;

/**
 * A light a particle, Cascade's {@code UParticleModuleLight} drawn as a simple light: each valid particle lights the
 * scene round it every frame, its colour times its emitter's light colour scale and brightness
 * ({@code CgVfxEmitter.Builder.light}), across its radius scale times its size, falling off by its exponent
 * ({@code ParticleSystemRender.cpp:870-935}). It draws no material: its lights join the world's light list.
 *
 * <pre>{@code
 * CgVfxRendererProperties glow = CgVfxRenderers.light("r1").field("inverse_squared_falloff", false).build();
 * CgVfxRendererProperties some = CgVfxRenderers.light("r2").field("spawn_fraction", 0.25).build();   // a quarter light
 * }</pre>
 *
 * <ul>
 *   <li>Its emitter needs a {@code light_attributes} module, which gives every particle its light.</li>
 *   <li>{@code affects_translucency} and {@code volumetric_scattering} fail at compile until lit transparents and the
 *       lights' fog are built.</li>
 * </ul>
 *
 * @param inverseSquaredFalloff whether it falls off by the inverse square, its exponent ignored, as Cascade's
 *                              {@code bUseInverseSquaredFalloff}
 * @param spawnFraction         the share of particles with a light, each rolled once from its seed
 * @param affectsTranslucency   whether it lights translucent materials too
 * @param volumetricScattering  how much it lights the air's fog
 */
public record CgVfxLightRendererProperties(Common common, Map<String, Object> tree, boolean inverseSquaredFalloff,
                                           float spawnFraction, boolean affectsTranslucency, float volumetricScattering)
        implements CgVfxRendererProperties {
}
