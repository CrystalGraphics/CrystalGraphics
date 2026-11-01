package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetVersions;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.clip.CgClipMarkers;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.object.CgTypes;

/**
 * Loads assets to {@link CgAssetState#COMPILED} on workers: read, merge over the parent, validate, then compile once
 * every hard dependency has. Each step's result is published on its loop, drained at the frame boundary, so the frame
 * thread only flips states. Unreal's {@code FStreamableManager} for definitions.
 *
 * <pre>{@code
 * CgAssetPipeline pipeline = new CgAssetPipeline(registry);
 * CgAssetLoad charge = pipeline.request(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
 *
 * // each frame, at its boundary:
 * pipeline.drain();
 * if (charge.state() == CgAssetState.COMPILED) play(charge.definition());
 * }</pre>
 *
 * <ul>
 *   <li>Request and drain on one thread, the loop's: a load's state changes only there.</li>
 *   <li>A request is idempotent: the same kind and id answer the same load.</li>
 *   <li>A parent resolves before its child; a hard dependency compiles before its referrer, and failing fails it,
 *       naming the dependency. A cycle of hard dependencies fails every asset in it.</li>
 *   <li>Nothing is ever released: a failed load stays failed until its files change and it is requested again.</li>
 * </ul>
 */
public final class CgAssetPipeline {

    private final CgAssetRegistry registry;
    private final CgJobLoop loop;
    private final Map<String, CgAssetLoad> loads = new LinkedHashMap<>();
    private final AtomicInteger offWorker = new AtomicInteger();
    private int running;

    /** A pipeline on a loop of its own, {@code assets}. */
    public CgAssetPipeline(CgAssetRegistry registry) {
        this(registry, CgJobLoop.create("assets"));
    }

    /** A pipeline reporting to {@code loop}. */
    public CgAssetPipeline(CgAssetRegistry registry, CgJobLoop loop) {
        this.registry = registry;
        this.loop = loop;
    }

    /** {@code kind}'s {@code id}, loading toward {@link CgAssetState#COMPILED}. */
    public CgAssetLoad request(CgType kind, String id) {
        endInit();
        String key = key(kind, id);
        CgAssetLoad load = loads.get(key);
        if (load != null) return load;
        load = new CgAssetLoad(kind, id);
        loads.put(key, load);
        CgAssetEntry entry;
        try {
            entry = registry.resolve(kind, id);
        } catch (CgAssetException e) {
            fail(load, e.getMessage());
            return load;
        }
        if (entry.definition() != null) {
            CgAssetLoad made = load;
            start(load, () -> entry.definition().get(), definition -> {
                made.definition = definition;
                advance(made, CgAssetState.COMPILED);
            }, "its builder threw");
            return load;
        }
        if (entry.file() == null) {
            fail(load, "a builder's asset has no tree to load");
            return load;
        }
        CgAssetLoad reading = load;
        start(load, () -> {
            String text = new String(Files.readAllBytes(entry.file()), StandardCharsets.UTF_8);
            if (kind == CgSequenceKinds.SEQUENCE && entry.file().toString().endsWith(CgClipMarkers.SUFFIX)) {
                // A GeckoLib clip's notify sequence, translated from the clip's own file
                return CgClipMarkers.translate(id, entry.file().toString(), text, new CgClipMarkers.Known() {
                    @Override
                    public boolean sequence(String ref) {
                        return registry.entry(CgSequenceKinds.SEQUENCE, ref) != null;
                    }

                    @Override
                    public boolean system(String ref) {
                        return registry.entry(CgParticleKinds.SYSTEM, ref) != null;
                    }

                    @Override
                    public double length(String ref) {
                        return sequenceLength(ref, 0);
                    }
                });
            }
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(text);
            return CgAssetVersions.upgrade(tree, kind);
        }, tree -> read(reading, (Map<?, ?>) tree), "unreadable");
        return load;
    }

    /** The first request ends the engine's init: every native kind declared, then the type registry frozen. */
    private static void endInit() {
        if (CgTypes.global().isFrozen()) return;
        CgAssetKinds.register();
        CgTypes.freeze();
    }

    /** The load of {@code kind}'s {@code id}, or null if none was requested. */
    @Nullable
    public CgAssetLoad load(CgType kind, String id) {
        return loads.get(key(kind, id));
    }

    /** Every load requested, in request order. */
    public Collection<CgAssetLoad> loads() {
        return Collections.unmodifiableCollection(loads.values());
    }

    /**
     * Publishes what the workers finished: the frame boundary's step. Once nothing is running, any load still
     * waiting waits on a cycle, and fails.
     */
    public void drain() {
        loop.drain();
        if (running == 0 && anyWaiting()) {
            // copied, as failing one changes the map; only when one waits, since drain runs every frame
            for (CgAssetLoad l : new ArrayList<>(loads.values())) {
                if (!l.state.atLeast(CgAssetState.COMPILED) && l.state != CgAssetState.FAILED) {
                    fail(l, "waits on a cycle of hard dependencies");
                }
            }
        }
    }

    private boolean anyWaiting() {
        for (CgAssetLoad l : loads.values()) {
            if (!l.state.atLeast(CgAssetState.COMPILED) && l.state != CgAssetState.FAILED) return true;
        }
        return false;
    }

    /** Whether no step is running and nothing waits to be published. */
    public boolean idle() {
        return running == 0 && loop.pendingCount() == 0;
    }

    /** How many steps ran off the job pool's workers: 0, or the frame thread did an asset's work. */
    public int stepsOffWorkers() {
        return offWorker.get();
    }

    // ── The steps ────────────────────────────────────────────────────────────────────────

    /** Its file's tree read: waits on its parent's resolution, if it names one. */
    private void read(CgAssetLoad load, Map<?, ?> tree) {
        load.tree = tree;
        advance(load, CgAssetState.TREE);
        Object parent = tree.get(CgType.PARENT);
        if (!(parent instanceof String)) {
            resolve(load, null);
            return;
        }
        CgAssetLoad p = request(load.kind(), (String) parent);
        when(p, CgAssetState.RESOLVED, () -> resolve(load, p.asset),
                () -> fail(load, "its parent " + parent + " failed: " + p.failure));
    }

    private static final class Resolved {
        final CgAssetData asset;
        final CgValidation validation;
        final List<CgAssetReferences.Reference> hard = new ArrayList<>();

        Resolved(CgAssetData asset, CgValidation validation) {
            this.asset = asset;
            this.validation = validation;
        }
    }

    /** Merged over {@code parent} and validated, then compiled once its hard dependencies are. */
    private void resolve(CgAssetLoad load, @Nullable CgAssetData parent) {
        Map<?, ?> tree = load.tree;
        start(load, () -> {
            CgAssetData asset = CgAssetData.of(load.id(), load.kind(), tree, parent);
            Resolved r = new Resolved(asset, asset.validate(CgValidation.Usecase.LOAD));
            if (r.validation.valid()) {
                for (CgAssetReferences.Reference ref : CgAssetReferences.of(asset)) {
                    if (ref.strength() == CgValueType.Strength.HARD) r.hard.add(ref);
                }
                if (load.kind() == CgSequenceKinds.SEQUENCE) clipNotifies(asset, r.hard);
            }
            return r;
        }, done -> {
            Resolved r = (Resolved) done;
            if (!r.validation.valid()) {
                load.issues = r.validation.issues();
                fail(load, r.validation.errors() + " validation errors, first " + r.validation.issues().get(0));
                return;
            }
            load.asset = r.asset;
            load.tree = null;
            advance(load, CgAssetState.RESOLVED);
            dependencies(load, r.hard);
        }, "does not resolve");
    }

    /**
     * A sequence's {@code animation} sections playing a clip with no {@code notifies} of their own take the clip's
     * notify sequence where one is registered (a GeckoLib clip's), so it compiles first, as a hard dependency.
     */
    private void clipNotifies(CgAssetData asset, List<CgAssetReferences.Reference> hard) {
        if (!(asset.resolved().get("tracks") instanceof List<?> tracks)) return;
        for (Object t : tracks) {
            if (!(t instanceof Map<?, ?> track) || !"animation".equals(track.get("kind"))) continue;
            if (!(track.get("sections") instanceof List<?> sections)) continue;
            for (Object s : sections) {
                if (!(s instanceof Map<?, ?> section) || section.get("notifies") != null) continue;
                if (!(section.get("clip") instanceof String clip)) continue;
                CgAssetEntry e = registry.entry(CgSequenceKinds.SEQUENCE, clip);
                if (e == null || e.file() == null || !e.file().toString().endsWith(CgClipMarkers.SUFFIX)) continue;
                hard.add(new CgAssetReferences.Reference(CgSequenceKinds.SEQUENCE, clip, CgValueType.Strength.HARD,
                        CgPropertyPath.of(asset.id())));
            }
        }
    }

    /** Sequence {@code id}'s playback range in seconds from its file, its parents' where it has none; NaN for none. */
    private double sequenceLength(String id, int depth) {
        CgAssetEntry e = registry.entry(CgSequenceKinds.SEQUENCE, id);
        if (e == null || e.file() == null || depth > 8) return Double.NaN;
        try {
            String text = new String(Files.readAllBytes(e.file()), StandardCharsets.UTF_8);
            if (e.file().toString().endsWith(CgClipMarkers.SUFFIX)) return CgClipMarkers.length(id, text);
            if (!(CgAssetYaml.read(text) instanceof Map<?, ?> tree)) return Double.NaN;
            if (tree.get("range") instanceof List<?> range && range.size() == 2 && range.get(0) instanceof Number from
                    && range.get(1) instanceof Number to) {
                double ticks = tree.get("tick_resolution") instanceof Number t ? t.doubleValue() : Double.NaN;
                return (to.doubleValue() - from.doubleValue()) / ticks;
            }
            return tree.get(CgType.PARENT) instanceof String parent ? sequenceLength(parent, depth + 1) : Double.NaN;
        } catch (IOException ex) {
            return Double.NaN;
        }
    }

    /**
     * A clip's notify sequence whose particle key names a system that plays for ever fails, naming the file, the
     * animation, the key's time and the system: Cascade's particle notify suppresses one, which nothing would end.
     */
    private void loopingKeys(CgAssetLoad load, CgAssetCompiler.Dependencies deps) {
        CgAssetEntry e = registry.entry(load.kind(), load.id());
        if (e == null || e.file() == null || !e.file().toString().endsWith(CgClipMarkers.SUFFIX)) return;
        if (!(load.asset.resolved().get("spawnables") instanceof List<?> spawnables)) return;
        String animation = load.id().substring(load.id().lastIndexOf('/') + 1);
        for (Object o : spawnables) {
            if (!(o instanceof Map<?, ?> s) || !(s.get("system") instanceof String system)) continue;
            if (deps.definition(CgParticleKinds.SYSTEM, system) instanceof CgVfxSystemDefinition d && d.loops()) {
                throw new CgAssetException(e.file() + ": animation " + animation + " at " + s.get("name") + ": " + system
                        + " loops for ever, which a key never ends: use begin= ... end=");
            }
        }
    }

    private void dependencies(CgAssetLoad load, List<CgAssetReferences.Reference> hard) {
        Map<String, CgAssetLoad> deps = new LinkedHashMap<>();
        for (CgAssetReferences.Reference ref : hard) {
            if (ref.id().startsWith("unreal:")) continue;   // the host's alias table, not a file
            String key = key(ref.kind(), ref.id());
            if (!key.equals(key(load.kind(), load.id()))) deps.put(key, request(ref.kind(), ref.id()));
        }
        int[] left = {deps.size()};
        if (left[0] == 0) {
            compile(load, deps);
            return;
        }
        for (CgAssetLoad dep : deps.values()) {
            when(dep, CgAssetState.COMPILED, () -> {
                if (--left[0] == 0 && load.state == CgAssetState.RESOLVED) compile(load, deps);
            }, () -> fail(load, "its hard dependency " + dep.kind().id() + " " + dep.id() + " failed: " + dep.failure));
        }
    }

    private void compile(CgAssetLoad load, Map<String, CgAssetLoad> deps) {
        Map<String, Object> definitions = new HashMap<>();
        for (Map.Entry<String, CgAssetLoad> d : deps.entrySet()) definitions.put(d.getKey(), d.getValue().definition);
        CgAssetData asset = load.asset;
        CgAssetCompiler<?> compiler = CgAssetCompiler.of(load.kind());
        String namespace = load.id().substring(0, Math.max(0, load.id().indexOf(':')));
        CgUnits units = CgUnits.of(asset.resolved().get(CgType.UNITS), registry.units(namespace));
        CgAssetCompiler.Dependencies answers = new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String id) {
                return definitions.get(key(kind, id));
            }

            @Override
            public CgUnits units() {
                return units;
            }
        };
        start(load, () -> {
            if (load.kind() == CgSequenceKinds.SEQUENCE) loopingKeys(load, answers);
            return compiler.compile(asset, answers);
        }, definition -> {
            load.definition = definition;
            advance(load, CgAssetState.COMPILED);
        }, "does not compile");
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────────────────

    /** Work that may throw. */
    @FunctionalInterface
    private interface Step {
        Object run() throws Exception;
    }

    /** Runs {@code step} on a worker, then {@code then} on the loop; a throw fails {@code load} instead. */
    private void start(CgAssetLoad load, Step step, Consumer<Object> then, String failing) {
        running++;
        Supplier<Object> work = () -> {
            if (!CgJob.isWorkerThread()) offWorker.incrementAndGet();
            try {
                return step.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CgAssetException(e.toString());
            }
        };
        CgJob.on(loop).run(work).then(result -> {
            running--;
            then.accept(result);
        }).failed(t -> {
            running--;
            fail(load, failing + ": " + t);
        });
    }

    /** Runs {@code reached} once {@code load} reaches {@code state}, or {@code failed} once it fails; now if it has. */
    private static void when(CgAssetLoad load, CgAssetState state, Runnable reached, Runnable failed) {
        Runnable check = new Runnable() {
            boolean done;

            @Override
            public void run() {
                if (done) return;
                if (load.state.atLeast(state)) {
                    done = true;
                    reached.run();
                } else if (load.state == CgAssetState.FAILED) {
                    done = true;
                    failed.run();
                }
            }
        };
        check.run();
        if (!load.state.atLeast(state) && load.state != CgAssetState.FAILED) load.waiters.add(check);
    }

    private static void advance(CgAssetLoad load, CgAssetState state) {
        load.state = state;
        for (Runnable w : new ArrayList<>(load.waiters)) w.run();
    }

    private static void fail(CgAssetLoad load, String why) {
        if (load.state == CgAssetState.FAILED) return;
        load.failure = why;
        load.tree = null;
        advance(load, CgAssetState.FAILED);
        load.waiters.clear();
    }

    private static String key(CgType kind, String id) {
        return kind.id() + '|' + id;
    }
}
