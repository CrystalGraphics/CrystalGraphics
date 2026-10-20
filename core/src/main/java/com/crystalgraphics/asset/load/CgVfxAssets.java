package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The format's assets as gameplay and plays see them: refs declared at init, loads toward READY, and every definition
 * published at the frame boundary. Unreal's {@code FStreamableManager} (StreamableManager.h:756,
 * {@code RequestAsyncLoad}; :786, {@code RequestSyncLoad}) over the registry and the pipeline.
 *
 * <pre>{@code
 * static final CgAssetRef<CgAssetData> NOVA = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "novamod:nova_cannon");
 *
 * CgVfxAssets.instance().registry().scanResources(resources);      // at init
 * CgLoadHandle h = CgVfxAssets.instance().load(List.of(NOVA), CgLoadPriority.MOVE);
 *
 * // the frame boundary, render thread (CgFrameBoundary runs these):
 * CgVfxAssets.instance().drain();     // the asset loop's results; prepare work in budget
 * CgVfxAssets.instance().publish();   // swaps
 * }</pre>
 *
 * <ul>
 *   <li>{@link #load}, {@link #drain} and {@link #publish} run on one thread, the asset loop's: the render thread at
 *       the frame boundary on a client, the server thread on a dedicated server.</li>
 *   <li>{@link #declare} and {@link #ref} run on any thread, at init.</li>
 *   <li>Nothing is released: a handle dropped ends nothing.</li>
 * </ul>
 */
public final class CgVfxAssets {

    private static final Logger LOGGER = LogManager.getLogger("CgVfxAssets");
    private static final int SYNC_LOADS = CgTrace.name("asset.sync-loads");
    private static final long SYNC_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    @Nullable private static volatile CgVfxAssets instance;

    private final CgAssetRegistry registry;
    private final CgAssetPipeline pipeline;
    private final Map<String, CgAssetRef<?>> refs = new HashMap<>();
    /** Requested and neither ready nor failed. */
    private final List<CgAssetRef<?>> loading = new ArrayList<>();
    /** Handles whose ready listeners have not fired. */
    private final List<CgLoadHandle> handles = new ArrayList<>();
    private long syncLoads;

    /** Assets over {@code registry}, loading on a loop of their own, {@code assets}. */
    public CgVfxAssets(CgAssetRegistry registry) {
        this(registry, CgJobLoop.create("assets"));
    }

    /** Assets over {@code registry}, the pipeline reporting to {@code loop}: for tests. */
    public CgVfxAssets(CgAssetRegistry registry, CgJobLoop loop) {
        this.registry = registry;
        this.pipeline = new CgAssetPipeline(registry, loop);
    }

    /** The process's assets, made over an empty registry on first ask. */
    public static CgVfxAssets instance() {
        CgVfxAssets a = instance;
        if (a != null) return a;
        synchronized (CgVfxAssets.class) {
            if (instance == null) instance = new CgVfxAssets(new CgAssetRegistry());
            return instance;
        }
    }

    /** Replaces the process's assets: a host with a registry of its own, or a test. */
    public static synchronized void install(CgVfxAssets assets) {
        instance = assets;
    }

    /** {@code kind}'s {@code id} on the process's assets: {@code instance().declare(kind, id)}. */
    public static <D> CgAssetRef<D> ref(CgType kind, String id) {
        return instance().declare(kind, id);
    }

    /** The ref of {@code kind}'s {@code id}: the same ref for the same asset. Loads nothing. Any thread. */
    @SuppressWarnings("unchecked")
    public synchronized <D> CgAssetRef<D> declare(CgType kind, String id) {
        String key = kind.id() + '|' + id;
        CgAssetRef<?> ref = refs.get(key);
        if (ref == null) refs.put(key, ref = new CgAssetRef<>(this, kind, id));
        return (CgAssetRef<D>) ref;
    }

    public CgAssetRegistry registry() {
        return registry;
    }

    public CgAssetPipeline pipeline() {
        return pipeline;
    }

    /**
     * Loads {@code primaries} toward READY at {@code priority}, or raises the loads already running. The asset loop's
     * thread.
     */
    public CgLoadHandle load(List<? extends CgAssetRef<?>> primaries, CgLoadPriority priority) {
        Set<CgAssetRef<?>> all = new LinkedHashSet<>(primaries);
        List<CgAssetRef<?>> assets = new ArrayList<>(all);
        for (CgAssetRef<?> a : assets) start(a, priority);
        CgLoadHandle h = new CgLoadHandle(new ArrayList<>(primaries), assets, priority);
        handles.add(h);
        return h;
    }

    /**
     * The frame boundary's drain: the pipeline's finished steps published, newly compiled definitions set on their
     * refs, and handles that became ready told. Allocates nothing when nothing finished.
     */
    public void drain() {
        pipeline.drain();
        for (int i = loading.size() - 1; i >= 0; i--) {
            if (settle(loading.get(i))) removeAt(loading, i);
        }
        for (int i = handles.size() - 1; i >= 0; i--) {
            if (handles.get(i).fireIfReady()) removeAt(handles, i);
        }
    }

    /** How many {@link CgAssetRef#get()} calls loaded synchronously: {@code asset.sync-loads}, 0 in a benchmark. */
    public long syncLoads() {
        return syncLoads;
    }

    // ── Loads ────────────────────────────────────────────────────────────────────────────

    private void start(CgAssetRef<?> ref, CgLoadPriority priority) {
        ref.priority = ref.priority == null ? priority : ref.priority.max(priority);
        if (ref.load != null) return;
        ref.load = pipeline.request(ref.kind(), ref.id());
        loading.add(ref);
    }

    /** Takes what {@code ref}'s load reached; answers whether it is settled, ready or failed. */
    private boolean settle(CgAssetRef<?> ref) {
        CgAssetLoad l = ref.load;
        if (l == null) return false;
        if (l.state() == CgAssetState.FAILED) return true;
        if (!l.state().atLeast(CgAssetState.COMPILED)) return false;
        if (ref.definition == null) {
            ref.definition = l.definition();
            ref.generation = 1;
        }
        ref.ready = true;
        return true;
    }

    /** {@link CgAssetRef#get()} on an asset not yet published. */
    @Nullable
    Object loadNow(CgAssetRef<?> ref) {
        CgAssetLoad l = ref.load;
        if (l != null && l.state() == CgAssetState.FAILED) return null;
        if (CgRenderStage.firing()) {
            if (!ref.loggedRefused) {
                ref.loggedRefused = true;
                LOGGER.warn("{} {} asked for inside a stage firing before it loaded: drawn without it", ref.kind().id(), ref.id());
            }
            return null;
        }
        syncLoads++;
        CgTrace.counter(CgChannels.MISC, SYNC_LOADS, syncLoads);
        if (!ref.loggedSync) {
            ref.loggedSync = true;
            LOGGER.warn("{} {} loaded synchronously: request it ahead of its first use", ref.kind().id(), ref.id());
        }
        start(ref, CgLoadPriority.PLAYING);
        long deadline = System.nanoTime() + SYNC_TIMEOUT_NANOS;
        while (!ref.load.state().atLeast(CgAssetState.COMPILED) && ref.load.state() != CgAssetState.FAILED) {
            pipeline.drain();
            if (System.nanoTime() > deadline) throw new IllegalStateException(ref + " did not load in 30 s");
            LockSupport.parkNanos(50_000);
        }
        if (ref.load.state() == CgAssetState.FAILED) {
            LOGGER.error("{} {} failed: {}", ref.kind().id(), ref.id(), ref.load.failure());
            return null;
        }
        settle(ref);
        return ref.definition;
    }

    private static <T> void removeAt(List<T> list, int i) {
        int last = list.size() - 1;
        list.set(i, list.get(last));
        list.remove(last);
    }
}
