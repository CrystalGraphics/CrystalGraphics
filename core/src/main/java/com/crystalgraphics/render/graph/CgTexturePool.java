package com.crystalgraphics.render.graph;

import java.util.function.ToLongFunction;
import java.util.function.Consumer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.variable.CgVar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Framebuffers for transient graph textures, kept between frames by description: a layer the same size as last
 * frame's reuses its storage, and one idle for {@link CgBufferPool#IDLE_NANOS} is freed. Past {@link #BUDGET_MB} held,
 * one unused for {@link #UNUSED_FRAMES} frames is freed too, as Unreal's render target pool does past
 * {@code r.RenderTargetPoolMin}: sizes never asked for twice cannot pile up. Render thread only; a steady frame
 * allocates nothing.
 */
final class CgTexturePool {

    static final CgVar.Num BUDGET_MB = CgVar.number("crystalgraphics.graph.poolMb", 400f, 0f, 65536f, CgVar.DEV,
            "Megabytes of transient graph textures held before one unused for a few frames is freed.");
    /** Frames a framebuffer sits unused before the budget frees it: what a frame or two of churn reuses stays. */
    static final int UNUSED_FRAMES = 10;

    private final Map<CgTextureDesc, Bucket> free = new HashMap<>();
    /** Every bucket made, kept when empty, so the idle sweep walks them by index. */
    private final List<Bucket> buckets = new ArrayList<>();
    /** {@link System#nanoTime()} at the last frame's end: a framebuffer remade is names and storage, driver waits. */
    private long now = System.nanoTime();
    private int created;
    private long frame;
    /** What a framebuffer of a description holds on the device, and the sum of it over every framebuffer held. */
    private ToLongFunction<CgTextureDesc> sizes = desc -> 0;
    private long bytes;
    /** Framebuffers held, in use and free, and their bytes by {@link #footprint}: on every backend. */
    private int framebuffers;
    private long footprintBytes;

    CgFrameBuffer acquire(CgTextureDesc desc) {
        Bucket bucket = free.get(desc);
        if (bucket != null && bucket.size > 0) return bucket.takeNewest();
        bytes += sizes.applyAsLong(desc);
        framebuffers++;
        footprintBytes += footprint(desc);
        return create("cg_graph_" + desc.width() + "x" + desc.height() + "_" + created++, desc);
    }

    /** Counts what each framebuffer made from now holds, by {@code sizes}: {@link #bytes()}. */
    void measure(ToLongFunction<CgTextureDesc> sizes) {
        this.sizes = sizes;
    }

    /** Bytes of the framebuffers held, in use and free, as {@link #measure} counts them. */
    long bytes() {
        return bytes;
    }

    /** Framebuffers held, in use and free. */
    int framebuffers() {
        return framebuffers;
    }

    /** Their bytes on the device, about, by {@link #footprint}. */
    long footprintBytes() {
        return footprintBytes;
    }

    /** What a framebuffer of {@code desc} holds on the device, about: every slot's texels, levels a third more. */
    static long footprint(CgTextureDesc desc) {
        CgFrameBufferFormat format = desc.format();
        long texel = 0;
        for (int slot = 0; slot < 8; slot++) {
            CgTextureType type = format.getColorSlot(slot);
            if (type != null) texel += CgReadback.pixelBytes(type);
        }
        if (format.hasDepth()) texel += CgReadback.pixelBytes(format.getDepthType());
        long texels = (long) desc.width() * desc.height() * Math.max(1, desc.depth()) * Math.max(1, desc.layers());
        return desc.levels() > 1 ? texels * texel * 4 / 3 : texels * texel;
    }

    /** Storage for {@code desc}: a framebuffer, an array's, or a volume's 3D texture. */
    static CgFrameBuffer create(String name, CgTextureDesc desc) {
        if (desc.isArray()) return CgFrameBuffer.createArray(name, desc.width(), desc.height(), desc.layers(), desc.format());
        return desc.isVolume() ? CgFrameBuffer.createVolume(name, desc.width(), desc.height(), desc.depth(), desc.format())
                : CgGLImports.installed() ? CgFrameBuffer.createOnDevice(name, desc.width(), desc.height(), desc.format(),
                desc.levels()) : CgFrameBuffer.createOwned(name, desc.width(), desc.height(), desc.format(), desc.levels());
    }

    void release(CgTextureDesc desc, CgFrameBuffer framebuffer) {
        Bucket bucket = free.get(desc);
        if (bucket == null) {
            free.put(desc, bucket = new Bucket(desc));
            buckets.add(bucket);
        }
        bucket.add(framebuffer, now, frame);
    }

    /**
     * Frees what has sat unused for {@link CgBufferPool#IDLE_NANOS}, and past the budget what has sat unused for
     * {@link #UNUSED_FRAMES} frames, each handed to {@code deleting} first. Once a frame.
     */
    void endFrame(Consumer<CgFrameBuffer> deleting) {
        now = System.nanoTime();
        frame++;
        long budget = (long) (BUDGET_MB.get() * 1024.0 * 1024.0);
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            while (bucket.size > 0 && (now - bucket.oldestAt() > CgBufferPool.IDLE_NANOS
                    || footprintBytes > budget && frame - bucket.oldestFrame() > UNUSED_FRAMES)) {
                CgFrameBuffer framebuffer = bucket.takeOldest();
                deleting.accept(framebuffer);
                framebuffer.delete();
                bytes -= sizes.applyAsLong(bucket.desc);
                framebuffers--;
                footprintBytes -= footprint(bucket.desc);
            }
        }
    }

    void delete() {
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            while (bucket.size > 0) bucket.takeOldest().delete();
        }
        free.clear();
        buckets.clear();
        bytes = 0;
        framebuffers = 0;
        footprintBytes = 0;
    }

    /** One description's free framebuffers, oldest first, as a ring of parallel arrays. */
    private static final class Bucket {
        final CgTextureDesc desc;
        private CgFrameBuffer[] framebuffers = new CgFrameBuffer[4];
        private long[] releasedAt = new long[4];
        private long[] releasedFrame = new long[4];
        private int head, size;

        Bucket(CgTextureDesc desc) {
            this.desc = desc;
        }

        void add(CgFrameBuffer framebuffer, long at, long frame) {
            if (size == framebuffers.length) grow();
            int i = (head + size) % framebuffers.length;
            framebuffers[i] = framebuffer;
            releasedAt[i] = at;
            releasedFrame[i] = frame;
            size++;
        }

        long oldestAt() {
            return releasedAt[head];
        }

        long oldestFrame() {
            return releasedFrame[head];
        }

        CgFrameBuffer takeOldest() {
            CgFrameBuffer fb = framebuffers[head];
            framebuffers[head] = null;
            head = (head + 1) % framebuffers.length;
            size--;
            return fb;
        }

        CgFrameBuffer takeNewest() {
            int i = (head + size - 1) % framebuffers.length;
            CgFrameBuffer fb = framebuffers[i];
            framebuffers[i] = null;
            size--;
            return fb;
        }

        private void grow() {
            int n = framebuffers.length;
            CgFrameBuffer[] f = new CgFrameBuffer[n * 2];
            long[] a = new long[n * 2], r = new long[n * 2];
            for (int k = 0; k < size; k++) {
                f[k] = framebuffers[(head + k) % n];
                a[k] = releasedAt[(head + k) % n];
                r[k] = releasedFrame[(head + k) % n];
            }
            framebuffers = f;
            releasedAt = a;
            releasedFrame = r;
            head = 0;
        }
    }
}
