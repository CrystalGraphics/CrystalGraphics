package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.service.CgHostWindow;

import net.minecraft.client.Minecraft;

/** {@link CgHostWindow.Host} over Minecraft's display; provided by {@code PlatformServiceLegacy.install} on a client. */
public final class HostWindowLegacy implements CgHostWindow.Host {

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
