package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.platform.service.CgRenderingService;
import net.minecraft.client.Minecraft;

/** The modern {@link CgRenderingService}: the viewport from {@code Windows.of(Minecraft.getInstance())}. */
public final class RenderingService implements CgRenderingService {


    @Override
    public int getDisplayWidth() {
        return Windows.of(Minecraft.getInstance()).getWidth();
    }

    @Override
    public int getDisplayHeight() {
        return Windows.of(Minecraft.getInstance()).getHeight();
    }
}
