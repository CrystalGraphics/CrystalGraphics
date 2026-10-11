package com.crystalgraphics.mc;

import com.crystalgraphics.util.io.CgDirectoryWatcher;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hot reload: watches each resource override root's {@code assets/} and hands every file edited there to
 * {@link CgAssetReloader#changed}, so the next frame draws with it. On in every dev run with nothing to set up: the
 * override roots default to the run's own source resource folders ({@link CgIO#overrideRoots}), and a production
 * client has none.
 *
 * <pre>{@code
 * ./gradlew runClient      # any consumer's dev run, unchanged
 * # edit src/main/resources/assets/mymod/shaders/glow.shader, save: the next frame draws with it
 * }</pre>
 *
 * <ul>
 *   <li>A file counts once its writes have been quiet for {@link #QUIET_MILLIS}: a save is several events.</li>
 *   <li>A shader or kernel that no longer compiles keeps drawing with its last good program; the log names the file
 *       and the error. Fix it and save again.</li>
 *   <li>While it watches, the shader preprocessor caches includes again (each edited one is evicted), so a compile
 *       does not re-read every library from disk.</li>
 *   <li>Lost events (the platform's queue overflowed) reload everything, as F3+T does.</li>
 *   <li>{@code -Dcrystalgraphics.hotReload=false} keeps the override roots without watching them;
 *       {@code -Dcrystalgraphics.resourceOverrideDirs=<dirs>} adds folders the run does not load from.</li>
 * </ul>
 */
public final class CgHotReload {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static final boolean ENABLED = CgVar.bool("crystalgraphics.hotReload", true, CgVar.DEV | CgVar.RESTART,
            "Watch each resource override root's assets/ and reload what an edited file reaches at the next frame.").get();

    /** How long a file's events must be quiet before it is read. */
    public static final long QUIET_MILLIS = 120;

    private static final List<Root> ROOTS = new ArrayList<>();
    private static boolean started;
    private static volatile boolean watching;
    private static boolean overflowed;

    private CgHotReload() {}

    /** Whether a watcher is open on at least one override root. */
    public static boolean watching() {
        return watching;
    }

    /**
     * Polls the watchers and applies every queued change. Render thread, once a frame:
     * {@code CgGraphicsLifecycle.tickFrame}. With nothing edited it allocates nothing and makes no system call: a poll
     * reads the queue the JDK's own watch thread fills.
     */
    public static void tick() {
        if (!started) start();   // once per process: the watchers live until it exits
        for (int i = 0; i < ROOTS.size(); i++) ROOTS.get(i).watcher.poll(ROOTS.get(i));
        if (overflowed) {
            overflowed = false;
            LOGGER.warn("[hot reload] the watcher dropped events: reloading every asset");
            CgAssetReloader.reload();
        }
        CgAssetReloader.applyChanges();
    }

    private static void start() {
        started = true;
        if (!ENABLED) return;
        for (File root : CgIO.overrideRoots()) {
            File assets = new File(root, "assets");
            if (!assets.isDirectory()) continue;
            try {
                CgDirectoryWatcher watcher = CgDirectoryWatcher.open(assets.toPath(), QUIET_MILLIS);
                ROOTS.add(new Root(watcher));
                LOGGER.info("[hot reload] watching {} ({} directories)", assets, watcher.watchedDirectories());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[hot reload] cannot watch {}: {}", assets, e.toString());
            }
        }
        watching = !ROOTS.isEmpty();
    }

    /** One watched {@code assets/} directory. */
    private static final class Root implements CgDirectoryWatcher.Listener {
        final CgDirectoryWatcher watcher;

        Root(CgDirectoryWatcher watcher) {
            this.watcher = watcher;
        }

        @Override
        public void changed(Path file) {
            Path relative = watcher.root().relativize(file.toAbsolutePath().normalize());
            if (relative.getNameCount() < 2) return;   // directly in assets/: no namespace
            StringBuilder path = new StringBuilder("assets");
            for (int i = 0; i < relative.getNameCount(); i++) path.append('/').append(relative.getName(i));
            CgAssetReloader.changed(path.toString());
        }

        @Override
        public void overflowed() {
            overflowed = true;
        }
    }
}
