package com.crystalgraphics.world;

/**
 * One of an entity's two hands, as Minecraft's {@code InteractionHand} names them: the payload of a swing or a use
 * ({@link CgEntityEvents#SWING_STARTED}, {@link CgEntityEvents#USE_STARTED}).
 *
 * <pre>{@code
 * entity.on(CgEntityEvents.SWING_STARTED, (e, hand) -> {
 *     if (hand == CgHand.OFF) playOffhandSlash();
 * });
 * }</pre>
 *
 * Which arm it is depends on handedness: a left-handed entity's main hand is its left ({@link CgEntityState#leftHanded}).
 */
public enum CgHand {
    MAIN, OFF;

    /** As {@link CgEntityState}'s ints number them ({@link CgEntityState#MAIN}, {@link CgEntityState#OFF}). */
    public int index() {
        return ordinal();
    }

    /** The hand {@link CgEntityState} numbers {@code index}. */
    public static CgHand of(int index) {
        return index == CgEntityState.OFF ? OFF : MAIN;
    }
}
