package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
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
 * <p>Minecraft writes GL through {@code GlStateManager}, which CrystalGraphics' state shadow cannot see, and we write
 * through {@code CgGL}, which {@code GlStateManager}'s cache cannot see. {@link #enter()} drops our shadow;
 * {@link #leave()} puts back what Minecraft assumes and drops the shadow again.</p>
 */
public final class HostPaintLegacy implements CgHostPaint.Host {

    @Override
    public void enter() {
        CgGL.fromHost();
        CgGlState.invalidateAllIfPresent();
    }

    @Override
    public void leave() {
        CgGL.toHost();
        // Through GlStateManager, so Minecraft's own cache agrees with the driver it presents on.
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        CgGlState.invalidateAllIfPresent();
    }
}
