package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
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
 * <p>Minecraft's present here is fixed-function and keeps no shadow of its own, so the repair on {@link #leave()} is raw
 * LWJGL: the texture unit, program and texture state Minecraft assumes next frame.</p>
 */
public final class HostPaint1710 implements CgHostPaint.Host {

    @Override
    public void enter() {
        CgGL.fromHost();
        CgGlState.invalidateAllIfPresent();
    }

    @Override
    public void leave() {
        CgGL.toHost();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        CgGlState.invalidateAllIfPresent();
    }
}
