package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Two targets' pixels compared, on the render thread: what a check mode that drew one picture two ways asks. Read back
 * synchronously, so it stalls the pipeline -- diagnostics only.
 *
 * <pre>{@code
 * recording.callback("check", null, () -> {
 *     CgTargetCompare.Difference d = CgTargetCompare.compare(limited, whole, 0, 0, w, h);
 *     if (d != null) LOGGER.warn("{} pixels differ", d.pixels());
 * }, limited, whole);                     // read after both are drawn
 * }</pre>
 */
public final class CgTargetCompare {

    /** Where two targets differ: how many pixels, by how much at most in any channel, and their bounds, bottom-left. */
    public record Difference(int pixels, int worst, int x0, int y0, int x1, int y1) {
    }

    private static ByteBuffer first, second;

    private CgTargetCompare() {
    }

    /**
     * Null where {@code a} and {@code b} hold the same pixels over {@code x, y, width, height}, bottom-left; where they
     * differ, how, in the same pixels. Render thread, inside an execution in which both were made.
     * @see CgRecording#callback
     */
    @Nullable
    public static Difference compare(CgGraphTexture a, CgGraphTexture b, int x, int y, int width, int height) {
        CgFrameBuffer fa = a.framebuffer(), fb = b.framebuffer();
        if (fa == null || fb == null) return null;
        width = Math.min(width, Math.min(fa.getWidth(), fb.getWidth()) - x);
        height = Math.min(height, Math.min(fa.getHeight(), fb.getHeight()) - y);
        if (width <= 0 || height <= 0) return null;
        int bytes = width * height * 4;
        if (first == null || first.capacity() < bytes) {
            first = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            second = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        read(fa, x, y, width, height, first);
        read(fb, x, y, width, height, second);
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1, count = 0, worst = 0;
        for (int i = 0; i < width * height; i++) {
            int p = first.getInt(i * 4), q = second.getInt(i * 4);
            if (p == q) continue;
            for (int c = 0; c < 32; c += 8) worst = Math.max(worst, Math.abs(((p >>> c) & 0xFF) - ((q >>> c) & 0xFF)));
            int px = x + i % width, py = y + i / width;
            x0 = Math.min(x0, px);
            y0 = Math.min(y0, py);
            x1 = Math.max(x1, px);
            y1 = Math.max(y1, py);
            count++;
        }
        return count == 0 ? null : new Difference(count, worst, x0, y0, x1, y1);
    }

    private static void read(CgFrameBuffer target, int x, int y, int width, int height, ByteBuffer into) {
        target.bind();
        into.clear();
        CgGL.glReadPixels(x, y, width, height, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, into);
    }
}
