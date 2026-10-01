package com.crystalgraphics.gl.texture;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Texture work queued where no GL may run — a recording, a worker — and applied on the render thread before anything
 * executes. {@code CgExecutor} applies it ahead of every frame, so a draw recorded after the work was queued always
 * finds it done.
 *
 * <pre>{@code
 * CgTexture2DArray atlas = CgTexture2DArray.allocateDeferred(1024, 1024, 1, spec);  // any thread, no GL
 * atlas.uploadLayerRegion(0, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, bytes);          // queued
 * CgTextureUploads.apply();                                                          // render thread
 * }</pre>
 */
public final class CgTextureUploads {

    /** A texture with queued work. */
    interface Pending {
        /** Applies the queued work, in the order it was queued. Render thread. */
        void applyPending();
    }

    private static final Set<Pending> PENDING = new LinkedHashSet<>();

    private CgTextureUploads() {}

    static void schedule(Pending texture) {
        synchronized (PENDING) {
            PENDING.add(texture);
        }
    }

    static void cancel(Pending texture) {
        synchronized (PENDING) {
            PENDING.remove(texture);
        }
    }

    /** Applies everything queued, oldest texture first. Render thread; cheap when nothing is queued. */
    public static void apply() {
        Pending[] work;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return;
            work = PENDING.toArray(new Pending[0]);
            PENDING.clear();
        }
        for (Pending texture : work) texture.applyPending();
    }
}
