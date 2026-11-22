package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

import javax.annotation.Nullable;

/**
 * The legacy host of {@link CgHostPictures} (Forge 1.8.9 - 1.12.2): a {@code Framebuffer} of Minecraft's own per
 * picture, bound, with the GUI's projection over the picture's units, through {@code GlStateManager} so its cache stays
 * true; the main framebuffer bound back. Legacy GUI code writes alpha raw, so pictures draw twice, over black and white.
 */
final class PicturesLegacy implements CgHostPictures.Host {

    // What was bound as the batch began, put back after each picture
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private int boundBefore = -1;
    private final int[] viewportBefore = new int[4];
    private boolean checking;

    @Override
    @Nullable
    public String absent() {
        return OpenGlHelper.isFramebufferEnabled() ? null : "framebuffers are off (Fast Render or an old driver)";
    }

    @Override
    public boolean twoClears() {
        return true;
    }

    @Override
    public boolean bottomUp() {
        return true;
    }

    @Override
    public Object createTarget(int width, int height) {
        Framebuffer f = new Framebuffer(width, height, true);
        rebind();
        return f;
    }

    @Override
    public void deleteTarget(Object target, @Nullable CgTexture texture) {
        CgHostPictures.forget(texture);
        ((Framebuffer) target).deleteFramebuffer();
        rebind();
    }

    @Override
    public CgTexture texture(Object target) {
        return CgHostPictures.imported(((Framebuffer) target).framebufferTexture);
    }

    @Override
    public void beginBatch(boolean check) {
        checking = check;
        boundBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        for (int i = 0; i < 4; i++) viewportBefore[i] = VIEWPORT.get(i);
    }

    @Override
    @Nullable
    public String endBatch() {
        if (!checking) return null;
        int now = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        return now == boundBefore ? null : "the draw framebuffer was " + boundBefore + ", now " + now;
    }

    @Override
    public void draw(Object target, int width, int height, float scale, float clear, CgPictureLighting lighting,
                     CgPictureRenderer drawer) {
        draw((Framebuffer) target, width, height, scale, clear, null, 0, drawer);
    }

    @Override
    public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                          CgPictureLighting lighting, CgPictureRenderer drawer) {
        draw((Framebuffer) target, width, height, scale, clear, rects, count, drawer);
    }

    // The whole framebuffer cleared, or each rect (pixels from the top-left; GL's scissor is bottom-up), the rest kept
    private void draw(Framebuffer f, int width, int height, float scale, float clear, @Nullable int[] rects, int count,
                      CgPictureRenderer drawer) {
        Minecraft mc = Minecraft.getMinecraft();
        float w = width / scale, h = height / scale;
        if (rects == null) {
            f.setFramebufferColor(clear, clear, clear, 0f);
            f.framebufferClear();
            f.bindFramebuffer(true);
        } else {
            f.bindFramebuffer(true);
            GlStateManager.clearColor(clear, clear, clear, 0f);
            GlStateManager.clearDepth(1.0);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            for (int i = 0; i < count; i++) {
                int k = i * 4;
                GL11.glScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        // The GUI's own projection and model-view, as setupOverlayRendering sets them, over the picture's units
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0f, 0f, -2000f);
        RenderHelper.enableGUIStandardItemLighting();
        // A slot's state, whatever the last picture's code left: a tooltip mod leaving depth off draws a glint over the
        // whole cell and a shield's back over its front
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
        try {
            drawer.draw(new Context(w, h, scale, partialTick(mc)));
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            rebind();
        }
    }

    // What the batch found bound, back: after a picture, and after making or freeing a target, which binds 0
    private void rebind() {
        if (boundBefore < 0) return;
        Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
        if (boundBefore == main.framebufferObject) {
            main.bindFramebuffer(true);
        } else {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, boundBefore);
            GlStateManager.viewport(viewportBefore[0], viewportBefore[1], viewportBefore[2], viewportBefore[3]);
        }
    }

    private static float partialTick(Minecraft mc) {
        //? if <1.10 {
        /*return 0f;   // 1.8.9 keeps its timer private
        *///?} else {
        return mc.getRenderPartialTicks();
        //?}
    }

    /** Legacy GUI code draws through {@code GlStateManager} at once: no GUI object to hand over. */
    private static final class Context implements CgHostDrawContext {
        private final float width, height, scale, partialTick;

        Context(float width, float height, float scale, float partialTick) {
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.partialTick = partialTick;
        }

        @Override public Object gui() { return null; }
        @Override public Object poseStack() { return null; }
        @Override public Object buffers() { return null; }
        @Override public float width() { return width; }
        @Override public float height() { return height; }
        @Override public float scale() { return scale; }
        @Override public float partialTick() { return partialTick; }
    }
}
