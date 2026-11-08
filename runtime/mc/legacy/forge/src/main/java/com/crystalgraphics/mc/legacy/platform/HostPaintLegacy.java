package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.service.CgHostPaint;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

/**
 * The GL discipline around anything that paints with CrystalGraphics inside Minecraft's frame: a screen, a HUD layer.
 *
 * <pre>{@code
 * CgHostPaint.enter();
 * try { paint(); } finally { CgHostPaint.leave(); }
 * }</pre>
 *
 * <p>A host section: its opening drops our state shadow, which {@code GlStateManager}'s writes never reach, and its
 * close sets back what {@code GlStateManager} caches. {@link #leave()} then puts back what Minecraft's present
 * assumes, through {@code GlStateManager} so its cache agrees with the driver.</p>
 */
public final class HostPaintLegacy implements CgHostPaint.Host {

    @Override
    public void enter() {
        CgGL.fromHost();
    }

    @Override
    public void leave() {
        CgGL.toHost();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
    }
}
