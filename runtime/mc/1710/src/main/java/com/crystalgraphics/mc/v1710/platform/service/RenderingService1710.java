package com.crystalgraphics.mc.v1710.platform.service;

import com.crystalgraphics.platform.service.CgRenderingService;
import net.minecraft.client.Minecraft;

/** MC 1.7.10's {@link CgRenderingService}: the viewport from {@code Minecraft.displayWidth/displayHeight}. */
public final class RenderingService1710 implements CgRenderingService {


    @Override public int getDisplayWidth()  { return Minecraft.getMinecraft().displayWidth; }
    @Override public int getDisplayHeight() { return Minecraft.getMinecraft().displayHeight; }
}
