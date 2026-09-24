package com.crystalgraphics.mc.modern.platform.gl;

// 1.13 ships LWJGL 3.1, which has no core-profile GLxxC classes.
//? if >=1.14 {
import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;
//?} else {
/*import com.crystalgraphics.lwjgl3.Lwjgl31GLBackend;
*///?}
import com.crystalgraphics.mc.modern.platform.Blaze3dTextureUnits;

//? if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
*///?} elif >=1.15 {
import com.mojang.blaze3d.platform.GlStateManager;
//?}
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
//? if >=1.21.11 {
/*import org.lwjgl.opengl.GL33C;
*///?}

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
 * <p>This class is per <b>era</b>: the {@code _} names are stable across 1.15–1.21.5; 1.14 has them
 * without the prefix, and a same-package {@code GlStateManager} shim spells them the later way there. Below 1.17 the
 * table also covers the alpha test, and routes no scissor (1.16.1-1.16.3 have none). The LWJGL2 pair needs none
 * of this — its backend has no host wrapper to route through.
 */
//? if >=1.14 {
public final class Blaze3dGLBackend extends Lwjgl3GLBackend {
//?} else {
/*public final class Blaze3dGLBackend extends Lwjgl31GLBackend {
*///?}

    /**
     * How many texture units Minecraft's own table models. Binding above it leaves the driver in a
     * state its shadow cannot represent, and the damage lands on whoever samples unit 0 next.
     */
    private final int trackedTextureUnits = Blaze3dTextureUnits.count();

    private int activeTextureUnit = 0;

    // 1.14 keeps its framebuffer calls in GLX, which caches nothing: tier 1 below 1.15.
    //? if >=1.15 {
    @Override
    public void bindFramebuffer(int target, int fbo) {
        GlStateManager._glBindFramebuffer(target, fbo);
    }
    //?}

    // Blaze3D models the blit from 1.16 and the scissor from 1.16.4, which shares a node with 1.16.1: below
    // those, tier 1 reaches the driver.
    //? if >=1.16 {
    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                 int dstX0, int dstY0, int dstX1, int dstY1,
                                 int mask, int filter) {
        GlStateManager._glBlitFrameBuffer(srcX0, srcY0, srcX1, srcY1,
                dstX0, dstY0, dstX1, dstY1, mask, filter);
    }
    //?}

    //? if >=1.15 {
    @Override
    public int genFramebuffers() {
        return GlStateManager.glGenFramebuffers();
    }

    @Override
    public void deleteFramebuffers(int fbo) {
        GlStateManager._glDeleteFramebuffers(fbo);
    }
    //?}

    @Override
    public void glBindTexture(int target, int texture) {
        if (target == GL11.GL_TEXTURE_2D && activeTextureUnit < trackedTextureUnits) {
            GlStateManager._bindTexture(texture);
            // 1.21.11 samples through sampler objects it leaves bound, always with a mipmapped min filter;
            // over our mip-less textures that is incomplete and reads black. It rebinds its own per draw.
            //? if >=1.21.11 {
            /*GL33C.glBindSampler(activeTextureUnit, 0);
            *///?}
            return;
        }
        // Minecraft tracks GL_TEXTURE_2D only; CG binds multiple targets.
        super.glBindTexture(target, texture);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GlStateManager._deleteTexture(texture);
    }

    @Override
    public void glActiveTexture(int texture) {
        activeTextureUnit = texture - GL13.GL_TEXTURE0;
        if (activeTextureUnit < trackedTextureUnits) {
            GlStateManager._activeTexture(texture);
            return;
        }
        super.glActiveTexture(texture);
    }

    // 1.13's GlStateManager has no texParameter, pixelStore or stencil calls, and caches none of them.
    //? if >=1.14 {
    @Override
    public void glTexParameteri(int target, int pname, int param) {
        GlStateManager._texParameter(target, pname, param);
    }
    //?}

    @Override
    public void glEnable(int cap) {
        // Before 1.17 the context is a compatibility profile and Blaze3D still tracks the alpha test.
        //? if <1.17 {
        /*if (cap == GL_ALPHA_TEST_LEGACY)         { GlStateManager._enableAlphaTest();      return; }
        *///?}
        if (cap == GL11.GL_BLEND)                { GlStateManager._enableBlend();             return; }
        if (cap == GL11.GL_DEPTH_TEST)           { GlStateManager._enableDepthTest();         return; }
        if (cap == GL11.GL_CULL_FACE)            { GlStateManager._enableCull();              return; }
        //? if >=1.17 {
        if (cap == GL11.GL_SCISSOR_TEST)         { GlStateManager._enableScissorTest();       return; }
        //?}
        if (cap == GL11.GL_POLYGON_OFFSET_FILL)  { GlStateManager._enablePolygonOffset();     return; }
        super.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        //? if <1.17 {
        /*if (cap == GL_ALPHA_TEST_LEGACY)         { GlStateManager._disableAlphaTest();     return; }
        *///?}
        if (cap == GL11.GL_BLEND)                { GlStateManager._disableBlend();            return; }
        if (cap == GL11.GL_DEPTH_TEST)           { GlStateManager._disableDepthTest();        return; }
        if (cap == GL11.GL_CULL_FACE)            { GlStateManager._disableCull();             return; }
        //? if >=1.17 {
        if (cap == GL11.GL_SCISSOR_TEST)         { GlStateManager._disableScissorTest();      return; }
        //?}
        if (cap == GL11.GL_POLYGON_OFFSET_FILL)  { GlStateManager._disablePolygonOffset();    return; }
        super.glDisable(cap);
    }

    //? if <1.17 {
    /*@Override
    public void glAlphaFunc(int func, float ref) {
        GlStateManager._alphaFunc(func, ref);
    }
    *///?}

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

    //? if >=1.17 {
    @Override
    public void glScissor(int x, int y, int width, int height) {
        GlStateManager._scissorBox(x, y, width, height);
    }
    //?}

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

    //? if >=1.14 {
    @Override
    public void glPixelStorei(int pname, int param) {
        GlStateManager._pixelStore(pname, param);
    }
    //?}

    // What 1.21.5 stopped modelling: tier 1's raw calls are the honest route there.
    //? if >=1.15 <1.21.5 {
    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        GlStateManager._glFramebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    @Override
    public int checkFramebufferStatus(int target) {
        return GlStateManager.glCheckFramebufferStatus(target);
    }
    //?}

    //? if >=1.14 <1.21.5 {
    @Override
    public void glStencilFunc(int func, int ref, int mask) {
        GlStateManager._stencilFunc(func, ref, mask);
    }

    @Override
    public void glStencilOp(int sfail, int dpfail, int dppass) {
        GlStateManager._stencilOp(sfail, dpfail, dppass);
    }

    @Override
    public void glStencilMask(int mask) {
        GlStateManager._stencilMask(mask);
    }
    //?}

    //? if <1.21.5 {
    @Override
    public void glClearColor(float r, float g, float b, float a) {
        GlStateManager._clearColor(r, g, b, a);
    }
    //?}

    // 1.16 has no renderbuffer calls, and 1.15's cache nothing: tier 1 below 1.17.
    //? if >=1.17 <1.21.5 {
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
