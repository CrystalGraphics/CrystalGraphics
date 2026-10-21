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
 * @param sort              how its particles are ordered for blending
 * @param subImage          its flipbook, null for none
 * @param cameraDistance    the camera distances it draws between, null for any
 * @param castShadows       whether it casts shadows
 */
public record CgVfxMeshRendererProperties(Common common, Map<String, Object> tree, List<Map<String, Object>> meshes,
                                          @Nullable String prefab, @Nullable String meshBinding,
                                          List<String> materialOverrides, Facing facing,
                                          @Nullable Map<String, Object> lockedAxis,
                                          CgVfxSpriteRendererProperties.Sort sort, @Nullable CgVfxFlipbook subImage,
                                          @Nullable float[] cameraDistance, boolean castShadows)
        implements CgVfxRendererProperties {

    /** {@code ENiagaraMeshFacingMode}. */
    public enum Facing { DEFAULT, VELOCITY, CAMERA_POSITION, CAMERA_PLANE }
}
