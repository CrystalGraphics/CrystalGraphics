package com.crystalgraphics.mc.legacy.platform;

import net.minecraft.client.Minecraft;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
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

    /** What the open paint draws into: Minecraft's framebuffer. */
    private CgExecutor.Into into;

    @Override
    public void enter() {
        CgDeviceSection.begin();
        Minecraft mc = Minecraft.getMinecraft();
        // A GUI over the frame: the main target's colour alone, as Minecraft's GUI tests no world depth
        into = CgExecutor.intoColor(OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0, mc.displayWidth, mc.displayHeight);
    }

    @Override
    public void leave() {
        try {
            if (into != null) into.close();
        } finally {
            into = null;
            CgDeviceSection.end();
        }
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
    }
}
