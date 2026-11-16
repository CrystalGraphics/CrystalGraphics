package com.crystalgraphics.animation.locomotion;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Where a character moving on the ground comes to rest, or turns round, under friction and braking: what a stop or a
 * pivot is distance matched to ({@link CgDistanceMatching#timeAtDistance}). Horizontal is x and z; y is up.
 *
 * <pre>{@code
 * Vector3f stop = CgMovementPrediction.stopLocation(velocity, 8f, 20f, new Vector3f());   // from the character, metres
 * float toGo = stop.length();
 * Vector3f pivot = CgMovementPrediction.pivotLocation(acceleration, velocity, 8f, new Vector3f());
 * }</pre>
 */
public final class CgMovementPrediction {

    private CgMovementPrediction() {
    }

    /**
     * Where a character at {@code velocity} stops from here, braking at {@code deceleration} (metres a second squared)
     * with {@code friction} (its speed's share lost a second): zero where nothing slows it.
     */
    public static Vector3f stopLocation(Vector3fc velocity, float friction, float deceleration, Vector3f out) {
        float f = Math.max(0f, friction), b = Math.max(0f, deceleration);
        float vx = velocity.x(), vz = velocity.z(), speed = (float) Math.sqrt(vx * vx + vz * vz);
        out.zero();
        float divisor = f * speed + b;
        if (divisor > 0f && speed > 0f) {
            float time = speed / divisor, dx = vx / speed, dz = vz / speed;
            out.set(vx * time + 0.5f * (-f * vx - b * dx) * time * time, 0f, vz * time + 0.5f * (-f * vz - b * dz) * time * time);
        }
        return out;
    }

    /**
     * Where a character at {@code velocity}, accelerating against it by {@code acceleration}, turns round: zero while it
     * accelerates along its way.
     */
    public static Vector3f pivotLocation(Vector3fc acceleration, Vector3fc velocity, float friction, Vector3f out) {
        out.zero();
        float ax = acceleration.x(), az = acceleration.z(), size = (float) Math.sqrt(ax * ax + az * az);
        if (size < 1e-8f) return out;
        float dx = ax / size, dz = az / size;
        float along = velocity.x() * dx + velocity.z() * dz;
        if (along < 0f) {
            float speedAlong = -along, flat = (float) Math.sqrt(velocity.x() * velocity.x() + velocity.z() * velocity.z());
            float time = speedAlong / (size + 2f * speedAlong * friction);
            float fx = acceleration.x() - (velocity.x() - dx * flat) * friction, fy = acceleration.y() - velocity.y() * friction,
                    fz = acceleration.z() - (velocity.z() - dz * flat) * friction;
            out.set(velocity.x() * time + 0.5f * fx * time * time, velocity.y() * time + 0.5f * fy * time * time,
                    velocity.z() * time + 0.5f * fz * time * time);
        }
        return out;
    }
}
