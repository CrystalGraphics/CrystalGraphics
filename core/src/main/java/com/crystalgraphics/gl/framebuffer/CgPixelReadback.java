package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.crystalgraphics.platform.gl.state.CgGlSlot.FBO;
import static com.crystalgraphics.platform.gl.state.CgGlSlot.SCISSOR;

/**
 * A small copy of a framebuffer, read back without stalling: shrunk on the GPU, read back through {@link CgReadback},
 * and handed over by {@link #poll} once it has landed, typically a frame or two later.
 *
 * <pre>{@code
 * CgPixelReadback readback = new CgPixelReadback(3);
 *
 * // when a picture is wanted, after the source is drawn:
 * readback.request(frameFbo.getId(), frameFbo.getWidth(), frameFbo.getHeight(), 256, frameIndex);
 *
 * // every frame, anywhere on the GL thread:
 * readback.poll(pixels -> store(pixels.tag(), pixels.width(), pixels.height(), pixels.rgb()));
 *
 * readback.delete();   // with the context still current
 * }</pre>
 *
 * <p>The source is halved with linear filtering until it is within twice the target, then blitted
 * to size — each halving is an exact two-by-two box, where one linear blit from full size reads four
 * texels of every sixteen and turns text into noise.</p>
 *
 * <h3>Easy to get wrong</h3>
 * <ul>
 *   <li>GL thread only, and {@link #request} after the source is complete: it copies what is there now.</li>
 *   <li>{@link #request} returns false when as many copies as it was made with are still on their way — the GPU is
 *       behind — and copies nothing. It never waits.</li>
 *   <li>The pixels arrive top row first, RGB, three bytes a pixel.</li>
 * </ul>
 */
public final class CgPixelReadback {

    /** One finished copy: {@code rgb} is {@code width * height * 3} bytes, top row first. */
    public record Pixels(long tag, int width, int height, byte[] rgb) {}

    private static final CgFrameBufferFormat FORMAT =
            CgFrameBufferFormat.builder("cg_readback").color(0, CgTextureType.RGBA8).build();

    private final int slots;
    private int inFlight;
    private final ArrayDeque<Pixels> landed = new ArrayDeque<>();
    /** The shrunk copy read back: the GPU reads it before the next request's blit, so one serves every request. */
    private CgFrameBuffer target;
    /** The halving steps, reused while the source keeps its size. */
    private final List<CgFrameBuffer> chain = new ArrayList<>();
    private int chainWidth;
    private int chainHeight;
    private int chainTarget;
    /** The landed bytes, copied out in one call: a get per byte from a direct buffer cost milliseconds. */
    private byte[] rgbaScratch = new byte[0];

    /** @param slots how many copies may be on their way at once */
    public CgPixelReadback(int slots) {
        this.slots = Math.max(1, slots);
    }

    /**
     * Copies {@code sourceFbo}, shrunk to {@code width} wide at its own aspect, and starts reading it back.
     *
     * @return false, copying nothing, when {@code slots} copies are still on their way
     */
    public boolean request(int sourceFbo, int sourceWidth, int sourceHeight, int width, long tag) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0) return false;
        if (inFlight >= slots) return false;
        int w = Math.min(width, sourceWidth);
        int h = Math.max(1, Math.round((float) sourceHeight * w / sourceWidth));

        try (CgGlScope ignored = CgGlState.save(FBO, SCISSOR)) {
            // A BLIT IS SCISSORED: a clip left on by whoever drew last would copy a corner.
            CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
            int from = sourceFbo;
            int fromW = sourceWidth;
            int fromH = sourceHeight;
            prepareChain(sourceWidth, sourceHeight, w);
            for (CgFrameBuffer step : chain) {
                blit(from, fromW, fromH, step.getId(), step.getWidth(), step.getHeight());
                from = step.getId();
                fromW = step.getWidth();
                fromH = step.getHeight();
            }
            if (target == null) {
                target = CgFrameBuffer.createOwned("cg_readback", w, h, FORMAT);
            } else if (target.getWidth() != w || target.getHeight() != h) {
                target.resize(w, h);
            }
            blit(from, fromW, fromH, target.getId(), w, h);
        }
        inFlight++;
        CgReadback.pixels(target.getId(), 0, 0, w, h, CgTextureType.RGBA8, new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer data) {
                inFlight--;
                landed.addLast(new Pixels(tag, w, h, rgb(data, w, h)));
            }

            @Override
            public void failed(String reason) {
                inFlight--;
            }
        });
        return true;
    }

    /** Hands every copy that has landed to {@code sink}, oldest first, and never waits for one that has not. */
    public void poll(Consumer<Pixels> sink) {
        while (!landed.isEmpty()) sink.accept(landed.pollFirst());
    }

    /** Whether a copy is still on its way, or landed and not yet polled. */
    public boolean isPending() {
        return inFlight > 0 || !landed.isEmpty();
    }

    /** Releases its framebuffers. GL thread, with the context current; a copy still on its way is dropped. */
    public void delete() {
        if (target != null) target.delete();
        target = null;
        for (CgFrameBuffer step : chain) step.delete();
        chain.clear();
        landed.clear();
        chainWidth = 0;
        chainHeight = 0;
        chainTarget = 0;
    }

    /** One halving per step while the next is still at least twice the target wide. */
    private void prepareChain(int sourceWidth, int sourceHeight, int width) {
        if (sourceWidth == chainWidth && sourceHeight == chainHeight && width == chainTarget) return;
        for (CgFrameBuffer step : chain) step.delete();
        chain.clear();
        int w = sourceWidth;
        int h = sourceHeight;
        while (w / 2 >= width * 2) {
            w /= 2;
            h = Math.max(1, h / 2);
            chain.add(CgFrameBuffer.createOwned("cg_readback_step", w, h, FORMAT));
        }
        chainWidth = sourceWidth;
        chainHeight = sourceHeight;
        chainTarget = width;
    }

    private static void blit(int from, int fromW, int fromH, int to, int toW, int toH) {
        CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, from);
        CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, to);
        CgGL.glBlitFramebuffer(0, 0, fromW, fromH, 0, 0, toW, toH, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_LINEAR);
    }

    /** RGBA rows bottom-up, as GL reads them, into RGB rows top-down. */
    private byte[] rgb(ByteBuffer data, int w, int h) {
        byte[] rgb = new byte[w * h * 3];
        int bytes = w * h * 4;
        if (rgbaScratch.length < bytes) rgbaScratch = new byte[bytes];
        byte[] rgba = rgbaScratch;
        data.get(rgba, 0, bytes);
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4;
            int dst = y * w * 3;
            for (int x = 0; x < w; x++, src += 4) {
                rgb[dst++] = rgba[src];
                rgb[dst++] = rgba[src + 1];
                rgb[dst++] = rgba[src + 2];
            }
        }
        return rgb;
    }
}
