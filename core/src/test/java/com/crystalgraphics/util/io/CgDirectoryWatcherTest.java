package com.crystalgraphics.util.io;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgDirectoryWatcherTest {

    @Test
    public void reportsEditsSettledAndSkipsScratch() throws Exception {
        Path root = Files.createTempDirectory("cg-watch");
        Path shaders = Files.createDirectories(root.resolve("ns/shaders"));
        Path lib = write(shaders.resolve("lib.glsl"), "a");
        try (CgDirectoryWatcher watcher = CgDirectoryWatcher.open(root, 0)) {
            write(lib, "b");
            write(shaders.resolve("lib.glsl~"), "backup");
            write(shaders.resolve("lib.glsl___jb_tmp___"), "temp");
            Set<Path> changed = pollUntil(watcher, lib);
            assertTrue(changed.contains(lib));
            for (Path p : changed) assertFalse(p.toString(), CgDirectoryWatcher.isEditorScratch(p.getFileName().toString()));
        }
    }

    @Test
    public void followsANewDirectory() throws Exception {
        Path root = Files.createTempDirectory("cg-watch");
        try (CgDirectoryWatcher watcher = CgDirectoryWatcher.open(root, 0)) {
            Path textures = Files.createDirectories(root.resolve("ns/textures"));
            Path first = write(textures.resolve("a.png"), "x");
            assertTrue(pollUntil(watcher, first).contains(first));
            Path second = write(textures.resolve("b.png"), "y");
            assertTrue(pollUntil(watcher, second).contains(second));
        }
    }

    @Test
    public void waitsForQuiet() throws Exception {
        Path root = Files.createTempDirectory("cg-watch");
        Path file = write(root.resolve("a.glsl"), "a");
        try (CgDirectoryWatcher watcher = CgDirectoryWatcher.open(root, 60_000)) {
            write(file, "b");
            Thread.sleep(300);
            Set<Path> changed = new LinkedHashSet<>();
            assertEquals(0, watcher.poll(changed::add));
        }
    }

    private static Path write(Path file, String text) throws IOException {
        return Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Polls until {@code wanted} is reported or five seconds pass (macOS's watch service polls every few seconds). */
    private static Set<Path> pollUntil(CgDirectoryWatcher watcher, Path wanted) throws InterruptedException {
        Set<Path> changed = new LinkedHashSet<>();
        long until = System.nanoTime() + 5_000_000_000L;
        while (!changed.contains(wanted) && System.nanoTime() < until) {
            watcher.poll(changed::add);
            Thread.sleep(20);
        }
        return changed;
    }
}
