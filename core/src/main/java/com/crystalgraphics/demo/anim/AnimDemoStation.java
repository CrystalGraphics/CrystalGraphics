package com.crystalgraphics.demo.anim;

/**
 * One station of the animation demo: a feature shown on its own set, with its own keys. {@link CgAnimDemo} stands each
 * along x, builds it once the casts have loaded, and hands it the frame and the keys while the camera is nearest it.
 *
 * <pre>{@code
 * final class StairsStation implements AnimDemoStation {
 *     public String name() { return "Feet on stairs"; }
 *     public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
 *         set.stairs(x, y, z - 2, AnimDemoSet.Facing.NORTH, 0xFF8A7A66);
 *         body = casts.humanoid().spawn().at(x, y, z, 0f);
 *     }
 *     ...
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Its keys are {@link CgAnimDemo#KEYS}' alone: the rest are Minecraft's or the demo's.</li>
 *   <li>Rebuilt whenever the demo is placed again: {@link #delete} must remove all it spawned.</li>
 * </ul>
 */
interface AnimDemoStation {

    /** Its title in the HUD. */
    String name();

    /** Builds its blocks and bodies around its origin ({@code x}, {@code y}, {@code z}), {@code y} the ground. */
    void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z);

    /** Where a camera stands to look at it, from its origin: x, y, z, then yaw and pitch in degrees (yaw 0 looks along -z). */
    float[] view();

    /** Each frame while it is the station in view, {@code dt} seconds on. */
    default void update(float dt) {
    }

    /** A key of {@link CgAnimDemo#KEYS} pressed while it is the station in view; true when it took it. */
    default boolean press(int key, boolean shift) {
        return false;
    }

    /** Its HUD lines, each starting with a line break: what it shows and its keys. */
    String hud();

    void delete();
}
