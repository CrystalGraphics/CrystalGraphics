package com.crystalgraphics.util.io;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports the files changed under a directory tree, each once its writes have settled: what hot reload reads. Polled
 * from the caller's tick, with no thread of its own.
 *
 * <pre>{@code
 * CgDirectoryWatcher watcher = CgDirectoryWatcher.open(Paths.get("src/main/resources/assets"), 120);
 * // each frame:
 * watcher.poll(new CgDirectoryWatcher.Listener() {
 *     public void changed(Path file) { reload(file); }
 *     public void overflowed() { reloadEverything(); }    // events were dropped
 * });
 * watcher.close();
 * }</pre>
 *
 * <ul>
 *   <li>A file is reported once no event has touched it for {@code quietMillis}: an editor's save is several events
 *       (truncate, write, rename onto the file), and reading after the first gets half a file.</li>
 *   <li>Created and modified are both "changed". A file gone when it settles is not reported: a deletion leaves
 *       nothing to re-read, and an atomic save's temporary has been renamed onto the file, which reports itself.</li>
 *   <li>Editors' scratch files (backups, locks, named temporaries) are never reported.</li>
 *   <li>A new directory is watched at once, and the files already in it are reported.</li>
 *   <li>On macOS the JDK's watch service polls, so changes faster than its interval can be missed
 *       (<a href="https://bugs.openjdk.org/browse/JDK-8293067">JDK-8293067</a>).</li>
 * </ul>
 *
 * <p>Ported from CrystalGUI's {@code NioFileEventSource}; the settling follows notify-debouncer-full (Bevy's file
 * watcher) and chokidar's {@code awaitWriteFinish}.</p>
 */
public final class CgDirectoryWatcher implements AutoCloseable {

    /** What {@link #poll} reports to. */
    public interface Listener {
        /** {@code file} changed and has settled. */
        void changed(Path file);

        /** The platform dropped events: anything under the root may have changed. */
        default void overflowed() {}
    }

    private final Path root;
    private final long quietNanos;
    private final WatchService service;
    private final Map<WatchKey, Path> registered = new HashMap<>();
    /** Files with unsettled events, and when the last one came. */
    private final Map<Path, Long> pending = new LinkedHashMap<>();
    private boolean closed;

    private CgDirectoryWatcher(Path root, long quietMillis, WatchService service) {
        this.root = root;
        this.quietNanos = quietMillis * 1_000_000L;
        this.service = service;
    }

    /** Watches {@code root} and everything under it. @throws IOException if the platform refuses */
    public static CgDirectoryWatcher open(Path root, long quietMillis) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        CgDirectoryWatcher watcher = new CgDirectoryWatcher(absolute, quietMillis, absolute.getFileSystem().newWatchService());
        watcher.registerTree(absolute, false);
        return watcher;
    }

    public Path root() {
        return root;
    }

    /** How many directories are watched. */
    public int watchedDirectories() {
        return registered.size();
    }

    /**
     * Drains the platform's events without waiting, and reports every file settled since; answers how many. Cheap
     * enough for every frame: with no events it reads a queue and allocates nothing. Open once and keep it; opening
     * walks the whole tree.
     */
    public int poll(Listener listener) {
        if (closed) return 0;
        long now = System.nanoTime();
        WatchKey key;
        while ((key = service.poll()) != null) {
            Path directory = registered.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    pending.clear();
                    listener.overflowed();
                    continue;
                }
                if (directory == null) continue;
                Path child = directory.resolve((Path) event.context());
                if (isEditorScratch(child.getFileName().toString())) continue;
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    registerTree(child, true);
                    continue;
                }
                pending.remove(child);   // re-inserted last: settled files come out in the order they settled
                pending.put(child, now);
            }
            if (!key.reset()) registered.remove(key);
        }
        if (pending.isEmpty()) return 0;   // the common case, every frame: no iterator
        int reported = 0;
        for (Iterator<Map.Entry<Path, Long>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, Long> entry = it.next();
            if (now - entry.getValue() < quietNanos) continue;
            it.remove();
            // Gone by now: an atomic save's temporary, renamed onto the real file (which reports itself), or a
            // deletion, which leaves nothing to re-read.
            if (!Files.isRegularFile(entry.getKey())) continue;
            listener.changed(entry.getKey());
            reported++;
        }
        return reported;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        registered.clear();
        pending.clear();
        try {
            service.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Whether {@code name} is an editor's scratch beside the file it saves: backups ({@code ~}), locks ({@code .#},
     * {@code ~$}), atomic-save temporaries (gio's, IntelliJ's, CrystalGUI's) and vim's swap files.
     */
    public static boolean isEditorScratch(String name) {
        if (name.isEmpty()) return false;
        return name.endsWith("~")
                || name.startsWith(".#")
                || name.startsWith("~$")
                || name.startsWith(".goutputstream-")
                || name.endsWith("___jb_tmp___") || name.endsWith("___jb_old___")
                || name.startsWith(".cgui-") && name.endsWith(".tmp")
                || name.endsWith(".swp") || name.endsWith(".swo") || name.endsWith(".swx");
    }

    /** Watches {@code start} and every directory under it; {@code report} marks the files already there as changed. */
    private void registerTree(Path start, boolean report) {
        long now = System.nanoTime();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    try {
                        registered.put(directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY), directory);
                    } catch (IOException | RuntimeException skip) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (report && !isEditorScratch(file.getFileName().toString())) pending.put(file, now);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A directory gone mid-walk: the rest of the tree is still watched.
        }
    }
}
