package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgHostDebug;
import net.minecraft.client.renderer.entity.RenderManager;

/** {@link CgHostDebug} on Minecraft 1.7.10: F3+B. Client only: constructed by {@code PlatformService1710}. */
public final class HostDebug1710 implements CgHostDebug {

    @Override
    public boolean hitboxes() {
        return RenderManager.debugBoundingBox;
    }
}
