package com.crystalgraphics.asset.registry;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.audio.CgKitSounds;
import com.crystalgraphics.audio.CgSubtitleStyle;
import com.crystalgraphics.i18n.CgKitStrings;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgModuleTypes;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.job.reply.CgPendingReply;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.job.reply.CgReplyError;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.clip.CgClipMarkers;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Every asset by kind and id, with what each refers to: Unreal's {@code IAssetRegistry}. A dev run fills it from its
 * source folders; a builder's assets register themselves. A dedicated server builds the same registry, so both sides
 * agree on every id before the first connection.
 *
 * <p>An id is unique within its kind, not across kinds: a reference's field declares the kind, so a cue and the
 * sequence it plays may share {@code mymod:nova_cannon}. A binary's id keeps its folder and extension, so it is
 * unique on its own.</p>
 *
 * <pre>{@code
 * CgAssetRegistry registry = new CgAssetRegistry();
 * registry.cache(gameDir.resolve("crystalgraphics/asset-registry.bin"));     // optional: unchanged files are not read again
 * registry.registerCode("mymod:kamehameha/charge", CgParticleKinds.SYSTEM);   // a builder's, at init
 * for (File root : CgDevResourceRoots.discover(System::getProperty, System::getenv)) registry.scanResources(root);
 *
 * CgAssetEntry e = registry.resolve(CgParticleKinds.SYSTEM, "mymod:old/charge");   // a redirect followed, warned once
 * e.references();                                                             // what its file names, parent first
 * registry.referencers(CgParticleKinds.EMITTER, "mymod:kamehameha/core");     // every entry naming it
 * registry.binary("mymod:textures/fx/noise.png");
 * registry.hash("mymod");                                                     // what a client compares with its server's
 * }</pre>
 *
 * Listening: a scan's {@code ADDED}s come to {@link #onEvents} as one list, then to {@link #onEvent} one by one, then
 * {@code SCANNED}:
 *
 * <pre>{@code
 * registry.onEvents(batch -> browser.addAll(batch));                         // OnAssetsAdded
 * registry.onEvent(e -> { if (e.type() == CgAssetRegistryEvent.Type.SCANNED) browser.refresh(e.namespace()); });
 * }</pre>
 *
 * <ul>
 *   <li>A scan reads every YAML file once, on the workers, for its redirect and references; a cached file whose
 *       modification time and kind are unchanged is not read.</li>
 *   <li>A scan adds all its entries or none: a builder's id and a file's id colliding is an error naming both.</li>
 *   <li>Files that are not the format's (an importer's report, a stray) are kept in {@link #strays()}, never
 *       registered.</li>
 *   <li>Events run on the thread that registers.</li>
 * </ul>
 */
public final class CgAssetRegistry {

    /** A namespace's files that are not assets: its units, content index and tags. */
    private static final Set<String> ROOT_FILES = new HashSet<>(Arrays.asList("units.yaml", "content.yaml", "tags.yaml",
            "carried.json", "ruled.json", "subtitle_style.yaml"));
    /** A namespace's folders that hold no assets: an importer's reports, a game's data kept for gameplay. */
    private static final Set<String> NOT_ASSETS = new HashSet<>(Arrays.asList("import", "roles", "strings", "fonts"));
    /** Binary folders and the extensions they hold. */
    private static final Map<String, List<String>> BINARIES = new HashMap<>();
    private static final String REDIRECT = "redirect";
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static {
        BINARIES.put("models/", Arrays.asList(".glb", ".gltf"));
        BINARIES.put("geo/", Collections.singletonList(".geo.json"));
        BINARIES.put("textures/", Collections.singletonList(".png"));
        BINARIES.put("fields/", Collections.singletonList(".fga"));
        BINARIES.put("sounds/", Collections.singletonList(".ogg"));
    }

    /** A YAML file the listing found, before it is read. */
    private record Listed(String id, CgType kind, Path file, long modified) {
    }

    private final Map<String, CgAssetEntry> entries = new ConcurrentHashMap<>();
    /** Each referenced key to the keys of the entries naming it. */
    private final Map<String, Set<String>> referencers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> contentIndex = new ConcurrentHashMap<>();
    /** Each namespace's {@code units.yaml}; a namespace without one is in the host's units. */
    private final Map<String, CgUnits> units = new ConcurrentHashMap<>();
    private final List<Path> strays = new CopyOnWriteArrayList<>();
    private final List<Consumer<CgAssetRegistryEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<CgAssetRegistryEvent>>> batchListeners = new CopyOnWriteArrayList<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    @Nullable private Path cacheFile;
    /** The cache, read from {@link #cacheFile} on a background worker; a scan waits for it, its caller does not. */
    @Nullable private CompletableFuture<CgAssetRegistryCache> cache;

    // ── Filling it ───────────────────────────────────────────────────────────────────────

    /**
     * Registers every namespace under {@code resources}{@code /assets/}: a dev run's source folder, as
     * {@code CgDevResourceRoots} finds them.
     */
    public void scanResources(File resources) {
        File[] namespaces = new File(resources, "assets").listFiles(File::isDirectory);
        if (namespaces == null) return;
        Arrays.sort(namespaces);
        for (File ns : namespaces) scan(ns.getName(), ns.toPath(), CgAssetSource.DEV);
    }

    /**
     * Registers every asset of {@code namespace} under {@code root}, its {@code assets/<namespace>/} folder, then
     * announces them: Unreal's gatherer. The listing is walked, each YAML file's redirect and references are read on
     * the workers, or taken from the cache, and nothing is added unless every id is free. Its {@code tags.yaml} is
     * declared in {@code CgTagRegistry.global()}.
     *
     * @throws CgAssetException if an id is already registered from another file or by a builder
     */
    public synchronized void scan(String namespace, Path root, CgAssetSource source) {
        CgAssetKinds.register();
        commit(gather(namespace, root, source, cacheLoad(), null));
        saveCache();
    }

    /**
     * {@link #scan} with its reading on a worker: the listing walked and every YAML file read there, then registered and
     * announced on this thread at its job loop's next drain, as Unreal's gatherer hands its results to the game thread's
     * tick. A loading screen keeps drawing meanwhile.
     *
     * <pre>{@code
     * registry.scanAsync("mykit", kitFolder, CgAssetSource.DEV)
     *         .then(() -> assets.load(refs, CgLoadPriority.MOVE))       // its ids are registered now
     *         .onError(e -> LOGGER.warn("mykit: {}", e.detail()));      // a collision or an unreadable file
     * }</pre>
     *
     * <ul>
     *   <li>Start it on a thread that drains its job loop (Minecraft's client thread, the harness's frame).</li>
     *   <li>Nothing of the namespace is registered until it settles: declare refs to it in {@code then}.</li>
     * </ul>
     */
    public CgReply<Void> scanAsync(String namespace, Path root, CgAssetSource source) {
        CgAssetKinds.register();
        CompletableFuture<CgAssetRegistryCache> records;
        synchronized (this) {
            records = cacheLoad();
        }
        CgPendingReply<Void> scanned = new CgPendingReply<>(null);
        // The loading screen's record names the kit scanning, and its progress is the walk's and the reads'
        CgLoadTask task = CgLoadPlan.get().track(namespace + ":scan", scanned);
        CgJob.run(() -> gather(namespace, root, source, records, task)).named("asset scan " + namespace)
                .then(gathered -> {
                    try {
                        synchronized (this) {
                            commit(gathered);
                        }
                        saveCacheLater();
                        scanned.resolve(null);
                    } catch (RuntimeException e) {
                        scanned.fail(CgReplyError.failed(e));
                    }
                })
                .onError(scanned::fail);
        return scanned;
    }

    /** What a scan read of one namespace: every entry, its content index and units, nothing registered yet. */
    private record Gathered(String namespace, Path root, List<CgAssetEntry> found, String[] keys,
                            Map<String, List<String>> links, @Nullable Map<String, String> index, @Nullable CgUnits units) {
    }

    /** The listing walked and each YAML file read, on the workers: touches nothing of the registry's but strays. */
    private Gathered gather(String namespace, Path root, CgAssetSource source, CompletableFuture<CgAssetRegistryCache> cache,
                            @Nullable CgLoadTask task) {
        // The walk needs no cache: it runs while the cache file is still being read
        List<Listed> yaml = new ArrayList<>();
        List<CgAssetEntry> found = new ArrayList<>();
        int[] lastTime = {0};   // the files the last scan read under root, once the cache is in; -1 for none
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, SCAN_WALK)) {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path rel = root.relativize(dir);
                    return rel.getNameCount() == 1 && NOT_ASSETS.contains(rel.toString()) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    long modified = attrs.lastModifiedTime().toMillis();
                    CgType kind = rel.endsWith(".yaml") ? kindOf(rel) : null;
                    if (CgClipMarkers.isClipFile(rel)) {
                        // A GeckoLib clip's markers: a sequence per animation, the file left as the library reads it
                        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                        for (String id : CgClipMarkers.ids(namespace, rel, json)) {
                            found.add(new CgAssetEntry(id, CgSequenceKinds.SEQUENCE, source, file, null, null,
                                    Collections.emptyList(), modified, Collections.emptyMap()));
                        }
                    } else if (kind != null) {
                        String id = namespace + ":" + rel.substring(kind.folder().length() + 1, rel.length() - ".yaml".length());
                        yaml.add(new Listed(id, kind, file, modified));
                        if (task != null && (yaml.size() & 255) == 0) walked(task, cache, root, yaml.size(), lastTime);
                    } else if (isBinary(rel)) {
                        found.add(new CgAssetEntry(namespace + ":" + rel, null, source, file, null, null,
                                Collections.emptyList(), modified, Collections.emptyMap()));
                    } else if (!(rel.indexOf('/') < 0 && ROOT_FILES.contains(rel))) {
                        strays.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("scanning " + namespace + " at " + root, e);
        }

        CgAssetRegistryCache records = awaited(cache);
        // Read from the cache, the reads are cheap and the walk was most of it; with nothing cached they are nearly all
        float walkShare = records.countUnder(root) > 0 ? SCAN_WALKED : SCAN_WALKED_COLD;
        records.scanning(root);
        if (task != null) task.progress(walkShare);
        CgAssetEntry[] gathered = new CgAssetEntry[yaml.size()];
        AtomicReference<RuntimeException> failed = new AtomicReference<>();
        AtomicInteger read = new AtomicInteger();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, SCAN_READ)) {
            CgJob.forEach(yaml.size(), i -> {
                try {
                    gathered[i] = gather(yaml.get(i), source, records);
                } catch (RuntimeException e) {
                    failed.compareAndSet(null, e);
                }
                int n = read.incrementAndGet();
                if (task != null && (n & 255) == 0) task.progress(walkShare + (1f - walkShare) * n / yaml.size());
            });
        }
        if (failed.get() != null) throw failed.get();
        Collections.addAll(found, gathered);
        found.sort((a, b) -> a.id().compareTo(b.id()));
        for (int i = 1; i < found.size(); i++) {
            CgAssetEntry a = found.get(i - 1), b = found.get(i);
            if (a.kind() == b.kind() && a.id().equals(b.id())) {
                throw new CgAssetException(key(b.kind(), b.id()) + " is registered twice: " + where(a) + " and " + where(b));
            }
        }
        // Each entry's key and the reverse links its references add, built here so the commit only merges them
        String[] keys = new String[found.size()];
        Map<String, List<String>> links = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            CgAssetEntry e = found.get(i);
            keys[i] = key(e.kind(), e.id());
            for (CgAssetReferences.Reference r : e.references()) {
                links.computeIfAbsent(key(r.kind(), r.id()), k -> new ArrayList<>()).add(keys[i]);
            }
        }
        // The kit's tables, read here as each registry is safe on any thread: before any cue or sequence compiles, a
        // cue's tag must be declared, a game's cue name resolves through its sound table, a subtitle through its strings
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, SCAN_TABLES)) {
            CgTagRegistry.global().registerKit(namespace, root);
            CgKitSounds.registerKit(root);
            CgKitStrings.registerKit(namespace, root);
            CgSubtitleStyle.registerKit(namespace, root);
        }
        Path index = root.resolve("content.yaml"), unitsFile = root.resolve("units.yaml");
        return new Gathered(namespace, root, found, keys, links, Files.isRegularFile(index) ? readIndex(index) : null,
                Files.isRegularFile(unitsFile) ? readUnits(unitsFile) : null);
    }

    /** The share of a scan its walk takes: 1.2 s of Kakarot's 1.7. */
    private static final float SCAN_WALKED = 0.6f;
    /** The same with nothing cached under the root: every file is read, and Kakarot's scan grows from 1.7 s to 13.8. */
    private static final float SCAN_WALKED_COLD = 0.1f;

    /** The walk's progress: YAML files listed against those the last scan read under {@code root}, once the cache is in. */
    private static void walked(CgLoadTask task, CompletableFuture<CgAssetRegistryCache> cache, Path root, int listed,
                               int[] lastTime) {
        if (lastTime[0] == 0) {
            CgAssetRegistryCache c = cache.getNow(null);
            if (c == null) return;
            int n = c.countUnder(root);
            lastTime[0] = n > 0 ? n : -1;
        }
        if (lastTime[0] > 0) task.progress(SCAN_WALKED * Math.min(1f, listed / (float) lastTime[0]));
    }

    private static final int SCAN_WALK = CgTrace.name("registry.scan.walk"), SCAN_READ = CgTrace.name("registry.scan.read"),
            SCAN_TABLES = CgTrace.name("registry.scan.tables");
    private static final int COMMIT = CgTrace.name("registry.commit"), COMMIT_ADD = CgTrace.name("registry.commit.add"), COMMIT_BROADCAST = CgTrace.name("registry.commit.broadcast");

    /** A gathered namespace registered and announced: all of it, or nothing where an id is taken. Registry's lock held. */
    private void commit(Gathered g) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, COMMIT)) {
            String namespace = g.namespace();
            Path root = g.root();
            List<CgAssetEntry> found = g.found();
            String[] keys = g.keys();
            for (int i = 0; i < keys.length; i++) {
                CgAssetEntry was = entries.get(keys[i]);
                if (was != null) throw new CgAssetException(keys[i] + " is registered twice: " + where(was) + " and " + where(found.get(i)));
            }
            if (g.index() != null) contentIndex.put(namespace, g.index());
            if (g.units() != null) units.put(namespace, g.units());
            List<CgAssetRegistryEvent> added = new ArrayList<>(found.size());
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, COMMIT_ADD)) {
                for (int i = 0; i < keys.length; i++) {
                    entries.put(keys[i], found.get(i));
                    added.add(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.ADDED, namespace, found.get(i), null));
                }
                for (Map.Entry<String, List<String>> l : g.links().entrySet()) {
                    referencers.computeIfAbsent(l.getKey(), k -> ConcurrentHashMap.newKeySet()).addAll(l.getValue());
                }
            }
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, COMMIT_BROADCAST)) {
                broadcast(added);
                broadcast(Collections.singletonList(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.SCANNED, namespace, null, null)));
            }
        }
    }

    /** The cache written on a background worker: what the commit's frame need not wait for. One write at a time. */
    private void saveCacheLater() {
        if (cacheFile == null) return;
        CgJob.executor(CgJobLane.BACKGROUND).execute(() -> {
            synchronized (CACHE_WRITE) {
                saveCache();
            }
        });
    }

    private static final Object CACHE_WRITE = new Object();

    /**
     * Keeps what a scan reads in {@code file}, so the next run reads only the files that changed: Unreal's
     * {@code CachedAssetRegistry.bin}. Set it before the first scan; without one, the cache lasts as long as the
     * registry. Returns at once: the file is read on a background worker, and the first scan waits for it.
     */
    public synchronized void cache(Path file) {
        cacheFile = Objects.requireNonNull(file);
        cache = CompletableFuture.supplyAsync(() -> {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, CACHE_READ)) {
                return CgAssetRegistryCache.load(file);
            }
        }, CgJob.executor(CgJobLane.BACKGROUND));
    }

    private static final int CACHE_READ = CgTrace.name("registry.cacheRead"), CACHE_WAIT = CgTrace.name("registry.cacheWait");

    /**
     * Registers a builder's asset, which has no file.
     *
     * @throws CgAssetException if {@code id} is already registered
     */
    public synchronized CgAssetEntry registerCode(String id, CgType kind) {
        return registerCode(id, kind, null);
    }

    /**
     * Registers a builder's asset whose definition code makes, compiled by calling {@code definition} on a worker:
     * the engine's built-in modules, declared in Java. A null {@code definition} is a builder's tree.
     *
     * @throws CgAssetException if {@code id} is already registered
     */
    public synchronized CgAssetEntry registerCode(String id, CgType kind, @Nullable Supplier<?> definition) {
        CgAssetEntry e = new CgAssetEntry(id, Objects.requireNonNull(kind), CgAssetSource.CODE, null, null, definition,
                Collections.emptyList(), 0, Collections.emptyMap());
        CgAssetEntry was = entries.putIfAbsent(key(kind, id), e);
        if (was != null) throw new CgAssetException(key(kind, id) + " is registered twice: " + where(was) + " and " + where(e));
        fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.ADDED, e.namespace(), e, null));
        return e;
    }

    /** Registers the engine's own assets: its built-in modules, each compiled to its {@code CgModuleTypes.Module}. */
    public synchronized void registerEngine() {
        CgModuleTypes modules = CgModuleTypes.builtIn();
        for (CgModuleTypes.Module m : modules.all()) registerCode(m.id(), CgParticleKinds.MODULE, () -> m);
    }

    /** Drops {@code id} of {@code kind} (null: a binary), its file deleted; nothing if it is not registered. */
    public synchronized void remove(@Nullable CgType kind, String id) {
        CgAssetEntry e = entries.remove(key(kind, id));
        if (e == null) return;
        link(e, false);
        fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.REMOVED, e.namespace(), e, null));
    }

    /**
     * Reads {@code id}'s file again, for its redirect and references, and announces the new entry; nothing if it is
     * not registered. A builder's asset or a binary is announced as it is.
     */
    public synchronized void changed(@Nullable CgType kind, String id) {
        CgAssetEntry e = entries.get(key(kind, id));
        if (e == null) return;
        if (kind != null && e.file() != null) {
            long modified;
            try {
                modified = Files.getLastModifiedTime(e.file()).toMillis();
            } catch (IOException missing) {
                throw new UncheckedIOException(missing);
            }
            CgAssetEntry now = gather(new Listed(id, kind, e.file(), modified), e.source(), cache());
            link(e, false);
            entries.put(key(kind, id), now);
            link(now, true);
            e = now;
        }
        fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.CHANGED, e.namespace(), e, null));
    }

    /**
     * Moves {@code from} to {@code to}, at {@code file}.
     *
     * @throws CgAssetException if {@code from} is not registered or {@code to} is
     */
    public synchronized CgAssetEntry rename(@Nullable CgType kind, String from, String to, @Nullable Path file) {
        CgAssetEntry old = entries.get(key(kind, from));
        if (old == null) throw new CgAssetException("rename: " + from + " is not registered");
        if (entries.containsKey(key(kind, to))) throw new CgAssetException("rename: " + to + " is already registered");
        CgAssetEntry moved = new CgAssetEntry(to, old.kind(), old.source(), file, null, old.definition(), old.references(),
                old.modified(), old.searchable());
        entries.remove(key(kind, from));
        link(old, false);
        entries.put(key(kind, to), moved);
        link(moved, true);
        fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.RENAMED, moved.namespace(), moved, from));
        return moved;
    }

    // ── Reading it ───────────────────────────────────────────────────────────────────────

    /** The {@code kind} asset {@code id} names, or null; a redirect's own entry, not followed. */
    @Nullable
    public CgAssetEntry entry(CgType kind, String id) {
        return entries.get(key(Objects.requireNonNull(kind), id));
    }

    /** The binary {@code id} names ({@code mymod:textures/fx/noise.png}), or null. */
    @Nullable
    public CgAssetEntry binary(String id) {
        return entries.get(key(null, id));
    }

    /**
     * The {@code kind} asset {@code id} names, redirects followed.
     *
     * @throws CgAssetException if it is not registered, or its redirects end nowhere or loop
     */
    public CgAssetEntry resolve(CgType kind, String id) {
        CgAssetEntry e = entries.get(key(kind, id));
        for (int hops = 0; e != null && e.redirect() != null; hops++) {
            if (hops == 16) throw new CgAssetException(id + ": its redirects loop");
            if (warned.add(key(kind, e.id()))) LOGGER.warn("{} has moved to {}: update what refers to it", e.id(), e.redirect());
            e = entries.get(key(kind, e.redirect()));
        }
        if (e == null) {
            throw new CgAssetException(kind.id() + " " + id + (entries.containsKey(key(kind, id))
                    ? " redirects to an id that is not registered" : " is not registered"));
        }
        return e;
    }

    /**
     * Every entry whose file names the {@code kind} asset {@code id}, hard or soft, by id: Unreal's
     * {@code GetReferencers}. The asset need not be registered; a redirect's referencers are its own, not followed.
     */
    public List<CgAssetEntry> referencers(CgType kind, String id) {
        Set<String> keys = referencers.get(key(Objects.requireNonNull(kind), id));
        List<CgAssetEntry> out = new ArrayList<>();
        if (keys != null) {
            for (String k : keys) {
                CgAssetEntry e = entries.get(k);
                if (e != null) out.add(e);
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /** Every entry of {@code namespace}, by id. */
    public List<CgAssetEntry> entries(String namespace) {
        List<CgAssetEntry> out = new ArrayList<>();
        for (CgAssetEntry e : entries.values()) if (e.namespace().equals(namespace)) out.add(e);
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /** Every entry of {@code kind} in every namespace, by id: one pass, sorting only those. */
    public List<CgAssetEntry> entries(CgType kind) {
        List<CgAssetEntry> out = new ArrayList<>();
        for (CgAssetEntry e : entries.values()) if (e.kind() == kind) out.add(e);
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /** How many entries it holds. */
    public int size() {
        return entries.size();
    }

    /** Every namespace with an entry, sorted. */
    public List<String> namespaces() {
        Set<String> out = new HashSet<>();
        for (CgAssetEntry e : entries.values()) out.add(e.namespace());
        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * The binary a namespace's content index holds under {@code hash} (SHA-1's first 16 hex digits), or null: a copy
     * a game made under another name resolves to the first file holding it.
     */
    @Nullable
    public String binaryByHash(String namespace, String hash) {
        Map<String, String> index = contentIndex.get(namespace);
        return index == null ? null : index.get(hash);
    }

    /** The units a namespace's assets are written in ({@code core.md} § Units): its {@code units.yaml}, else the host's. */
    public CgUnits units(String namespace) {
        return units.getOrDefault(namespace, CgUnits.HOST);
    }

    /** Files a scan found that are not the format's, in the order found. */
    public List<Path> strays() {
        return Collections.unmodifiableList(strays);
    }

    /**
     * A namespace's registry hash: its ids, kinds and redirects, which a client compares with its server's at login.
     * It sees an asset added, removed, moved or retyped; a changed file's bytes are the cook's hash.
     */
    public String hash(String namespace) {
        Map<String, String> lines = new TreeMap<>();
        for (CgAssetEntry e : entries(namespace)) {
            lines.put(key(e.kind(), e.id()), e.redirect() == null ? "" : "-> " + e.redirect());
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (Map.Entry<String, String> l : lines.entrySet()) {
                sha.update((l.getKey() + '\t' + l.getValue() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is missing from this JVM", e);
        }
    }

    /** Hears every event one at a time, on the registering thread; run the answer to stop. */
    public Runnable onEvent(Consumer<CgAssetRegistryEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Hears events in batches, before {@link #onEvent}'s listeners hear them one by one: a scan's {@code ADDED}s as
     * one list, anything else as a list of one. Unreal's {@code OnAssetsAdded}. Run the answer to stop.
     */
    public Runnable onEvents(Consumer<List<CgAssetRegistryEvent>> listener) {
        batchListeners.add(listener);
        return () -> batchListeners.remove(listener);
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────

    /** A {@code kind}'s {@code id}, or a binary's: ids are unique within a kind. */
    private static String key(@Nullable CgType kind, String id) {
        return (kind == null ? "binary" : kind.id()) + '|' + id;
    }

    private static String where(CgAssetEntry e) {
        return e.file() != null ? e.file().toString() : "a builder (" + e.source() + ")";
    }

    private void link(CgAssetEntry e, boolean add) {
        String from = key(e.kind(), e.id());
        for (CgAssetReferences.Reference r : e.references()) {
            String to = key(r.kind(), r.id());
            if (add) {
                referencers.computeIfAbsent(to, k -> ConcurrentHashMap.newKeySet()).add(from);
            } else {
                Set<String> s = referencers.get(to);
                if (s != null) s.remove(from);
            }
        }
    }

    private void fire(CgAssetRegistryEvent event) {
        broadcast(Collections.singletonList(event));
    }

    /** Unreal's broadcast order: the batch, then each event alone. */
    private void broadcast(List<CgAssetRegistryEvent> events) {
        if (events.isEmpty()) return;
        List<CgAssetRegistryEvent> batch = Collections.unmodifiableList(events);
        for (Consumer<List<CgAssetRegistryEvent>> l : batchListeners) l.accept(batch);
        for (CgAssetRegistryEvent e : events) {
            for (Consumer<CgAssetRegistryEvent> l : listeners) l.accept(e);
        }
    }

    private CgAssetRegistryCache cache() {
        return awaited(cacheLoad());
    }

    private static CgAssetRegistryCache awaited(CompletableFuture<CgAssetRegistryCache> load) {
        if (load.isDone()) return load.join();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, CACHE_WAIT)) {
            return load.join();
        }
    }

    private CompletableFuture<CgAssetRegistryCache> cacheLoad() {
        if (cache == null) cache = CompletableFuture.completedFuture(CgAssetRegistryCache.load(null));
        return cache;
    }

    private void saveCache() {
        if (cacheFile == null) return;
        try {
            cache().save(cacheFile);
        } catch (IOException e) {
            LOGGER.warn("The asset registry's cache could not be written to {}: {}", cacheFile, e.toString());
        }
    }

    /** Whether {@code rel} is a binary: a file of a binary folder with that folder's extension. */
    private static boolean isBinary(String rel) {
        for (Map.Entry<String, List<String>> b : BINARIES.entrySet()) {
            if (!rel.startsWith(b.getKey())) continue;
            for (String ext : b.getValue()) if (rel.endsWith(ext)) return true;
        }
        return false;
    }

    /** The kind whose folder starts {@code rel}; the longest when two do. */
    @Nullable
    private static CgType kindOf(String rel) {
        CgType best = null;
        for (CgType kind : CgAssetKinds.all()) {
            if (rel.startsWith(kind.folder() + "/") && (best == null || kind.folder().length() > best.folder().length())) {
                best = kind;
            }
        }
        return best;
    }

    /** A YAML file's entry: its redirect and references from the cache while its file is unchanged, else read. */
    private static CgAssetEntry gather(Listed f, CgAssetSource source, CgAssetRegistryCache records) {
        String key = f.file().toAbsolutePath().normalize().toString();   // one key a file, however its root was spelled
        CgAssetRegistryCache.Record r = records.find(key, f.modified(), f.kind());
        if (r == null) {
            r = read(f);
            if (r != null) {
                records.put(key, r);
            } else {
                // Uncached, so every scan names it until it is fixed.
                r = new CgAssetRegistryCache.Record(f.modified(), f.kind().id(), null, Collections.emptyList(), Collections.emptyMap());
            }
        }
        return new CgAssetEntry(f.id(), f.kind(), source, f.file(), r.redirect(), null, r.references(), f.modified(),
                r.searchable());
    }

    /**
     * What {@code f} says of itself: a redirect, hard on where it points as Unreal's redirector is, or its references;
     * null, logged by file, when it does not parse.
     */
    private static CgAssetRegistryCache.Record read(Listed f) {
        String text;
        try {
            text = new String(Files.readAllBytes(f.file()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Object tree = CgAssetYaml.read(text);
            if (tree instanceof Map) {
                Map<?, ?> file = (Map<?, ?>) tree;
                Object to = file.get(REDIRECT);
                if (to instanceof String) {
                    return new CgAssetRegistryCache.Record(f.modified(), f.kind().id(), (String) to, Collections.singletonList(
                            new CgAssetReferences.Reference(f.kind(), (String) to, CgValueType.Strength.HARD,
                                    CgPropertyPath.of(f.id()).key(REDIRECT))), Collections.emptyMap());
                }
                return new CgAssetRegistryCache.Record(f.modified(), f.kind().id(), null,
                        Collections.unmodifiableList(CgAssetReferences.ofFile(f.id(), f.kind(), file)), searchable(f.kind(), file));
            }
            // A cue is found by the tag read here, so one that never parses is never loaded to fail by name.
            LOGGER.error("{} {} ({}) is not a YAML map, so nothing finds it by what it declares",
                    f.kind().id(), f.id(), f.file());
        } catch (RuntimeException unreadable) {
            LOGGER.error("{} {} ({}) does not parse, so nothing finds it by what it declares: {}",
                    f.kind().id(), f.id(), f.file(), unreadable.getMessage());
        }
        return null;
    }

    /** {@code kind}'s {@code SEARCHABLE} properties as {@code file} writes them: Unreal's asset registry tags. */
    private static Map<String, String> searchable(CgType kind, Map<?, ?> file) {
        Map<String, String> out = null;
        for (CgProperty p : kind.properties()) {
            if (!p.has(CgPropertyFlag.SEARCHABLE)) continue;
            Object v = file.get(p.name());
            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                if (out == null) out = new LinkedHashMap<>();
                out.put(p.name(), String.valueOf(v));
            }
        }
        return out == null ? Collections.emptyMap() : Collections.unmodifiableMap(out);
    }

    private static CgUnits readUnits(Path file) {
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return CgUnits.of(tree instanceof Map<?, ?> m ? m.get("units") : null, CgUnits.HOST);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(file + ": " + e.getMessage());
        }
    }

    private static Map<String, String> readIndex(Path index) {
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(index), StandardCharsets.UTF_8));
            Map<String, String> out = new HashMap<>();
            if (tree instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) tree).entrySet()) {
                    if (e.getValue() instanceof String) out.put(String.valueOf(e.getKey()), (String) e.getValue());
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
