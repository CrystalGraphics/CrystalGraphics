package com.crystalgraphics.vfx.particle.renderer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;

/**
 * A strip through an emitter's particles: Niagara's {@code UNiagaraRibbonRendererProperties}, or with {@link #trail()}
 * Cascade's ribbon trail ({@code UParticleModuleTypeDataRibbon}), drawn as each engine draws it.
 *
 * <pre>{@code
 * // Niagara's: one ribbon through the emitter's particles by age, its U over its length
 * CgVfxRendererProperties stream = CgVfxRenderers.ribbon("r1").material("crystalgraphics:materials/ribbon").build();
 *
 * // a tube of six sides, U tiled every two blocks from its start
 * CgVfxRenderers.ribbon("r2").material(STREAM).field("shape", "tube")
 *         .field("uv0", Map.of("distribution", "tiled_from_start_over_ribbon_length", "tiling_length", 2.0)).build();
 *
 * // Cascade's trail: points by distance, its width the particles' size either side
 * CgVfxRenderers.ribbon("r3").material(STREAM).field("trail", Map.of("tessellation", Map.of("distance", 0.15))).build();
 * }</pre>
 *
 * <p>An {@code anim_trail} renderer reads as one of these with {@link #anim()}: Cascade's anim trail, a strip swept by
 * an edge its effect sets.</p>
 *
 * <ul>
 *   <li>One ribbon renderer an emitter: Cascade's emitter has one type data, and a second fails at draw by name.</li>
 *   <li>{@code facing: custom} and {@code custom_side_vector} read a facing particles do not carry yet, so they fail at
 *       compile; so do {@code shape: custom}, which has no vertices to read, and {@code max_ribbons}.</li>
 * </ul>
 *
 * @param facing          {@code ENiagaraRibbonFacingMode}
 * @param shape           {@code ENiagaraRibbonShapeMode}
 * @param widthSegments   segments across a plane or each of a multi-plane's planes: Niagara's 1 unless set
 * @param maxRibbons      the most ribbons it draws, 0 for no limit
 * @param uv0             its first UV channel ({@code FNiagaraRibbonUVSettings})
 * @param uv1             its second
 * @param tessellation    how finely a segment is cut
 * @param linkOrderById   whether it links particles by spawn id, newest first, rather than by age
 * @param maxLinkGap      the most spawns two linked particles may be apart, 0 for any
 * @param trail           Cascade's trail emitter, null for Niagara's ribbon
 * @param anim            Cascade's anim trail, null for a ribbon; with it, {@code trail} is null
 */
public record CgVfxRibbonRendererProperties(Common common, Map<String, Object> tree, Facing facing, Shape shape,
                                            int widthSegments, int maxRibbons, Uv uv0, Uv uv1,
                                            Tessellation tessellation, boolean linkOrderById, int maxLinkGap,
                                            @Nullable Trail trail, @Nullable AnimTrail anim) implements CgVfxRendererProperties {

    /** Niagara's {@code MultiPlaneCount} and {@code TubeSubdivisions}, which the format leaves at their defaults. */
    public static final int MULTI_PLANES = 2, TUBE_SIDES = 3;

    /** {@code ENiagaraRibbonFacingMode}. */
    public enum Facing { SCREEN, CUSTOM, CUSTOM_SIDE_VECTOR }

    /** {@code ENiagaraRibbonShapeMode}. */
    public enum Shape { PLANE, MULTI_PLANE, TUBE, CUSTOM }

    /** {@code ENiagaraRibbonUVDistributionMode}, in its order. */
    public enum Distribution { SCALED_UNIFORMLY, SCALED_USING_RIBBON_SEGMENT_LENGTH, TILED_OVER_RIBBON_LENGTH,
        TILED_FROM_START_OVER_RIBBON_LENGTH }

    /** {@code ENiagaraRibbonUVEdgeMode}, in its order. */
    public enum Edge { SMOOTH_TRANSITION, LOCKED }

    /** {@code ENiagaraRibbonTessellationMode}, in its order. */
    public enum TessellationMode { AUTOMATIC, CUSTOM, DISABLED }

    /** Cascade's {@code ETrailsRenderAxisOption}. */
    public enum RenderAxis { CAMERA_UP, SOURCE_UP, WORLD_UP }

    /** Where an anim trail's edge lies about its two points: Cascade's {@code ETrailWidthMode}. */
    public enum WidthMode { CENTRE, FIRST, SECOND }

    /**
     * A value along an anim trail's length, its newest sample at 0 and its oldest at 1: piecewise linear through at most
     * {@link #MAX_KEYS} keys, {@code components} floats each.
     *
     * <pre>{@code
     * Along.constant(1f);                                                  // the same everywhere
     * new Along(new float[] {0f, 0.5f, 1f}, new float[] {1f, 0.6f, 0f}, 1);  // thinning toward its tail
     * }</pre>
     */
    public record Along(float[] times, float[] values, int components) {
        /** The most keys the GPU holds for one. */
        public static final int MAX_KEYS = 4;

        public Along {
            if (times.length < 1 || times.length > MAX_KEYS || values.length != times.length * components) {
                throw new IllegalArgumentException(times.length + " keys of " + components + ": 1 to " + MAX_KEYS);
            }
            for (int i = 1; i < times.length; i++) {
                if (times[i] < times[i - 1]) throw new IllegalArgumentException("keys out of order: " + Arrays.toString(times));
            }
        }

        public static Along constant(float... value) {
            return new Along(new float[] {0f}, value.clone(), value.length);
        }

        /** Its value at {@code t} into {@code out}. */
        public void at(float t, float[] out) {
            int n = times.length, i = 0;
            while (i + 1 < n && times[i + 1] < t) i++;
            int j = Math.min(i + 1, n - 1);
            float span = times[j] - times[i], a = span > 0f ? Math.max(0f, Math.min(1f, (t - times[i]) / span)) : 0f;
            if (t <= times[0]) a = 0f;
            for (int c = 0; c < components; c++) {
                out[c] = values[i * components + c] + (values[j * components + c] - values[i * components + c]) * a;
            }
        }
    }

    /**
     * Cascade's anim trail ({@code UParticleModuleTypeDataAnimTrail}), lengths in blocks: a sample a tick of the edge
     * between user parameters {@code first} and {@code second}, points from its effect's origin.
     *
     * @param life             samples kept, 0 for as long as each lives
     * @param distanceStep     {@code DistanceTessellationStepSize}, 0 for none
     * @param angleStep        {@code TangentTessellationStepSize} over 180 degrees, 0 for none
     * @param widthStep        {@code WidthTessellationStepSize}, 0 for none
     * @param maxTessellation  the most points between two samples, 0 for no limit
     * @param width            its edge's scale along its length
     * @param colour           its colour's scale along its length, RGBA
     * @param tilingDistance   {@code TilingDistance}, 0 for none: the second U by distance
     * @param retract          samples a stopped trail loses a tick while it keeps sampling, 0 for none: it dies at once
     * @param fadeAlpha        alpha a retracting trail loses a tick
     * @param fadeWidth        width a retracting trail loses a tick
     */
    public record AnimTrail(String first, String second, WidthMode widthMode, int life, float distanceStep,
                            float angleStep, float widthStep, int maxTessellation, boolean recalculatePrevious,
                            boolean everyFrame, Along width, Along colour, float tilingDistance, int retract,
                            float fadeAlpha, float fadeWidth) {
    }

    /**
     * One UV channel: {@code FNiagaraRibbonUVSettings}, its defaults Niagara's (segment length, both edges locked,
     * tiles of 100 Unreal units, unscaled).
     */
    public record Uv(Distribution distribution, Edge leadingEdge, Edge trailingEdge, float tilingLength,
                     float offsetU, float offsetV, float scaleU, float scaleV) {

        /** Niagara's defaults, the tiling length in blocks. */
        public static final Uv DEFAULT = new Uv(Distribution.SCALED_USING_RIBBON_SEGMENT_LENGTH, Edge.LOCKED, Edge.LOCKED,
                1f, 0f, 0f, 1f, 1f);
    }

    /**
     * How a Niagara ribbon's segments are cut: its mode, the custom factor and minimum angle (radians, Niagara's 1 degree
     * floor applied), whether the factor is constant and whether it adapts to the screen; and the curve tension.
     */
    public record Tessellation(TessellationMode mode, int factor, float minAngle, boolean constant, boolean screenSpace,
                               float tension) {

        /** Niagara's defaults: automatic, 16, 15 degrees, adaptive on screen, no tension. */
        public static final Tessellation DEFAULT = new Tessellation(TessellationMode.AUTOMATIC, 16,
                (float) Math.toRadians(15), false, true, 0f);
    }

    /**
     * Cascade's trail: {@code UParticleModuleTypeDataRibbon}, lengths in blocks. Its width is the particles' size
     * either side, twice a Niagara ribbon's of the same size.
     *
     * @param distanceStep     {@code DistanceTessellationStepSize}, 0 for none
     * @param tangentScale     {@code TangentTessellationScalar}
     * @param tangentInterp    {@code bEnableTangentDiffInterpScale}
     * @param maxTessellation  {@code MaxTessellationBetweenParticles}
     * @param tilingDistance   {@code TilingDistance}, 0 for none: the second U by distance
     */
    public record Trail(int maxTrails, int maxParticles, int sheets, RenderAxis renderAxis, float distanceStep,
                        int maxTessellation, float tangentScale, boolean tangentInterp, boolean recalculatePrevious,
                        boolean everyFrame, float tangentSpawnScale, boolean spawnInitialParticle,
                        boolean clipSourceSegment, boolean deadOnDeactivate, boolean deadOnSourceLoss,
                        float tilingDistance) {
    }
}
