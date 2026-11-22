package com.crystalgraphics.mc.render;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.target.CgRenderTarget;

/**
 * The host of {@link CgHostPictures} where no Minecraft runs (the GL debug harness, a test): each picture a
 * {@link CgRenderTarget} the engine's own canvas draws into, so everything above the draw point (sizes, refresh,
 * budget, failure, the composite) runs as in a game.
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostPictures.HOST, new CgStandInPictures());
 *
 * // A renderer that also draws here: the stand-in hands the canvas as the GUI object
 * CgCustomPicture.of("mymod:radar").renderer(ctx -> {
 *     if (ctx.gui() instanceof CgCanvas canvas) canvas.fill(0, 0, ctx.width(), ctx.height(), 0xFF2A6F3F);
 *     else RadarScreen.draw((GuiGraphics) ctx.gui(), ctx.width(), ctx.height());
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A renderer written for Minecraft alone fails here (its cast throws): its picture shows the missing texture.</li>
 *   <li>Render thread, outside a stage firing's recording, as every draw point.</li>
 * </ul>
 */
public final class CgStandInPictures implements CgHostPictures.Host {

    private final CgCanvas canvas = new CgCanvas();
    private final boolean twoClears;

    /** Draws each picture once, keeping alpha. */
    public CgStandInPictures() {
        this(false);
    }

    /**
     * With {@code twoClears}, draws each picture over black and over white as a host whose GUI writes alpha raw does:
     * the harness's test of that composite.
     */
    public CgStandInPictures(boolean twoClears) {
        this.twoClears = twoClears;
    }

    @Override
    @Nullable
    public String absent() {
        return null;
    }

    @Override
    public boolean twoClears() {
        return twoClears;
    }

    @Override
    public boolean bottomUp() {
        return true;
    }

    @Override
    public Object createTarget(int width, int height) {
        return CgRenderTarget.create("stand_in_picture", width, height, CgRenderTarget.Format.RGBA8).clearColor(0f, 0f, 0f, 0f);
    }

    @Override
    public CgTexture texture(Object target) {
        return ((CgRenderTarget) target).texture();
    }

    @Override
    public void deleteTarget(Object target, @Nullable CgTexture texture) {
        ((CgRenderTarget) target).release();
    }

    @Override
    public void draw(Object target, int width, int height, float scale, float clear, CgPictureLighting lighting,
                     CgPictureRenderer drawer) {
        CgRenderTarget t = ((CgRenderTarget) target).clearColor(clear, clear, clear, 0f);
        try (CgExecutor.Into ignored = CgExecutor.into(t.beginDraw(false))) {
            canvas.beginNow(width, height);
            canvas.save().scale(scale, scale);
            try {
                drawer.draw(new Context(canvas, width / scale, height / scale, scale));
            } finally {
                canvas.restore();
                canvas.end();
            }
        }
        t.drawn();
    }

    /** The canvas as the GUI object, in the picture's units. */
    private record Context(CgCanvas gui, float width, float height, float scale) implements CgHostDrawContext {
        @Override public Object poseStack() { return null; }
        @Override public Object buffers() { return null; }
        @Override public float partialTick() { return 0f; }
    }
}
