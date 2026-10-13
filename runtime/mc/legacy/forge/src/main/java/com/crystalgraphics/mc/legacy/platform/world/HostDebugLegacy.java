package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgHostDebug;
import net.minecraft.client.Minecraft;

/** {@link CgHostDebug} on Forge 1.8.9 to 1.12.2: F3+B. Client only: constructed by {@code PlatformServiceLegacy}. */
public final class HostDebugLegacy implements CgHostDebug {

    @Override
    public boolean hitboxes() {
        return Minecraft.getMinecraft().getRenderManager().isDebugBoundingBox();
    }
}
