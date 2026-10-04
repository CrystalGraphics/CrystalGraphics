package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static com.crystalgraphics.platform.gl.state.CgGlSlot.FBO;

/**
 * Reads a buffer range or a framebuffer region back without a stall: copied on the GPU into a buffer the CPU maps,
 * behind a fence, and handed to a sink on the render thread once the GPU has finished, typically two or three frames
 * later. Unity's {@code AsyncGPUReadback}. In a frame graph, {@code CgRecording.readback} records one, ordered after
 * whatever writes what it reads.
 *
 * <pre>{@code
 * // GL buffer counts: the uint at byte 0, a few frames from now
 * CgReadback.buffer(counts, 0, 4, data -> alive = data.getInt(0));
 *
 * // a framebuffer's colour attachment 0, read as its texture type: rows bottom first, tightly packed
 * CgReadback.pixels(heightFbo.getId(), 0, 0, 64, 64, CgTextureType.R32F, data -> data.asFloatBuffer().get(heights));
 *
 * // told when it can never arrive
 * CgReadback.buffer(stats, 0, 64, new CgReadback.Sink() {
 *     public void accept(ByteBuffer data) { show(data); }
 *     public void failed(String reason) { LOG.warn("stats lost: {}", reason); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, at a point where the source holds what is wanted: the copy is ordered after the GPU work
 *       issued before it, as any GL command is.</li>
 *   <li>The data is valid only during {@link Sink#accept}, in native byte order: copy out what is kept.</li>
 *   <li>Sinks run from {@link #poll}, once a frame in {@code CgGraphicsLifecycle.tickFrame}, oldest first. A sink that
 *       throws is told it failed; the rest are still delivered.</li>
 *   <li>Teardown drops what is in flight ({@link #releaseAll}), telling each sink.</li>
 * </ul>
 */
public final class CgReadback {

    /** Where a readback's bytes go. */
    @FunctionalInterface
    public interface Sink {

        /** The bytes read, native order; valid only during the call. */
        void accept(ByteBuffer data);

        /** They will never arrive: the context was torn down, the read failed, or {@link #accept} threw. */
        default void failed(String reason) {}
    }

    private static final Logger LOGGER = LogManager.getLogger("CgReadback");
    private static final int REQUESTS = CgTrace.name("readback.requests");
    private static final int BYTES = CgTrace.name("readback.bytes");
    private static final int DELIVERED = CgTrace.name("readback.delivered");
    private static final int FRAMES = CgTrace.name("readback.frames");

    /** Staging kept between readbacks; one past this is deleted when its readback lands. */
    private static final int KEPT = 16;
    /** The smallest staging buffer, so small reads share a size. */
    private static final long SMALLEST = 256;

    private static final ArrayDeque<CgReadback> PENDING = new ArrayDeque<>();
    private static final List<CgReadback> IDLE = new ArrayList<>();
    private static long polls;
    /** The framebuffer {@link #slices} attaches each slice to, made at first use. */
    private static int sliceReader;

    private int buffer;
    private long capacity;
    private long bytes;
    private long fence;
    private long requestedAt;
    private Sink sink;
    private ByteBuffer view;

    private CgReadback() {}

    /** Reads {@code size} bytes of GL buffer {@code name} from {@code offset}. */
    public static void buffer(int name, long offset, long size, Sink sink) {
        if (size <= 0) throw new IllegalArgumentException("a readback of " + size + " bytes");
        CgReadback r = take(size);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, name);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, r.buffer);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, offset, 0, size);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        r.start(size, sink);
    }

    /**
     * Reads a {@code width} x {@code height} region at {@code (x, y)} of {@code framebuffer}'s colour attachment 0, as
     * {@code type}'s base format and pixel type: {@code width * height * } {@link #pixelBytes} bytes, rows bottom first.
     */
    public static void pixels(int framebuffer, int x, int y, int width, int height, CgTextureType type, Sink sink) {
        if (!type.isColor()) throw new IllegalArgumentException("a readback reads colour, not " + type);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("a readback of " + width + "x" + height);
        int row = width * pixelBytes(type);
        long size = (long) row * height;
        CgReadback r = take(size);
        try (CgGlScope ignored = CgGlState.save(FBO)) {
            CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, framebuffer);
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, r.buffer);
            boolean unaligned = (row & 3) != 0;
            if (unaligned) CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 1);
            CgGL.glReadPixels(x, y, width, height, type.glBaseFormat, type.glType, 0L);
            if (unaligned) CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 4);   // GL's default
            // UNBOUND AT ONCE: a bound pack buffer takes every later glReadPixels in the process, a host's screenshot
            // included.
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, 0);
        }
        r.start(size, sink);
    }

    /**
     * Reads a {@code width} x {@code height} x {@code depth} box at {@code (x, y, z)} of level {@code level} of 3D
     * texture {@code texture}, as {@code type}'s base format and pixel type: slices from {@code z} up, each laid out as
     * {@link #pixels} lays out a region.
     *
     * <pre>{@code
     * CgReadback.slices(grid.getId(), 0, 0, 0, 0, 64, 64, 64, CgTextureType.R16F, data -> keep(data));
     * }</pre>
     */
    public static void slices(int texture, int level, int x, int y, int z, int width, int height, int depth,
                              CgTextureType type, Sink sink) {
        if (!type.isColor()) throw new IllegalArgumentException("a readback reads colour, not " + type);
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("a readback of " + width + "x" + height + "x" + depth);
        }
        int row = width * pixelBytes(type);
        long slice = (long) row * height, size = slice * depth;
        CgReadback r = take(size);
        try (CgGlScope ignored = CgGlState.save(FBO)) {
            // Read through a framebuffer bound for reading alone: a slice of a 3D image is no draw target on a device.
            if (sliceReader == 0) sliceReader = CgGL.glGenFramebuffers();
            CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, sliceReader);
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, r.buffer);
            boolean unaligned = (row & 3) != 0;
            if (unaligned) CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 1);
            for (int s = 0; s < depth; s++) {
                CgGL.glFramebufferTextureLayer(CgGL.GL_READ_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, texture, level, z + s);
                CgGL.glReadPixels(x, y, width, height, type.glBaseFormat, type.glType, s * slice);
            }
            if (unaligned) CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 4);
            CgGL.glFramebufferTextureLayer(CgGL.GL_READ_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, 0, 0, 0);
            CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, 0);
        }
        r.start(size, sink);
    }

    /** Bytes per pixel of {@code type} read as its base format and pixel type. */
    public static int pixelBytes(CgTextureType type) {
        int t = type.glType;
        if (t == CgGL.GL_UNSIGNED_INT_2_10_10_10_REV || t == CgGL.GL_UNSIGNED_INT_10F_11F_11F_REV
                || t == CgGL.GL_UNSIGNED_INT_24_8) return 4;
        if (t == CgGL.GL_FLOAT_32_UNSIGNED_INT_24_8_REV) return 8;
        int f = type.glBaseFormat;
        int components = f == CgGL.GL_RED || f == CgGL.GL_RED_INTEGER || f == CgGL.GL_DEPTH_COMPONENT
                || f == CgGL.GL_STENCIL_INDEX ? 1 : f == CgGL.GL_RG || f == CgGL.GL_RG_INTEGER ? 2
                : f == CgGL.GL_RGB || f == CgGL.GL_RGB_INTEGER ? 3 : 4;
        int size = t == CgGL.GL_UNSIGNED_BYTE || t == CgGL.GL_BYTE ? 1
                : t == CgGL.GL_UNSIGNED_SHORT || t == CgGL.GL_SHORT || t == CgGL.GL_HALF_FLOAT ? 2 : 4;
        return components * size;
    }

    /** Hands what the GPU has finished to its sink, oldest first, and never waits. Once a frame, render thread. */
    public static void poll() {
        polls++;
        while (!PENDING.isEmpty()) {
            CgReadback r = PENDING.peekFirst();
            int state = CgGL.glClientWaitSync(r.fence, 0, 0L);
            if (state == CgGL.GL_TIMEOUT_EXPIRED) return;   // fences signal in order: nothing newer is done either
            PENDING.pollFirst();
            CgGL.glDeleteSync(r.fence);
            r.fence = 0L;
            if (state == CgGL.GL_WAIT_FAILED) r.fail("its fence failed");
            else r.deliver();
            r.recycle();
        }
    }

    /** How many readbacks are on their way. */
    public static int pending() {
        return PENDING.size();
    }

    /** Drops what is in flight, telling each sink, and frees every staging buffer. At context teardown. */
    public static void releaseAll() {
        for (CgReadback r : PENDING) {
            CgGL.glDeleteSync(r.fence);
            r.fail("the context was torn down");
            CgGL.glDeleteBuffers(r.buffer);
        }
        for (CgReadback r : IDLE) CgGL.glDeleteBuffers(r.buffer);
        PENDING.clear();
        IDLE.clear();
        if (sliceReader != 0) CgGL.glDeleteFramebuffers(sliceReader);
        sliceReader = 0;
    }

    /** One with staging of at least {@code size} bytes: an idle one of that size class, else new. */
    private static CgReadback take(long size) {
        long capacity = Math.max(SMALLEST, Long.highestOneBit(size - 1) << 1);
        for (int i = IDLE.size() - 1; i >= 0; i--) {
            if (IDLE.get(i).capacity == capacity) return IDLE.remove(i);
        }
        CgReadback r = new CgReadback();
        r.capacity = capacity;
        r.buffer = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, r.buffer);
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, capacity, CgGL.GL_STREAM_READ);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        return r;
    }

    private void start(long size, Sink sink) {
        this.bytes = size;
        this.sink = sink;
        this.requestedAt = polls;
        fence = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        PENDING.addLast(this);
        CgTrace.add(CgChannels.GL, REQUESTS, 1);
        CgTrace.add(CgChannels.GL, BYTES, size);
    }

    private void deliver() {
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, buffer);
        try {
            ByteBuffer mapped = CgGL.glMapBufferRange(CgGL.GL_COPY_READ_BUFFER, 0, bytes, CgGL.GL_MAP_READ_BIT, view);
            if (mapped == null) {
                fail("its staging could not be mapped");
                return;
            }
            view = mapped;
            try {
                sink.accept(mapped.order(ByteOrder.nativeOrder()));
                CgTrace.add(CgChannels.GL, DELIVERED, 1);
                CgTrace.add(CgChannels.GL, FRAMES, polls - requestedAt);
            } catch (RuntimeException e) {
                LOGGER.error("A readback's sink threw", e);
                fail("its sink threw " + e);
            } finally {
                CgGL.glUnmapBuffer(CgGL.GL_COPY_READ_BUFFER);
            }
        } finally {
            CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        }
    }

    private void fail(String reason) {
        try {
            sink.failed(reason);
        } catch (RuntimeException e) {
            LOGGER.error("A readback's sink threw on being told it failed", e);
        }
    }

    private void recycle() {
        sink = null;
        if (IDLE.size() < KEPT) {
            IDLE.add(this);
        } else {
            CgGL.glDeleteBuffers(buffer);
        }
    }
}
