package com.crystalgraphics.vfx.particle.renderer;

import java.util.Map;

/**
 * A beam a particle, Cascade's {@code UParticleModuleTypeDataBeam2}: a strip from each particle's source to its target,
 * bent by its tangents and its noise ({@code CgVfxModule.BeamSource}, {@code BeamTarget}, {@code BeamNoise}). Drawn as
 * {@code sheets} quad strips turned about the beam, each as wide as the particle's size, its material as a ribbon's.
 *
 * <pre>{@code
 * CgVfxBeamRendererProperties bolt = CgVfxRenderers.beam("r1").material("mymod:materials/lightning")
 *         .field("sheets", 3).field("max_beam_count", 6).field("interpolation_points", 25).build();
 * }</pre>
 *
 * <ul>
 *   <li>Its particles need a {@code beam_source} and a {@code beam_target}: those place it, not the particle's motion.</li>
 *   <li>{@code speed} and {@code sheets} reach the target and noise modules too: an asset's compiler copies them.</li>
 * </ul>
 *
 * @param sheets              strips about the beam, each turned {@code π / sheets} from the last
 * @param maxBeamCount        the most beams alive at once: the emitter's capacity
 * @param speed               how fast its far end travels to the target, blocks a second; 0 arrives at once
 * @param interpolationPoints segments along its tangents' curve, 0 a straight line; at most 250
 * @param upVectorStepSize    0 turns each segment to the eye, 1 the whole beam by its first
 * @param textureTile         times its texture repeats along it, where {@code textureTileDistance} is 0
 * @param textureTileDistance blocks a repeat; 0 uses {@code textureTile}
 * @param alwaysOn            whether it bursts back up to {@code maxBeamCount} whenever fewer live and no rate runs
 */
public record CgVfxBeamRendererProperties(Common common, Map<String, Object> tree, int sheets, int maxBeamCount, float speed,
                                          int interpolationPoints, int upVectorStepSize, float textureTile,
                                          float textureTileDistance, boolean alwaysOn) implements CgVfxRendererProperties {

    /** Cascade's {@code FDynamicBeam2EmitterData::MaxInterpolationPoints}. */
    public static final int MAX_INTERPOLATION_POINTS = 250;
}
