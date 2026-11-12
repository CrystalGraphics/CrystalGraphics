package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.platform.rhi.sync.CgGpuReadback;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import com.crystalgraphics.api.texture.CgTexture;

/**
 * Reads a buffer range or a texture region back without a stall: copied by the device into its readback memory and
 * handed to a sink on the render thread once the frame it was issued in has retired, a few frames later. Unreal's
 * {@code FRHIGPUBufferReadback}, Unity's {@code AsyncGPUReadback}. In a frame graph, {@code CgRecording.readback}
 * records one, ordered after whatever writes what it reads.
 *
 * <pre>{@code
 * // a buffer's uint at byte 0, a few frames from now
 * CgReadback.buffer(counts, 0, 4, data -> alive = data.getInt(0));
 *
 * // a texture's region, texels as it holds them, tightly packed
 * CgReadback.texture(heightMap, CgTextureRegion.of2D(0, 0, 0, 64, 64), data -> keep(data));
 *
 * // a texture of yours, level 0: rows from its row 0
 * CgReadback.texture(heights, 0, 0, 0, 64, 64, data -> data.asFloatBuffer().get(field));
 *
 * // told when it can never arrive
 * CgReadback.buffer(stats, 0, 64, new CgReadback.Sink() {
 *     public void accept(ByteBuffer data) { show(data); }
 *     public void failed(String reason) { LOG.warn("stats lost: {}", reason); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, at a point where the source holds what is wanted and is readable as a copy's: the copy is
 *       ordered after the work recorded before it.</li>
 *   <li>The data is valid only during {@link Sink#accept}, in native byte order: copy out what is kept.</li>
 *   <li>Sinks run from {@link #poll}, once a frame in {@code CgGraphicsLifecycle.tickFrame}, oldest first. A sink that
 *       throws is told it failed; the rest are still delivered.</li>
 *   <li>A device's teardown drops what is in flight ({@link #releaseAll}), telling each sink.</li>
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
    private static final int DELIVER = CgTrace.name("readback.deliver");
    /** A sink's zone, {@code readback.sink.<the class its lambda was written in>}. */
    private static final ClassValue<Integer> SINK_NAMES = new ClassValue<Integer>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            String n = type.getName();
            int lambda = n.indexOf("$$Lambda");
            String declared = lambda >= 0 ? n.substring(0, lambda) : n;
            return CgTrace.name("readback.sink." + declared.substring(declared.lastIndexOf('.') + 1));
        }
    };
    /** The frame's queue's time copying into staging, on {@code gl.detail}: what a copy queue could take. */
    private static final int GPU_COPY = CgGpuTrace.name("readback.copy");

    /** Staging kept between readbacks; one past this is released when its readback lands. */
    private static final int KEPT = 16;
    /** The smallest staging buffer, so small reads share a size and reuse kept staging: new staging is new memory. */
    private static final long SMALLEST = 64 * 1024;

    private static final ArrayDeque<CgReadback> PENDING = new ArrayDeque<>();
    private static final List<CgReadback> IDLE = new ArrayList<>();
    private static long polls;

    /** Its staging on the device, and that device. */
    private CgGpuReadback staging;
    private CgRHI stagingOf;
    private long capacity;
    private long bytes;
    private long requestedAt;
    private Sink sink;

    private CgReadback() {}

    /** Reads {@code size} bytes of buffer {@code name}, known by its device name ({@code CgDeviceNames}), from {@code offset}. */
    public static void buffer(int name, long offset, long size, Sink sink) {
        buffer(CgDeviceNames.buffer(name), offset, size, sink);
    }

    /**
     * Reads {@code size} bytes of {@code source} from {@code offset}: copied by the device in a device section, its
     * source already readable as a copy's ({@code CgAccess.COPY_READ}).
     */
    public static void buffer(CgGpuBuffer source, long offset, long size, Sink sink) {
        if (size <= 0) throw new IllegalArgumentException("a readback of " + size + " bytes");
        CgReadback r = take(size);
        boolean gpu = gpuZone();
        if (gpu) CgGpuTrace.begin(GPU_COPY);
        CgDeviceSection.begin();
        try {
            r.staging.enqueue(CgRHI.get().encoder(), source, offset, size);
        } finally {
            CgDeviceSection.end();
            if (gpu) CgGpuTrace.end();
        }
        r.start(size, sink);
    }

    /**
     * Reads {@code region} of {@code source}, texels tightly packed with rows from the first in memory, as
     * {@link #pixels} lays them out; its source already readable as a copy's.
     */
    public static void texture(CgGpuTexture source, CgTextureRegion region, Sink sink) {
        long size = source.desc().format().bytes(region.width(), region.height(), region.depth());
        CgReadback r = take(size);
        boolean gpu = gpuZone();
        if (gpu) CgGpuTrace.begin(GPU_COPY);
        CgDeviceSection.begin();
        try {
            r.staging.enqueue(CgRHI.get().encoder(), source, region);
        } finally {
            CgDeviceSection.end();
            if (gpu) CgGpuTrace.end();
        }
        r.start(size, sink);
    }

    private static boolean gpuZone() {
        return CgTrace.isEnabled(CgChannels.GL_DETAIL) && CgGpuTrace.isMeasuring();
    }

    /**
     * Reads a {@code width} x {@code height} region at {@code (x, y)} of {@code texture}'s level {@code level}: texels
     * tightly packed as its format holds them, rows from its row 0. Render thread, outside a recording; the sink runs
     * frames later. In a graph, {@code rec.readback} orders it after the writers.
     *
     * @throws IllegalStateException for a texture with no storage yet
     */
    public static void texture(CgTexture texture, int level, int x, int y, int width, int height, Sink sink) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("a readback of " + width + "x" + height);
        CgTextureAbstract own = (CgTextureAbstract) texture;
        own.getId();   // lands what is queued
        CgGpuTexture storage = own.rhiTexture();
        if (storage == null) throw new IllegalStateException(texture + " has no storage to read");
        texture(storage, new CgTextureRegion(level, x, y, 0, width, height, 1), sink);
    }

    /**
     * {@code width} x {@code height} texels at {@code (x, y)} of {@code view}'s level and layer, read now, waiting for
     * the GPU: RGBA8, or RGBA32F in native order where {@code floats}, whatever the texture holds (a single channel
     * answers in R). Rows from the texture's row 0. Render thread, outside a recording: a check or a capture, never a
     * frame's work, which {@link #texture} reads without waiting.
     *
     * <pre>{@code
     * ByteBuffer picture = CgReadback.now(target.color(), 0, 0, w, h, false);
     * }</pre>
     */
    public static ByteBuffer now(CgTextureView view, int x, int y, int width, int height, boolean floats) {
        CgGpuTexture texture = view.texture();
        CgFormat format = texture.desc().format();
        CgTextureRegion region = region(view, x, y, width, height);
        int texels = width * height;
        ByteBuffer raw = ByteBuffer.allocateDirect((int) format.bytes(width, height, 1)).order(ByteOrder.nativeOrder());
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().readTexture(texture, region, raw);
        } finally {
            CgDeviceSection.end();
        }
        ByteBuffer out = ByteBuffer.allocateDirect(texels * (floats ? 16 : 4)).order(ByteOrder.nativeOrder());
        if (floats) CgTexelConvert.toRgba32f(raw, format, texels, out);
        else CgTexelConvert.toRgba8(raw, format, texels, out);
        return out;
    }

    /** The region at {@code (x, y)} of {@code view}'s level and layer. */
    public static CgTextureRegion region(CgTextureView view, int x, int y, int width, int height) {
        return new CgTextureRegion(view.baseMip(), x, y, view.baseLayer(), width, height, 1);
    }

    /** Bytes per pixel of {@code type} read as its base format and pixel type. */
    public static int pixelBytes(CgTextureType type) {
        int t = type.glType;
        if (t == GLC.GL_UNSIGNED_INT_2_10_10_10_REV || t == GLC.GL_UNSIGNED_INT_10F_11F_11F_REV
                || t == GLC.GL_UNSIGNED_INT_24_8) return 4;
        if (t == GLC.GL_FLOAT_32_UNSIGNED_INT_24_8_REV) return 8;
        int f = type.glBaseFormat;
        int components = f == GLC.GL_RED || f == GLC.GL_RED_INTEGER || f == GLC.GL_DEPTH_COMPONENT
                || f == GLC.GL_STENCIL_INDEX ? 1 : f == GLC.GL_RG || f == GLC.GL_RG_INTEGER ? 2
                : f == GLC.GL_RGB || f == GLC.GL_RGB_INTEGER ? 3 : 4;
        int size = t == GLC.GL_UNSIGNED_BYTE || t == GLC.GL_BYTE ? 1
                : t == GLC.GL_UNSIGNED_SHORT || t == GLC.GL_SHORT || t == GLC.GL_HALF_FLOAT ? 2 : 4;
        return components * size;
    }

    /**
     * Hands what the GPU has finished to its sink, oldest first. Once a frame, render thread: the device retires frames
     * in order, so the first not done ends the poll.
     */
    public static void poll() {
        polls++;
        while (!PENDING.isEmpty()) {
            CgReadback r = PENDING.peekFirst();
            if (!r.staging.ready()) return;
            PENDING.pollFirst();
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.GL, DELIVER)) {
                ByteBuffer data = r.staging.data();
                data.clear().limit((int) r.bytes);
                r.accept(data.order(ByteOrder.nativeOrder()));
            }
            r.recycle();
        }
    }

    /** How many readbacks are on their way. */
    public static int pending() {
        return PENDING.size();
    }

    /** Drops what is in flight, telling each sink, and frees every staging buffer. At the device's teardown. */
    public static void releaseAll() {
        for (CgReadback r : PENDING) {
            r.fail("the device was torn down");
            r.free();
        }
        for (CgReadback r : IDLE) r.free();
        PENDING.clear();
        IDLE.clear();
    }

    /** The size class {@code size} bytes stage in. */
    private static long capacity(long size) {
        return Math.max(SMALLEST, Long.highestOneBit(size - 1) << 1);
    }

    /** One staged on the installed device with at least {@code size} bytes: an idle one of that size class, else new. */
    private static CgReadback take(long size) {
        long capacity = capacity(size);
        CgRHI rhi = CgRHI.get();
        for (int i = IDLE.size() - 1; i >= 0; i--) {
            CgReadback idle = IDLE.get(i);
            if (idle.stagingOf == rhi && idle.capacity == capacity) return IDLE.remove(i);
        }
        CgReadback r = new CgReadback();
        r.capacity = capacity;
        r.staging = new CgGpuReadback(rhi, "readback", capacity);
        r.stagingOf = rhi;
        return r;
    }

    private void start(long size, Sink sink) {
        this.bytes = size;
        this.sink = sink;
        this.requestedAt = polls;
        PENDING.addLast(this);
        CgTrace.add(CgChannels.GL, REQUESTS, 1);
        CgTrace.add(CgChannels.GL, BYTES, size);
    }

    private void accept(ByteBuffer data) {
        // A zone per sink, named by the class its lambda was written in: a slow one names its owner.
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, SINK_NAMES.get(sink.getClass()))) {
            sink.accept(data);
            CgTrace.add(CgChannels.GL, DELIVERED, 1);
            CgTrace.add(CgChannels.GL, FRAMES, polls - requestedAt);
        } catch (RuntimeException e) {
            LOGGER.error("A readback's sink threw", e);
            fail("its sink threw " + e);
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
            free();
        }
    }

    /** Its staging released. */
    private void free() {
        staging.release();
    }
}
