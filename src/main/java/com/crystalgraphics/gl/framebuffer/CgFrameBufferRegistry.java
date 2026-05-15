package com.crystalgraphics.gl.framebuffer;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton registry and lifecycle manager for all owned {@link CgFrameBuffer} instances.
 *
 * <p>{@link CgFrameBuffer#create} delegates here via {@link #getOrCreate} — external callers
 * never interact with this class directly except through those static entry points.</p>
 *
 * <p>Screen-sized FBOs (created via {@link CgFrameBuffer#createScreenSized}) are resized
 * in-place on every window resize by {@link #onResize(int, int)} — the Java reference
 * remains valid and can be cached safely.</p>
 *
 * <p>All owned FBOs are deleted by {@link #deleteAll()}, called from
 * {@link CgGraphicsLifecycle#destroyContext()}.</p>
 *
 * @see CgFrameBuffer#create(String, int, int, CgFrameBufferFormat)
 * @see CgFrameBuffer#createScreenSized(String, CgFrameBufferFormat)
 */
public final class CgFrameBufferRegistry {

    private static final CgFrameBufferRegistry INSTANCE = new CgFrameBufferRegistry();

    private final Map<FrameBufferKey, CgFrameBuffer> framebuffers = new LinkedHashMap<>();

    /** Current width of main game window*/
    private int currentWidth = 0;
    /** Current height of main game window*/
    private int currentHeight = 0;

    private CgFrameBufferRegistry() {}

    /** Returns the global singleton registry. */
    public static CgFrameBufferRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns (or lazily creates) an owned FBO for the given name, dimensions, and format.
     * On a cache hit the existing FBO is returned regardless of whether its dimensions match.
     *
     * @param name   human-readable instance name (cache key together with {@code format})
     * @param width  width in pixels used only on a cache miss
     * @param height height in pixels used only on a cache miss
     * @param format attachment layout descriptor
     * @return the owned {@code CgFrameBuffer} for this name/format pair, never null
     */
    public CgFrameBuffer getOrCreate(String name, int width, int height, CgFrameBufferFormat format) {
        FrameBufferKey key = new FrameBufferKey(name, format);
        CgFrameBuffer existing = framebuffers.get(key);
        if (existing != null && !existing.isDeleted()) return existing;
        CgFrameBuffer fbo = CgFrameBuffer.createInternal(name, width, height, format);
        framebuffers.put(key, fbo);
        return fbo;
    }

    /**
     * Returns (or lazily creates) a screen-sized FBO for the given name and format.
     * The FBO is created at the current screen dimensions (as recorded by the most recent
     * {@link #onResize(int, int)} call, or 1×1 if never called).
     * The returned reference is stable — the FBO is resized in-place on window resize.
     *
     * @param name   human-readable instance name (cache key)
     * @param format attachment layout descriptor
     * @return the screen-sized FBO (never null)
     */
    public CgFrameBuffer acquireScreenSized(String name, CgFrameBufferFormat format) {
        int w = currentWidth > 0 ? currentWidth : 1;
        int h = currentHeight > 0 ? currentHeight : 1;
        CgFrameBuffer fbo = getOrCreate(name, w, h, format);
        fbo.screenSized = true;
        return fbo;
    }

    /**
     * Notifies the registry that the window has been resized.
     * All screen-sized FBOs are resized in-place — Java references remain valid.
     * 
     * Set by {@link CgGraphicsLifecycle#onResize(int, int)}
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void onResize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        currentWidth = width;
        currentHeight = height;
        for (CgFrameBuffer fbo : framebuffers.values())
            if (fbo.isScreenSized() && !fbo.isDeleted())
                fbo.resize(width, height);
    }

    /**
     * Deletes all owned FBOs and clears the registry.
     * Called by {@link CgGraphicsLifecycle#destroyContext()}.
     */
    public void deleteAll() {
        for (CgFrameBuffer fbo : framebuffers.values())
            if (!fbo.isDeleted()) fbo.delete();

        framebuffers.clear();
        currentWidth = 0;
        currentHeight = 0;
    }

    @Desugar
    private record FrameBufferKey(String name, CgFrameBufferFormat format) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FrameBufferKey)) return false;
            FrameBufferKey that = (FrameBufferKey) o;
            return name.equals(that.name) && format.equals(that.format);
        }
    }
}
