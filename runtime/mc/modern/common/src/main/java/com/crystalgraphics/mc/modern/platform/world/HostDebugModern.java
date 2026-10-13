package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.world.CgHostDebug;

import net.minecraft.client.Minecraft;
//? if >=1.21.10 {
/*import net.minecraft.client.gui.components.debug.DebugScreenEntries;
*///?}

/** {@link CgHostDebug} for the modern hosts: F3+B. Client only: constructed by {@code PlatformServiceModern.gl()}. */
public final class HostDebugModern implements CgHostDebug {

    @Override
    public boolean hitboxes() {
        //? if >=1.21.10 {
        /*return Minecraft.getInstance().debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES);
        *///?} else {
        return Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes();
        //?}
    }
}
