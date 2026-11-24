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
 * The legacy host of {@link CgHostPictures} (Forge 1.8.9 - 1.12.2): a {@code Framebuffer} of Minecraft's own per page,
 * bound once; per cell its rect cleared and the viewport on it, with the GUI's projection over the cell's units, through
 * {@code GlStateManager} so its cache stays true; the main framebuffer bound back. Legacy GUI code writes alpha raw, so
 * pages draw twice, over black and white.
 */
final class PicturesLegacy implements CgHostPictures.Host {

    // What was bound as the batch began, put back after each page
    // What was bound as the batch began, put back after each page: Minecraft's main framebuffer at the HUD, answered
    // from Minecraft once the first CHECKED batches' glGet agreed (a glGet is a round trip to the driver each frame);
    // one disagreement and every batch reads
    private static final int CHECKED = 120;
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private int boundBefore = -1;
    private final int[] viewportBefore = new int[4];
    private int agreed;
    private boolean reads;
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
        int main = Minecraft.getMinecraft().getFramebuffer().framebufferObject;
        if (!reads && agreed >= CHECKED) {
            boundBefore = main;
            return;
        }
        boundBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (boundBefore == main) {
            agreed++;
            return;
        }
        reads = true;
        // Not the main framebuffer: its viewport is put back too (the main one's binding sets its own)
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
    public void drawPage(Object target, int width, int height, float clear, CgHostPictures.Cells cells) {
        float partialTick = partialTick(Minecraft.getMinecraft());
        ((Framebuffer) target).bindFramebuffer(false);
        GlStateManager.clearColor(clear, clear, clear, 0f);
        GlStateManager.clearDepth(1.0);
        pushProjection();
        try {
            for (int i = 0; i < cells.count(); i++) {
                int w = cells.width(i), h = cells.height(i), x = cells.x(i), y = height - cells.y(i) - h;
                float scale = cells.scale(i);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(x, y, w, h);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GlStateManager.viewport(x, y, w, h);
                project(w / scale, h / scale);
                context.set(w / scale, h / scale, scale, partialTick);
                try {
                    cells.draw(i, context);
                } finally {
                    RenderHelper.disableStandardItemLighting();
                }
            }
        } finally {
            popProjection();
            rebind();
        }
    }

    @Override
    public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                          CgPictureLighting lighting, CgPictureRenderer drawer) {
        ((Framebuffer) target).bindFramebuffer(true);
        GlStateManager.clearColor(clear, clear, clear, 0f);
        GlStateManager.clearDepth(1.0);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            GL11.glScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        pushProjection();
        try {
            project(width / scale, height / scale);
            context.set(width / scale, height / scale, scale, partialTick(Minecraft.getMinecraft()));
            drawer.draw(context);
        } finally {
            RenderHelper.disableStandardItemLighting();
            popProjection();
            rebind();
        }
    }

    private static void pushProjection() {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
    }

    private static void popProjection() {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }

    // The GUI's own projection and model-view, as setupOverlayRendering sets them, over the units; a slot's light and
    // state, whatever the last cell's code left: a tooltip mod leaving depth off draws a glint over the whole cell and a
    // shield's back over its front
    private static void project(float w, float h) {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translate(0f, 0f, -2000f);
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Frees {@code f} outside a batch, the draw framebuffer kept: {@code deleteFramebuffer} binds 0. */
    static void delete(Framebuffer f) {
        int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        f.deleteFramebuffer();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, bound);
    }

    // What the batch found bound, back: after a page, and after making or freeing a target, which binds 0
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

    /** Legacy GUI code draws through {@code GlStateManager} at once: no GUI object to hand over. One, set per cell. */
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
