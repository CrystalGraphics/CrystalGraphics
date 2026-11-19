package com.crystalgraphics.api.vector.svg;

import com.crystalgraphics.render.ui.svg.CgSvgOps;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link CgSvg#preload} does the loading a first frame would otherwise do, off the render thread.
 *
 * <p><b>Nothing here clears the document cache.</b> It is global and static, and other tests hold live
 * {@code CgSvg} instances from it — clearing it mid-suite hands them a freshly parsed object and
 * turns their identity assertions into order-dependent failures. Every assertion below holds against a warm cache anyway.</p>
 *
 * <p>The interesting assertion is not that it completes — it is that a preloaded path is afterwards served
 * from the cache as the <em>same instance</em>, since that is the whole mechanism. A preload that parsed
 * correctly into a map nobody consults would be indistinguishable from working, right up until it made no
 * difference to a frame time.</p>
 */
public class CgSvgPreloadTest {

    @Test
    public void preloadedIconsAreServedFromTheCacheAfterwards() throws Exception {
        List<String> paths = shippedIconPaths();
        assertTrue("expected a shipped icon set, found " + paths.size(), paths.size() > 5);

        CgSvg.preload(paths).get(30, TimeUnit.SECONDS);

        for (String path : paths) {
            CgSvg first = CgSvg.of(path);
            assertNotNull(path + " did not load", first);
            // Same instance: proves the worker populated the cache the render thread reads, rather than
            // parsing into something of its own that of() then quietly redid.
            assertSame(path + " was re-parsed rather than served from cache", first, CgSvg.of(path));
        }
    }

    /** A path that cannot be read must not fail the batch, exactly as it would not on the render thread. */
    @Test
    public void anUnreadablePathDoesNotFailTheBatch() throws Exception {
        List<String> paths = new ArrayList<>(shippedIconPaths());
        paths.add("crystalgraphics:ui/icons/definitely-not-here.svg");
        CgSvg.preload(paths).get(30, TimeUnit.SECONDS);
        assertEquals(null, CgSvg.of("crystalgraphics:ui/icons/definitely-not-here.svg"));
    }

    /** Preloading twice is a no-op, so a caller may warm the same set on every screen open. */
    @Test
    public void preloadingAnAlreadyCachedSetIsHarmless() throws Exception {
        List<String> paths = shippedIconPaths();
        CgSvg.preload(paths).get(30, TimeUnit.SECONDS);
        CgSvg first = CgSvg.of(paths.get(0));
        CgSvg.preload(paths).get(30, TimeUnit.SECONDS);
        assertSame("a second preload replaced a cached document", first, CgSvg.of(paths.get(0)));
    }

    /** Documents are shared across threads, so the lazy mesh must survive concurrent first use. */
    @Test
    public void concurrentFirstUseOfTheLazyMeshIsConsistent() throws Exception {
        List<String> paths = shippedIconPaths();
        CgSvg.preload(paths).get(30, TimeUnit.SECONDS);

        CgSvg document = CgSvg.of(paths.get(0));
        assertNotNull(document);
        List<Thread> racers = new ArrayList<>();
        List<List<CgSvgOps.Op>> seen = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            seen.add(null);
            int slot = i;
            Thread thread = new Thread(() -> seen.set(slot, document.ops()));
            racers.add(thread);
            thread.start();
        }
        for (Thread thread : racers) thread.join(30_000);
        for (List<CgSvgOps.Op> ops : seen) {
            assertSame("ops() handed out two different meshes", seen.get(0), ops);
        }
    }

    /** Twelve icons written once to a temporary folder, loaded by absolute path as CgIO reads any file. */
    private static List<String> shippedIconPaths() throws IOException {
        if (fixtures == null) {
            Path dir = Files.createTempDirectory("cgsvg-preload");
            dir.toFile().deleteOnExit();
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Path file = dir.resolve("icon" + i + ".svg");
                Files.write(file, ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\""
                        + (2 + i * 0.5f) + "\" fill=\"currentColor\"/></svg>").getBytes(StandardCharsets.UTF_8));
                file.toFile().deleteOnExit();
                paths.add(file.toAbsolutePath().toString());
            }
            fixtures = List.copyOf(paths);
        }
        return fixtures;
    }

    private static List<String> fixtures;
}
