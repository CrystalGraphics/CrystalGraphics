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
 * CgVfxRendererProperties many = CgVfxRenderers.light("r3").field("allow_mega_lights", true).build();  // sampled
 * }</pre>
 *
 * <ul>
 *   <li>Its emitter needs a {@code light_attributes} module, which gives every particle its light.</li>
 *   <li>Its lights reach transparent surfaces only with {@code affects_translucency}, and the air only by
 *       {@code volumetric_scattering}: both off unless set, as Cascade's simple lights are.</li>
 *   <li>Each light is shaded exactly at every pixel it reaches unless {@code allow_mega_lights}, Niagara's
 *       {@code bAllowMegaLights} (off by default, {@code NiagaraLightRendererProperties.cpp:41}): then the shadow passes
 *       sample them, a cost per pixel that stays flat however many overlap, at the price of their grain. Not for a
 *       swarm of alike lights: a sample's weight is clamped at {@code r.MegaLights.MaxShadingWeight} (20), so where
 *       many more than 20 light a pixel about equally most of their light is lost (a firework burst's floor, a fifth
 *       darker).</li>
 * </ul>
 *
 * @param inverseSquaredFalloff whether it falls off by the inverse square, its exponent ignored, as Cascade's
 *                              {@code bUseInverseSquaredFalloff}
 * @param intensityScale        an inverse-squared light's colour times this: its asset's square length unit in square
 *                              blocks, 1e-4 for Unreal's centimetres (the {@code intensity_scale} field), 1 by default
 * @param spawnFraction         the share of particles with a light, each rolled once from its seed
 * @param affectsTranslucency   whether it lights translucent materials too
 * @param volumetricScattering  how much it lights the air's fog
 * @param allowMegaLights       whether the shadow passes sample its lights rather than the apply shading each exactly
 */
public record CgVfxLightRendererProperties(Common common, Map<String, Object> tree, boolean inverseSquaredFalloff,
                                           float intensityScale, float spawnFraction, boolean affectsTranslucency, float volumetricScattering,
                                           boolean allowMegaLights)
        implements CgVfxRendererProperties {
}
