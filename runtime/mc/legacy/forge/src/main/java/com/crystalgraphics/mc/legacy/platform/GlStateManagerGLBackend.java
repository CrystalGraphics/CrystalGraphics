package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.lwjgl2.Lwjgl2GLBackend;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

/**
 * {@link Lwjgl2GLBackend} plus telling Minecraft what we changed: Forge 1.8–1.12.2's
 * {@link GlStateManager} caches GL state and skips calls it believes redundant, so a raw call from us
 * leaves its cache wrong and the damage lands on Minecraft's next draw.
 *
 * <p>Every override is a domain that cache covers, routed through its own method; everything else goes
 * to the driver from tier 1. The same methods exist on all three plateaus, measured on 1.8.9, 1.10.2 and
 * 1.12.2 — 1.9 only added to the class. {@code Blaze3dGLBackend} is the modern counterpart.</p>
 *
 * <ul>
 *   <li>The texture table has {@link #TRACKED_TEXTURE_UNITS} entries; a unit above it goes to the driver,
 *       and leaving one re-issues the switch raw, since the cache still holds the unit before it.</li>
 *   <li>Only {@code GL_TEXTURE_2D} bindings are cached.</li>
 * </ul>
 */
public final class GlStateManagerGLBackend extends Lwjgl2GLBackend {

    /** The units {@code GlStateManager.textureState} holds: 8 on every legacy plateau. */
    public static final int TRACKED_TEXTURE_UNITS = 8;

    private static final int GL_LIGHT_COUNT = 8;

    private int activeTextureUnit = 0;

    @Override
    public void glActiveTexture(int texture) {
        int unit = texture - GL13.GL_TEXTURE0;
        boolean leavingUntracked = activeTextureUnit >= TRACKED_TEXTURE_UNITS;
        activeTextureUnit = unit;
        if (unit >= TRACKED_TEXTURE_UNITS || leavingUntracked) super.glActiveTexture(texture);
        if (unit < TRACKED_TEXTURE_UNITS) GlStateManager.setActiveTexture(texture);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        if (target == GL11.GL_TEXTURE_2D && activeTextureUnit < TRACKED_TEXTURE_UNITS) {
            GlStateManager.bindTexture(texture);
            return;
        }
        super.glBindTexture(target, texture);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GlStateManager.deleteTexture(texture);
    }

    @Override
    public void glEnable(int cap) {
        if (!toggle(cap, true)) super.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        if (!toggle(cap, false)) super.glDisable(cap);
    }

    /** Routes a cap the cache models; false for one it does not. */
    private boolean toggle(int cap, boolean on) {
        switch (cap) {
            case GL11.GL_ALPHA_TEST:          if (on) GlStateManager.enableAlpha();          else GlStateManager.disableAlpha();          return true;
            case GL11.GL_BLEND:               if (on) GlStateManager.enableBlend();          else GlStateManager.disableBlend();          return true;
            case GL11.GL_DEPTH_TEST:          if (on) GlStateManager.enableDepth();          else GlStateManager.disableDepth();          return true;
            case GL11.GL_CULL_FACE:           if (on) GlStateManager.enableCull();           else GlStateManager.disableCull();           return true;
            case GL11.GL_POLYGON_OFFSET_FILL: if (on) GlStateManager.enablePolygonOffset();  else GlStateManager.disablePolygonOffset();  return true;
            case GL11.GL_LIGHTING:            if (on) GlStateManager.enableLighting();       else GlStateManager.disableLighting();       return true;
            case GL11.GL_FOG:                 if (on) GlStateManager.enableFog();            else GlStateManager.disableFog();            return true;
            case GL11.GL_COLOR_LOGIC_OP:      if (on) GlStateManager.enableColorLogic();     else GlStateManager.disableColorLogic();     return true;
            case GL11.GL_NORMALIZE:           if (on) GlStateManager.enableNormalize();      else GlStateManager.disableNormalize();      return true;
            case GL12.GL_RESCALE_NORMAL:      if (on) GlStateManager.enableRescaleNormal();  else GlStateManager.disableRescaleNormal();  return true;
            case GL11.GL_COLOR_MATERIAL:      if (on) GlStateManager.enableColorMaterial();  else GlStateManager.disableColorMaterial();  return true;
            case GL11.GL_TEXTURE_2D:
                if (activeTextureUnit >= TRACKED_TEXTURE_UNITS) return false;
                if (on) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
                return true;
            default:
                int light = cap - GL11.GL_LIGHT0;
                if (light < 0 || light >= GL_LIGHT_COUNT) return false;
                if (on) GlStateManager.enableLight(light); else GlStateManager.disableLight(light);
                return true;
        }
    }

    @Override
    public void glAlphaFunc(int func, float ref) {
        GlStateManager.alphaFunc(func, ref);
    }

    @Override
    public void glBlendFunc(int sfactor, int dfactor) {
        // Separate, so the cached alpha factors follow the ones GL just set.
        GlStateManager.tryBlendFuncSeparate(sfactor, dfactor, sfactor, dfactor);
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        GlStateManager.tryBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GlStateManager.depthMask(flag);
    }

    @Override
    public void glDepthFunc(int func) {
        GlStateManager.depthFunc(func);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GlStateManager.colorMask(red, green, blue, alpha);
    }

    @Override
    public void glClearColor(float r, float g, float b, float a) {
        GlStateManager.clearColor(r, g, b, a);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GlStateManager.doPolygonOffset(factor, units);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GlStateManager.viewport(x, y, width, height);
    }
}
