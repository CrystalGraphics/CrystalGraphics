package com.crystalgraphics.mc.container;

/**
 * What a click on a slot does: vanilla's click types, named the same on every version. A host maps by name, never by
 * position.
 *
 * <pre>{@code
 * CgContainers.act(id, new Action.Click(36, 0, ClickType.PICKUP));   // left-click the hotbar's first slot
 * CgContainers.act(id, new Action.Click(20, 2, ClickType.SWAP));     // number key 3 over slot 20
 * }</pre>
 */
public enum ClickType {
    /** Pick up, put down, swap with the cursor, or split: button {@code 0} left, {@code 1} right. */
    PICKUP,
    /** Shift-click: move the stack to the menu's other side. */
    QUICK_MOVE,
    /** A number key or the offhand key: swap with a hotbar slot, button {@code 0-8}, or {@code 40} for the offhand. */
    SWAP,
    /** Middle-click in creative: a full stack onto the cursor. */
    CLONE,
    /** The drop key: button {@code 0} one, {@code 1} the whole stack. */
    THROW,
    /** A drag across slots, in three clicks: see {@link Action.Click#dragStart}. */
    QUICK_CRAFT,
    /** Double-click: gather every matching stack onto the cursor. */
    PICKUP_ALL
}
