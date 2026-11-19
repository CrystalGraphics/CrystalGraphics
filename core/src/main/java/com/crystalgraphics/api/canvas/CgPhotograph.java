package com.crystalgraphics.api.canvas;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;

/**
 * A small picture of a target read back to the CPU without waiting: shrunk on the GPU, copied out frames later. A
 * frame's thumbnail, a trace's photograph, a test's capture.
 *
 * <pre>{@code
 * CgPhotograph.take(recording, frameTarget, w, h, 320, (w, h, rgb) -> save(w, h, rgb));   // any thread that records
 * canvas.photograph(320, (w, h, rgb) -> thumbnail = rgb);                            // what a canvas drew so far
 * }</pre>
 *
 * <ul>
 *   <li>The sink runs on the render thread about three frames later, its rows top first, three bytes a pixel.</li>
 *   <li>At most three are in flight: a fourth is refused ({@code false}) rather than queued.</li>
 *   <li>Halved with linear filtering until within twice the width, then copied to size: each halving an exact two-by-two
 *       box, where one copy from full size would read four texels of every sixteen and turn text into noise.</li>
 * </ul>
 *
 * <p>Ported from CrystalGUI's {@code UiPhotograph}.</p>
 */
public final class CgPhotograph {

    /** What a photograph delivers. */
    @FunctionalInterface
    public interface Sink {
        /** {@code rgb} is {@code width * height * 3} bytes, the top row first; the caller's to keep. */
        void accept(int width, int height, byte[] rgb);
    }

    /** Photographs on their way at once: three covers a GPU two frames behind. */
    private static final int SLOTS = 3;
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private CgPhotograph() {
    }

    /**
     * Records a photograph of {@code target}, {@code width} x {@code height} pixels, into {@code recording}, at most
     * {@code maxWidth} pixels wide, its aspect kept. False, with nothing recorded, when three are already on their way.
     * The size is the caller's: a host's target imported into a graph does not know its own.
     */
    public static boolean take(CgRecording recording, CgGraphTexture target, int width, int height, int maxWidth, Sink sink) {
        if (maxWidth < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("a photograph " + maxWidth + " wide of " + width + "x" + height);
        }
        if (IN_FLIGHT.get() >= SLOTS) return false;
        int fromW = width, fromH = height;
        int w = Math.min(maxWidth, fromW);
        int h = Math.max(1, Math.round((float) fromH * w / fromW));
        CgGraphTexture from = target;
        while (fromW / 2 >= w * 2) {
            int stepW = fromW / 2, stepH = Math.max(1, fromH / 2);
            CgGraphTexture step = shrunk(stepW, stepH);
            recording.copy(from, 0, 0, fromW, fromH, step, 0, 0, stepW, stepH, true);
            from = step;
            fromW = stepW;
            fromH = stepH;
        }
        CgGraphTexture photo = shrunk(w, h);
        recording.copy(from, 0, 0, fromW, fromH, photo, 0, 0, w, h, true);
        IN_FLIGHT.incrementAndGet();
        recording.readback(photo, 0, 0, 0, w, h, new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer data) {
                IN_FLIGHT.decrementAndGet();
                sink.accept(w, h, rgb(data, w, h));
            }

            @Override
            public void failed(String reason) {
                IN_FLIGHT.decrementAndGet();
            }
        });
        return true;
    }

    private static CgGraphTexture shrunk(int w, int h) {
        return CgGraphTexture.transientTexture("cg_photograph", new CgTextureDesc(w, h, CgFormat.RGBA8_UNORM));
    }

    /** RGBA rows bottom first, as a readback answers, into RGB rows top first. */
    private static byte[] rgb(ByteBuffer data, int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        data.get(rgba);
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4, dst = y * w * 3;
            for (int x = 0; x < w; x++, src += 4, dst += 3) {
                rgb[dst] = rgba[src];
                rgb[dst + 1] = rgba[src + 1];
                rgb[dst + 2] = rgba[src + 2];
            }
        }
        return rgb;
    }
}
