package com.crystalgraphics.mc.render.world;

/**
 * How an item is held or set down, Minecraft's {@code ItemDisplayContext}: each picks the item model's own transform
 * for that use, so a sword sits in a hand as a player holds it.
 *
 * <pre>{@code
 * CgItemStackComponent.of(scene, sword).display(CgItemDisplay.THIRD_PERSON_RIGHT_HAND).attach(body, "hand_r").spawn();
 * }</pre>
 */
public enum CgItemDisplay {
    /** The model as it is, no transform. */
    NONE,
    THIRD_PERSON_LEFT_HAND,
    THIRD_PERSON_RIGHT_HAND,
    FIRST_PERSON_LEFT_HAND,
    FIRST_PERSON_RIGHT_HAND,
    HEAD,
    GUI,
    /** Lying on the ground, as a dropped item. */
    GROUND,
    /** Hung in an item frame. */
    FIXED
}
