package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.text.cache.CgFontRegistry;

/**
 * Textures, meshes, fonts and other files read ahead of their first use: Unreal's
 * {@code FStreamableManager::RequestAsyncLoad} for what is not an asset of its own. A request names them, a worker
 * decodes each texture (queuing its upload, which lands before the next frame executes) and reads each mesh file, and
 * {@link Request#ready()} says when all of it is done. Pipelines are {@code CgPipelinePrecache}'s; a scene waits on both.
 *
 * <pre>{@code
 * CgPreload.Request arena = CgPreload.request(l -> l.texture(FLOOR_TEXTURE).mesh("mymod:models/arena.glb"));
 * CgPipelinePrecache.Request pipelines = CgPipelinePrecache.request(p -> p.add(FLOOR, false));
 * if (arena.ready() && pipelines.ready()) draw();                    // each frame until both are
 * }</pre>
 *
 * <p>An asset's preparer names the same through its plan ({@code plan.preload(l -> ...)}), which waits for it before
 * the asset is READY.
 *
 * <ul>
 *   <li>The collector runs on the calling thread, where a texture is made from its path (the registry's); only the
 *       reading is a worker's.</li>
 *   <li>A file that will not read is logged and counts as loaded: a texture draws as the checkerboard, a mesh's first
 *       draw reports it again.</li>
 *   <li>A mesh made in code ({@code CgMesh}, {@code CgMeshLods}) has nothing to read: naming one costs nothing.</li>
 * </ul>
 */
public final class CgPreload {

    private static final Logger LOGGER = LogManager.getLogger("CgPreload");

    /** Names what to read into the list it is handed. */
    @FunctionalInterface
    public interface Collector {
        void collect(Loads loads);
    }

    /** What a request reads: each named once however often. */
    public static final class Loads {
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<String> paths = new HashSet<>();
        private final List<Runnable> reads = new ArrayList<>();

        private Loads() {
        }

        /** {@code texture} decoded, its upload queued. */
        public Loads texture(CgTexture texture) {
            if (seen.add(texture)) reads.add(texture::getWidth);
            return this;
        }

        /** A mesh file read, by path, as {@code CgMeshLoader.resolve} reads it; a mesh made in code is skipped. */
        public Loads mesh(Object mesh) {
            if (mesh instanceof String path && paths.add(path)) reads.add(() -> CgMeshLoader.resolve(path));
            return this;
        }

        /**
         * A font opened on the worker and its ASCII glyphs queued to the glyph workers at {@code effectivePx}, the sizes
         * it is drawn at after the pose's scale (none: the distance field alone). The request is ready once the font is
         * open; the glyphs land over the next frames in which nothing on screen waits for one.
         *
         * <pre>{@code
         * CgPreload.request(l -> l.font(() -> CgDefaultFont.unsized(700), 24, 48));   // a title at 1x and 2x
         * }</pre>
         */
        public Loads font(Supplier<CgFont> font, int... effectivePx) {
            int[] sizes = effectivePx.clone();
            reads.add(() -> {
                CgFont f = font.get();
                if (f == null) return;
                // The warmer keys by a size-bound font; the distance field keys by its own scale whatever the size
                CgFontRegistry.get().warmAscii(f.atSize(sizes.length > 0 ? sizes[0] : 32), sizes);
            });
            return this;
        }

        /** Any other reading done on the worker: a sound decoded, a mesh made on first ask, a parse. */
        public Loads read(Runnable read) {
            reads.add(read);
            return this;
        }
    }

    /** A request's reading, on a worker. */
    public static final class Request {
        private final CompletableFuture<Void> done;

        private Request(CompletableFuture<Void> done) {
            this.done = done;
        }

        /** Whether every read is done. Any thread. */
        public boolean ready() {
            return done.isDone();
        }
    }

    private CgPreload() {
    }

    /** Collects {@code collector} now and reads what it named on a worker. */
    public static Request request(Collector collector) {
        Loads loads = new Loads();
        collector.collect(loads);
        if (loads.reads.isEmpty()) return new Request(CompletableFuture.completedFuture(null));
        List<Runnable> reads = loads.reads;
        return new Request(CompletableFuture.runAsync(() -> {
            for (int i = 0; i < reads.size(); i++) {
                try {
                    reads.get(i).run();
                } catch (RuntimeException e) {
                    LOGGER.warn("a preload failed: {}", e.toString());
                }
            }
        }, CgJob.executor(CgJobLane.LATENCY)));
    }
}
