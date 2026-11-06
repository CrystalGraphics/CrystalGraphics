package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.service.CgHostWindow;

import net.minecraft.client.Minecraft;

/** {@link CgHostWindow.Host} over Minecraft's window; provided by {@code PlatformServiceModern.installClient}. */
public final class HostWindowModern implements CgHostWindow.Host {

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || Windows.of(mc) == null ? 0 : Windows.of(mc).getWidth();
    }

    @Override
    public int height() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || Windows.of(mc) == null ? 0 : Windows.of(mc).getHeight();
    }
}
