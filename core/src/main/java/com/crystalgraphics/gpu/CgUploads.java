package com.crystalgraphics.gpu;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;

/**
 * Where uploads are staged: {@link CgUploadLease}s, leased on any thread and landed by a texture on the render thread.
 * The bytes are copied once, by the thread that has them, and not again on the render thread.
 *
 * <pre>{@code
 * CgUploadLease lease = CgUploads.lease(bytes);   // any thread
 * fill(lease.bytes());
 * volume.uploadRegion(0, 0, 0, 0, w, h, d, lease, GL_RGBA, GL_HALF_FLOAT);
 * }</pre>
 *
 * <p>Two tiers, chosen once a context:</p>
 * <ul>
 *   <li>{@link Tier#UNPACK}, where a device is installed: blocks of a persistently mapped device buffer, which a
 *       texture or buffer copies from on the GPU.
 *       A block is made on the render thread only, so a lease off it that finds no room takes direct memory (a miss),
 *       and the next frame grows the pool by what missed, up to {@code -Dcrystalgraphics.uploads.cap} bytes (128 MB).
 *       A block is reused once nothing is leased from it and the last frame that read it has retired.</li>
 *   <li>{@link Tier#DIRECT}: pooled direct memory, made on any thread, written by the device at the call.
 *       {@code -Dcrystalgraphics.uploads.tier=direct} forces it.</li>
 * </ul>
 *
 * <p>An upload the driver converts as it copies (a format or type other than the texture's) takes direct memory
 * either way ({@link #lease(int, boolean)}): converting reads the source, and unpack-buffer memory is slow to read.</p>
 */
public final class CgUploads {

    /** Where leases come from. */
    public enum Tier { UNPACK, DIRECT }

    private static final int ALIGN = 256;
    private static final int BLOCK = CgVar.integer("crystalgraphics.uploads.block", 8 << 20, 64 << 10, 1 << 30,
            CgVar.DEV | CgVar.RESTART, "Bytes in an upload block; a lease over half a block gets one of its own.").get();
    private static final long CAP = CgVar.integer("crystalgraphics.uploads.cap", 128 << 20, 0, Integer.MAX_VALUE,
            CgVar.DEV | CgVar.RESTART, "Bytes the unpack pool grows to: past it a lease finding no room takes direct memory,"
                    + " and direct blocks are not pooled.").get();
    /**
     * What the first tick makes, ahead of a load's uploads: each new block's mapping waits for a threaded driver's queue,
     * deepest behind the loading screen's compiles (two blocks grown mid-load held it 478 and 392 ms, Forge 1.20.1).
     */
    private static final long AHEAD = CgVar.integer("crystalgraphics.uploads.ahead", 32 << 20, 0, Integer.MAX_VALUE,
            CgVar.DEV | CgVar.RESTART, "Bytes of upload blocks made at the first frame, before a load needs them.").get();
    private static final boolean FORCE_DIRECT = CgVar.choice("crystalgraphics.uploads.tier", Tier.UNPACK,
            CgVar.DEV | CgVar.RESTART, "unpack: leases from persistently mapped device buffers, copied on the GPU."
                    + " direct: from direct memory, which the device writes at the call.").get() == Tier.DIRECT;

    private static final int LEASES = CgTrace.name("upload.leases");
    private static final int LEASE_BYTES = CgTrace.name("upload.lease-bytes");
    /** Unpack-tier leases served from direct memory: no block had room, and none could be made on this thread. */
    private static final int MISSES = CgTrace.name("upload.lease-misses");
    /** A caller's bytes copied into a lease, on the thread that asked: the CPU half of a deferred upload. */
    private static final int COPY = CgTrace.name("upload.lease-copy");
    private static final int COPY_BYTES = CgTrace.name("upload.lease-copy-bytes");
    private static final int POOL_BYTES = CgTrace.name("upload.pool-bytes");

    /** A run of staging memory leases are bumped out of. */
    static final class Block {
        /** The device buffer; null for direct memory. */
        final CgGpuBuffer buffer;
        final ByteBuffer memory;
        final int capacity, generation;
        int cursor, outstanding;
        /** The newest frame an upload read it in; -1 if none since it was last reused. */
        long lastUse = -1;

        Block(CgGpuBuffer buffer, ByteBuffer memory, int capacity, int generation) {
            this.buffer = buffer;
            this.memory = memory;
            this.capacity = capacity;
            this.generation = generation;
        }
    }

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    private static final ArrayList<Block> free = new ArrayList<>(), busy = new ArrayList<>();
    private static final ArrayDeque<CgUploadLease> spare = new ArrayDeque<>();
    private static Block openUnpack, openDirect;
    private static Tier tier;
    private static long unpackBytes, directBytes, missedBytes;
    private static int missedLargest, generation;

    private CgUploads() {}

    /** {@link #lease(int, boolean)} for an upload in the texture's own format and type. */
    public static CgUploadLease lease(int bytes) {
        return lease(bytes, false);
    }

    /**
     * {@code bytes} of staging memory, any thread.
     *
     * @param converts the driver converts the bytes as it copies: direct memory, which it can read quickly
     */
    public static CgUploadLease lease(int bytes, boolean converts) {
        if (bytes <= 0) throw new IllegalArgumentException("a lease of " + bytes + " bytes");
        int need = align(bytes);
        boolean renderThread = CgRHI.onDeviceThread();
        CgUploadLease lease;
        boolean missed = false;
        synchronized (LOCK) {
            if (tier == null && renderThread) tier = chooseTier();
            Block b = null;
            if (tier == Tier.UNPACK && !converts) {
                b = take(true, need, renderThread);
                if (b == null) {
                    missed = true;
                    missedBytes += need;
                    missedLargest = Math.max(missedLargest, need);
                }
            }
            if (b == null) b = take(false, need, true);
            lease = spare.isEmpty() ? new CgUploadLease() : spare.pop();
            lease.lease(b, b.cursor, bytes);
            b.cursor += need;
            b.outstanding++;
        }
        CgTrace.add(CgChannels.GL, LEASES, 1);
        CgTrace.add(CgChannels.GL, LEASE_BYTES, bytes);
        if (missed) CgTrace.add(CgChannels.GL, MISSES, 1);
        return lease;
    }

    /** A lease holding a copy of {@code pixels}' remaining bytes, their position left alone: one copy, on this thread. */
    public static CgUploadLease copyOf(ByteBuffer pixels, boolean converts) {
        long start = CgTrace.stamp(CgChannels.GL);
        CgUploadLease lease = lease(pixels.remaining(), converts).put(pixels);
        CgTrace.zoneDone(CgChannels.GL, COPY, start);
        CgTrace.add(CgChannels.GL, COPY_BYTES, pixels.remaining());
        return lease;
    }

    /** {@link #copyOf(ByteBuffer, boolean)} for floats. */
    public static CgUploadLease copyOf(FloatBuffer pixels, boolean converts) {
        long start = CgTrace.stamp(CgChannels.GL);
        CgUploadLease lease = lease(4 * pixels.remaining(), converts).put(pixels);
        CgTrace.zoneDone(CgChannels.GL, COPY, start);
        CgTrace.add(CgChannels.GL, COPY_BYTES, 4L * pixels.remaining());
        return lease;
    }

    /** The tier this context's leases come from; {@code null} until the render thread first asks. */
    public static Tier tier() {
        synchronized (LOCK) {
            return tier;
        }
    }

    /**
     * Render thread, once a frame: reuses blocks nothing is leased from whose last reading frame retired, and grows the
     * unpack pool by what missed since the last call.
     */
    public static void tick() {
        synchronized (LOCK) {
            if (tier == null) tier = chooseTier();
            long retiredBy = CgFrameRing.frame() - CgFrameRing.FRAMES;
            for (int i = 0; i < busy.size(); i++) {   // by index: a frame's tick allocates no iterator
                Block b = busy.get(i);
                if (b.outstanding > 0 || b.lastUse > retiredBy) continue;
                if (b.lastUse >= 0) CgFrameRing.awaitRetired(b.lastUse);   // three frames back: already signalled
                busy.remove(i--);
                reuse(b);
            }
            if (tier == Tier.UNPACK && (missedBytes > 0 || unpackBytes == 0)) grow();
        }
        CgTrace.counter(CgChannels.GL, POOL_BYTES, unpackBytes + directBytes);
    }

    /** Context teardown, render thread: frees every block. A lease still out must not be written or landed after. */
    public static void releaseAll() {
        synchronized (LOCK) {
            int out = 0;
            for (Block b : all()) {
                out += b.outstanding;
                if (b.buffer != null) release(b.buffer);
            }
            if (out > 0) System.err.println("[crystalgraphics] " + out + " upload leases outstanding at teardown");
            free.clear();
            busy.clear();
            spare.clear();
            openUnpack = openDirect = null;
            tier = null;
            unpackBytes = directBytes = missedBytes = 0;
            missedLargest = 0;
            generation++;
        }
    }

    // ── a lease's end ───────────────────────────────────────────────────────────────────────────────────────

    /** Bytes of every lease landed since launch: what {@link CgDeferral} holds a frame's landing to. Render thread. */
    private static long landedBytes;

    static long landedBytes() {
        return landedBytes;
    }

    /** Render thread: the lease's upload was issued, so its block is read by this frame. */
    static void landed(CgUploadLease lease) {
        landedBytes += lease.size();
        synchronized (LOCK) {
            Block b = lease.block;
            if (b.generation == generation) {
                if (b.buffer != null) b.lastUse = CgFrameRing.frame();
                b.outstanding--;
            }
            giveBack(lease);
        }
    }

    /** Any thread: the lease will never be landed. */
    static void dropped(CgUploadLease lease) {
        synchronized (LOCK) {
            if (lease.block.generation == generation) lease.block.outstanding--;
            giveBack(lease);
        }
    }

    // ── internals, under LOCK ─────────────────────────────────────────────────────────────────────────────

    private static Tier chooseTier() {
        return !FORCE_DIRECT && CgRHI.installed() ? Tier.UNPACK : Tier.DIRECT;
    }

    /**
     * A block of this kind with {@code need} bytes free: the open one, else a free one, else a new one where it may be
     * made. A lease over half a block takes one of its own, closed at once, so the open block keeps its room.
     */
    private static Block take(boolean unpack, int need, boolean mayCreate) {
        boolean dedicated = need > BLOCK / 2;
        Block open = unpack ? openUnpack : openDirect;
        if (!dedicated && open != null && open.capacity - open.cursor >= need) return open;
        Block b = smallestFree(unpack, dedicated ? need : BLOCK);
        if (b == null && mayCreate) b = create(unpack, dedicated ? need : BLOCK);
        if (b == null) return null;
        if (dedicated) {
            busy.add(b);
            return b;
        }
        if (open != null) busy.add(open);
        if (unpack) openUnpack = b;
        else openDirect = b;
        return b;
    }

    private static Block smallestFree(boolean unpack, int need) {
        int best = -1;
        for (int i = 0; i < free.size(); i++) {
            Block b = free.get(i);
            if ((b.buffer != null) == unpack && b.capacity >= need && (best < 0 || b.capacity < free.get(best).capacity)) best = i;
        }
        return best < 0 ? null : free.remove(best);
    }

    /** A block of at least {@code need} bytes; an unpack one only on the render thread and within the cap. */
    private static Block create(boolean unpack, int need) {
        int capacity = Math.max(BLOCK, align(need));
        if (!unpack) {
            directBytes += capacity;
            return new Block(null, CgBufferUtils.createByteBuffer(capacity), capacity, generation);
        }
        if (unpackBytes + capacity > CAP) return null;
        CgGpuBuffer buffer = CgRHI.get().createBuffer(new CgGpuBuffer.Desc("cg_uploads", capacity,
                EnumSet.of(CgGpuBuffer.Usage.COPY_SRC), true, false, true));
        unpackBytes += capacity;
        return new Block(buffer, buffer.mapped().duplicate().order(ByteOrder.nativeOrder()), capacity, generation);
    }

    /** Blocks covering what missed, each as big as the largest miss; the first frame makes {@link #AHEAD}. */
    private static void grow() {
        int size = Math.max(BLOCK, missedLargest);
        long want = Math.max(missedBytes, unpackBytes == 0 ? AHEAD : 1);
        while (want > 0) {
            Block b = create(true, size);
            if (b == null) break;
            free.add(b);
            want -= b.capacity;
        }
        missedBytes = 0;
        missedLargest = 0;
    }

    private static void reuse(Block b) {
        b.cursor = 0;
        b.lastUse = -1;
        if (b.buffer == null && directBytes > CAP) {
            directBytes -= b.capacity;   // over the cap: left to the collector
            return;
        }
        free.add(b);
    }

    private static void release(CgGpuBuffer buffer) {
        if (CgGLImports.installed()) CgGLImports.get().forget(buffer);
        CgRHI.get().release(buffer);
    }

    private static void giveBack(CgUploadLease lease) {
        lease.lease(null, 0, 0);
        spare.push(lease);
    }

    private static ArrayList<Block> all() {
        ArrayList<Block> all = new ArrayList<>(free);
        all.addAll(busy);
        if (openUnpack != null) all.add(openUnpack);
        if (openDirect != null) all.add(openDirect);
        return all;
    }

    private static int align(int bytes) {
        return (bytes + ALIGN - 1) & -ALIGN;
    }
}
