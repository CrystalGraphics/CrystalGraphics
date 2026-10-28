package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgHostCamera;

/**
 * The host's camera, moved by a little for one frame: the offset every playing {@code CgCameraShake} sums to. The only
 * reader of {@link CgHostCamera}; an effect shakes through {@code CgCameraShake}, never here.
 *
 * <pre>{@code
 * CgCamera.offset(dx, dy, dz, yaw, pitch, roll, fovScale);   // once a frame; zeros and 1 to stop
 * if ((CgCamera.capabilities() & CgHostCamera.ROLL) == 0) foldRollIntoYaw();
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only. Where nothing has a camera (a server, the harness) every call is ignored.</li>
 * </ul>
 */
public final class CgCamera {

    private static int writes;

    private CgCamera() {
    }

    /** This frame's offset, holding until the next call: blocks along the world's axes, degrees, an FOV multiplier. */
    public static void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        writes++;
        camera().offset(x, y, z, yaw, pitch, roll, fovScale);
    }

    /** How many offsets have been set: a writer that reads it after its own call can tell when another replaced it. */
    public static int writes() {
        return writes;
    }

    /** The parts of an offset this host applies ({@link CgHostCamera#ROTATION}, {@code ROLL}, {@code FOV}). */
    public static int capabilities() {
        return camera().capabilities();
    }

    /** The parts whose hook has run since start: one in {@link #capabilities()} and missing here never fires. */
    public static int applied() {
        return camera().applied();
    }

    private static CgHostCamera camera() {
        return CgPlatform.get(CgHostCamera.SERVICE);
    }
}
