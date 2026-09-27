package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.service.CgRenderingService;
import net.minecraft.client.Minecraft;

/**
 * MC 1.20.x implementation of {@link CgRenderingService}.
 *
 * <p>{@link #onFrameBegin} is called by {@code CgClientLifecycleBridge} each frame
 * after {@code GameRenderer.renderLevel()} returns.
 * Viewport dimensions are read from {@code Windows.of(Minecraft.getInstance())}.</p>
 */
public final class RenderingService implements CgRenderingService {

    @Override
    public void onFrameBegin(float partialTick) {
        CgRenderPipeline.getInstance().execute(partialTick);
    }

    @Override
    public int getDisplayWidth() {
        return Windows.of(Minecraft.getInstance()).getWidth();
    }

    @Override
    public int getDisplayHeight() {
        return Windows.of(Minecraft.getInstance()).getHeight();
    }
}
