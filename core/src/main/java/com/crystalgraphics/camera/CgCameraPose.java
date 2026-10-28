package com.crystalgraphics.camera;

import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.crystalgraphics.object.value.CgUnits;

/**
 * Where a camera is and where it looks, in the host's units and axes: a position, a turn and a horizontal field of view
 * in degrees. Mirrors Gameplay Cameras' {@code FCameraPose}: what every rig node reads and writes, and what a blend
 * interpolates.
 *
 * <pre>{@code
 * CgCameraPose pose = new CgCameraPose().location(x, y, z).fov(70);
 * pose.rotation().set(turn);                          // its forward is the turn of +x, its up of +y
 * pose.lerp(other, 0.25f);                            // a quarter of the way to other, as a blend does
 * }</pre>
 *
 * <ul>
 *   <li>Its turn of +x is where it looks and of +y its up: Unreal's forward and up through the host's axes, so a rig
 *       written in Unreal's terms means the same here.</li>
 *   <li>Mutable and owned: a rig and a stack each keep their own, and never hand one out to be kept.</li>
 * </ul>
 */
public final class CgCameraPose {

    /** The host FOV a pose has before anything sets one, in degrees. */
    public static final float DEFAULT_FOV = 90f;

    private final Vector3d location = new Vector3d();
    private final Quaternionf rotation = new Quaternionf();
    private float fov = DEFAULT_FOV;
    private final double[] from = new double[3], to = new double[3];
    private final float[] quat = new float[4];

    public Vector3d location() {
        return location;
    }

    public CgCameraPose location(double x, double y, double z) {
        location.set(x, y, z);
        return this;
    }

    /** Its turn, of +x (forward) and +y (up), in the host's axes. */
    public Quaternionf rotation() {
        return rotation;
    }

    /** Horizontal, in degrees. */
    public float fov() {
        return fov;
    }

    public CgCameraPose fov(float degrees) {
        fov = degrees;
        return this;
    }

    public CgCameraPose set(CgCameraPose other) {
        location.set(other.location);
        rotation.set(other.rotation);
        fov = other.fov;
        return this;
    }

    /**
     * {@code factor} of the way to {@code target}, as {@code FCameraPose::LerpAll} blends: 0 changes nothing and 1
     * becomes it; between, the location and FOV by {@code FMath::Lerp} and the turn as Unreal's rotator lerps it, each
     * of pitch, yaw and roll the shortest way.
     */
    public CgCameraPose lerp(CgCameraPose target, float factor) {
        if (factor == 0f) return this;
        if (factor == 1f) return set(target);
        location.lerp(target.location, factor);
        fov += (target.fov - fov) * factor;
        rotator(rotation, from);
        rotator(target.rotation, to);
        // FMath::Lerp<FRotator>: A + (B - A).GetNormalized() * Alpha (Rotator.h).
        double pitch = from[0] + normalizeAxis(to[0] - from[0]) * factor;
        double yaw = from[1] + normalizeAxis(to[1] - from[1]) * factor;
        double roll = from[2] + normalizeAxis(to[2] - from[2]) * factor;
        CgUnits.rotatorToHost(Math.toRadians(roll), Math.toRadians(pitch), Math.toRadians(yaw), quat);
        rotation.set(quat[0], quat[1], quat[2], quat[3]);
        return this;
    }

    /**
     * {@code q}'s rotator in Unreal's terms, {@code [pitch, yaw, roll]} in degrees: {@code FQuat::Rotator}
     * ({@code UnrealMath.cpp}) on the host's turn taken back through Unreal's axes.
     */
    public static double[] rotator(Quaternionf q, double[] out) {
        // CgUnits.rotatorToHost's axis swap is its own inverse: (-x, -z, -y, w).
        double x = -q.x, y = -q.z, z = -q.y, w = q.w;
        double singularity = z * x - w * y;
        double yawY = 2 * (w * z + x * y), yawX = 1 - 2 * (y * y + z * z);
        if (singularity < -0.4999995) {
            out[0] = -90;
            out[1] = normalizeAxis(-2 * Math.toDegrees(Math.atan2(x, w)));
            out[2] = 0;
        } else if (singularity > 0.4999995) {
            out[0] = 90;
            out[1] = normalizeAxis(2 * Math.toDegrees(Math.atan2(x, w)));
            out[2] = 0;
        } else {
            out[0] = Math.toDegrees(Math.asin(2 * singularity));
            out[1] = Math.toDegrees(Math.atan2(yawY, yawX));
            out[2] = Math.toDegrees(Math.atan2(-2 * (w * x + y * z), 1 - 2 * (x * x + y * y)));
        }
        return out;
    }

    /** {@code FRotator::NormalizeAxis}: into (-180, 180]. */
    public static double normalizeAxis(double degrees) {
        double a = degrees % 360;
        if (a < 0) a += 360;
        return a > 180 ? a - 360 : a;
    }

    @Override
    public String toString() {
        return String.format("CgCameraPose[%.3f %.3f %.3f, %s, fov %.2f]", location.x, location.y, location.z, rotation, fov);
    }
}
