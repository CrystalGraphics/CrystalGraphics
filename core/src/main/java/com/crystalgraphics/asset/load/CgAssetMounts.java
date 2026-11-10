package com.crystalgraphics.asset.load;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.util.io.CgIO;

/**
 * Mounts every loaded mod's assets into the process's registry, as Unreal mounts each content plugin and its asset
 * registry gathers the mount point: a dev run's source folders first, then each mod's {@code assets/<ns>/} from its jar.
 * The engine's lifecycle runs it on both sides as the game initialises ({@code CgEngineLifecycle}): {@link #mount} at
 * pre-init, {@link #loadAhead} once every mod has initialised, as Unreal's cue manager loads its runtime library at
 * startup. A mod registers nothing.
 *
 * <pre>{@code
 * // CgEngineLifecycle, never a host:
 * CgAssetMounts.mount();      // pre-init: every mod's cues, shakes and systems, and the tags they declare, before anything compiles
 * CgAssetMounts.loadAhead();  // post-init: everything mounted (less what a preload.yaml opts out), starts loading
 *
 * // a tool or a test, over a registry of its own:
 * CgAssetMounts.Result r = CgAssetMounts.mount(registry, List.of(sourceFolder), List.of(modJar));
 * }</pre>
 *
 * <ul>
 *   <li>A namespace mounts only where it holds the format's content: {@code units.yaml},
 *       {@code content.yaml}, {@code preload.yaml}, or a kind's folder other than {@code textures} and {@code models}, which every
 *       Minecraft namespace has. Unreal mounts only plugins that can contain content.</li>
 *   <li>A namespace mounts from the first source that has it, so a dev run's source folder wins over its own build
 *       output, and edits there reload.</li>
 *   <li>A jar the loader has not opened is opened here and kept open for the session, a mounted pak.</li>
 *   <li>A second call mounts nothing.</li>
 * </ul>
 */
public final class CgAssetMounts {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final String PRELOAD = "preload.yaml";
    private static final Set<String> ROOT_FILES = new HashSet<>(Arrays.asList("units.yaml", "content.yaml", PRELOAD));
    /** Kind folders Minecraft's own namespaces use too: holding one says nothing. */
    private static final Set<String> SHARED = new HashSet<>(Arrays.asList("textures", "models"));

    private static boolean mounted, loaded;
    private static List<String> namespaces = List.of();
    /** Each mounted namespace's folder, where its {@code preload.yaml} is read. */
    private static final Map<String, Path> ROOTS = new LinkedHashMap<>();

    private CgAssetMounts() {
    }

    /**
     * Mounts the dev roots and {@link CgModLoader#roots()}' mods into {@link CgVfxAssets#instance()}'s registry, once,
     * and logs what it mounted and how long it took.
     */
    public static synchronized void mount() {
        if (mounted) return;
        mounted = true;
        // The roots hot reload watches: crystalgraphics.resourceOverrideDirs, then a dev run's source folders
        List<Path> dev = new ArrayList<>();
        for (File f : CgIO.overrideRoots()) dev.add(f.toPath());
        namespaces = mount(CgVfxAssets.instance().registry(), dev, CgModLoader.roots()).mounted();
    }

    /**
     * The file {@code id} names in its mounted namespace, {@code <namespace>:<path>}: a mod's own data beside its
     * assets, read with {@code CgFiles}. A dev run's source folder answers before its jar, so an edit is read.
     *
     * <pre>{@code
     * Path grades = CgAssetMounts.file("mymod:hud/combo/grades.yaml");
     * List<Grade> table = CgFiles.YAML.read(grades, CgCodecs.listOf(Grade.CODEC));
     * }</pre>
     *
     * <ul>
     *   <li>Only a mounted namespace has one: null before {@link #mount()} and for a namespace holding no assets.</li>
     *   <li>The path may not exist: reading it fails, naming it.</li>
     * </ul>
     */
    @Nullable
    public static synchronized Path file(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("not <namespace>:<path>: " + id);
        Path root = ROOTS.get(id.substring(0, colon));
        return root == null ? null : root.resolve(id.substring(colon + 1));
    }

    /**
     * Loads ahead ({@link CgVfxAssets#prefetch}) what the game can play, behind the loading screen: every asset of
     * every mounted namespace, unless its {@code preload.yaml} says {@code all: false} (then its cues alone), and what
     * each {@code preload.yaml} declares from any namespace. What none of it reaches loads at its first play, in game
     * time, counted ({@code asset.unplanned-loads}). Asked from any thread, started at the asset loop's next drain. Once.
     *
     * <pre>{@code
     * # assets/mymod/preload.yaml, for a namespace too large to compile whole (a library):
     * all: false                                                                  # only my cues, and what follows
     * # An id, or a glob over ids: * within one folder, ** across folders
     * sequence:
     *   - kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001*      # Goku's Kamehameha, every variant
     *   - spzero_kit:SS/**_0000_00_actSPM2*                                       # a move whose files span folders
     * cue: [kakarot_kit:Blueprints/Character/C001A/BTL/SKL/BP_Projectile_C001A_S_SKL001*]
     * }</pre>
     */
    public static synchronized void loadAhead() {
        if (loaded) return;
        loaded = true;
        CgVfxAssets assets = CgVfxAssets.instance();
        CgAssetRegistry registry = assets.registry();
        Set<String> seen = new HashSet<>();
        List<CgAssetRef<?>> ahead = new ArrayList<>();
        int own = 0, declared = 0;
        for (String ns : namespaces) {
            Path root = ROOTS.get(ns);
            Preload preload = root == null ? Preload.NONE : Preload.read(root.resolve(PRELOAD));
            for (CgAssetEntry e : registry.entries(ns)) {
                if (e.binary() || e.redirect() != null || !(preload.all() || e.kind() == CgGameplayKinds.CUE)) continue;
                if (add(ahead, seen, e)) own++;
            }
            for (Map.Entry<CgType, List<String>> d : preload.declared().entrySet()) {
                for (String id : d.getValue()) {
                    int before = ahead.size(), matched = 0;
                    if (id.indexOf('*') >= 0) {
                        Pattern glob = glob(id);
                        for (CgAssetEntry e : registry.entries(d.getKey())) {
                            if (e.redirect() != null || !glob.matcher(e.id()).matches()) continue;
                            matched++;
                            add(ahead, seen, e);
                        }
                    } else {
                        CgAssetEntry e = registry.entry(d.getKey(), id);
                        if (e != null) {
                            matched++;
                            add(ahead, seen, e);
                        }
                    }
                    if (matched == 0) {
                        LOGGER.warn("[crystalgraphics] assets: {}'s preload.yaml names {} {}, which matches nothing mounted",
                                ns, d.getKey().id(), id);
                    }
                    declared += ahead.size() - before;
                }
            }
        }
        LOGGER.info("[crystalgraphics] assets ahead: {} primaries ({} of the namespaces' own, {} declared), with what they reach",
                ahead.size(), own, declared);
        assets.prefetch(ahead);
    }

    private static boolean add(List<CgAssetRef<?>> ahead, Set<String> seen, CgAssetEntry e) {
        if (!seen.add(e.kind().id() + "|" + e.id())) return false;
        ahead.add(CgVfxAssets.ref(e.kind(), e.id()));
        return true;
    }

    /** An id glob as a pattern: {@code **} any characters, {@code *} any but {@code /}, the rest literal. */
    static Pattern glob(String id) {
        StringBuilder re = new StringBuilder();
        int literal = 0;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) != '*') continue;
            if (i > literal) re.append(Pattern.quote(id.substring(literal, i)));
            boolean deep = i + 1 < id.length() && id.charAt(i + 1) == '*';
            re.append(deep ? ".*" : "[^/]*");
            if (deep) i++;
            literal = i + 1;
        }
        if (literal < id.length()) re.append(Pattern.quote(id.substring(literal)));
        return Pattern.compile(re.toString());
    }

    /** A namespace's {@code preload.yaml}: each kind's ids and globs, and whether all its own assets load (unless {@code all: false}). */
    record Preload(boolean all, Map<CgType, List<String>> declared) {
        /** No file: every asset of the namespace, as a mod expects. */
        static final Preload NONE = new Preload(true, Map.of());

        /** The file at {@code file}, or {@link #NONE} where there is none. A kind it names that is not one warns. */
        static Preload read(Path file) {
            if (!Files.isRegularFile(file)) return NONE;
            Object tree;
            try {
                tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[crystalgraphics] assets: cannot read {}: {}", file, e.toString());
                return NONE;
            }
            if (!(tree instanceof Map<?, ?> map)) return NONE;
            boolean all = !Boolean.FALSE.equals(map.get("all"));   // everything unless it says false
            Map<CgType, List<String>> declared = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.equals("all") || key.equals(CgType.EDITOR)) continue;
                CgType kind = kindNamed(key);
                if (kind == null) {
                    LOGGER.warn("[crystalgraphics] assets: {} names {}, which is no kind", file, key);
                    continue;
                }
                List<String> ids = new ArrayList<>();
                if (e.getValue() instanceof List<?> list) for (Object id : list) ids.add(String.valueOf(id));
                else if (e.getValue() != null) ids.add(String.valueOf(e.getValue()));
                declared.computeIfAbsent(kind, k -> new ArrayList<>()).addAll(ids);
            }
            return new Preload(all, declared);
        }

        /** {@code cue} or {@code crystalgraphics:cue}. */
        private static CgType kindNamed(String key) {
            String id = key.indexOf(':') >= 0 ? key : "crystalgraphics:" + key;
            for (CgType kind : CgTypes.kinds()) if (kind.id().equals(id)) return kind;
            return null;
        }
    }

    /**
     * Mounts {@code dev} (source folders holding {@code assets/}) as {@code DEV}, then {@code mods} (each a mod's root:
     * a directory, a path in an open jar, or a jar file) as {@code JAR}, into {@code registry}. What it mounted.
     */
    public static Result mount(CgAssetRegistry registry, List<Path> dev, List<Path> mods) {
        long start = System.nanoTime();
        CgAssetKinds.register();
        Set<String> kindFolders = kindFolders();
        Set<String> seen = new HashSet<>(registry.namespaces());
        List<String> mounted = new ArrayList<>();
        int opened = 0, before = registry.size();
        for (Path root : dev) mountRoot(registry, root, CgAssetSource.DEV, seen, kindFolders, mounted);
        for (Path root : mods) {
            Path at = root;
            if (Files.isRegularFile(root)) {
                at = open(root);
                if (at == null) continue;
                opened++;
            }
            mountRoot(registry, at, CgAssetSource.JAR, seen, kindFolders, mounted);
        }
        Result r = new Result(List.copyOf(mounted), registry.size() - before, dev.size() + mods.size(), opened,
                (System.nanoTime() - start) / 1_000_000L);
        LOGGER.info("[crystalgraphics] assets: {} mounted, {} assets from {} roots ({} jars opened) in {} ms",
                r.mounted(), r.assets(), r.roots(), r.opened(), r.millis());
        return r;
    }

    /** What a mount found: the namespaces mounted, assets registered, roots looked at, jars it opened itself. */
    public record Result(List<String> mounted, int assets, int roots, int opened, long millis) {
    }

    private static void mountRoot(CgAssetRegistry registry, Path root, CgAssetSource source, Set<String> seen,
                                  Set<String> kindFolders, List<String> mounted) {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return;
        try (DirectoryStream<Path> spaces = Files.newDirectoryStream(assets, Files::isDirectory)) {
            for (Path ns : spaces) {
                String name = ns.getFileName().toString().replace("/", "");
                if (seen.contains(name) || !holdsContent(ns, kindFolders)) continue;
                seen.add(name);
                registry.scan(name, ns, source);
                mounted.add(name);
                synchronized (CgAssetMounts.class) {
                    ROOTS.put(name, ns);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[crystalgraphics] assets: cannot list {}: {}", assets, e.toString());
        }
    }

    /** Whether {@code ns} holds the format's content, by its root files and its kinds' folders. */
    static boolean holdsContent(Path ns, Set<String> kindFolders) {
        for (String f : ROOT_FILES) if (Files.isRegularFile(ns.resolve(f))) return true;
        for (String folder : kindFolders) if (Files.isDirectory(ns.resolve(folder))) return true;
        return false;
    }

    private static Set<String> kindFolders() {
        Set<String> out = new HashSet<>();
        for (CgType kind : CgTypes.kinds()) {
            String folder = kind.folder();
            if (folder != null && !SHARED.contains(folder)) out.add(folder);
        }
        return out;
    }

    /** {@code jar} as a file system kept open for the session, or null where it is no zip. */
    private static Path open(Path jar) {
        try {
            FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null);
            return fs.getPath("/");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[crystalgraphics] assets: cannot open {}: {}", jar, e.toString());
            return null;
        }
    }
}
