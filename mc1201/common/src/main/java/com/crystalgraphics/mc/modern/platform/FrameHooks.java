package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;

import net.minecraft.client.Minecraft;

/**
 * End-of-frame lifecycle for MC 1.20.x: the resize check and {@link CgGraphicsLifecycle#tickFrame()}.
 *
 * <p>Each loader calls {@link #endFrame()} once per frame from its own render event. Without it the
 * screen-sized FBO registry never learns the window changed, so the UI keeps rendering at the previous
 * size after a resize.</p>
 *
 * <p>Resize is polled rather than subscribed: 1.20.1 Forge has no window-resize event, and polling two
 * ints once a frame is cheaper than a mixin per loader. The first call always reports a resize, which
 * is what sizes the targets initially.</p>
 */
public final class FrameHooks {

    private FrameHooks() {}

    private static int lastWidth = -1;
    private static int lastHeight = -1;

    public static void endFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) {
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                lastWidth = width;
                lastHeight = height;
                CgGraphicsLifecycle.onResize(width, height);
            }
        }
        CgGraphicsLifecycle.tickFrame();
    }

    /** Forgets the last known size, so the next {@link #endFrame()} resizes. For context teardown. */
    public static void reset() {
        lastWidth = -1;
        lastHeight = -1;
    }
}
