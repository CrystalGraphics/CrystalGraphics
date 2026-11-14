package com.crystalgraphics.mc.container;

/** What a slot is, from the container that holds it. */
public enum SlotKind {
    /** One of the container's own slots: a chest's, a furnace's input. */
    CONTAINER,
    /** The player's main inventory, 27 slots. */
    INVENTORY,
    /** The player's hotbar, 9 slots. */
    HOTBAR,
    /** The player's armour. */
    ARMOR,
    /** The player's offhand (1.9 and later). */
    OFFHAND,
    /** A slot nothing can be put in: a crafting result, a furnace's output, a merchant's payment result. */
    RESULT
}
