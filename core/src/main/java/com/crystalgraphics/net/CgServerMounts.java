package com.crystalgraphics.net;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.net.object.CgNetTypeCompiler;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.util.io.CgIO;
import java.util.TreeMap;
import java.util.function.Supplier;
import com.crystalgraphics.net.object.CgNetType;

/**
 * A server's files on a client that lacks its mod: the server offers a namespace, and each client without it fetches
 * the files it may take, by content hash, caches them, and mounts them for that connection alone. Minecraft's server
 * resource pack, for a mod's own files.
 *
 * <pre>{@code
 * // the server mod, at init
 * CgServerMounts.offer("shop").floor("cgui.format", 3);
 *
 * // a consumer that reads a mounted kind, on the client, at init
 * CgServerMounts.allowKind("page", path -> path.endsWith(".cgui"));
 * CgServerMounts.supports("cgui.format", 4);
 * CgServerMounts.onMounted((namespace, folder) -> Pages.reload(namespace));
 * }</pre>
 *
 * <ul>
 *   <li>The hello lists what a server offers; a client asks only for a namespace it does not have itself (a mod
 *       loaded, a namespace mounted, a message namespace declared). Nothing is sent to a client that has it.</li>
 *   <li>A body is stored under the hash the client works out for it, never the one the server named: one that does
 *       not hash to its name is dropped and counted ({@link #mismatched()}).</li>
 *   <li>Only allowed kinds are taken: images, language files and {@code net_type} files here, and what a consumer
 *       allows. Anything else is refused by name ({@link #refusedKinds()}).</li>
 *   <li>The cache keeps bodies under a total cap ({@link #cacheCap}, 256 MB), least recently used first out.</li>
 *   <li>A floor a namespace names refuses a client below it with both versions named; the client is sent no body.</li>
 *   <li>Language files: the player's language and {@code en_us} alone.</li>
 *   <li>A namespace offered from a source folder (a dev run) is checked once a second, and a change reloads it on
 *       every client that mounted it.</li>
 * </ul>
 */
public final class CgServerMounts {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    // ── Kinds ───────────────────────────────────────────────────────────────────────────────────

    static final Map<String, Predicate<String>> KINDS = new ConcurrentHashMap<>();

    static {
        KINDS.put("image", path -> path.endsWith(".png"));
        KINDS.put("lang", path -> path.startsWith("lang/") && path.endsWith(".json"));
        KINDS.put("net_type", path -> path.startsWith("net_types/") && path.endsWith(".yaml"));
    }

    /** Lets a server's files {@code kind} be mounted: those {@code path} matches, within the namespace. Both sides. */
    public static void allowKind(String kind, Predicate<String> path) {
        KINDS.put(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(path, "path"));
    }

    /** The allowed kind {@code path} is, or null for none. */
    @Nullable
    static String kindOf(String path) {
        for (Map.Entry<String, Predicate<String>> e : KINDS.entrySet()) if (e.getValue().test(path)) return e.getKey();
        return null;
    }

    // ── What a client supports, and what a server needs ─────────────────────────────────────────

    private static final Map<String, Integer> SUPPORTED = new ConcurrentHashMap<>();

    /** Client: that this client reads {@code capability} up to {@code version}, which a server's floor is held to. */
    public static void supports(String capability, int version) {
        SUPPORTED.merge(capability, version, Math::max);
    }

    // ── The server's offers ─────────────────────────────────────────────────────────────────────

    /** One namespace a server offers. */
    public static final class Offer {
        final String namespace;
        @Nullable
        final Path folder;
        final Map<String, Integer> floor = new LinkedHashMap<>();
        /** Files the server writes itself, by path: made again with each manifest. */
        final Map<String, Supplier<byte[]>> generated = new ConcurrentHashMap<>();
        /** The bodies the last manifest made, by hash. */
        volatile Map<String, byte[]> generatedBodies = Map.of();
        @Nullable
        volatile Manifest manifest;

        private Offer(String namespace, @Nullable Path folder) {
            this.namespace = namespace;
            this.folder = folder;
        }

        /** A client must support {@code capability} at {@code version} or later, or is refused, both versions named. */
        public Offer floor(String capability, int version) {
            floor.put(capability, version);
            manifest = null;
            return this;
        }

        /**
         * A file the server writes itself at {@code path} (as under {@code assets/<namespace>/}), made with each manifest:
         * a description of something declared in code. A file of the same path in the folder gives way to it.
         *
         * <pre>{@code
         * CgServerMounts.offer("mybank").generated("application/bank.json", () -> describe(Bank.APP));
         * }</pre>
         */
        public Offer generated(String path, Supplier<byte[]> body) {
            if (kindOf(path) == null) throw new IllegalArgumentException(path + " is no kind a client takes");
            generated.put(path, Objects.requireNonNull(body, "body"));
            manifest = null;
            return this;
        }

        /**
         * Offers {@code type}, declared in Java, as its {@code net_type} file: a client without the mod declares it for
         * its connection and reads, sends and hears all of it. Its name's namespace is this offer's.
         *
         * <pre>{@code
         * CgServerMounts.offer("mybank").type(Bank.ACCOUNT);
         * }</pre>
         */
        public Offer type(CgNetType<?> type) {
            String name = type.name();
            if (!name.startsWith(namespace + ":")) throw new IllegalArgumentException(name + " is not in " + namespace);
            String yaml = CgNetTypeCompiler.describe(type);
            return generated("net_types/" + name.substring(namespace.length() + 1) + ".yaml",
                    () -> yaml.getBytes(StandardCharsets.UTF_8));
        }

        /** Its folder: the one given, else where {@code CgAssetMounts} mounted the namespace. */
        @Nullable
        Path folder() {
            return folder != null ? folder : CgAssetMounts.folder(namespace);
        }

        /** Whether its folder is a source folder (not inside a jar): one a dev run edits. */
        boolean fromSource() {
            Path f = folder();
            return f != null && f.getFileSystem() == FileSystems.getDefault();
        }

        Manifest manifest() {
            Manifest m = manifest;
            if (m == null) manifest = m = Manifest.of(this);
            return m;
        }
    }

    private static final Map<String, Offer> OFFERS = new ConcurrentHashMap<>();

    /** Server: offers {@code namespace}, the files of its mounted folder, to clients without it. */
    public static Offer offer(String namespace) {
        return OFFERS.computeIfAbsent(namespace, ns -> new Offer(ns, null));
    }

    /** Server: offers {@code folder} as {@code namespace}: a folder of files laid out as {@code assets/<namespace>/}. */
    public static Offer offer(String namespace, Path folder) {
        Offer o = new Offer(namespace, folder);
        OFFERS.put(namespace, o);
        return o;
    }

    /** Server: stops offering {@code namespace} to clients that join from now on. */
    public static void withdraw(String namespace) {
        OFFERS.remove(namespace);
    }

    /** What the hello says a server offers: each namespace and its manifest's hash. */
    static Map<String, String> offered() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Offer o : OFFERS.values()) out.put(o.namespace, o.manifest().hash);
        return out;
    }

    // ── The wire ────────────────────────────────────────────────────────────────────────────────

    /** One file of a manifest: its path in the namespace, its kind, the hash of its bytes, its size. */
    record Entry(String path, String kind, String hash, int size) {
        static final CgCodec<Entry> CODEC = CgCodecs.record(
                CgCodecs.field("p", CgCodecs.STRING, Entry::path),
                CgCodecs.field("k", CgCodecs.STRING, Entry::kind),
                CgCodecs.field("h", CgCodecs.STRING, Entry::hash),
                CgCodecs.field("s", CgCodecs.INT, Entry::size),
                Entry::new);
    }

    /** A namespace's files as offered, and their hash as a whole. */
    record Manifest(String hash, List<Entry> entries) {
        static final CgCodec<Manifest> CODEC = CgCodecs.record(
                CgCodecs.field("h", CgCodecs.STRING, Manifest::hash),
                CgCodecs.field("e", CgCodecs.listOf(Entry.CODEC), Manifest::entries),
                Manifest::new);

        static Manifest of(Offer offer) {
            Path folder = offer.folder();
            List<Entry> entries = new ArrayList<>();
            Map<String, byte[]> made = new HashMap<>();
            for (Map.Entry<String, Supplier<byte[]>> g : new TreeMap<>(offer.generated).entrySet()) {
                byte[] bytes = g.getValue().get();
                String hash = sha256(bytes);
                made.put(hash, bytes);
                entries.add(new Entry(g.getKey(), kindOf(g.getKey()), hash, bytes.length));
            }
            offer.generatedBodies = made;
            if (folder != null && Files.isDirectory(folder)) {
                try (Stream<Path> walk = Files.walk(folder)) {
                    for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                        String path = folder.relativize(file).toString().replace('\\', '/');
                        String kind = kindOf(path);
                        if (kind == null || offer.generated.containsKey(path)) continue;
                        byte[] bytes = Files.readAllBytes(file);
                        entries.add(new Entry(path, kind, sha256(bytes), bytes.length));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read the offered " + offer.namespace + " at " + folder, e);
                }
            }
            StringBuilder all = new StringBuilder();
            for (Entry e : entries) all.append(e.path).append('=').append(e.hash).append('\n');
            for (Map.Entry<String, Integer> f : offer.floor.entrySet()) all.append(f.getKey()).append('>').append(f.getValue()).append('\n');
            return new Manifest(sha256(all.toString().getBytes(StandardCharsets.UTF_8)), entries);
        }
    }

    /** A client's ask for a namespace: its languages and what it supports. */
    record Ask(String namespace, List<String> languages, Map<String, Integer> supported) {
        static final CgCodec<Ask> CODEC = CgCodecs.record(
                CgCodecs.field("n", CgCodecs.STRING, Ask::namespace),
                CgCodecs.field("l", CgCodecs.listOf(CgCodecs.STRING), Ask::languages),
                CgCodecs.field("s", CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT), Ask::supported),
                Ask::new);
    }

    /** One body, by its namespace and hash. */
    record Body(String namespace, String hash) {
        static final CgCodec<Body> CODEC = CgCodecs.record(
                CgCodecs.field("n", CgCodecs.STRING, Body::namespace),
                CgCodecs.field("h", CgCodecs.STRING, Body::hash),
                Body::new);
    }

    /** A refusal for a floor, naming the capability and both versions. */
    public static final String REFUSED_FLOOR = "crystalgraphics/mount_floor";

    static final CgRequest<Ask, Manifest> INDEX = CgRequest.toServer("crystalgraphics:mount/index", Ask.CODEC, Manifest.CODEC);
    static final CgRequest<Body, byte[]> BODY = CgRequest.toServer("crystalgraphics:mount/body", Body.CODEC, CgCodecs.BYTES);
    static final CgMessage<String> CHANGED = CgMessage.toClients("crystalgraphics:mount/changed", CgCodecs.STRING);

    static {
        INDEX.onServer((peer, ask, reply) -> {
            Offer offer = OFFERS.get(ask.namespace());
            if (offer == null) {
                reply.fail("crystalgraphics/mount_unknown " + ask.namespace());
                return;
            }
            for (Map.Entry<String, Integer> f : offer.floor.entrySet()) {
                int has = ask.supported().getOrDefault(f.getKey(), -1);
                if (has < f.getValue()) {
                    reply.fail(REFUSED_FLOOR + " " + ask.namespace() + " needs " + f.getKey() + " " + f.getValue()
                            + ", the client has " + (has < 0 ? "none" : String.valueOf(has)));
                    return;
                }
            }
            Manifest whole = offer.manifest();
            List<Entry> sent = new ArrayList<>(whole.entries().size());
            for (Entry e : whole.entries()) {
                if (e.kind().equals("lang") && !wanted(e.path(), ask.languages())) continue;
                sent.add(e);
            }
            reply.ok(new Manifest(whole.hash(), sent));
        });
        BODY.onServer((peer, body, reply) -> {
            Offer offer = OFFERS.get(body.namespace());
            byte[] made = offer == null ? null : offer.generatedBodies.get(body.hash());
            if (made != null) {
                reply.ok(made);
                return;
            }
            Path folder = offer == null ? null : offer.folder();
            if (folder != null) {
                for (Entry e : offer.manifest().entries()) {
                    if (!e.hash().equals(body.hash())) continue;
                    try {
                        reply.ok(Files.readAllBytes(folder.resolve(e.path())));
                    } catch (IOException failed) {
                        reply.fail("crystalgraphics/mount_unreadable " + e.path());
                    }
                    return;
                }
            }
            // only what a manifest lists is ever served
            reply.fail("crystalgraphics/mount_unknown " + body.hash());
        });
        CHANGED.onReceive(namespace -> {
            for (Mounted m : CgServerMounts.MOUNTED.values()) {
                if (m.namespace.equals(namespace) && !m.connection.isClosed()) fetch(m.connection, namespace, true);
            }
        });
    }

    private static boolean wanted(String path, List<String> languages) {
        String code = path.substring("lang/".length(), path.length() - ".json".length());
        return code.equals("en_us") || languages.contains(code);
    }

    /** Server thread, each tick: a source folder's offer looked at once a second; a change sent to every client. */
    static void serverTick(long tick) {
        if (tick % 20 != 0 || OFFERS.isEmpty()) return;
        for (Offer o : OFFERS.values()) {
            if (!o.fromSource()) continue;
            Manifest before = o.manifest;
            Manifest now = Manifest.of(o);
            if (before != null && !before.hash().equals(now.hash())) {
                o.manifest = now;
                CHANGED.send(CgAudience.all(), o.namespace);
            } else if (before == null) {
                o.manifest = now;
            }
        }
    }

    // ── The client ──────────────────────────────────────────────────────────────────────────────

    private static final AtomicLong MISMATCHED = new AtomicLong(), REFUSED_KINDS = new AtomicLong(), FETCHED = new AtomicLong();
    private static final List<BiConsumer<String, Path>> ON_MOUNTED = new CopyOnWriteArrayList<>();
    private static final List<Consumer<String>> ON_UNMOUNTED = new CopyOnWriteArrayList<>();
    private static final List<Mounts> ON_MOUNTS = new CopyOnWriteArrayList<>();

    /** A connection's mounts as they come and go: where one process holds several clients (a test rig). */
    public interface Mounts {
        /** {@code namespace} mounted for {@code connection} from {@code folder} (again, after a reload). */
        void mounted(CgProtocolConnection<Object> connection, String namespace, Path folder);

        /** {@code namespace} unmounted, its connection closed. */
        default void unmounted(CgProtocolConnection<Object> connection, String namespace) {
        }
    }

    /**
     * Client: {@code listener} with each mount and unmount and its connection.
     *
     * <pre>{@code
     * CgServerMounts.onMounts((connection, namespace, folder) -> kinds.load(connection, folder));
     * }</pre>
     */
    public static void onMounts(Mounts listener) {
        ON_MOUNTS.add(Objects.requireNonNull(listener, "listener"));
    }
    private static volatile long cap = 256L * 1024 * 1024;
    @Nullable
    private static volatile Path cacheRoot;

    /** One namespace mounted for one connection. */
    private record Mounted(CgProtocolConnection<Object> connection, String namespace, Path folder) {
    }

    private static final Map<String, Mounted> MOUNTED = new ConcurrentHashMap<>();

    /** Client: {@code listener} with each namespace a server's files were mounted for (again, after a reload). */
    public static void onMounted(BiConsumer<String, Path> listener) {
        ON_MOUNTED.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Client: {@code listener} with each namespace unmounted, as its connection closed. */
    public static void onUnmounted(Consumer<String> listener) {
        ON_UNMOUNTED.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Client: the cache's total cap, in bytes; past it the least recently used bodies go. */
    public static void cacheCap(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("cacheCap must be positive: " + bytes);
        cap = bytes;
    }

    /** Client: where bodies are kept, in place of {@code CgCacheDirectory.of("mounts")}: a test's folder. */
    public static void cacheIn(Path folder) {
        cacheRoot = folder;
    }

    /** The namespaces mounted now, from any server. */
    public static List<String> mounted() {
        List<String> out = new ArrayList<>();
        for (Mounted m : MOUNTED.values()) out.add(m.namespace);
        return out;
    }

    /** Bodies that did not hash to their name, dropped. */
    public static long mismatched() {
        return MISMATCHED.get();
    }

    /** Files of a kind this client does not allow, refused. */
    public static long refusedKinds() {
        return REFUSED_KINDS.get();
    }

    /** Bodies fetched from a server, rather than found in the cache. */
    public static long fetched() {
        return FETCHED.get();
    }

    /** Client: the hello named what the server offers; each namespace this client lacks is fetched. */
    static void offered(CgProtocolConnection<Object> connection, Map<String, String> offers) {
        if (offers.isEmpty()) return;
        connection.onClosed(reason -> {
            PENDING.remove(connection);
            unmountAll(connection);
        });
        for (String namespace : offers.keySet()) {
            if (installed(namespace)) continue;
            PENDING.computeIfAbsent(connection, c -> ConcurrentHashMap.newKeySet()).add(namespace);
            fetch(connection, namespace, false);
        }
    }

    /** Namespaces each connection's server offered that are on their way: asked for, not yet mounted or refused. */
    private static final Map<CgProtocolConnection<Object>, Set<String>> PENDING = new ConcurrentHashMap<>();

    /**
     * Client: whether {@code namespace}'s files are on their way from {@code connection}'s server: offered, and neither
     * mounted nor refused yet. What waits for a server's files ({@code CgServerMounts.onMounts}) asks first.
     */
    public static boolean mounting(CgProtocolConnection<Object> connection, String namespace) {
        Set<String> waiting = PENDING.get(connection);
        return waiting != null && waiting.contains(namespace);
    }

    private static void settled(CgProtocolConnection<Object> connection, String namespace) {
        Set<String> waiting = PENDING.get(connection);
        if (waiting != null) waiting.remove(namespace);
    }

    /** Whether this client has {@code namespace} itself: a mod, a mounted namespace, or a message namespace declared. */
    private static boolean installed(String namespace) {
        return CgModLoader.isLoaded(namespace) || CgAssetMounts.folder(namespace) != null || CgMessage.namespaces().containsKey(namespace);
    }

    private static void fetch(CgProtocolConnection<Object> connection, String namespace, boolean reload) {
        List<String> languages = new ArrayList<>();
        String language = CgI18n.language();
        if (language != null) languages.add(language);
        INDEX.ask(connection, new Ask(namespace, languages, new HashMap<>(SUPPORTED)))
                .then(manifest -> fetchBodies(connection, namespace, manifest, reload))
                .onError(error -> {
                    settled(connection, namespace);
                    LOGGER.warn("[cg-net] {}'s files are not mounted: {}", namespace, error.code());
                });
    }

    private static void fetchBodies(CgProtocolConnection<Object> connection, String namespace, Manifest manifest, boolean reload) {
        Path store = store();
        List<Entry> taken = new ArrayList<>();
        for (Entry e : manifest.entries()) {
            String kind = kindOf(e.path());
            if (kind == null || !kind.equals(e.kind())) {
                REFUSED_KINDS.incrementAndGet();
                LOGGER.warn("[cg-net] {} offered {}, a kind this client does not take: refused", namespace, e.path());
                continue;
            }
            taken.add(e);
        }
        int[] waiting = {taken.size()};
        Map<Entry, Path> bodies = new ConcurrentHashMap<>();
        Runnable done = () -> mount(connection, namespace, taken, bodies, reload);
        if (taken.isEmpty()) {
            done.run();
            return;
        }
        for (Entry e : taken) {
            Path cached = store.resolve(e.hash());
            if (Files.isRegularFile(cached)) {
                touch(cached);
                bodies.put(e, cached);
                if (--waiting[0] == 0) done.run();
                continue;
            }
            BODY.ask(connection, new Body(namespace, e.hash())).always(() -> {
                if (--waiting[0] == 0) done.run();
            }).then(bytes -> {
                FETCHED.incrementAndGet();
                // stored under the hash it has, never the one it was named
                String hash = sha256(bytes);
                if (!hash.equals(e.hash())) {
                    MISMATCHED.incrementAndGet();
                    LOGGER.warn("[cg-net] {}'s {} did not hash to its name: dropped", namespace, e.path());
                    return;
                }
                bodies.put(e, write(store, hash, bytes));
            });
        }
    }

    private static void mount(CgProtocolConnection<Object> connection, String namespace, List<Entry> taken,
                              Map<Entry, Path> bodies, boolean reload) {
        if (connection.isClosed()) return;
        Path folder = store().getParent().resolve("live").resolve(Integer.toHexString(System.identityHashCode(connection))).resolve(namespace);
        try {
            if (Files.isDirectory(folder)) delete(folder);
            for (Entry e : taken) {
                Path body = bodies.get(e);
                if (body == null) continue;
                Path to = folder.resolve(e.path());
                Files.createDirectories(to.getParent());
                Files.copy(body, to);
            }
        } catch (IOException failed) {
            LOGGER.warn("[cg-net] {}'s files could not be laid out at {}: {}", namespace, folder, failed.toString());
            return;
        }
        CgIO.mount(namespace, folder);
        MOUNTED.put(namespace, new Mounted(connection, namespace, folder));
        if (!reload) {
            for (Entry e : taken) {
                if (!e.kind().equals("net_type") || bodies.get(e) == null) continue;
                String name = e.path().substring("net_types/".length(), e.path().length() - ".yaml".length());
                // one process serving itself (an integrated server): its own declaration is the type
                if (CgNetType.named(namespace + ":" + name) != null) continue;
                try {
                    CgNetTypeCompiler.declareFor(connection, namespace + ":" + name,
                            new String(Files.readAllBytes(folder.resolve(e.path())), StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException failed) {
                    LOGGER.warn("[cg-net] {}'s {} is not declared: {}", namespace, e.path(), failed.getMessage());
                }
            }
        }
        LOGGER.info("[cg-net] {} mounted from the server: {} files", namespace, taken.size());
        for (BiConsumer<String, Path> listener : ON_MOUNTED) listener.accept(namespace, folder);
        settled(connection, namespace);
        for (Mounts listener : ON_MOUNTS) listener.mounted(connection, namespace, folder);
    }

    private static void unmountAll(CgProtocolConnection<Object> connection) {
        for (Mounted m : new ArrayList<>(MOUNTED.values())) {
            if (m.connection != connection) continue;
            MOUNTED.remove(m.namespace);
            CgIO.unmount(m.namespace);
            try {
                delete(m.folder);
            } catch (IOException ignored) {
                // a file held open stays until the next session's cleanup
            }
            for (Consumer<String> listener : ON_UNMOUNTED) listener.accept(m.namespace);
            for (Mounts listener : ON_MOUNTS) listener.unmounted(m.connection, m.namespace);
        }
    }

    // ── The cache ───────────────────────────────────────────────────────────────────────────────

    private static Path store() {
        Path root = cacheRoot;
        if (root == null) {
            Path dir = CgCacheDirectory.of("mounts");
            root = dir != null ? dir : Path.of(System.getProperty("java.io.tmpdir"), "crystalgraphics-mounts");
            cacheRoot = root;
        }
        Path store = root.resolve("bodies");
        try {
            Files.createDirectories(store);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make " + store, e);
        }
        return store;
    }

    private static Path write(Path store, String hash, byte[] bytes) {
        Path to = store.resolve(hash);
        try {
            Files.write(to, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + to, e);
        }
        evict(store);
        return to;
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // an untouched body is only evicted sooner
        }
    }

    /** Least recently used bodies out until the store fits its cap. */
    static void evict(Path store) {
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (Stream<Path> list = Files.list(store)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                files.add(p);
                total += Files.size(p);
            }
            if (total <= cap) return;
            files.sort(Comparator.comparingLong(CgServerMounts::modified));
            for (Path p : files) {
                if (total <= cap) break;
                total -= Files.size(p);
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            LOGGER.warn("[cg-net] the mount cache could not be trimmed: {}", e.toString());
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            for (int i = all.size() - 1; i >= 0; i--) Files.deleteIfExists(all.get(i));
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    private CgServerMounts() {
    }
}
