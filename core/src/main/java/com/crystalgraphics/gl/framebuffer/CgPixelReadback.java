package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.crystalgraphics.platform.gl.state.CgGlSlot.FBO;
import static com.crystalgraphics.platform.gl.state.CgGlSlot.SCISSOR;

/**
 * A small copy of a framebuffer, read back without stalling: shrunk on the GPU, read into a
 * pixel-pack buffer behind a fence, and handed over by {@link #poll} once the GPU has finished —
 * typically a frame or two later.
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
 *   <li>{@link #request} returns false when every slot is still in flight — the GPU is behind — and
 *       copies nothing. It never waits.</li>
 *   <li>The pixels arrive top row first, RGB, three bytes a pixel.</li>
 * </ul>
 */
public final class CgPixelReadback {

    /** One finished copy: {@code rgb} is {@code width * height * 3} bytes, top row first. */
    public record Pixels(long tag, int width, int height, byte[] rgb) {}

    private static final CgFrameBufferFormat FORMAT =
            CgFrameBufferFormat.builder("cg_readback").color(0, CgTextureType.RGBA8).build();

    private static final class Slot {
        CgFrameBuffer target;
        int pbo;
        long pboBytes;
        long fence;
        long tag;
        int width;
        int height;
    }

    private final Slot[] slots;
    /** The halving steps, reused while the source keeps its size. */
    private final List<CgFrameBuffer> chain = new ArrayList<>();
    private int chainWidth;
    private int chainHeight;
    private int chainTarget;

    public CgPixelReadback(int slots) {
        this.slots = new Slot[Math.max(1, slots)];
        for (int i = 0; i < this.slots.length; i++) this.slots[i] = new Slot();
    }

    /**
     * Copies {@code sourceFbo}, shrunk to {@code width} wide at its own aspect, and starts reading it back.
     *
     * @return false, copying nothing, when every slot is still waiting on the GPU
     */
    public boolean request(int sourceFbo, int sourceWidth, int sourceHeight, int width, long tag) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0) return false;
        Slot slot = freeSlot();
        if (slot == null) return false;
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
            if (slot.target == null) {
                slot.target = CgFrameBuffer.createOwned("cg_readback", w, h, FORMAT);
            } else if (slot.target.getWidth() != w || slot.target.getHeight() != h) {
                slot.target.resize(w, h);
            }
            blit(from, fromW, fromH, slot.target.getId(), w, h);

            long bytes = (long) w * h * 4L;
            if (slot.pbo == 0) slot.pbo = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, slot.pbo);
            if (slot.pboBytes != bytes) {
                CgGL.glBufferData(CgGL.GL_PIXEL_PACK_BUFFER, bytes, CgGL.GL_STREAM_READ);
                slot.pboBytes = bytes;
            }
            CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, slot.target.getId());
            CgGL.glReadPixels(0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, 0L);
            // UNBOUND AT ONCE: a bound pack buffer takes every later glReadPixels in the process,
            // a host's screenshot included.
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, 0);
        }
        slot.fence = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        slot.tag = tag;
        slot.width = w;
        slot.height = h;
        return true;
    }

    /** Hands every copy the GPU has finished to {@code sink}, and never waits for one that has not. */
    public void poll(Consumer<Pixels> sink) {
        for (Slot slot : slots) {
            if (slot.fence == 0L) continue;
            int state = CgGL.glClientWaitSync(slot.fence, 0, 0L);
            if (state == CgGL.GL_TIMEOUT_EXPIRED) continue;
            CgGL.glDeleteSync(slot.fence);
            slot.fence = 0L;
            if (state == GL_WAIT_FAILED) continue;
            sink.accept(new Pixels(slot.tag, slot.width, slot.height, read(slot)));
        }
    }

    /** Whether a copy is still on its way. */
    public boolean isPending() {
        for (Slot slot : slots) {
            if (slot.fence != 0L) return true;
        }
        return false;
    }

    /** Releases every GL object. GL thread, with the context current. */
    public void delete() {
        for (Slot slot : slots) {
            if (slot.fence != 0L) CgGL.glDeleteSync(slot.fence);
            if (slot.pbo != 0) CgGL.glDeleteBuffers(slot.pbo);
            if (slot.target != null) slot.target.delete();
            slot.fence = 0L;
            slot.pbo = 0;
            slot.pboBytes = 0L;
            slot.target = null;
        }
        for (CgFrameBuffer step : chain) step.delete();
        chain.clear();
        chainWidth = 0;
        chainHeight = 0;
        chainTarget = 0;
    }

    private static final int GL_WAIT_FAILED = 0x911D;

    private Slot freeSlot() {
        for (Slot slot : slots) {
            if (slot.fence == 0L) return slot;
        }
        return null;
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

    /** The mapped bytes, copied out in one call: a get per byte from a direct buffer cost milliseconds. */
    private byte[] rgbaScratch = new byte[0];

    /** RGBA rows bottom-up, as GL reads them, into RGB rows top-down. */
    private byte[] read(Slot slot) {
        int w = slot.width;
        int h = slot.height;
        byte[] rgb = new byte[w * h * 3];
        int bytes = w * h * 4;
        if (rgbaScratch.length < bytes) rgbaScratch = new byte[bytes];
        byte[] rgba = rgbaScratch;
        CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, slot.pbo);
        try {
            ByteBuffer mapped = CgGL.glMapBufferRange(CgGL.GL_PIXEL_PACK_BUFFER, 0L, slot.pboBytes,
                    CgGL.GL_MAP_READ_BIT, null);
            if (mapped == null) return rgb;
            mapped.get(rgba, 0, bytes);
            CgGL.glUnmapBuffer(CgGL.GL_PIXEL_PACK_BUFFER);
        } finally {
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, 0);
        }
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
