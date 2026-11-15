package com.crystalgraphics.api.animation;

import java.util.Objects;

/**
 * A span of a clip whose root motion is bent so the character ends it at a target the game names: a rush that lands on
 * its opponent wherever they stand, a teleport strike behind them, a grab that reaches. The clip carries the window;
 * the character's {@code CgMotionWarping} holds where each named target is now.
 *
 * <pre>{@code
 * CgAnimSequence rush = clip.with(clip.settings().withWarpWindows(List.of(
 *         CgWarpWindow.of("opponent", 0.2f, 0.9f))));                         // translation and turn, the usual way
 * anim.motionWarping().target("opponent", x, y, z, yaw);                       // the game, before the window
 * }</pre>
 *
 * <pre>{@code
 * // turn to face the target while closing in, never faster than 720 degrees a second; height left alone
 * CgWarpWindow.of("opponent", 0.2f, 0.9f).facing(true).rotation(CgWarpWindow.Rotation.SLERP_WITH_CLAMPED_RATE, 1f, 720f)
 * }</pre>
 *
 * <ul>
 *   <li>Times are the clip's seconds; a window must lie within it.</li>
 *   <li>A window whose target the character has not been given plays its clip's own motion, and says nothing.</li>
 *   <li>Height is left as the clip has it unless {@link #ignoreHeight} is false.</li>
 * </ul>
 *
 * @param target               the warp target's name
 * @param start                where it opens, seconds
 * @param end                  where it closes, seconds
 * @param translation          whether the travel is bent toward the target
 * @param ignoreHeight         whether the target's height is ignored (the travel stays on the clip's plane)
 * @param rotation             whether the turn is bent toward the target's
 * @param facing               whether the target's own turn is ignored, the character turning to face it instead
 * @param method               how the turn is reached
 * @param rotationTime         how much of the window's remaining time the turn takes, 1 for all of it
 * @param maxRotationRate      degrees a second, for the clamped methods
 * @param maxSpeedRatio        the warped step's most over the clip's own (0: unbounded)
 * @param yawOffset            degrees added to the target's turn
 */
public record CgWarpWindow(String target, float start, float end, boolean translation, boolean ignoreHeight, boolean rotation,
                           boolean facing, Rotation method, float rotationTime, float maxRotationRate, float maxSpeedRatio,
                           float yawOffset) {

    /** How a window's turn reaches its target's. */
    public enum Rotation {
        /** Slerped toward it over the time left. */
        SLERP,
        /** As {@link #SLERP}, never faster than the max rate. */
        SLERP_WITH_CLAMPED_RATE,
        /** At the max rate, until reached. */
        CONSTANT_RATE,
        /** The clip's own turn scaled to end on it. */
        SCALE
    }

    public CgWarpWindow {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        if (!(end > start)) throw new IllegalArgumentException("a warp window ends after it starts: " + start + " to " + end);
    }

    /** A window toward {@code target}, translation and rotation warped, height ignored, slerped. */
    public static CgWarpWindow of(String target, float start, float end) {
        return new CgWarpWindow(target, start, end, true, true, true, false, Rotation.SLERP, 1f, 0f, 0f, 0f);
    }

    public CgWarpWindow translation(boolean on, boolean ignoreHeight) {
        return new CgWarpWindow(target, start, end, on, ignoreHeight, rotation, facing, method, rotationTime, maxRotationRate, maxSpeedRatio, yawOffset);
    }

    /** Whether the turn is warped and, if so, toward facing the target ({@code true}) or its own turn. */
    public CgWarpWindow facing(boolean facing) {
        return new CgWarpWindow(target, start, end, translation, ignoreHeight, true, facing, method, rotationTime, maxRotationRate, maxSpeedRatio, yawOffset);
    }

    public CgWarpWindow rotation(Rotation method, float timeMultiplier, float maxRateDegrees) {
        return new CgWarpWindow(target, start, end, translation, ignoreHeight, true, facing, method, timeMultiplier, maxRateDegrees, maxSpeedRatio, yawOffset);
    }

    public CgWarpWindow noRotation() {
        return new CgWarpWindow(target, start, end, translation, ignoreHeight, false, facing, method, rotationTime, maxRotationRate, maxSpeedRatio, yawOffset);
    }

    public CgWarpWindow maxSpeedRatio(float ratio) {
        return new CgWarpWindow(target, start, end, translation, ignoreHeight, rotation, facing, method, rotationTime, maxRotationRate, ratio, yawOffset);
    }

    public CgWarpWindow yawOffset(float degrees) {
        return new CgWarpWindow(target, start, end, translation, ignoreHeight, rotation, facing, method, rotationTime, maxRotationRate, maxSpeedRatio, degrees);
    }
}
