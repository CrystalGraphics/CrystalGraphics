package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.vfx.look.CgVfxFlipbook;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * A mesh a particle: Niagara's {@code UNiagaraMeshRendererProperties}. Its meshes are files, a prefab, or a user
 * parameter naming one.
 *
 * <pre>{@code
 * CgVfxMeshRendererProperties rocks = CgVfxRenderers.mesh("r1").material("crystalgraphics:materials/rock")
 *         .field("meshes", List.of(Map.of("mesh", "crystalgraphics:models/rock.glb"))).build();
 * }</pre>
 *
 * @param meshes            its meshes, each {@code { mesh, scale, rotation, pivot_offset, pivot_space }}; empty for a prefab or a binding
 * @param prefab            the prefab each particle plays, null for meshes
 * @param meshBinding       the user parameter naming its mesh, null otherwise
 * @param materialOverrides a material a section, in place of the meshes' own
 * @param facing            {@code ENiagaraMeshFacingMode}
 * @param lockedAxis        {@code { axis, space }}, null for none
 * @param facingUp          Cascade's up while facing, null for Niagara's ({@link CgVfxMeshFacing})
 * @param rollAbout         the axis the sprite rotation rolls a facing mesh about, null for no roll
 * @param preRotation       a rotation or a range of them, rolled per particle and applied innermost; null for none
 * @param sort              how its particles are ordered for blending
 * @param subImage          its flipbook, null for none
 * @param cameraDistance    the camera distances it draws between, null for any
 * @param castShadows       whether it casts shadows
 */
public record CgVfxMeshRendererProperties(Common common, Map<String, Object> tree, List<Map<String, Object>> meshes,
                                          @Nullable String prefab, @Nullable String meshBinding,
                                          List<String> materialOverrides, Facing facing,
                                          @Nullable Map<String, Object> lockedAxis, @Nullable FacingUp facingUp,
                                          @Nullable RollAbout rollAbout, @Nullable Object preRotation,
                                          CgVfxSpriteRendererProperties.Sort sort, @Nullable CgVfxFlipbook subImage,
                                          @Nullable float[] cameraDistance, boolean castShadows)
        implements CgVfxRendererProperties {

    /** {@code ENiagaraMeshFacingMode}. */
    public enum Facing { DEFAULT, VELOCITY, CAMERA_POSITION, CAMERA_PLANE }

    /**
     * Cascade's up while facing, which Niagara lacks: world up for every mode, Z turned toward the camera across the
     * facing direction (Cascade's VelocityAligned), or X turned to it by the shortest arc (Cascade's NoUp).
     */
    public enum FacingUp { WORLD, CAMERA, NONE }

    /** What the particle's sprite rotation rolls a facing mesh about: the direction it faces along, or the camera's. */
    public enum RollAbout { FACING, CAMERA }
}
