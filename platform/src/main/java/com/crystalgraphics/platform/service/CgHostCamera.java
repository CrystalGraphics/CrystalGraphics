package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Moves the host's camera by a little for one frame: what a shake and an FOV kick are. A host applies the last offset
 * it was given where it sets its camera up, once a frame; {@code com.crystalgraphics.world.CgCameraShake} sums every
 * effect's shake into the one offset it gives, so a host only adds numbers.
 *
 * <pre>{@code
 * // core, once a frame
 * CgPlatform.get(CgHostCamera.SERVICE).offset(dx, dy, dz, yaw, pitch, roll, fovScale);
 *
 * // a host, once, on a client only
 * CgPlatform.provide(CgHostCamera.SERVICE, new HostCameraModern());
 * }</pre>
 *
 * <ul>
 *   <li>Translation in blocks along the world's axes; angles in degrees, added to the camera's; {@code fovScale}
 *       multiplies the field of view, 1 for none.</li>
 *   <li>It holds until the next call: a caller that stops shaking gives zeros and 1, and {@link #NONE} ignores it all,
 *       which is right where nothing has a camera to move.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgHostCamera {

    /** No camera to move: the harness, a dedicated server. */
    CgHostCamera NONE = (x, y, z, yaw, pitch, roll, fovScale) -> { };

    CgService<CgHostCamera> SERVICE = CgService.of("crystalgraphics:host_camera", NONE);

    void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale);
}
