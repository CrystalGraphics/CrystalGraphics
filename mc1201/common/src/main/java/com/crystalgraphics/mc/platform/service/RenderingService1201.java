package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.service.CgRenderingService;
import net.minecraft.client.Minecraft;

/**
 * MC 1.20.x implementation of {@link CgRenderingService}.
 *
 * <p>{@link #onFrameBegin} is called by {@code CgClientLifecycleBridge} each frame
 * after {@code GameRenderer.renderLevel()} returns.
 * Viewport dimensions are read from {@code Minecraft.getInstance().getWindow()}.</p>
 */
public final class RenderingService1201 implements CgRenderingService {

    @Override
    public void onFrameBegin(float partialTick) {
        CgRenderPipeline.getInstance().execute(partialTick);
    }

    @Override
    public int getDisplayWidth() {
        return Minecraft.getInstance().getWindow().getWidth();
    }

    @Override
    public int getDisplayHeight() {
        return Minecraft.getInstance().getWindow().getHeight();
    }
}
