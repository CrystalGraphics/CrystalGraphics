package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.platform.service.CgHostWindow;

import net.minecraft.client.Minecraft;

/** {@link CgHostWindow.Host} over Minecraft's display; provided by {@code PlatformService1710.install} on a client. */
public final class HostWindow1710 implements CgHostWindow.Host {

    @Override
    public int width() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? 0 : mc.displayWidth;
    }

    @Override
    public int height() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? 0 : mc.displayHeight;
    }
}
