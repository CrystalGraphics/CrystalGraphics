package com.crystalgraphics.mc.render;

/**
 * The light Minecraft sets up before a picture draws, as its own call sites choose. Mojang's {@code Lighting.Entry}.
 *
 * <pre>{@code
 * CgCustomPicture.of("mymod:statue").lighting(CgPictureLighting.ENTITY_IN_UI).renderer(Statue::draw);
 * }</pre>
 */
public enum CgPictureLighting {
    /** A flat item's: lit from the front ({@code Lighting.setupForFlatItems}). */
    ITEMS_FLAT,
    /** A block item's and the GUI's default ({@code Lighting.setupFor3DItems}). */
    ITEMS_3D,
    /** An entity in a screen, as the inventory's player ({@code Lighting.setupForEntityInInventory}). */
    ENTITY_IN_UI
}
