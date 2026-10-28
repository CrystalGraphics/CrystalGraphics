package com.crystalgraphics.platform.world;

import java.util.Objects;

/**
 * The host's camera setup reached for a frame: where core runs the world's frame up to its shakes ({@code WORLD_BEGIN}),
 * so a shake started this frame moves this frame's camera, terrain included. A host calls {@link #fire} at the head of
 * each camera hook it has, before it applies {@link CgHostCamera}'s offset; core listens.
 *
 * <pre>{@code
 * // a host's camera hook, before it applies the offset
 * CgWorldBegin.fire(partialTick);
 *
 * // a host with no camera hook (the harness): the world's frame runs at its first opaque stage instead, as expected
 * CgWorldBegin.atStage();
 * }</pre>
 *
 * <ul>
 *   <li>Call it every time the hook runs, the hand's and a shadow camera's included: core runs once a frame.</li>
 *   <li>{@code partialTick} is the frame's interpolation between game ticks, 0 to 1, as the render call has it.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public final class CgWorldBegin {

    /** What runs the world's frame from a hook: core's. */
    @FunctionalInterface
    public interface Listener {
        void begin(float partialTick);
    }

    private static volatile Listener listener = partialTick -> { };
    private static volatile boolean atStage;

    private CgWorldBegin() {
    }

    /** Core's, once: what {@link #fire} runs. */
    public static void listen(Listener listener) {
        CgWorldBegin.listener = Objects.requireNonNull(listener, "listener");
    }

    /** A camera hook ran this frame with {@code partialTick}. */
    public static void fire(float partialTick) {
        listener.begin(partialTick);
    }

    /** This host has no camera hook: a frame whose world runs at its first opaque stage is no fault. */
    public static void atStage() {
        atStage = true;
    }

    /** Whether {@link #atStage} was declared. */
    public static boolean isAtStage() {
        return atStage;
    }
}
