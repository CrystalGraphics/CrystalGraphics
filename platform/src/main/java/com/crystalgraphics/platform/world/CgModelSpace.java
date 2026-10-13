package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgService;

/**
 * What a matrix a host's entity renderer draws with is relative to, so core can turn a model's pose into a world point:
 * the camera's absolute position, and the rotation the world's entity pose stack starts from. Core's
 * {@code CgEntityAttachments.mark} is the caller; a mod never is.
 *
 * <pre>{@code
 * // a host, once, client side
 * CgPlatform.provide(CgModelSpace.SERVICE, new ModelSpaceModern());
 *
 * // core: a pose matrix's point p in the world is camera + inverse(base) * p
 * if (CgPlatform.get(CgModelSpace.SERVICE).frame(camera, base)) ...
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, during the level render: what it reports is the frame being drawn.</li>
 *   <li>{@link #NONE} answers {@code false}: a host with no pose stack (before Minecraft 1.15), or a server.</li>
 * </ul>
 */
public interface CgModelSpace {

    /**
     * Writes the camera's absolute position into {@code camera} ({@code double[3]}) and the base rotation, column-major,
     * into {@code base} ({@code float[16]}: identity where the stack starts unrotated); {@code false} when unknown.
     */
    boolean frame(double[] camera, float[] base);

    CgModelSpace NONE = (camera, base) -> false;

    CgService<CgModelSpace> SERVICE = CgService.of("crystalgraphics:model_space", NONE);
}
