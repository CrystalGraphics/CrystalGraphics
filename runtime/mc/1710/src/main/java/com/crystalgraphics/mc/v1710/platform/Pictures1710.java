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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

import javax.annotation.Nullable;

/**
 * The 1.7.10 host of {@link CgHostPictures}: a {@code Framebuffer} of Minecraft's own per page, bound once; per cell its
 * rect cleared and the viewport on it, with the GUI's projection over the cell's units in raw GL, as 1.7.10's GUI sets
 * it (it keeps no state cache); the main framebuffer bound back. Its GUI writes alpha raw, so pages draw twice, over
 * black and white.
 */
final class Pictures1710 implements CgHostPictures.Host {

    /** The frame's partial tick, as the GUI event that fires the draw point carries it. */
    static float partialTick;

    /** What a cell's draw may change and the next must not see. */
    private static final int CELL_STATE = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
            | GL11.GL_LIGHTING_BIT;

    // What was bound as the batch began, put back after each page
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private int boundBefore = -1;
    private final int[] viewportBefore = new int[4];
    private boolean checking;
    private final Context context = new Context();

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
    public Object createTarget(int width, int height, float clear) {
        Framebuffer f = new Framebuffer(width, height, true);
        f.setFramebufferColor(clear, clear, clear, 0f);
        f.framebufferClear();
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

    // Each cell starts from the same state, the hotbar's for its slots: otherwise a cell starts from what the one before
    // it left, and the white pass's from the black's, and the matte between them is wrong where they differ
    @Override
    public void drawPage(Object target, int width, int height, float clear, CgHostPictures.Cells cells) {
        Framebuffer f = (Framebuffer) target;
        f.bindFramebuffer(false);
        GL11.glClearColor(clear, clear, clear, 0f);
        GL11.glClearDepth(1.0);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glPushAttrib(CELL_STATE);
        try {
            for (int i = 0; i < cells.count(); i++) {
                int w = cells.width(i), h = cells.height(i), x = cells.x(i), y = height - cells.y(i) - h;
                float scale = cells.scale(i), units = w / scale, unitsHigh = h / scale;
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(x, y, w, h);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glViewport(x, y, w, h);
                // The GUI's own projection and model-view, as setupOverlayRendering sets them, over the cell's units
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glOrtho(0.0, units, unitsHigh, 0.0, 1000.0, 3000.0);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glTranslatef(0f, 0f, -2000f);
                GL11.glColor4f(1f, 1f, 1f, 1f);
                GL11.glEnable(GL11.GL_BLEND);
                OpenGlHelper.glBlendFunc(770, 771, 1, 0);
                GL11.glEnable(GL12.GL_RESCALE_NORMAL);
                RenderHelper.enableGUIStandardItemLighting();
                context.set(units, unitsHigh, scale, partialTick);
                try {
                    cells.draw(i, context);
                } finally {
                    RenderHelper.disableStandardItemLighting();
                    GL11.glPopAttrib();
                    GL11.glPushAttrib(CELL_STATE);
                }
            }
        } finally {
            GL11.glPopAttrib();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            rebind();
        }
    }

    @Override
    public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                          CgPictureLighting lighting, CgPictureRenderer drawer) {
        drawCells((Framebuffer) target, width, height, scale, clear, rects, count, drawer);
    }

    // Each rect cleared (pixels from the top-left; GL's scissor is bottom-up), the rest kept, one drawer over the whole
    private void drawCells(Framebuffer f, int width, int height, float scale, float clear, int[] rects, int count,
                           CgPictureRenderer drawer) {
        float w = width / scale, h = height / scale;
        f.bindFramebuffer(true);
        GL11.glClearColor(clear, clear, clear, 0f);
        GL11.glClearDepth(1.0);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            GL11.glScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        // The GUI's own projection and model-view, as setupOverlayRendering sets them, over the picture's units
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, w, h, 0.0, 1000.0, 3000.0);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0f, 0f, -2000f);
        // Each clear's draw starts from the same state (see drawPage). 1.7.10 caches no GL state
        GL11.glPushAttrib(CELL_STATE);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            context.set(w, h, scale, partialTick);
            drawer.draw(context);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GL11.glPopAttrib();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
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
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundBefore);
            GL11.glViewport(viewportBefore[0], viewportBefore[1], viewportBefore[2], viewportBefore[3]);
        }
    }

    /** 1.7.10's GUI code draws in raw GL at once: no GUI object to hand over. One, set per cell. */
    private static final class Context implements CgHostDrawContext {
        private float width, height, scale, partialTick;

        void set(float width, float height, float scale, float partialTick) {
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
