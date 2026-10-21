package com.crystalgraphics.vfx.particle.renderer;

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
 */
public record CgVfxSpriteRendererProperties(Common common, Map<String, Object> tree, Alignment alignment, Facing facing,
                                            @Nullable float[] facingBlend, Sort sort, float[] pivot,
                                            @Nullable CgVfxFlipbook subImage, @Nullable float[] cameraDistance,
                                            boolean castShadows, float softDepth) implements CgVfxRendererProperties {

    /** {@code ENiagaraSpriteAlignment}. */
    public enum Alignment { UNALIGNED, VELOCITY, CUSTOM }

    /** {@code ENiagaraSpriteFacingMode}. */
    public enum Facing { CAMERA, CAMERA_PLANE, CAMERA_POSITION, CUSTOM, CAMERA_DISTANCE_BLEND }

    /** {@code ENiagaraSortMode}, and the spawn orders ours adds. */
    public enum Sort { NONE, VIEW_DEPTH, VIEW_DISTANCE, SPAWN_ORDER, OLDEST_FIRST, NEWEST_FIRST }

    /** The sprite factory's facing, {@code fx_particle.glsl}'s {@code FX_FACE_*}: camera 0, position up 5, aligned 6. */
    public static final float FACE_CAMERA = 0f, FACE_POSITION_UP = 5f, FACE_ALIGNED = 6f;

    /**
     * How the sprite factory faces it ({@code _cg_Facing}), as {@code NiagaraSpriteVertexFactory.ush}'s
     * {@code GetTangents} does: an aligned sprite stands along its velocity whatever it faces, an unaligned one lies in
     * the eye's plane (camera, camera plane) or turns toward the eye round world up (camera position).
     *
     * @throws IllegalArgumentException for a custom axis or facing, or a distance blend: not built yet
     */
    public float factoryFacing(String who) {
        if (alignment == Alignment.CUSTOM || facing == Facing.CUSTOM || facing == Facing.CAMERA_DISTANCE_BLEND) {
            throw new IllegalArgumentException(who + " aligns " + alignment + " facing " + facing
                    + ": custom axes and distance blends need the sprite attributes, which are not built yet");
        }
        if (alignment == Alignment.VELOCITY) return FACE_ALIGNED;
        return facing == Facing.CAMERA_POSITION ? FACE_POSITION_UP : FACE_CAMERA;
    }
}
