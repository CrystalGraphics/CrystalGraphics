package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

import javax.annotation.Nullable;

/**
 * The 1.7.10 host of {@link CgHostPictures}: a {@code Framebuffer} of Minecraft's own per picture, bound, with the
 * GUI's projection over the picture's units in raw GL, as 1.7.10's GUI sets it (it keeps no state cache); the main
 * framebuffer bound back. Its GUI writes alpha raw, so pictures draw twice, over black and white.
 */
final class Pictures1710 implements CgHostPictures.Host {

    /** The frame's partial tick, as the GUI event that fires the draw point carries it. */
    static float partialTick;

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
        return new Framebuffer(width, height, true);
    }

    @Override
    public void deleteTarget(Object target, @Nullable CgTexture texture) {
        CgHostPictures.forget(texture);
        ((Framebuffer) target).deleteFramebuffer();
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
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer f = (Framebuffer) target;
        float w = width / scale, h = height / scale;
        f.setFramebufferColor(clear, clear, clear, 0f);
        f.framebufferClear();
        f.bindFramebuffer(true);
        // The GUI's own projection and model-view, as setupOverlayRendering sets them, over the picture's units
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, w, h, 0.0, 1000.0, 3000.0);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0f, 0f, -2000f);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            drawer.draw(new Context(w, h, scale, partialTick));
        } finally {
            RenderHelper.disableStandardItemLighting();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            Framebuffer main = mc.getFramebuffer();
            if (boundBefore == main.framebufferObject) {
                main.bindFramebuffer(true);
            } else {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundBefore);
                GL11.glViewport(viewportBefore[0], viewportBefore[1], viewportBefore[2], viewportBefore[3]);
            }
        }
    }

    /** 1.7.10's GUI code draws in raw GL at once: no GUI object to hand over. */
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
