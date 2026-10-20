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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Every asset by kind and id, known without reading one: Unreal's {@code IAssetRegistry}. A dev run fills it from
 * its source folders' listings; a builder's assets register themselves. A dedicated server builds the same registry,
 * so both sides agree on every id before the first connection.
 *
 * <p>An id is unique within its kind, not across kinds: a reference's field declares the kind, so a cue and the
 * sequence it plays may share {@code mymod:nova_cannon}. A binary's id keeps its folder and extension, so it is
 * unique on its own.</p>
 *
 * <pre>{@code
 * CgAssetRegistry registry = new CgAssetRegistry();
 * registry.registerCode("mymod:kamehameha/charge", CgParticleKinds.SYSTEM);   // a builder's, at init
 * for (File root : CgDevResourceRoots.discover(System::getProperty, System::getenv)) registry.scanResources(root);
 *
 * CgAssetEntry e = registry.resolve(CgParticleKinds.SYSTEM, "mymod:old/charge");   // a redirect followed, warned once
 * registry.binary("mymod:textures/fx/noise.png");
 * registry.entries("mymod");                                 // every entry of a namespace, by id
 * registry.hash("mymod");                                    // what a client compares with its server's
 * }</pre>
 *
 * <ul>
 *   <li>A scan reads a folder listing, plus files small enough to be redirects; nothing else is read.</li>
 *   <li>A builder's id and a file's id colliding is an error naming both, whichever registers second.</li>
 *   <li>Files that are not the format's (an importer's report, a stray) are kept in {@link #strays()}, never
 *       registered.</li>
 *   <li>Events run on the thread that registers; a scan's {@code ADDED}s come before its {@code SCANNED}.</li>
 * </ul>
 */
public final class CgAssetRegistry {

    /** A namespace's files that are not assets: its units, content index and tags. */
    private static final Set<String> ROOT_FILES = new HashSet<>(Arrays.asList("units.yaml", "content.yaml", "tags.yaml",
            "carried.json", "ruled.json"));
    /** A namespace's folders that hold no assets: an importer's reports, a game's data kept for gameplay. */
    private static final Set<String> NOT_ASSETS = new HashSet<>(Arrays.asList("import", "roles"));
    /** Binary folders and the extensions they hold. */
    private static final Map<String, List<String>> BINARIES = new HashMap<>();
    /** A file this size or smaller is read at scan, in case it is a redirect ({@code { _v: 1, redirect: <id> }}). */
    private static final long REDIRECT_BYTES = 160;
    private static final String REDIRECT = "redirect";
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static {
        BINARIES.put("models/", Arrays.asList(".glb", ".gltf"));
        BINARIES.put("textures/", Collections.singletonList(".png"));
        BINARIES.put("fields/", Collections.singletonList(".fga"));
        BINARIES.put("sounds/", Collections.singletonList(".ogg"));
    }

    private final Map<String, CgAssetEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> contentIndex = new ConcurrentHashMap<>();
    private final List<Path> strays = new CopyOnWriteArrayList<>();
    private final List<Consumer<CgAssetRegistryEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

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
     * fires {@code SCANNED}.
     *
     * @throws CgAssetException if an id is already registered from another file or by a builder
     */
    public synchronized void scan(String namespace, Path root, CgAssetSource source) {
        CgAssetKinds.register();
        List<CgAssetEntry> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path rel = root.relativize(dir);
                    return rel.getNameCount() == 1 && NOT_ASSETS.contains(rel.toString()) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    CgAssetEntry e = entryOf(namespace, rel, file, attrs.size(), source);
                    if (e != null) found.add(e);
                    else if (!(rel.indexOf('/') < 0 && ROOT_FILES.contains(rel))) strays.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("scanning " + namespace + " at " + root, e);
        }
        Path index = root.resolve("content.yaml");
        if (Files.isRegularFile(index)) contentIndex.put(namespace, readIndex(index));
        found.sort((a, b) -> a.id().compareTo(b.id()));
        for (CgAssetEntry e : found) add(e);
        fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.SCANNED, namespace, null, null));
    }

    /**
     * Registers a builder's asset, which has no file.
     *
     * @throws CgAssetException if {@code id} is already registered
     */
    public synchronized CgAssetEntry registerCode(String id, CgType kind) {
        CgAssetEntry e = new CgAssetEntry(id, Objects.requireNonNull(kind), CgAssetSource.CODE, null, null);
        add(e);
        return e;
    }

    /** Drops {@code id} of {@code kind} (null: a binary), its file deleted; nothing if it is not registered. */
    public synchronized void remove(@Nullable CgType kind, String id) {
        CgAssetEntry e = entries.remove(key(kind, id));
        if (e != null) fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.REMOVED, e.namespace(), e, null));
    }

    /** Announces that {@code id}'s file changed; nothing if it is not registered. */
    public void changed(@Nullable CgType kind, String id) {
        CgAssetEntry e = entries.get(key(kind, id));
        if (e != null) fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.CHANGED, e.namespace(), e, null));
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
        CgAssetEntry moved = new CgAssetEntry(to, old.kind(), old.source(), file, null);
        entries.remove(key(kind, from));
        entries.put(key(kind, to), moved);
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

    /** Every entry of {@code namespace}, by id. */
    public List<CgAssetEntry> entries(String namespace) {
        List<CgAssetEntry> out = new ArrayList<>();
        for (CgAssetEntry e : entries.values()) if (e.namespace().equals(namespace)) out.add(e);
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

    /** Hears every event, on the registering thread; run the answer to stop. */
    public Runnable onEvent(Consumer<CgAssetRegistryEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────

    private void add(CgAssetEntry e) {
        CgAssetEntry was = entries.putIfAbsent(key(e.kind(), e.id()), e);
        if (was != null) {
            throw new CgAssetException(key(e.kind(), e.id()) + " is registered twice: " + where(was) + " and " + where(e));
        }
        fire(new CgAssetRegistryEvent(CgAssetRegistryEvent.Type.ADDED, e.namespace(), e, null));
    }

    /** A {@code kind}'s {@code id}, or a binary's: ids are unique within a kind. */
    private static String key(@Nullable CgType kind, String id) {
        return (kind == null ? "binary" : kind.id()) + '|' + id;
    }

    private static String where(CgAssetEntry e) {
        return e.file() != null ? e.file().toString() : "a builder (" + e.source() + ")";
    }

    private void fire(CgAssetRegistryEvent event) {
        for (Consumer<CgAssetRegistryEvent> l : listeners) l.accept(event);
    }

    /** The entry a file is, or null for a file that is not an asset. */
    @Nullable
    private static CgAssetEntry entryOf(String namespace, String rel, Path file, long size, CgAssetSource source) {
        if (rel.endsWith(".yaml")) {
            CgType kind = kindOf(rel);
            if (kind == null) return null;
            String id = namespace + ":" + rel.substring(kind.folder().length() + 1, rel.length() - ".yaml".length());
            return new CgAssetEntry(id, kind, source, file, size <= REDIRECT_BYTES ? redirectOf(file) : null);
        }
        for (Map.Entry<String, List<String>> b : BINARIES.entrySet()) {
            if (!rel.startsWith(b.getKey())) continue;
            for (String ext : b.getValue()) {
                if (rel.endsWith(ext)) return new CgAssetEntry(namespace + ":" + rel, null, source, file, null);
            }
        }
        return null;
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

    @Nullable
    private static String redirectOf(Path file) {
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            Object to = tree instanceof Map ? ((Map<?, ?>) tree).get(REDIRECT) : null;
            return to instanceof String ? (String) to : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException notYaml) {
            return null;   // validation names it when the file is read
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
