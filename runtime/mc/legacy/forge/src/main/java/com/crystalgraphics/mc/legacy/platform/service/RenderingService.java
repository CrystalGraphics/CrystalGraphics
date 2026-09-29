package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.service.CgRenderingService;
import net.minecraft.client.Minecraft;

/** Forge 1.8–1.12.2's {@link CgRenderingService}. */
public final class RenderingService implements CgRenderingService {

    @Override
    public void onFrameBegin(float partialTick) {
        CgRenderPipeline.getInstance().execute(partialTick);
    }

    @Override public int getDisplayWidth()  { return Minecraft.getMinecraft().displayWidth; }
    @Override public int getDisplayHeight() { return Minecraft.getMinecraft().displayHeight; }
}
