package com.crystalgraphics.text.render;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Singleton registry and lifecycle backstop for all {@link CgTextRenderer} instances —
 * mirrors {@link com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry}'s ownership
 * model, but for text renderers instead of framebuffers.
 *
 * <p>Every {@link CgTextRenderer#createManualSized()}/{@link CgTextRenderer#create()} call
 * registers here. Individual owners ({@code CgUiPaintContext}, {@code HUDRenderer},
 * {@code CgFontDemo}, harness scenes, etc.) remain responsible for calling
 * {@link CgTextRenderer#delete()} promptly when they're done with a renderer — this
 * registry is a <strong>backstop</strong>, not the primary release path. {@link #deleteAll()}
 * (called from {@link com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle#destroyContext()})
 * sweeps any renderer still alive at GL context teardown, exactly like every other
 * GPU-resource registry in this codebase.</p>
 *
 * <h3>Screen-sized resize tracking</h3>
 * <p>Only renderers created via {@link CgTextRenderer#create()} — tracked via
 * {@link CgTextRenderer#isScreenSized()} — have their owned {@link CgTextRenderContext}
 * auto-resized on {@link #onResize}. Renderers created via the plain
 * {@link CgTextRenderer#createManualSized()} (offscreen FBO dumps, atlas captures, and other
 * fixed-size contexts unrelated to the display window — see {@code AtlasDumpScene},
 * {@code TextScene2D}, {@code WorldTextRenderHelper}) are still tracked for teardown, just
 * never auto-resized; their owner sizes the context explicitly.</p>
 *
 * <h3>World-context safety</h3>
 * <p>{@link CgTextRenderContext#updateOrtho} rebuilds the projection matrix in place —
 * calling it on a renderer whose context was swapped to a world context via
 * {@link CgTextRenderer#context(CgTextRenderContext)} would silently clobber the
 * perspective projection. {@link #onResize} skips any registered renderer whose current
 * context reports {@link CgTextRenderContext#isWorldText()}.</p>
 *
 * @see com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry
 */
public final class CgTextRendererRegistry {

    private static final CgTextRendererRegistry INSTANCE = new CgTextRendererRegistry();

    private final Set<CgTextRenderer> renderers = new LinkedHashSet<>();

    private CgTextRendererRegistry() {}

    /** Returns the global singleton registry. */
    public static CgTextRendererRegistry get() {
        return INSTANCE;
    }

    /**
     * Registers a renderer for teardown-time cleanup (and, if screen-sized, resize
     * propagation). Called by {@link CgTextRenderer#createManualSized()} — not a public entry point.
     */
    void register(CgTextRenderer renderer) {
        renderers.add(renderer);
    }

    /**
     * Unregisters a renderer. Called by {@link CgTextRenderer#delete()} — not a public
     * entry point.
     */
    void unregister(CgTextRenderer renderer) {
        renderers.remove(renderer);
    }

    /**
     * Notifies the registry that the window has been resized. Every registered
     * screen-sized renderer still in orthographic mode has its context resized in-place;
     * non-screen-sized renderers and any renderer currently in world mode
     * ({@link CgTextRenderContext#isWorldText()}) are skipped.
     *
     * <p>Called by {@link CgGraphicsLifecycle#onResize(int, int)}.</p>
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void onResize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        for (CgTextRenderer renderer : renderers) {
            if (renderer.isDeleted() || !renderer.isScreenSized()) continue;
            CgTextRenderContext context = renderer.context();
            if (context.isWorldText()) continue;
            context.updateOrtho(width, height);
        }
    }

    /**
     * Deletes every renderer still alive — a backstop for callers that never called
     * {@link CgTextRenderer#delete()} themselves — then clears all tracking. Safe to
     * call even when every renderer was already deleted by its owner ({@code delete()}
     * is idempotent). Called by
     * {@link CgGraphicsLifecycle#destroyContext()}.
     */
    public void deleteAll() {
        // Copy first: CgTextRenderer.delete() calls back into unregister(), which would
        // mutate `renderers` mid-iteration otherwise.
        List<CgTextRenderer> snapshot = new ArrayList<>(renderers);
        for (CgTextRenderer renderer : snapshot) {
            if (!renderer.isDeleted()) renderer.delete();
        }
        renderers.clear();
    }
}
