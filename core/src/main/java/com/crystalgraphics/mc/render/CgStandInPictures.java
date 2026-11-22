package com.crystalgraphics.mc.render;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.target.CgRenderTarget;

/**
 * The host of {@link CgHostPictures} where no Minecraft runs (the GL debug harness, a test): each page a
 * {@link CgRenderTarget} the engine's own canvas draws its cells into, so everything above the draw point (pages, sizes,
 * refresh, budget, failure, the composite) runs as in a game.
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
    private final CellContext context = new CellContext(canvas);
    /** Targets drawn into once: cleared then, kept by a cell draw after. */
    private final Set<Object> started = Collections.newSetFromMap(new IdentityHashMap<>());
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
    public Object createTarget(int width, int height, float clear) {
        return CgRenderTarget.create("stand_in_picture", width, height, CgRenderTarget.Format.RGBA8)
                .clearColor(clear, clear, clear, 0f);
    }

    @Override
    public CgTexture texture(Object target) {
        return ((CgRenderTarget) target).texture();
    }

    @Override
    public void deleteTarget(Object target, @Nullable CgTexture texture) {
        started.remove(target);
        ((CgRenderTarget) target).release();
    }

    @Override
    public void drawPage(Object target, int width, int height, float clear, CgHostPictures.Cells cells) {
        CgRenderTarget t = ((CgRenderTarget) target).clearColor(clear, clear, clear, 0f);
        try (CgExecutor.Into ignored = CgExecutor.into(started.add(t) ? t.beginDraw(false) : t.beginDrawKept(false))) {
            canvas.beginNow(width, height);
            try {
                for (int i = 0; i < cells.count(); i++) {
                    float scale = cells.scale(i);
                    context.set(cells.width(i) / scale, cells.height(i) / scale, scale);
                    canvas.save().clip(cells.x(i), cells.y(i), cells.width(i), cells.height(i));
                    clearCell(cells.x(i), cells.y(i), cells.width(i), cells.height(i), clear);
                    canvas.translate(cells.x(i), cells.y(i)).scale(scale, scale);
                    try {
                        cells.draw(i, context);
                    } finally {
                        canvas.restore();
                    }
                }
            } finally {
                canvas.end();
            }
        }
        t.drawn();
    }

    // A cell back to (clear, clear, clear, 0) on a kept page. The canvas has no clear: a MASK by alpha 0 empties it, and
    // over white an opaque white added then an opaque black taken away leaves white at alpha 0
    private void clearCell(int x, int y, int w, int h, float clear) {
        canvas.box(x, y, w, h).fill(0x00000000).blend(CgBlendMode.MASK).draw();
        if (clear <= 0f) return;
        canvas.box(x, y, w, h).fill(0xFFFFFFFF).blend(CgBlendMode.ADDITIVE).draw();
        canvas.box(x, y, w, h).fill(0xFF000000).blend(CgBlendMode.SUBTRACT).draw();
    }

    /**
     * Cleared whole the first time it is drawn into, kept after: what the canvas draws a cell over replaces it, as the
     * stand-in's icons are opaque squares filling their cell.
     */
    @Override
    public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                          CgPictureLighting lighting, CgPictureRenderer drawer) {
        CgRenderTarget t = ((CgRenderTarget) target).clearColor(clear, clear, clear, 0f);
        drawInto(started.add(t) ? t.beginDraw(false) : t.beginDrawKept(false), width, height, scale, drawer);
        t.drawn();
    }

    private void drawInto(CgExternalTarget into, int width, int height, float scale, CgPictureRenderer drawer) {
        try (CgExecutor.Into ignored = CgExecutor.into(into)) {
            canvas.beginNow(width, height);
            canvas.save().scale(scale, scale);
            try {
                drawer.draw(new Context(canvas, width / scale, height / scale, scale));
            } finally {
                canvas.restore();
                canvas.end();
            }
        }
    }

    /** The canvas as the GUI object, in the picture's units. */
    private record Context(CgCanvas gui, float width, float height, float scale) implements CgHostDrawContext {
        @Override public Object poseStack() { return null; }
        @Override public Object buffers() { return null; }
        @Override public float partialTick() { return 0f; }
    }

    /** The canvas as the GUI object for one cell after another: one, reset per cell. */
    private static final class CellContext implements CgHostDrawContext {
        private final CgCanvas canvas;
        private float width, height, scale;

        CellContext(CgCanvas canvas) {
            this.canvas = canvas;
        }

        void set(float width, float height, float scale) {
            this.width = width;
            this.height = height;
            this.scale = scale;
        }

        @Override public Object gui() { return canvas; }
        @Override public Object poseStack() { return null; }
        @Override public Object buffers() { return null; }
        @Override public float width() { return width; }
        @Override public float height() { return height; }
        @Override public float scale() { return scale; }
        @Override public float partialTick() { return 0f; }
    }
}
