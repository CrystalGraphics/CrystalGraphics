package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.memory.CgTransientMemory;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The graph's state on the device, which outlives any one execution: the transient pools and placed heaps, every
 * storage's tracked state, the async fences, the external targets imported, the current targets open, the persistent
 * buffers made, and the executions running, one per nesting depth. Unreal keeps these on the RHI and its pools
 * ({@code GRenderTargetPool}, the transient allocator), never on a graph builder.
 *
 * <p>One, reset at context teardown ({@link #destroy}). Render thread.
 */
final class CgGraphDevice {

    private static final CgGraphDevice DEVICE = new CgGraphDevice();

    static CgGraphDevice get() {
        return DEVICE;
    }

    static final boolean ALIASING = CgVar.bool("crystalgraphics.graph.aliasing", true, CgVar.DEV | CgVar.RESTART,
            "Transient 2D textures share memory where the device can place them (Vulkan); false pools them by description,"
                    + " as GL does: Unreal's r.RDG.TransientAllocator 0, for comparing the two.").get();
    private static final int TRIM_POOLS = CgTrace.name("graph.trimPools");
    /** KiB of 2D transient textures held: heaps where placed, the pooled framebuffers' images where not. */
    private static final int TRANSIENT_KB = CgTrace.name("graph.transient-kb");
    /** The texture pool's framebuffers and their bytes, on every backend: transient-kb is the device heap's alone. */
    private static final int POOL_FRAMEBUFFERS = CgTrace.name("graph.pool-framebuffers"),
            POOL_KB = CgTrace.name("graph.pool-kb");
    /** KiB of placed transient textures alive at once at the last frame's most. */
    private static final int TRANSIENT_PEAK_KB = CgTrace.name("graph.transient-peak-kb");
    private static final int MAKE_STORAGE = CgTrace.name("graph.makeStorage"),
            STORAGE_MADE = CgTrace.name("graph.storage-made-bytes");

    final CgTexturePool pool = new CgTexturePool();
    final CgBufferPool buffers = new CgBufferPool();
    final CgResourceStates states = new CgResourceStates();
    final CgAsyncWaits async = new CgAsyncWaits();
    /** Every transition an execution states, on the device's command list. */
    final CgGraphBarriers transitions = new CgGraphBarriers();
    /** The device compute pass between dispatches, and whether a barrier batch is open in it. */
    final CgDeviceCompute compute = new CgDeviceCompute();
    boolean computing, barrierBatch;
    /** Each device pass's description, kept while its attachments and loads are the same. */
    final CgPassDescs passDescs = new CgPassDescs();
    final IntConsumer forgetBuffer = this::forget;
    final Consumer<CgGraphStorage> forgetStorage = this::forgetStorage;

    /** Transients placed in the device's transient heaps, made with the first frame on a device that places; else null. */
    @Nullable
    private CgTransientTextures transients;
    /** Persistent and history buffers whose storage the graph made, freed at teardown if never released. */
    private final List<CgGraphBuffer> kept = new ArrayList<>();
    /** The storages of the external targets opened last, most recent first: a host alternates a few. */
    private final CgExternalTarget[] opened = new CgExternalTarget[8];
    private final CgGraphStorage[] openedStorage = new CgGraphStorage[8];
    /** Each compute pass's GPU zone by its name, interned once: a pass is recorded anew each frame. */
    private final Map<String, Integer> gpuZones = new HashMap<>();
    /** Each texture's image views a dispatch bound, kept while it lives. */
    final Map<CgGpuTexture, ImageViews> imageViews = new WeakHashMap<>();
    /** {@code i} at {@code i}: what a draw of its own records reads them through, grown as one reaches further. */
    float[] identity = new float[0];
    /** A texture upload's bytes on the render thread, where every upload runs: one buffer serves them all. */
    @Nullable
    ByteBuffer texels;

    /**
     * Set by the first frame with a kernel or a buffer operation. Until then nothing can race what a draw does but a
     * kernel's later write, which the device's graphics-to-compute wait already orders; so a process that never runs
     * one keeps no accesses at all.
     */
    boolean kernelsSeen;
    /** The compute pass executing, numbered: its overlap sets and its claims on storage, unique across executions. */
    int passSerial;
    private long trimmedFrame = -1;
    /** The last sizes a texture and the depth it draws with disagreed at, logged once. */
    long besideMismatch;
    boolean refusedLogged;
    /** The views a target copy's blits named last, the oldest replaced first: a frame copies between a few. */
    final CgTextureView[] attachedViews = new CgTextureView[8];
    int nextAttached;
    /** A comparison's two reads, grown to the largest asked. */
    @Nullable
    ByteBuffer compareFirst, compareSecond;
    /** Whether a rehearsal's pass is running. */
    boolean rehearsing;
    /** The ring frame {@link CgFrameScope}'s blackboard holds. */
    long scopeFrame = -1;
    /** The step whose bindings {@link CgGraphCheck} asks about, named in a refusal; null outside one. */
    @Nullable
    CgPass checkedStep;

    /** The executions, one per nesting depth, and how many are running. */
    final List<CgExecutor> executions = new ArrayList<>();
    int depth;
    /** The current targets {@link CgExecutor#into} opened, innermost last; reused, so opening one allocates nothing. */
    final CgExecutor.Into[] into = new CgExecutor.Into[8];
    int intoDepth;
    /** What executions take as their current target while it is open; null where none is. */
    @Nullable
    CgExecutor.Into current;

    private CgGraphDevice() {
    }

    /** The innermost execution running; null between executions. */
    @Nullable
    CgExecutor executing() {
        return depth == 0 ? null : executions.get(depth - 1);
    }

    /** At the first execution of a ring frame: the pools let go of what the last frame left unused. */
    void trim() {
        long ringFrame = CgFrameRing.frame();
        if (depth != 0 || ringFrame == trimmedFrame) return;
        try (CgTrace.Zone trim = CgTrace.zone(CgChannels.GL, TRIM_POOLS)) {
            pool.endFrame(forgetStorage);
            buffers.endFrame(forgetBuffer);
            CgTrace.counter(CgChannels.GL, POOL_FRAMEBUFFERS, pool.held());
            CgTrace.counter(CgChannels.GL, POOL_KB, pool.footprintBytes() >> 10);
            // The last frame's end waited for all its async work, as an execution takes it.
            if (transients != null) {
                transients.endFrame(async.latest, forgetStorage);
                CgTrace.counter(CgChannels.GL, TRANSIENT_KB, (ALIASING ? transients.heapBytes() : pool.bytes()) >> 10);
                if (ALIASING) CgTrace.counter(CgChannels.GL, TRANSIENT_PEAK_KB, transients.framePeak() >> 10);
            }
        }
        trimmedFrame = ringFrame;
    }

    // ── Transient storage ────────────────────────────────────────────────────

    /**
     * Storage for a transient of {@code desc} first used on the compute queue where {@code async}: placed in the
     * device's transient heaps where it places them, its contents undefined, else pooled.
     */
    CgGraphStorage takeStorage(CgTextureDesc desc, boolean async) {
        CgTransientTextures placing = placing(desc);
        if (placing == null) return pool.acquire(desc);
        return placing.acquire(desc, async ? Long.MAX_VALUE : this.async.joined());
    }

    /** A transient's storage given back after its last use, with the async work still pending on it. */
    void giveBack(CgGraphResource resource) {
        if (resource instanceof CgGraphTexture texture) {
            CgTransientTextures placing = placing(texture.desc());
            if (placing != null) placing.release(texture.storage(), async.pending(texture.storage()));
            else pool.release(texture.desc(), texture.storage());
            texture.resolve(null);
        } else {
            CgGraphBuffer buffer = (CgGraphBuffer) resource;
            buffers.release(buffer.desc(), buffer.bufferId());
            buffer.resolve(0);
        }
    }

    /** The device's transient heaps where {@code desc} is placed in them; null where it is pooled. */
    @Nullable
    private CgTransientTextures placing(CgTextureDesc desc) {
        if (!CgRHI.installed()) return null;
        if (transients == null) {
            CgRHI rhi = CgRHI.get();
            CgTransientMemory memory = rhi.transientMemory();
            if (memory == null) return null;
            CgTransientTextures made = new CgTransientTextures(rhi, memory);
            pool.measure(d -> CgTransientTextures.places(d) ? made.bytes(d) : 0);
            transients = made;
        }
        return ALIASING && CgTransientTextures.places(desc) ? transients : null;
    }

    // ── Storage a graph texture or buffer names ──────────────────────────────

    /**
     * A texture's storage now: the current target's as the execution opened it, an imported one's as made with it, a
     * requested one's made on first use, a history's both versions, cleared.
     */
    CgGraphStorage storage(CgGraphTexture texture) {
        CgGraphStorage[] versions = texture.versions();
        if (versions != null && versions[0] == null) {
            CgTrace.add(CgChannels.GL, "graph.history.made", 1);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                for (int v = 0; v < 2; v++) {
                    versions[v] = CgTexturePool.create("cg_graph_" + texture.resource().name() + v, texture.desc());
                    forgetStorage(versions[v]);
                    versions[v].clearColor();
                }
            }
        }
        CgGraphStorage storage = texture.storage();
        if (storage == null && texture.kind() == CgGraphTexture.Kind.REQUESTED) {
            // At a requested texture's first use; made again later, the picture it held was lost.
            CgTrace.add(CgChannels.GL, "graph.requested.made", 1);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                storage = CgTexturePool.create("cg_graph_" + texture.name(), texture.desc());
            }
            forgetStorage(storage);
            texture.resolve(storage);
        }
        if (storage == null) {
            throw new IllegalStateException(texture.isCurrent() ? "no current target is open: open CgExecutor.into round"
                    + " the execution" : texture + " has no storage in this pass");
        }
        return storage;
    }

    /**
     * The storage {@code view} is used through now, made at a persistent or history buffer's first use: a write to a
     * history goes to its next version, a read to its newest, its previous view's read to the other.
     */
    int bufferStorage(CgGraphBuffer view, boolean writes) {
        CgGraphBuffer buffer = view.resource();
        switch (buffer.kind()) {
            case PERSISTENT -> {
                if (buffer.bufferId() == 0) {
                    CgTrace.marker(CgChannels.GL, "graph.storage-made", buffer.name());   // which, at first use
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                        buffer.resolve(CgBufferPool.create(buffer.size()));
                    }
                    CgTrace.add(CgChannels.GL, STORAGE_MADE, buffer.size());
                    kept.add(buffer);
                }
            }
            case HISTORY -> {
                int[] versions = buffer.versions();
                if (versions[0] == 0) {
                    CgTrace.marker(CgChannels.GL, "graph.storage-made", buffer.name());
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                        versions[0] = CgBufferPool.create(buffer.size());
                        versions[1] = CgBufferPool.create(buffer.size());
                    }
                    CgTrace.add(CgChannels.GL, STORAGE_MADE, 2 * buffer.size());
                    kept.add(buffer);
                }
                return writes ? buffer.nextVersion() : view.bufferId();
            }
            default -> { }
        }
        int id = buffer.bufferId();
        if (id == 0) throw new IllegalStateException(view + " has no storage in this pass");
        return id;
    }

    /** Frees a persistent or history buffer's storage, kept or released. */
    void freeBuffer(CgGraphBuffer buffer) {
        kept.remove(buffer);
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) {
            int[] versions = buffer.versions();
            for (int v = 0; v < 2; v++) {
                if (versions[v] == 0) continue;
                CgBufferPool.free(versions[v]);
                forget(versions[v]);
                versions[v] = 0;
            }
        } else if (buffer.bufferId() != 0) {
            CgBufferPool.free(buffer.bufferId());
            forget(buffer.bufferId());
            buffer.resolve(0);
        }
    }

    /** Whether the graph made {@code buffer}'s storage and holds it. */
    boolean keeps(CgGraphBuffer buffer) {
        return kept.contains(buffer);
    }

    /** A requested or history texture's storage released, its states forgotten. */
    void freeTexture(CgGraphTexture texture) {
        CgGraphStorage[] versions = texture.versions();
        if (versions != null) {
            for (int v = 0; v < versions.length; v++) {
                free(versions[v]);
                versions[v] = null;
            }
        } else {
            free(texture.storage());
            texture.resolve(null);
        }
    }

    private void free(@Nullable CgGraphStorage storage) {
        if (storage == null) return;
        forgetStorage(storage);
        storage.release();
    }

    /** GL buffer {@code buffer} is freed: what is known of it goes with it. */
    void forget(int buffer) {
        states.forget(CgResourceStates.buffer(buffer));
    }

    /** {@code storage}'s states forgotten, before it is released: its images' names are dropped with it. */
    void forgetStorage(CgGraphStorage storage) {
        states.forget(storage.key());
        for (int slot = 0; slot < storage.colorSlots(); slot++) {
            CgGpuTexture color = storage.color(slot);
            if (color != null) states.forget(CgGraphStorage.key(color));
        }
    }

    // ── External targets ─────────────────────────────────────────────────────

    /** {@code target}'s storage, made at its first opening and kept while it is among the last opened. */
    CgGraphStorage opened(CgExternalTarget target) {
        for (int i = 0; i < opened.length; i++) {
            if (opened[i] != target) continue;
            CgGraphStorage found = openedStorage[i];
            if (i > 0) {
                System.arraycopy(opened, 0, opened, 1, i);
                System.arraycopy(openedStorage, 0, openedStorage, 1, i);
                opened[0] = target;
                openedStorage[0] = found;
            }
            return found;
        }
        System.arraycopy(opened, 0, opened, 1, opened.length - 1);
        System.arraycopy(openedStorage, 0, openedStorage, 1, openedStorage.length - 1);
        opened[0] = target;
        return openedStorage[0] = CgGraphStorage.imported(target);
    }

    /** The host's targets are imported afresh. */
    void forgetHostTargets() {
        passDescs.clear();
        Arrays.fill(opened, null);
        Arrays.fill(openedStorage, null);
    }

    // ── Names interned for traces ────────────────────────────────────────────

    /** A compute pass's GPU zone. */
    int gpuZone(String pass) {
        Integer id = gpuZones.get(pass);
        if (id == null) gpuZones.put(pass, id = CgGpuTrace.name(pass));
        return id;
    }

    /**
     * Frees every execution's buffers, the transient pools and every persistent or history buffer's storage still held,
     * and forgets every state: at context teardown, before the framebuffer sweep.
     */
    void destroy() {
        for (CgExecutor execution : executions) execution.delete();
        executions.clear();
        pool.delete();
        if (transients != null) transients.delete();
        transients = null;
        buffers.delete();
        for (CgGraphBuffer buffer : new ArrayList<>(kept)) freeBuffer(buffer);
        kept.clear();
        states.clear();
        imageViews.clear();
        CgBufferInspector.reset();
        async.reset();
    }

    /** A texture's kept image views and what each was asked as: one per level and layer (-1: all). */
    static final class ImageViews {
        int count;
        int[] levels = new int[2], layers = new int[2];
        CgTextureView[] views = new CgTextureView[2];

        void add(int level, int layer, CgTextureView view) {
            if (count == views.length) {
                levels = Arrays.copyOf(levels, count * 2);
                layers = Arrays.copyOf(layers, count * 2);
                views = Arrays.copyOf(views, count * 2);
            }
            levels[count] = level;
            layers[count] = layer;
            views[count++] = view;
        }
    }
}
