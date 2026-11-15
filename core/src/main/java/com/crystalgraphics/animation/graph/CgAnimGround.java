package com.crystalgraphics.animation.graph;

import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The ground under a character, as an anim node asks it on the game thread: Unreal's world sweep and character movement
 * component, as a foot placement node reads them through its proxy. An instance is handed one
 * ({@code CgAnimInstance.ground}); nodes read it in their {@code preUpdate}, never on the worker.
 *
 * <pre>{@code
 * // the engine's, over a level's collision: what a skeletal mesh component in the world hands its instance
 * anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.client(partialTick)));
 *
 * // a flat floor of your own, for a test or a preview
 * anim.ground(new CgAnimGround() {
 *     public boolean onGround() { return true; }
 *     public boolean floor(Vector3d point, Vector3f normal) { point.set(0, 0, 0); normal.set(0, 1, 0); return true; }
 *     public void velocity(Vector3d out) { out.zero(); }
 *     public boolean sweep(double x, double y, double z, float dx, float dy, float dz, double distance, double radius,
 *                          Vector3d point, Vector3f normal) { ... }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>World space, absolute, in doubles; +Y up.</li>
 *   <li>{@link #NONE} is Unreal's character with no movement component: on the ground, its floor where it stands, and
 *       nothing walkable to sweep, so a foot placement plants on the character's own plane.</li>
 * </ul>
 */
public interface CgAnimGround {

    /** No world: on the ground, at its own feet, nothing to sweep. */
    CgAnimGround NONE = new CgAnimGround() {
        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public boolean floor(Vector3d point, Vector3f normal) {
            return false;
        }

        @Override
        public void velocity(Vector3d out) {
            out.zero();
        }

        @Override
        public boolean sweep(double x, double y, double z, float dx, float dy, float dz, double distance, double radius,
                             Vector3d point, Vector3f normal) {
            return false;
        }
    };

    /** Whether the character walks on a floor: {@code MOVE_Walking} on a blocking floor, in Unreal's terms. */
    boolean onGround();

    /** The floor it stands on, into {@code point} and {@code normal}; false where it has none (in the air). */
    boolean floor(Vector3d point, Vector3f normal);

    /** Its velocity, blocks a second. */
    void velocity(Vector3d out);

    /**
     * A ball of {@code radius} swept from {@code (x, y, z)} along unit {@code (dx, dy, dz)} for {@code distance}: its first
     * walkable impact's point and normal, or false where it meets nothing walkable.
     */
    boolean sweep(double x, double y, double z, float dx, float dy, float dz, double distance, double radius,
                  Vector3d point, Vector3f normal);
}
