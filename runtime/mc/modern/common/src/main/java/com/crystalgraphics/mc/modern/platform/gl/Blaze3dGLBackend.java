package com.crystalgraphics.mc.modern.platform.gl;

import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;

//? if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
*///?} else {
import com.mojang.blaze3d.platform.GlStateManager;
//?}
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;

/**
 * {@link Lwjgl3GLBackend} plus the one thing tier 1 cannot do: <b>telling Minecraft what we changed.</b>
 *
 * <p>Minecraft caches GL state in {@code GlStateManager} and elides calls it believes redundant. A raw
 * {@code glEnable(GL_BLEND)} from us therefore leaves its cache saying "off", and the next vanilla draw
 * that wants blending skips the enable — so the damage lands on <i>Minecraft's</i> next draw, not ours,
 * which is what makes it expensive to trace back to here.
 *
 * <p>So every method below overrides one whose domain that cache covers, and routes it through
 * {@code GlStateManager}'s own {@code _} methods — never {@code RenderSystem}'s wrappers, which only
 * forwarded to them and are gone from 1.21.5. Nothing else is overridden: the other ~110 entry points
 * reach the driver directly from tier 1, because Minecraft does not model them and telling it would be a
 * lie.
 *
 * <h3>The list is the contract</h3>
 *
 * <p>Measured on 1.20.1 and pinned by {@code Blaze3dMirrorTest}: {@code BLEND}, {@code DEPTH},
 * {@code CULL}, {@code POLY_OFFSET}, {@code STENCIL}, {@code SCISSOR}, {@code COLOR_MASK}, the active
 * unit and the per-unit texture bindings, plus the framebuffer and renderbuffer calls routed so its FBO
 * bookkeeping stays honest. 1.21.5 moved the class to {@code com.mojang.blaze3d.opengl} and stopped
 * modelling stencil, clear colour, renderbuffers and attachments, so those go to the driver there.
 *
 * <p><b>A missing override is a missing GL call, not an exception</b> — wrong rendering with nothing in
 * any log. {@code -Dcrystalgraphics.host.verify=true} is the runtime answer: it reads the driver after
 * each of our passes and compares it against {@code GlStateManager}'s own fields, naming the domain that
 * disagrees. The test is the compile-time half and only proves the list has not shrunk.
 *
 * <p>This class is per <b>era</b>: the {@code _} names are stable across 1.17–1.21.5. 1.13–1.16 need a
 * sibling against the un-prefixed {@code GlStateManager}, and the LWJGL2 pair needs none at all — its
 * backend has no host wrapper to route through.
 */
public final class Blaze3dGLBackend extends Lwjgl3GLBackend {

    /**
     * How many texture units Minecraft's own table models. Binding above it leaves the driver in a
     * state its shadow cannot represent, and the damage lands on whoever samples unit 0 next.
     */
    private static final int MC_TRACKED_TEXTURE_UNITS = 12;

    private int activeTextureUnit = 0;

    @Override
    public void bindFramebuffer(int target, int fbo) {
        GlStateManager._glBindFramebuffer(target, fbo);
    }

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                 int dstX0, int dstY0, int dstX1, int dstY1,
                                 int mask, int filter) {
        GlStateManager._glBlitFrameBuffer(srcX0, srcY0, srcX1, srcY1,
                dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    @Override
    public int genFramebuffers() {
        return GlStateManager.glGenFramebuffers();
    }

    @Override
    public void deleteFramebuffers(int fbo) {
        GlStateManager._glDeleteFramebuffers(fbo);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        if (target == GL11C.GL_TEXTURE_2D && activeTextureUnit < MC_TRACKED_TEXTURE_UNITS) {
            GlStateManager._bindTexture(texture);
            return;
        }
        // Minecraft tracks GL_TEXTURE_2D only; CG binds multiple targets.
        GL11C.glBindTexture(target, texture);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GlStateManager._deleteTexture(texture);
    }

    @Override
    public void glActiveTexture(int texture) {
        activeTextureUnit = texture - GL13C.GL_TEXTURE0;
        if (activeTextureUnit < MC_TRACKED_TEXTURE_UNITS) {
            GlStateManager._activeTexture(texture);
            return;
        }
        GL13C.glActiveTexture(texture);
    }

    @Override
    public void glTexParameteri(int target, int pname, int param) {
        GlStateManager._texParameter(target, pname, param);
    }

    @Override
    public void glEnable(int cap) {
        if (cap == GL_ALPHA_TEST_LEGACY)         throw new UnsupportedOperationException("GL_ALPHA_TEST is unavailable in OpenGL core profile (MC 1.20+)");
        if (cap == GL11C.GL_BLEND)               { GlStateManager._enableBlend();             return; }
        if (cap == GL11C.GL_DEPTH_TEST)          { GlStateManager._enableDepthTest();         return; }
        if (cap == GL11C.GL_CULL_FACE)           { GlStateManager._enableCull();              return; }
        if (cap == GL11C.GL_SCISSOR_TEST)        { GlStateManager._enableScissorTest();       return; }
        if (cap == GL11C.GL_POLYGON_OFFSET_FILL) { GlStateManager._enablePolygonOffset();     return; }
        GL11C.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        if (cap == GL_ALPHA_TEST_LEGACY)         throw new UnsupportedOperationException("GL_ALPHA_TEST is unavailable in OpenGL core profile (MC 1.20+)");
        if (cap == GL11C.GL_BLEND)               { GlStateManager._disableBlend();            return; }
        if (cap == GL11C.GL_DEPTH_TEST)          { GlStateManager._disableDepthTest();        return; }
        if (cap == GL11C.GL_CULL_FACE)           { GlStateManager._disableCull();             return; }
        if (cap == GL11C.GL_SCISSOR_TEST)        { GlStateManager._disableScissorTest();      return; }
        if (cap == GL11C.GL_POLYGON_OFFSET_FILL) { GlStateManager._disablePolygonOffset();    return; }
        GL11C.glDisable(cap);
    }

    @Override
    public void glBlendFunc(int sfactor, int dfactor) {
        // Separate, so the cached alpha factors follow the ones GL just set.
        GlStateManager._blendFuncSeparate(sfactor, dfactor, sfactor, dfactor);
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        GlStateManager._blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GlStateManager._depthMask(flag);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GlStateManager._viewport(x, y, width, height);
    }

    @Override
    public void glScissor(int x, int y, int width, int height) {
        GlStateManager._scissorBox(x, y, width, height);
    }

    @Override
    public void glPolygonMode(int face, int mode) {
        GlStateManager._polygonMode(face, mode);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GlStateManager._colorMask(red, green, blue, alpha);
    }

    @Override
    public void glDepthFunc(int func) {
        GlStateManager._depthFunc(func);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GlStateManager._polygonOffset(factor, units);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GlStateManager._pixelStore(pname, param);
    }

    // What 1.21.5 stopped modelling: tier 1's raw calls are the honest route there.
    //? if <1.21.5 {
    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        GlStateManager._glFramebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    @Override
    public int checkFramebufferStatus(int target) {
        return GlStateManager.glCheckFramebufferStatus(target);
    }

    @Override
    public void glStencilFunc(int func, int ref, int mask) {
        GlStateManager._stencilFunc(func, ref, mask);
    }

    @Override
    public void glStencilOp(int sfail, int dpfail, int dppass) {
        GlStateManager._stencilOp(sfail, dpfail, dppass);
    }

    @Override
    public void glClearColor(float r, float g, float b, float a) {
        GlStateManager._clearColor(r, g, b, a);
    }

    @Override
    public void glStencilMask(int mask) {
        GlStateManager._stencilMask(mask);
    }

    @Override
    public int glGenRenderbuffers() {
        return GlStateManager.glGenRenderbuffers();
    }

    @Override
    public void glDeleteRenderbuffers(int rbo) {
        GlStateManager._glDeleteRenderbuffers(rbo);
    }

    @Override
    public void glBindRenderbuffer(int target, int renderbuffer) {
        GlStateManager._glBindRenderbuffer(target, renderbuffer);
    }

    @Override
    public void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        GlStateManager._glRenderbufferStorage(target, internalFormat, width, height);
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment,
                                          int renderbufferTarget, int renderbuffer) {
        GlStateManager._glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
    }
    //?}
}
