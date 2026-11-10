package com.crystalgraphics.mc.v1710.platform;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.Minecraft;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.service.CgHostPaint;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * The GL discipline around anything that paints with CrystalGraphics inside Minecraft's frame: a screen, a HUD layer.
 *
 * <pre>{@code
 * CgHostPaint.enter();
 * try { paint(); } finally { CgHostPaint.leave(); }
 * }</pre>
 *
 * <p>A host section: its opening drops the state shadow. Minecraft's present here is fixed-function and keeps no shadow
 * of its own, so the repair on {@link #leave()} is raw LWJGL: the texture unit, program and texture state Minecraft
 * assumes next frame.</p>
 */
public final class HostPaint1710 implements CgHostPaint.Host {

    /** What the open paint draws into: Minecraft's framebuffer. */
    private CgExecutor.Into into;

    @Override
    public void enter() {
        CgDeviceSection.begin();
        Minecraft mc = Minecraft.getMinecraft();
        into = CgExecutor.into(OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0, mc.displayWidth, mc.displayHeight);
    }

    @Override
    public void leave() {
        try {
            if (into != null) into.close();
        } finally {
            into = null;
            CgDeviceSection.end();
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
