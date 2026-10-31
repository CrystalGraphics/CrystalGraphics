package com.crystalgraphics.asset.load;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.kind.CgAssetKinds;
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
 * CgAssetMounts.mount();      // pre-init: every mod's cues, shakes, systems and tags.yaml, before anything compiles
 * CgAssetMounts.loadAhead();  // post-init: all of it starts loading
 *
 * // a tool or a test, over a registry of its own:
 * CgAssetMounts.Result r = CgAssetMounts.mount(registry, List.of(sourceFolder), List.of(modJar));
 * }</pre>
 *
 * <ul>
 *   <li>A namespace mounts only where it holds the format's content: {@code tags.yaml}, {@code units.yaml},
 *       {@code content.yaml}, or a kind's folder other than {@code textures} and {@code models}, which every
 *       Minecraft namespace has. Unreal mounts only plugins that can contain content.</li>
 *   <li>A namespace mounts from the first source that has it, so a dev run's source folder wins over its own build
 *       output, and edits there reload.</li>
 *   <li>A jar the loader has not opened is opened here and kept open for the session, a mounted pak.</li>
 *   <li>A second call mounts nothing.</li>
 * </ul>
 */
public final class CgAssetMounts {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Set<String> ROOT_FILES = new HashSet<>(Arrays.asList("tags.yaml", "units.yaml", "content.yaml"));
    /** Kind folders Minecraft's own namespaces use too: holding one says nothing. */
    private static final Set<String> SHARED = new HashSet<>(Arrays.asList("textures", "models"));

    private static boolean mounted, loaded;
    private static List<String> namespaces = List.of();

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
     * Loads every asset {@link #mount} mounted ahead ({@link CgVfxAssets#prefetch}): asked from any thread, started at the
     * asset loop's next drain. Once.
     */
    public static synchronized void loadAhead() {
        if (loaded) return;
        loaded = true;
        CgVfxAssets assets = CgVfxAssets.instance();
        List<CgAssetRef<?>> ahead = new ArrayList<>();
        for (String ns : namespaces) {
            for (CgAssetEntry e : assets.registry().entries(ns)) {
                if (!e.binary() && e.redirect() == null) ahead.add(CgVfxAssets.ref(e.kind(), e.id()));
            }
        }
        assets.prefetch(ahead);
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
