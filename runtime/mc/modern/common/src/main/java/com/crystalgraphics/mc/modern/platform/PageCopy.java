package com.crystalgraphics.mc.modern.platform;

//? if >=1.21.5 <1.21.6 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
*///?}

/**
 * Copies a picture's cell from the scratch target it was drawn into to its rect of the page, on 1.21.5 (the device's
 * rect is bottom-up; from 1.21.6 a page renders whole). It blits through framebuffers of its own, by
 * {@code GlStateManager} so its binding cache stays true: Mojang's GL {@code copyTextureToTexture} hands
 * {@code glBlitFramebuffer} widths where it takes corners, so a copy to a nonzero offset lands stretched.
 */
final class PageCopy {
    //? if >=1.21.5 <1.21.6 {
    /*private static final int READ = 36008, DRAW = 36009, COLOR_ATTACHMENT0 = 36064, TEXTURE_2D = 3553,
            COLOR_BUFFER_BIT = 16384, NEAREST = 9728;
    private int readFbo, drawFbo;

    void copy(RenderTarget from, RenderTarget to, int x, int y, int w, int h) {
        if (readFbo == 0) {
            readFbo = GlStateManager.glGenFramebuffers();
            drawFbo = GlStateManager.glGenFramebuffers();
        }
        int readBefore = GlStateManager.getFrameBuffer(READ), drawBefore = GlStateManager.getFrameBuffer(DRAW);
        GlStateManager._glBindFramebuffer(READ, readFbo);
        GlStateManager._glFramebufferTexture2D(READ, COLOR_ATTACHMENT0, TEXTURE_2D, (Integer) PicturesModern.handle(from), 0);
        GlStateManager._glBindFramebuffer(DRAW, drawFbo);
        GlStateManager._glFramebufferTexture2D(DRAW, COLOR_ATTACHMENT0, TEXTURE_2D, (Integer) PicturesModern.handle(to), 0);
        // A blit is scissored: off, as Mojang's own copy leaves it
        GlStateManager._disableScissorTest();
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, x, y, x + w, y + h, COLOR_BUFFER_BIT, NEAREST);
        GlStateManager._glBindFramebuffer(READ, readBefore);
        GlStateManager._glBindFramebuffer(DRAW, drawBefore);
    }
    *///?}
}
