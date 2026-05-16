package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.mixins.early.impl.client.CgRenderHook;
import com.crystalgraphics.platform.service.CgRenderingService;
import net.minecraft.client.Minecraft;

/**
 * MC 1.7.10 implementation of {@link CgRenderingService}.
 *
 * <p>{@link #onFrameBegin} is called directly by
 * {@link CgRenderHook} each frame.
 * Viewport dimensions read from {@code Minecraft.displayWidth/displayHeight}.</p>
 */
public final class RenderingService1710 implements CgRenderingService {

    @Override
    public void onFrameBegin(float partialTick) {
        CgRenderPipeline.getInstance().execute(partialTick);
    }

    @Override public int getDisplayWidth()  { return Minecraft.getMinecraft().displayWidth; }
    @Override public int getDisplayHeight() { return Minecraft.getMinecraft().displayHeight; }
}
