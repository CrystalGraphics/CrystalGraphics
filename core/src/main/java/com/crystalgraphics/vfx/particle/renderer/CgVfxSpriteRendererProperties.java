package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.vfx.look.CgVfxFlipbook;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * A camera-facing quad a particle: Niagara's {@code UNiagaraSpriteRendererProperties}.
 *
 * <pre>{@code
 * CgVfxSpriteRendererProperties sparks = CgVfxRenderers.sprite("r1").material("crystalgraphics:materials/spark")
 *         .alignment("velocity").subImage(Map.of("size", List.of(4, 4), "blend", true)).build();
 * }</pre>
 *
 * @param alignment      what its up axis follows ({@code ENiagaraSpriteAlignment}): nothing, its velocity, or a custom axis
 * @param facing         what it faces ({@code ENiagaraSpriteFacingMode})
 * @param facingBlend    {@code camera_distance_blend}'s two distances, null otherwise
 * @param sort           how its particles are ordered for blending
 * @param pivot          the quad's pivot in UV, Unreal's {@code PivotInUVSpace}: (0.5, 0.5) its middle
 * @param subImage       its flipbook, null for one image
 * @param cameraDistance the camera distances it draws between, null for any
 * @param castShadows    whether it casts shadows
 * @param softDepth      the depth it fades into what is behind it over, blocks; NaN for its material's
 * @param pixelCoverage  under a pixel, drawn a pixel in half-size and its colour scaled ({@code PixelCoverageMode})
 * @param pixelCoverageBlend how far its colour is scaled by its coverage, 0 to 1 ({@code PixelCoverageBlend})
 */
public record CgVfxSpriteRendererProperties(Common common, Map<String, Object> tree, Alignment alignment, Facing facing,
                                            @Nullable float[] facingBlend, Sort sort, float[] pivot,
                                            @Nullable CgVfxFlipbook subImage, @Nullable float[] cameraDistance,
                                            boolean castShadows, float softDepth, PixelCoverage pixelCoverage,
                                            float pixelCoverageBlend) implements CgVfxRendererProperties {

    /** {@code ENiagaraSpriteAlignment}. */
    public enum Alignment { UNALIGNED, VELOCITY, CUSTOM }

    /** {@code ENiagaraSpriteFacingMode}. */
    public enum Facing { CAMERA, CAMERA_PLANE, CAMERA_POSITION, CUSTOM, CAMERA_DISTANCE_BLEND }

    /**
     * {@code ENiagaraRendererPixelCoverageMode}: {@code AUTOMATIC} on for a translucent or additive material, the others
     * as named; {@code ENABLED} widens without scaling its colour.
     */
    public enum PixelCoverage { AUTOMATIC, DISABLED, ENABLED, RGBA, RGB, A }

    /** {@code ENiagaraSortMode}, and the spawn orders ours adds. */
    public enum Sort { NONE, VIEW_DEPTH, VIEW_DISTANCE, SPAWN_ORDER, OLDEST_FIRST, NEWEST_FIRST }

    /**
     * The sprite factory's facing ({@code _cg_Facing}): {@code lib/vfx/particle.glsl}'s {@code FX_FACE_*} plus 8 times its
     * {@code FX_ALIGN_*}. Camera 0, aligned to velocity 8, turned to the eye round up 3.
     */
    public static final float FACE_CAMERA = 0f, FACE_POSITION_UP = 3f, FACE_ALIGNED = 8f;

    /**
     * How the sprite factory faces it ({@code _cg_Facing}), as {@code NiagaraSpriteVertexFactory.ush}'s
     * {@code GetTangents}: unaligned, it lies in the eye's plane (camera, camera plane), across its custom facing
     * vector, or turned toward the eye round world up (camera position); aligned, it stands along its velocity or its
     * custom alignment vector, facing the eye or its custom facing vector.
     *
     * <ul>
     *   <li>The custom vectors are Niagara's {@code Particles.SpriteFacing} and {@code SpriteAlignment}: particles carry
     *       no such attributes yet, so the renderer's values {@code _cg_FacingVector} and {@code _cg_AlignmentVector}
     *       hold one for every particle, (1, 0, 0) unless set, Niagara's default.</li>
     * </ul>
     *
     * @throws IllegalArgumentException for a distance blend: not built yet
     */
    /**
     * The sprite factory's pixel coverage for its material's Forward blend ({@code _cg_PixelCoverageBlend}): the colour
     * channels' share scaled by coverage, or null where it is off. As {@code NiagaraRendererSprites.cpp}: automatic is on
     * for a translucent blend (alpha) and an additive one (RGBA), off for a solid and any other blend.
     */
    @Nullable
    public float[] coverageBlend(@Nullable CgBlendState blend) {
        float b = pixelCoverageBlend;
        return switch (pixelCoverage) {
            case DISABLED -> null;
            case ENABLED -> new float[] {0f, 0f, 0f, 0f};
            case RGBA -> new float[] {b, b, b, b};
            case RGB -> new float[] {b, b, b, 0f};
            case A -> new float[] {0f, 0f, 0f, b};
            case AUTOMATIC -> blend == null || !blend.enabled() ? null
                    : blend.dstRgb() == GLC.GL_ONE && blend.blendEquationRgb() == GLC.GL_FUNC_ADD ? new float[] {b, b, b, b}
                    : blend.srcRgb() == GLC.GL_SRC_ALPHA && blend.dstRgb() == GLC.GL_ONE_MINUS_SRC_ALPHA ? new float[] {0f, 0f, 0f, b}
                    : null;
        };
    }

    public float factoryFacing(String who) {
        if (facing == Facing.CAMERA_DISTANCE_BLEND) {
            throw new IllegalArgumentException(who + " faces camera_distance_blend, which is not built yet");
        }
        int f = switch (facing) {
            case CAMERA -> 0;
            case CAMERA_PLANE -> 1;
            case CUSTOM -> 2;
            default -> 3;
        };
        return f + 8 * alignment.ordinal();
    }
}
