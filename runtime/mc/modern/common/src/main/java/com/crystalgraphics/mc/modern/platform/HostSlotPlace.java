package com.crystalgraphics.mc.modern.platform;

/**
 * A game slot whose position can be moved: implemented on {@code Slot} by each loader's {@code SlotPlaceHook} (Mojang's
 * field names) or {@code SlotPlaceSrgHook} (an installed Forge's), since {@code x} and {@code y} are final from 1.15.2.
 * Where neither applied, a slot is not one and keeps its position.
 */
public interface HostSlotPlace {

    void crystalgraphics$place(int x, int y);
}
