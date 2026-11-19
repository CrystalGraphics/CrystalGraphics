package com.crystalgraphics.render.ui.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;

/**
 * The path cache: coverage rasters of small paths in one atlas the process shares, so an icon drawn every frame by any
 * canvas on any thread is rasterised once. A cell is a path's exact-area coverage at one scale, white with its area
 * in alpha, tinted or painted at draw; it is placed 1:1 on the pixel grid.
 *
 * <p><b>The publication rule</b>, a guarantee for canvases recording on several threads: a cell is drawn only by the
 * canvas that claimed it until its build has executed, when a callback recorded after the build opens it to every
 * canvas. Another canvas meeting a pending cell rasterises the path for its own frame instead. The atlas is the same:
 * a new generation is cleared by the recording that made it, and nobody else claims a cell in it until that clear has
 * executed. When the atlas fills, a new generation replaces it, so a recording made before still draws from the
 * texture its cells name; the one before that is released by the canvas that caused the reset.</p>
 *
 * <pre>{@code
 * CgPathCache.Entry cell = CgPathCache.SHARED.find(key);
 * if (cell == null || !cell.ready() && cell.owner() != me) cell = CgPathCache.SHARED.claim(key, w, h, me);
 * // claimed with needsBuild(): record the clear (clears()), the build, then a callback calling ready(cell)
 * }</pre>
 *
 * <p>Ported from CrystalGUI's {@code SvgAtlas}, keyed by path in place of document.</p>
 */
public final class CgPathCache {

    /** The process's cache. */
    public static final CgPathCache SHARED = new CgPathCache();

    /** The atlas's side, in pixels. */
    public static final int SIZE = 1024;
    /** Paths larger than this on either side, in device pixels, are rasterised for their frame alone. */
    public static final int MAX_PX = 128;
    /** Empty pixels round a raster, so its antialiased edge and its sampling never meet a neighbour. */
    public static final int PAD = 2;
    /** Coverage sums in alpha: a half float keeps the exact areas. */
    public static final CgFormat FORMAT = CgFormat.RGBA16_FLOAT;

    /** A raster's identity: the path, its stroke (null for a fill) and the device scale it is drawn at. */
    public record Key(CgVectorPath path, @Nullable CgStroke stroke, float scaleX, float scaleY) {
    }

    /** One texture of cells, and whether its clear has executed. */
    public static final class Generation {
        private final CgGraphTexture texture;
        private final Object owner;
        private volatile boolean ready;

        Generation(CgGraphTexture texture, Object owner) {
            this.texture = texture;
            this.owner = owner;
        }

        public CgGraphTexture texture() {
            return texture;
        }
    }

    /** One rasterised path: its place in the atlas, and where the path's origin sits inside it, in device pixels. */
    public static final class Entry {
        private Generation atlas;
        private int x, y, width, height;
        private float originX, originY;
        private volatile boolean ready;
        private Object owner;
        private boolean needsBuild, clears;
        @Nullable private CgGraphTexture releases;

        public CgGraphTexture texture() {
            return atlas.texture;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** Where the path's origin lies from the cell's corner, in device pixels; set by its builder. */
        public float originX() {
            return originX;
        }

        public float originY() {
            return originY;
        }

        public void origin(float x, float y) {
            originX = x;
            originY = y;
        }

        /** Its build has executed: any canvas may draw it. */
        public boolean ready() {
            return ready;
        }

        public Object owner() {
            return owner;
        }

        /** For its builder, once: it has yet to record the build. Answers and forgets. */
        public boolean takeBuild() {
            boolean b = needsBuild;
            needsBuild = false;
            return b;
        }

        /** For its builder, once: its generation is new, and its clear is recorded first. Answers and forgets. */
        public boolean takeClear() {
            boolean c = clears;
            clears = false;
            return c;
        }

        /** For its builder, once: a generation it replaced, to release in its recording; null when none. */
        @Nullable
        public CgGraphTexture takeRelease() {
            CgGraphTexture r = releases;
            releases = null;
            return r;
        }

        public Generation generation() {
            return atlas;
        }
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final List<CgGraphTexture> lived = new ArrayList<>();
    @Nullable private Generation current;
    @Nullable private CgGraphTexture previous;
    private int shelfX, shelfY, shelfHeight, generations;
    private volatile int resets;
    private volatile int claims;

    private CgPathCache() {
    }

    /** The cell for {@code key}, built or pending; null when there is none. Lock-free. */
    @Nullable
    public Entry find(Key key) {
        return entries.get(key);
    }

    /**
     * The cell for {@code key}, placed if no one has: {@code by} is its builder when {@link Entry#takeBuild} answers
     * true. Null when the atlas is another canvas's to clear first.
     */
    @Nullable
    public synchronized Entry claim(Key key, int width, int height, Object by) {
        Entry existing = entries.get(key);
        if (existing != null) return existing;
        if (width > SIZE || height > SIZE) throw new IllegalArgumentException("a path raster " + width + "x" + height + " past the atlas");
        CgGraphTexture released = null;
        boolean clears = false;
        if (current == null) {
            current = made(by);
            clears = true;
        } else if (!current.ready && current.owner != by) {
            return null;
        }
        if (shelfX + width > SIZE) {
            shelfY += shelfHeight;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + height > SIZE) {
            released = previous;
            lived.remove(released);
            previous = current.texture;
            current = made(by);
            clears = true;
            entries.clear();
            shelfX = shelfY = shelfHeight = 0;
            resets++;
        }
        Entry entry = new Entry();
        entry.atlas = current;
        entry.x = shelfX;
        entry.y = shelfY;
        entry.width = width;
        entry.height = height;
        entry.owner = by;
        entry.needsBuild = true;
        entry.clears = clears;
        entry.releases = released;
        shelfX += width;
        shelfHeight = Math.max(shelfHeight, height);
        entries.put(key, entry);
        claims++;
        return entry;
    }

    private Generation made(Object by) {
        CgGraphTexture texture = CgGraphTexture.requested("cg_path_atlas_" + generations++, CgTextureDesc.of(SIZE, SIZE, FORMAT));
        lived.add(texture);
        return new Generation(texture, by);
    }

    /** Render thread: the generation's clear has executed. */
    public void ready(Generation generation) {
        generation.ready = true;
    }

    /** Render thread: the build of {@code entry} has executed. */
    public void ready(Entry entry) {
        entry.ready = true;
    }

    /**
     * Whatever {@code by} claimed and never executed goes: its frame was abandoned, so nothing will make its cells
     * ready, and everyone else would wait for them.
     */
    public synchronized void abandon(Object by) {
        entries.values().removeIf(entry -> entry.owner == by && !entry.ready);
        if (current != null && !current.ready && current.owner == by) {
            current = null;
            entries.clear();
            shelfX = shelfY = shelfHeight = 0;
        }
    }

    /** Times the atlas filled and its paths were placed again: what a kept chunk drawing from it is stale past. */
    public int resets() {
        return resets;
    }

    /** Cells claimed so far, each built once by its claimer. */
    public int claims() {
        return claims;
    }

    /** The graphics context is going: every generation freed. Render thread. */
    public synchronized void destroy() {
        for (CgGraphTexture texture : lived) CgExecutor.freeStorage(texture);
        lived.clear();
        entries.clear();
        current = null;
        previous = null;
        shelfX = shelfY = shelfHeight = 0;
        resets++;
    }
}
