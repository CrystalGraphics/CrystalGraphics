package com.crystalgraphics.asset.load;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.codec.CgAssetVersions;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.asset.tree.CgChange;
import com.crystalgraphics.asset.tree.CgEdit;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadListener;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The format's assets as gameplay and plays see them: refs declared at init, bundles loaded toward READY, and every
 * definition published at the frame boundary. Unreal's {@code FStreamableManager} (StreamableManager.h:756,
 * {@code RequestAsyncLoad}; :786, {@code RequestSyncLoad}) and the asset manager's bundles (AssetManager.h:308,
 * {@code LoadPrimaryAssets}) over the registry and the pipeline.
 *
 * <pre>{@code
 * static final CgAssetRef<CgAssetData> NOVA = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "novamod:nova_cannon");
 *
 * CgVfxAssets assets = CgVfxAssets.instance();
 * assets.registry().scanResources(resources);                       // at init
 * assets.preparer(CgParticleKinds.SYSTEM, systemPreparer);          // a client's GPU work per kind
 * CgLoadHandle h = assets.load(List.of(NOVA), CgLoadPriority.MOVE);  // its whole bundle
 *
 * // the frame boundary, render thread (CgFrameBoundary runs these):
 * assets.drain();      // the asset loop's results; prepare work within the frame's budgets
 * assets.publish();    // swaps
 * }</pre>
 *
 * <ul>
 *   <li>{@link #load}, {@link #drain} and {@link #publish} run on one thread, the asset loop's: the render thread at
 *       the frame boundary on a client, the server thread on a dedicated server.</li>
 *   <li>{@link #declare}, {@link #ref} and {@link #preparer} run on any thread, at init.</li>
 *   <li>A bundle is the closure of its primaries' hard and soft references; an asset in two bundles loads once.</li>
 *   <li>READY means its GPU work is done and its hard dependencies are READY. Prepare runs the most urgent load
 *       first, within {@link #budget()} and the warm budget.</li>
 *   <li>Nothing is released: a handle dropped ends nothing.</li>
 * </ul>
 */
public final class CgVfxAssets {

    private static final Logger LOGGER = LogManager.getLogger("CgVfxAssets");
    private static final int SYNC_LOADS = CgTrace.name("asset.sync-loads");
    private static final long SYNC_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final CgLoadPriority[] PRIORITIES = CgLoadPriority.values();

    @Nullable private static volatile CgVfxAssets instance;

    private final CgAssetRegistry registry;
    private final CgAssetPipeline pipeline;
    private final Map<String, CgAssetRef<?>> refs = new HashMap<>();
    private final Map<CgType, CgAssetPreparer> preparers = new ConcurrentHashMap<>();
    @Nullable private volatile CgAssetPreparer.PoolSizer poolSizer;
    private final CgPrepareBudget budget = new CgPrepareBudget();
    /** Requested, not yet compiled or failed. */
    private final List<CgAssetRef<?>> loading = new ArrayList<>();
    /** Compiled, not yet READY. */
    private final List<CgAssetRef<?>> preparing = new ArrayList<>();
    /** Handles whose ready listeners have not fired. */
    private final List<CgLoadHandle> handles = new ArrayList<>();
    /** Changes compiled, waiting on their GPU work or the next publish. */
    private final List<Swap> swaps = new ArrayList<>();
    private final CgJobLoop loop;
    /** Files saved since the last drain, as {@code ns:path}. */
    private final Queue<String> saved = new ConcurrentLinkedQueue<>();
    private int compiling;
    private long syncLoads;

    /** Assets over {@code registry}, loading on a loop of their own, {@code assets}. */
    public CgVfxAssets(CgAssetRegistry registry) {
        this(registry, CgJobLoop.create("assets"));
    }

    /** Assets over {@code registry}, the pipeline reporting to {@code loop}: for tests. */
    public CgVfxAssets(CgAssetRegistry registry, CgJobLoop loop) {
        this.registry = registry;
        this.loop = loop;
        this.pipeline = new CgAssetPipeline(registry, loop);
    }

    /** The process's assets, made over an empty registry on first ask. */
    public static CgVfxAssets instance() {
        CgVfxAssets a = instance;
        if (a != null) return a;
        synchronized (CgVfxAssets.class) {
            if (instance == null) {
                instance = new CgVfxAssets(new CgAssetRegistry());
                CgAssetReloader.addListener(SAVES);
            }
            return instance;
        }
    }

    /** Hot reload's saves, handed to whichever assets are the process's then. */
    private static final CgReloadListener SAVES = new CgReloadListener() {
        @Override
        public void onReload() {
        }

        @Override
        public void onChanged(String id) {
            CgVfxAssets a = instance;
            if (a != null) a.saved(id);
        }
    };

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

    /** Sets {@code kind}'s GPU work. Register before the first load of the kind. */
    public CgVfxAssets preparer(CgType kind, CgAssetPreparer preparer) {
        preparers.put(kind, preparer);
        return this;
    }

    /** Sets what grows the particle pools to each bundle's peak. */
    public CgVfxAssets poolSizer(CgAssetPreparer.PoolSizer sizer) {
        poolSizer = sizer;
        return this;
    }

    /** The frame's prepare budget. */
    public CgPrepareBudget budget() {
        return budget;
    }

    public CgAssetRegistry registry() {
        return registry;
    }

    public CgAssetPipeline pipeline() {
        return pipeline;
    }

    /**
     * Loads the bundles of {@code primaries} toward READY at {@code priority}, or raises the loads already running.
     * The asset loop's thread.
     */
    public CgLoadHandle load(List<? extends CgAssetRef<?>> primaries, CgLoadPriority priority) {
        List<CgAssetRef<?>> assets = bundle(primaries);
        for (CgAssetRef<?> a : assets) start(a, priority);
        CgLoadHandle h = new CgLoadHandle(new ArrayList<>(primaries), assets, priority);
        handles.add(h);
        return h;
    }

    /** Every asset {@code primaries} reach through hard and soft references, primaries first. */
    public List<CgAssetRef<?>> bundle(List<? extends CgAssetRef<?>> primaries) {
        Set<CgAssetRef<?>> out = new LinkedHashSet<>(primaries);
        Deque<CgAssetRef<?>> open = new ArrayDeque<>(primaries);
        while (!open.isEmpty()) {
            CgAssetRef<?> a = open.poll();
            CgAssetEntry e;
            try {
                e = registry.resolve(a.kind(), a.id());
            } catch (CgAssetException missing) {
                continue;   // the pipeline fails it, naming why
            }
            for (CgAssetReferences.Reference r : e.references()) {
                if (r.id().startsWith("unreal:")) continue;
                CgAssetRef<?> d = declare(r.kind(), r.id());
                if (out.add(d)) open.add(d);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The frame boundary's drain: the pipeline's finished steps taken, compiled assets planned, prepare work run within
     * the frame's budgets, handles that became ready told. Allocates nothing when nothing finished.
     */
    public void drain() {
        if (!saved.isEmpty()) {
            for (String id; (id = saved.poll()) != null; ) reread(id);
        }
        pipeline.drain();
        for (int i = loading.size() - 1; i >= 0; i--) {
            if (settle(loading.get(i))) removeAt(loading, i);
        }
        budget.begin();
        for (int i = 0, n = swaps.size(); i < n; i++) {   // a change waits on nothing but its own GPU work
            Swap s = swaps.get(i);
            if (s.prepared) continue;
            boolean done = true;
            for (int k = 0; k < s.plans.size() && done; k++) done = s.plans.get(k).advance(budget);
            s.prepared = done;
        }
        for (CgLoadPriority p : PRIORITIES) {
            for (int i = 0; i < preparing.size(); ) {
                CgAssetRef<?> r = preparing.get(i);
                if (r.priority == p && prepare(r)) preparing.remove(i);
                else i++;
            }
        }
        for (int i = handles.size() - 1; i >= 0; i--) {
            CgLoadHandle h = handles.get(i);
            if (!h.pooled) pool(h);
            if (h.fireIfReady()) removeAt(handles, i);
        }
    }

    /**
     * The frame boundary's publish, after {@link #drain}: each change whose recompiles and GPU work are done swaps its
     * definitions together, bumping each ref's generation. Never call it between two firings of one frame.
     */
    public void publish() {
        for (int i = 0; i < swaps.size(); ) {
            Swap s = swaps.get(i);
            if (!s.prepared) {
                i++;
                continue;
            }
            for (int k = 0, n = s.refs.size(); k < n; k++) {
                CgAssetRef<?> r = s.refs.get(k);
                r.definition = s.definitions.get(k);
                int g = r.generation + 1;
                if (s.structural) r.structural = g;
                r.generation = g;
            }
            swaps.remove(i);
        }
    }

    /**
     * Applies {@code edits} to {@code ref}'s tree as one transaction and recompiles it and what follows it (children
     * of it, definitions inlining it); their swap publishes at a later frame boundary. Answers the inverses. The asset
     * loop's thread; the asset must have compiled from a file.
     *
     * <pre>{@code
     * assets.edit(SHURIKEN, List.of(CgEdit.set(SHURIKEN_PATH.key("delay"), 0.25)));   // VALUE: patched next step
     * }</pre>
     *
     * @throws IllegalStateException if it has not compiled, or was built by code
     * @throws CgAssetException      if an edit names nothing it can edit; the tree is then as it was
     */
    public List<CgEdit> edit(CgAssetRef<?> ref, List<CgEdit> edits) {
        CgAssetData asset = tree(ref);
        if (asset == ref.definition) {   // a kind with no compiler plays its tree: edit a copy, never what plays
            asset = CgAssetData.of(asset.id(), asset.kind(), asset.delta(), asset.parent());
            ref.load.asset = asset;
        }
        CgChange[] change = {CgChange.VALUE};
        Runnable stop = asset.onChanged(e -> change[0] = change[0].max(e.change()));
        List<CgEdit> inverses;
        try {
            inverses = asset.apply(edits);
        } finally {
            stop.run();
        }
        recompile(ref, change[0]);
        return inverses;
    }

    /**
     * A file saved, as {@code ns:path} ({@code mymod:vfx/systems/charge.yaml}): at the next drain each loaded asset read
     * from it is re-read on a worker, diffed against the file it replaces into edits, and published as {@link #edit}
     * would. A file that no longer decodes, resolves or validates keeps the last good definition, logged. Any thread;
     * {@code CgHotReload}'s saves arrive here.
     */
    public void saved(String id) {
        saved.add(id);
    }

    private void reread(String saved) {
        String tail = "/assets/" + saved.replace(':', '/');
        List<CgAssetRef<?>> hit = new ArrayList<>();
        synchronized (this) {
            for (CgAssetRef<?> r : refs.values()) {
                if (r.load == null || r.load.asset() == null || !r.load.state().atLeast(CgAssetState.COMPILED)) continue;
                CgAssetEntry e = registry.entry(r.kind(), r.id());
                if (e != null && e.file() != null && e.file().toString().replace('\\', '/').endsWith(tail)) hit.add(r);
            }
        }
        for (CgAssetRef<?> r : hit) {
            Path file = registry.entry(r.kind(), r.id()).file();
            CgJob.on(loop).run(() -> {
                try {
                    Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    return CgAssetVersions.upgrade(tree, r.kind());
                } catch (Exception e) {
                    throw new CgAssetException(file + ": " + e);
                }
            }).then(tree -> reload(r, tree)).failed(t -> LOGGER.error(
                    "[hot reload] {} no longer decodes; the last good definition plays on: {}", r.id(), t.getMessage()));
        }
    }

    /** {@code ref}'s file replaced by {@code file}: its diff as edits, or the whole file where edits cannot say it. */
    void reload(CgAssetRef<?> ref, Map<?, ?> file) {
        CgAssetData live = tree(ref);
        List<CgEdit> edits = CgAssetFileDiff.diff(ref.id(), live.delta(), file);
        if (edits != null && edits.isEmpty()) return;
        if (edits != null) {
            try {
                edit(ref, edits);
                return;
            } catch (CgAssetException e) {
                LOGGER.warn("[hot reload] {}: its diff did not apply ({}); reloading it whole", ref.id(), e.getMessage());
            }
        }
        CgAssetData whole;
        try {
            whole = CgAssetData.of(ref.id(), ref.kind(), file, live.parent());
        } catch (CgAssetException e) {
            LOGGER.error("[hot reload] {} no longer resolves; the last good definition plays on: {}", ref.id(), e.getMessage());
            return;
        }
        ref.load.asset = whole;
        recompile(ref, CgChange.STRUCTURAL);
    }

    /** {@code ref}'s editable tree: the asset its load resolved. */
    CgAssetData tree(CgAssetRef<?> ref) {
        CgAssetLoad l = ref.load;
        if (l == null || !l.state().atLeast(CgAssetState.COMPILED)) throw new IllegalStateException(ref + " has not compiled");
        if (l.asset() == null) throw new IllegalStateException(ref + " was built by code: it reloads by restarting");
        return l.asset();
    }

    /** Changes waiting on their compile or GPU work, or for the next publish. */
    public int swapsPending() {
        return swaps.size() + compiling;
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

    /** Takes what {@code ref}'s load reached; answers whether it left loading, compiled or failed. */
    private boolean settle(CgAssetRef<?> ref) {
        CgAssetLoad l = ref.load;
        if (l == null) return false;
        if (l.state() == CgAssetState.FAILED) return true;
        if (!l.state().atLeast(CgAssetState.COMPILED)) return false;
        if (ref.definition == null) {
            ref.definition = l.definition();
            ref.generation = 1;
        }
        plan(ref, l);
        preparing.add(ref);
        return true;
    }

    /** Lists {@code ref}'s GPU work and its hard dependencies, which it waits on. */
    private void plan(CgAssetRef<?> ref, CgAssetLoad l) {
        CgPreparePlan plan = new CgPreparePlan();
        CgAssetPreparer p = preparers.get(ref.kind());
        if (p != null && ref.definition != null) p.plan(ref.definition, plan);
        CgAssetData asset = l.asset();
        if (asset != null) {
            List<CgAssetRef<?>> hard = new ArrayList<>();
            for (CgAssetReferences.Reference r : CgAssetReferences.of(asset)) {
                if (r.strength() != CgValueType.Strength.HARD || r.id().startsWith("unreal:")) continue;
                CgAssetRef<?> d = declare(r.kind(), r.id());
                if (d == ref) continue;
                start(d, ref.priority);
                hard.add(d);
            }
            ref.hard = hard;
        }
        ref.plan = plan;
    }

    /** Runs {@code ref}'s prepare under the frame's budget; answers whether it is READY. */
    private boolean prepare(CgAssetRef<?> ref) {
        for (int i = 0, n = ref.hard.size(); i < n; i++) if (!ref.hard.get(i).ready) return false;
        if (!ref.plan.advance(budget)) return false;
        ref.ready = true;
        return true;
    }

    /** Once every asset of {@code h} planned, hands the bundle's peaks to the pool sizer. */
    private void pool(CgLoadHandle h) {
        List<CgAssetRef<?>> assets = h.assets();
        for (int i = 0, n = assets.size(); i < n; i++) if (assets.get(i).plan == null) return;
        CgAssetPreparer.PoolSizer sizer = poolSizer;
        if (sizer != null) {
            Map<Object, Integer> peaks = new LinkedHashMap<>();
            for (CgAssetRef<?> a : assets) {
                for (Map.Entry<Object, Integer> e : a.plan.peaks.entrySet()) peaks.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            for (Map.Entry<Object, Integer> e : peaks.entrySet()) sizer.reserve(e.getKey(), e.getValue());
        }
        h.pooled = true;
    }

    // ── Changes ──────────────────────────────────────────────────────────────────────────

    /** One change's swaps: published together once every definition compiled and its GPU work is done. */
    private static final class Swap {
        final List<CgAssetRef<?>> refs;
        final List<Object> definitions;
        final List<CgPreparePlan> plans = new ArrayList<>();
        final boolean structural;
        boolean prepared;

        Swap(List<CgAssetRef<?>> refs, List<Object> definitions, boolean structural) {
            this.refs = refs;
            this.definitions = definitions;
            this.structural = structural;
        }
    }

    /**
     * Recompiles {@code root}, which changed by {@code change}, and every loaded asset that follows it, on a worker;
     * queues their swap. A set that no longer validates or compiles keeps the last good definitions, logged.
     */
    void recompile(CgAssetRef<?> root, CgChange change) {
        List<CgAssetRef<?>> order = affected(root);
        // Snapshots on this thread, so the worker never reads a tree the next edit changes.
        Map<CgAssetRef<?>, CgAssetData> fresh = new LinkedHashMap<>();
        for (CgAssetRef<?> r : order) {
            CgAssetData live = tree(r);
            CgAssetData parent = live.parent();
            if (parent != null) {
                CgAssetRef<?> p = declare(r.kind(), parent.id());
                if (fresh.containsKey(p)) parent = fresh.get(p);
            }
            fresh.put(r, CgAssetData.of(live.id(), live.kind(), live.delta(), parent));
        }
        compiling++;
        CgJob.on(loop).run(() -> {
            Map<String, Object> made = new HashMap<>();
            List<Object> definitions = new ArrayList<>();
            for (Map.Entry<CgAssetRef<?>, CgAssetData> e : fresh.entrySet()) {
                CgAssetData a = e.getValue();
                CgValidation v = a.validate(CgValidation.Usecase.LOAD);
                if (!v.valid()) throw new CgAssetException(a.id() + ": " + v.errors() + " validation errors, first " + v.issues().get(0));
                Object d = CgAssetCompiler.of(a.kind()).compile(a, (kind, id) -> {
                    Object m = made.get(kind.id() + '|' + id);
                    return m != null ? m : declare(kind, id).loaded();
                });
                made.put(a.kind().id() + '|' + a.id(), d);
                definitions.add(d);
            }
            return definitions;
        }).then(definitions -> {
            compiling--;
            List<CgAssetRef<?>> refs = new ArrayList<>(fresh.keySet());
            // children re-resolve over the live parent, so the next edit of either reaches both
            for (CgAssetRef<?> r : refs) {
                if (r == root) continue;
                CgAssetData live = tree(r);
                if (live.parent() != null) {
                    CgAssetRef<?> p = declare(r.kind(), live.parent().id());
                    if (fresh.containsKey(p)) r.load.asset = CgAssetData.of(live.id(), live.kind(), live.delta(), tree(p));
                }
            }
            Swap s = new Swap(refs, definitions, change == CgChange.STRUCTURAL);
            for (int i = 0; i < refs.size(); i++) {
                CgPreparePlan plan = new CgPreparePlan();
                CgAssetPreparer p = preparers.get(refs.get(i).kind());
                if (p != null) p.plan(definitions.get(i), plan);
                s.plans.add(plan);
            }
            swaps.add(s);
        }).failed(t -> {
            compiling--;
            LOGGER.error("{} {} changed and no longer compiles; the last good definition plays on: {}",
                    root.kind().id(), root.id(), t.getMessage());
        });
    }

    /** {@code root}, then every loaded asset following it (a child, an inliner), each after what it follows. */
    private List<CgAssetRef<?>> affected(CgAssetRef<?> root) {
        List<CgAssetRef<?>> loaded = new ArrayList<>();
        synchronized (this) {
            for (CgAssetRef<?> r : refs.values()) {
                if (r.load != null && r.load.state().atLeast(CgAssetState.COMPILED) && r.load.asset() != null) loaded.add(r);
            }
        }
        List<CgAssetRef<?>> post = new ArrayList<>();
        visit(root, loaded, new HashSet<CgAssetRef<?>>(), post);
        Collections.reverse(post);
        return post;
    }

    private void visit(CgAssetRef<?> at, List<CgAssetRef<?>> loaded, Set<CgAssetRef<?>> seen, List<CgAssetRef<?>> post) {
        if (!seen.add(at)) return;
        for (CgAssetRef<?> r : loaded) {
            CgAssetData parent = r.load.asset().parent();
            boolean child = parent != null && r.kind() == at.kind() && parent.id().equals(at.id());
            if (child || r.hard.contains(at)) visit(r, loaded, seen, post);
        }
        post.add(at);
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
        if (ref.definition == null) {
            ref.definition = ref.load.definition();
            ref.generation = 1;
        }
        return ref.definition;
    }

    private static <T> void removeAt(List<T> list, int i) {
        int last = list.size() - 1;
        list.set(i, list.get(last));
        list.remove(last);
    }
}
