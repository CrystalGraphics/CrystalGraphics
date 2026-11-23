package com.crystalgraphics.mc.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.mc.render.world.CgPocketLevel;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.view.CgViewPicture;
import com.crystalgraphics.text.atlas.packing.MaxRectsPacker;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

/**
 * The draw point: where Minecraft draws every {@link CgHostPicture} shown, in one batch at the head of each GUI firing,
 * before the engine's device section opens, so Minecraft's code runs where it always runs and with its own state.
 * Pictures are cells on shared pages, each page one host call: Unreal's {@code FAssetThumbnailPool::Tick}, Mojang's
 * {@code GuiItemAtlas}.
 *
 * <pre>{@code
 * // A host's GUI hook, before CgDeviceSection.begin():
 * CgHostPictures.drawPoint();
 *
 * // An era's host, at client start:
 * CgPlatform.provide(CgHostPictures.HOST, new PicturesModern());
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, outside any device section: a checked run ({@code crystalgraphics.pictures.check}, on in
 *       a dev run) refuses a draw point inside one by name.</li>
 *   <li>First draws of a frame stop at {@code crystalgraphics.pictures.firstDrawMs} (2 ms); the rest land the next
 *       frame. Redraws of pictures already shown are not budgeted: vanilla redraws its own every frame.</li>
 *   <li>A picture whose draw throws is marked failed, logged once with what draws it, and shows Minecraft's missing
 *       texture until a resource reload retries it. The others still draw.</li>
 *   <li>{@link #stats} is the last draw point's numbers; the same go to the trace as counters on
 *       {@code crystalgraphics.pictures}.</li>
 * </ul>
 */
public final class CgHostPictures {

    private static final Logger LOGGER = LogManager.getLogger("CgHostPictures");

    /**
     * What each era fills: targets of the host's own, and Minecraft's GUI pointed at a cell of one. Its absent value
     * draws nothing, and every picture stays pending.
     *
     * <pre>{@code
     * final class PicturesModern implements CgHostPictures.Host {
     *     public Object createTarget(int w, int h, float clear) { ...a TextureTarget, cleared... }
     *     public void drawPage(Object target, int w, int h, float clear, CgHostPictures.Cells cells) {
     *         ...bind it once; per cell clear its rect, point the GUI at it, cells.draw(i, ctx); bind back...
     *     }
     *     ...
     * }
     * }</pre>
     */
    public interface Host {

        /** Why pictures do not draw here, or null where they do. */
        @Nullable
        String absent();

        /**
         * Whether a picture page draws twice, over black and over white, its alpha taken from the two: where the GUI
         * writes alpha raw so that one draw over a clear page is wrong. False where one draw is right -- a single layer's
         * raw alpha over {@code (0, 0, 0, 0)} is its coverage -- as every modern GUI from 1.15 and 1.7.10's measure.
         */
        boolean twoClears();

        /**
         * As {@link #twoClears}, for the icon atlas's pages: an item's glint and decorations write alpha raw over the
         * item below them on every immediate GUI, so the atlas may draw twice where pictures draw once.
         */
        default boolean iconsTwoClears() {
            return twoClears();
        }

        /** Whether a target's rows run bottom-up (GL). */
        boolean bottomUp();

        /**
         * A target of the host's own, colour and depth, {@code width} by {@code height} pixels, its colour cleared to
         * {@code (clear, clear, clear, 0)}: what a page holds outside its cells, so a filtered sample at a cell's edge
         * reads that pass's background.
         */
        Object createTarget(int width, int height, float clear);

        /**
         * {@code target}'s colour as a texture the graph reads, asked once per target: a host texture is
         * {@link CgHostPictures#imported}.
         */
        CgTexture texture(Object target);

        /** Gives {@code target} back, with what {@link #texture} answered for it (null where it was never asked). */
        void deleteTarget(Object target, @Nullable CgTexture texture);

        /**
         * Draws {@code cells} into {@code target}, {@code width} by {@code height} pixels, in one go: bound once; per
         * cell its rect (pixels from the top-left) cleared to {@code (clear, clear, clear, 0)} and its depth, the GUI
         * pointed at the rect with {@code width(i) / scale(i)} by {@code height(i) / scale(i)} units, its lighting set,
         * then {@link Cells#draw}; everything it changed put back once, in a {@code finally}. What is outside the
         * cells keeps what it holds, unless {@link #keepsPage} says otherwise.
         *
         * <pre>{@code
         * bind(target);
         * try {
         *     for (int i = 0; i < cells.count(); i++) {
         *         clearRect(cells.x(i), cells.y(i), cells.width(i), cells.height(i), clear);
         *         viewport(cells.x(i), cells.y(i), cells.width(i), cells.height(i));
         *         project(cells.width(i) / cells.scale(i), cells.height(i) / cells.scale(i));
         *         light(cells.lighting(i));
         *         cells.draw(i, ctx);       // never throws: a failure is the picture's
         *     }
         * } finally {
         *     bindBack();
         * }
         * }</pre>
         */
        void drawPage(Object target, int width, int height, float clear, Cells cells);

        /**
         * Whether {@link #drawPage} keeps what is outside its cells. Where it cannot, it clears the page whole, and the
         * draw point passes every cell the page holds each time it draws one.
         */
        default boolean keepsPage() {
            return true;
        }

        /**
         * The multiple of pixels a cell drawn at {@code scale} pixels per unit starts on and is sized up to, 1 by
         * default. A host that places cells in whole GUI units answers its GUI scale for {@code scale}: a recorded GUI
         * (1.21.6 on) scissors in whole units. A page holds cells of one alignment.
         */
        default int cellAlign(float scale) {
            return 1;
        }

        /**
         * As {@link #drawPage} for the icon atlas, one drawer over the whole target's units: each of the first
         * {@code count} rects (pixels from the top-left, {@code x, y, w, h} in a row) is cleared to
         * {@code (clear, clear, clear, 0)} and its depth, then {@code drawer} runs. The rest keeps what it holds.
         */
        void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                       CgPictureLighting lighting, CgPictureRenderer drawer);

        /**
         * Whether {@link #drawCells} keeps what is outside its rects. Where it cannot (an era whose device clears only a
         * whole target), it clears the target whole, and the caller draws every cell it shows in the same call.
         */
        default boolean clearsRects() {
            return true;
        }

        /** The largest side, in pixels, a target of the host's may have. */
        default int maxTargetSize() {
            return 4096;
        }

        /** Before a batch; with {@code check}, notes the state {@link #endBatch} compares. */
        default void beginBatch(boolean check) {
        }

        /** After a batch: what of Minecraft's state it left changed, where {@link #beginBatch} was asked to check; else null. */
        @Nullable
        default String endBatch() {
            return null;
        }

        /** Draws nothing. */
        Host NONE = new Host() {
            @Override public String absent() { return "no host draws Minecraft's objects here"; }
            @Override public boolean twoClears() { return false; }
            @Override public boolean bottomUp() { return false; }
            @Override public Object createTarget(int width, int height, float clear) { throw new UnsupportedOperationException(); }
            @Override public CgTexture texture(Object target) { throw new UnsupportedOperationException(); }
            @Override public void deleteTarget(Object target, CgTexture texture) {}
            @Override
            public void drawPage(Object target, int width, int height, float clear, Cells cells) {
                throw new UnsupportedOperationException();
            }
            @Override
            public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                                  CgPictureLighting lighting, CgPictureRenderer drawer) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * The cells one {@link Host#drawPage} call draws: where each is, its units and light, and its draw. Filled by the
     * draw point; a host reads it and calls {@link #draw} once per cell, after pointing the GUI at the cell.
     *
     * <ul>
     *   <li>{@link #draw} never throws: a picture that throws is marked failed and logged, and the next cell draws.</li>
     *   <li>A first draw past the frame's budget does nothing: its cell stays cleared, and it lands the next frame. The
     *       white pass skips what the black one skipped.</li>
     * </ul>
     */
    public static final class Cells {

        private CgHostPicture.Size[] sizes = new CgHostPicture.Size[16];
        private boolean[] skipped = new boolean[16], failed = new boolean[16];
        private int count, pass;
        private boolean whole;

        Cells() {
        }

        /** How many cells. */
        public int count() {
            return count;
        }

        /** Whether these are every cell the page holds: the page may be cleared whole rather than cell by cell. */
        public boolean whole() {
            return whole;
        }

        /** Cell {@code i}'s left, in pixels from the target's left. */
        public int x(int i) {
            return sizes[i].x;
        }

        /** Cell {@code i}'s top, in pixels from the target's top. */
        public int y(int i) {
            return sizes[i].y;
        }

        /** Cell {@code i}'s width in pixels, its bleed included. */
        public int width(int i) {
            return CgHostPicture.cellWidth(sizes[i]);
        }

        /** @see #width */
        public int height(int i) {
            return CgHostPicture.cellHeight(sizes[i]);
        }

        /** Cell {@code i}'s pixels per GUI unit. */
        public float scale(int i) {
            return sizes[i].scale;
        }

        /** The light Minecraft sets up for cell {@code i}. */
        public CgPictureLighting lighting(int i) {
            return sizes[i].owner.lighting();
        }

        /** Draws cell {@code i}'s picture with Minecraft's code, the GUI pointed at the cell: render thread, never throws. */
        public void draw(int i, CgHostDrawContext ctx) {
            CgHostPicture.Size s = sizes[i];
            CgHostPicture p = s.owner;
            boolean first = s.image == null;
            if (pass == 0) {
                skipped[i] = first && !BUDGET.firstAllowed();
                failed[i] = false;
                if (skipped[i]) {
                    BUDGET.deferred(1);
                    return;
                }
            } else if (skipped[i] || failed[i]) {
                return;
            }
            long zone = CgTrace.begin(CgChannels.PICTURES, p.traceZone());
            long start = pass == 0 && first ? System.nanoTime() : 0L;
            try {
                p.drawer.draw(ctx);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                failed[i] = true;
                fail(p, t);
            } finally {
                CgTrace.end(zone);
            }
            if (pass != 0 || failed[i]) return;
            if (first) BUDGET.firstDrawn(System.nanoTime() - start, 1);
            else BUDGET.redrawn(1);
        }

        void clear() {
            Arrays.fill(sizes, 0, count, null);
            count = 0;
        }

        void add(CgHostPicture.Size s) {
            if (count == sizes.length) {
                sizes = Arrays.copyOf(sizes, count * 2);
                skipped = Arrays.copyOf(skipped, count * 2);
                failed = Arrays.copyOf(failed, count * 2);
            }
            sizes[count++] = s;
        }

        // After a page's passes: each cell drawn shows its new image
        void finish(Page page, Host host, long frame) {
            for (int i = 0; i < count; i++) {
                CgHostPicture.Size s = sizes[i];
                s.moved = false;
                if (skipped[i] || failed[i]) continue;
                CgPicture.Image image = s.image;
                if (image == null || s.imagePage != page || s.imageX != s.x || s.imageY != s.y) {
                    image = page.image(s, host.bottomUp());
                    s.imagePage = page;
                    s.imageX = s.x;
                    s.imageY = s.y;
                }
                synchronized (s.owner) {
                    s.owner.drawn(s, frame, image);
                }
            }
        }
    }

    /**
     * What draws at the draw point besides pictures, in the same batch and budget: the item icon atlas. Internal to
     * {@code mc.render}'s kinds; a mod draws through a kind, never a batch of its own.
     */
    public interface Batch {

        /** Whether it has anything to draw or give back this frame: false costs the draw point nothing. */
        boolean due();

        /** Draws what is due. Render thread, inside the batch; the host is present. */
        void draw(Host host, long frame, Budget budget);
    }

    /** A batch's first-draw allowance and its numbers, shared by pictures and every {@link Batch}. */
    public static final class Budget {

        private long budget, firstNanos;
        int drawn, firstDraws, deferred;

        Budget() {
        }

        void begin(long budgetNanos) {
            budget = budgetNanos;
            firstNanos = 0L;
            drawn = firstDraws = deferred = 0;
        }

        /** Whether a first draw may still run this frame. */
        public boolean firstAllowed() {
            return firstNanos < budget;
        }

        /** {@code count} first draws, together {@code nanos}. */
        public void firstDrawn(long nanos, int count) {
            firstNanos += nanos;
            firstDraws += count;
            drawn += count;
        }

        /** {@code count} redraws of what is shown already: not budgeted, as vanilla redraws its own every frame. */
        public void redrawn(int count) {
            drawn += count;
        }

        /** {@code count} first draws left for the next frame. */
        public void deferred(int count) {
            deferred += count;
        }

        /** Before a host call inside the batch: its allocation is Minecraft's, not the batch's. */
        public long hostBegin() {
            return ALLOCATION.hostBegin();
        }

        /** After the host call {@link #hostBegin} marked. */
        public void hostEnd(long mark) {
            ALLOCATION.hostEnd(mark);
        }

        /** A draw that threw: counted with the batch's failures. */
        public void failed() {
            failedTotal++;
            CgTrace.add(CgChannels.PICTURES, "pictures-failed", 1);
        }
    }

    /** The era's host; {@link Host#NONE} until one is provided. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_pictures", Host.NONE);

    /**
     * A draw point's numbers.
     *
     * @param frame      the frame it ran in
     * @param held       pictures shown recently enough to keep
     * @param drawn      sizes and icon cells drawn
     * @param firstDraws of those, drawn for the first time
     * @param deferred   first draws left for the next frame by the budget
     * @param failed     pictures failed so far, all told
     * @param nanos      the batch's CPU time
     * @param ourBytes   what the draw point allocated, Minecraft's host calls left out; -1 where unmeasured (its
     *                   channel, {@code crystalgraphics.pictures}, off)
     */
    public record Stats(long frame, int held, int drawn, int firstDraws, int deferred, int failed, long nanos,
                        long ourBytes) {
        static final Stats NONE = new Stats(-1L, 0, 0, 0, 0, 0, 0L, -1L);
    }

    private static final CgVar.Num FIRST_DRAW_MS = CgVar.number("crystalgraphics.pictures.firstDrawMs", 2f, 0f, 1000f,
            "Time a frame spends drawing pictures for the first time, in ms; the rest land the next frame");
    private static final CgVar.Text CHECK = CgVar.text("crystalgraphics.pictures.check", "", CgVar.DEV | CgVar.RESTART,
            "A draw point inside a device section, and Minecraft state a batch leaves changed: throw, log, or off; a dev run throws");

    /** Frames a size may go unshown before its cell is given back. */
    static final int EVICT_FRAMES = 300;
    /** Frames between a cell given back and its rect reused, or its page deleted: a recording may still read it. */
    private static final int RELEASE_AFTER = 3;
    /** A page's side in pixels, where the host allows it; a cell larger than that has a page of its own. */
    private static final int PAGE_SIDE = 2048;
    /** Pixels between cells: a filtered sample at a cell's edge reads none of its neighbour. */
    private static final int GUTTER = 2;
    /** Frames since {@link #stats} was last read within which the draw point still publishes them. */
    private static final int STATS_READ_FRAMES = 60;

    private static final Queue<CgHostPicture> ASKED = new ConcurrentLinkedQueue<>();
    private static final Queue<Release> RELEASED = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<Release> RELEASING = new ArrayDeque<>();
    private static final List<CgHostPicture> HELD = new ArrayList<>();
    private static final List<Page> PAGES = new ArrayList<>();
    private static final CgHostPicture.Size[] DUE = new CgHostPicture.Size[CgHostPicture.MAX_SIZES];
    private static final List<Batch> BATCHES = new CopyOnWriteArrayList<>();
    private static final Budget BUDGET = new Budget();
    private static final Cells CELLS = new Cells();
    private static final CgHostAllocation ALLOCATION = new CgHostAllocation(CgChannels.PICTURES, "pictures-our-bytes");
    /**
     * How often a {@link CgPictureRefresh#TICK} picture is drawn again, a second: the game's tick rate by default; 0
     * draws it every frame it shows, as vanilla draws its own:
     * <pre>{@code
     * CgHostPictures.TICK_HZ.set(0f);   // a pixel comparison against vanilla drawn this frame
     * }</pre>
     */
    public static final CgVar.Num TICK_HZ = CgVar.number("crystalgraphics.pictures.tickHz", 20f, 0f, 1000f,
            "How often a picture refreshed each tick is drawn again, a second; 0 every frame");
    private static final CgPace TICKS = new CgPace();
    private static boolean ticked;
    private static volatile Stats stats = Stats.NONE;
    private static volatile long statsRead = -STATS_READ_FRAMES - 1L;
    // The last batch's numbers, published as Stats only while something reads them
    private static long lastFrame = -1L, lastNanos;
    private static int lastHeld, lastDrawn, lastFirst, lastDeferred;
    private static int failedTotal;
    private static boolean leakLogged;
    /** A resource reload came: failed pictures draw again. */
    private static volatile boolean retry;

    static {
        CgAssetReloader.addListener(() -> retry = true);
    }

    private CgHostPictures() {
    }

    /** Whether this draw point falls on a tick: {@link CgPictureRefresh#TICK} pictures draw again. Render thread. */
    static boolean ticked() {
        return ticked;
    }

    /**
     * Draws every picture due: shown for the first time, or due by its refresh. Render thread, at the head of a GUI
     * firing, before {@code CgDeviceSection.begin}. Nothing is due most frames, and then it costs a queue poll.
     */
    public static void drawPoint() {
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.drawPoint");
        ALLOCATION.begin();
        long ours;
        try {
            drawPointInside();
        } finally {
            ours = ALLOCATION.end();
            CgTrace.end(zone);
        }
        // Published past the measure, and only for a reader: a steady frame nobody reads allocates nothing
        if (lastFrame >= 0L && CgFrameClock.frameIndex() - statsRead <= STATS_READ_FRAMES && stats.frame() != lastFrame) {
            stats = new Stats(lastFrame, lastHeld, lastDrawn, lastFirst, lastDeferred, failedTotal, lastNanos, ours);
        }
    }

    private static void drawPointInside() {
        // Pocket levels first, then the view pictures drawn over them: what a UI composites later in this firing
        if (!CgDeviceSection.open()) {
            // Each keeps its own allocation measure: left out of this one's
            long mark = ALLOCATION.hostBegin();
            CgPocketLevel.drawPoint(CgStageHost.frame(CgStageHost.WORLD_OPAQUE).partialTick());
            CgViewPicture.drawPoint();
            ALLOCATION.hostEnd(mark);
        }
        for (CgHostPicture p; (p = ASKED.poll()) != null; ) HELD.add(p);
        if (HELD.isEmpty() && RELEASED.isEmpty() && RELEASING.isEmpty() && !batchDue()) return;
        if (CgDeviceSection.open()) {
            refuse("CgHostPictures.drawPoint() inside a device section: Minecraft's code would run with the engine's "
                    + "state bound. Call it before CgDeviceSection.begin()");
            return;
        }
        Host host = CgPlatform.get(HOST);
        long frame = CgFrameClock.frameIndex();
        ticked = TICKS.step(System.nanoTime(), TICK_HZ.get());
        releaseDue(host, frame);
        if (host.absent() != null) return;
        if (retry) {
            retry = false;
            for (CgHostPicture p : HELD) {
                synchronized (p) {
                    p.retry();
                }
            }
        }
        long start = System.nanoTime();
        Budget budget = BUDGET;
        budget.begin((long) (FIRST_DRAW_MS.get() * 1_000_000L));
        boolean check = check() != 0;
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.batch");
        // The batch's own host calls are Minecraft's work too (1.21.6 - 26.1: its fog buffer's frame)
        long mark = ALLOCATION.hostBegin();
        long step = CgTrace.begin(CgChannels.PICTURES, "pictures.begin");
        host.beginBatch(check);
        CgTrace.end(step);
        ALLOCATION.hostEnd(mark);
        try {
            step = CgTrace.begin(CgChannels.PICTURES, "pictures.collect");
            collectDue(host, frame);
            CgTrace.end(step);
            for (int i = 0; i < PAGES.size(); i++) drawPage(host, PAGES.get(i), frame);
            for (int i = 0; i < BATCHES.size(); i++) {
                Batch b = BATCHES.get(i);
                if (b.due()) b.draw(host, frame, budget);
            }
        } finally {
            mark = ALLOCATION.hostBegin();
            long end = CgTrace.begin(CgChannels.PICTURES, "pictures.end");
            String leak = host.endBatch();
            CgTrace.end(end);
            ALLOCATION.hostEnd(mark);
            CgTrace.end(zone);
            if (leak != null) leaked(leak);
        }
        lastFrame = frame;
        lastNanos = System.nanoTime() - start;
        lastHeld = HELD.size();
        lastDrawn = budget.drawn;
        lastFirst = budget.firstDraws;
        lastDeferred = budget.deferred;
        CgTrace.counter(CgChannels.PICTURES, "pictures-drawn", budget.drawn);
        CgTrace.counter(CgChannels.PICTURES, "pictures-first-draws", budget.firstDraws);
        CgTrace.counter(CgChannels.PICTURES, "pictures-deferred", budget.deferred);
        CgTrace.counter(CgChannels.PICTURES, "pictures-held", HELD.size());
        CgTrace.counter(CgChannels.PICTURES, "pictures-pages", PAGES.size());
    }

    // Each held picture's sizes due this frame, placed on a page and listed there; sizes unshown long enough given back
    private static void collectDue(Host host, long frame) {
        for (int i = HELD.size() - 1; i >= 0; i--) {
            CgHostPicture p = HELD.get(i);
            int due = 0;
            synchronized (p) {
                for (int k = p.sizeCount - 1; k >= 0; k--) {
                    CgHostPicture.Size s = p.sizes[k];
                    if (s.shownFrame < frame - EVICT_FRAMES) {
                        release(s);
                        p.sizes[k] = p.sizes[--p.sizeCount];
                        p.sizes[p.sizeCount] = null;
                    } else if (p.due(s, frame)) {
                        DUE[due++] = s;
                    }
                }
                if (p.sizeCount == 0) {
                    p.held = false;
                    HELD.set(i, HELD.get(HELD.size() - 1));
                    HELD.remove(HELD.size() - 1);
                    continue;
                }
            }
            for (int k = 0; k < due; k++) {
                CgHostPicture.Size s = DUE[k];
                DUE[k] = null;
                if (s.page == null && !place(host, s)) continue;
                s.page.due.add(s);
            }
        }
    }

    // One host call a page (two where the GUI writes alpha raw): its due cells, the cells a repack moved, and every
    // cell where the host clears the page whole
    private static void drawPage(Host host, Page page, long frame) {
        Cells cells = CELLS;
        cells.clear();
        if (host.keepsPage()) {
            for (int i = 0; i < page.due.size(); i++) cells.add(page.due.get(i));
            for (int i = 0; i < page.sizes.size(); i++) {
                CgHostPicture.Size s = page.sizes.get(i);
                if (s.moved && !page.due.contains(s)) cells.add(s);
            }
        } else if (!page.due.isEmpty() || page.anyMoved()) {
            for (int i = 0; i < page.sizes.size(); i++) cells.add(page.sizes.get(i));
        }
        page.due.clear();
        if (cells.count() == 0) return;
        cells.whole = cells.count() == page.sizes.size();
        long mark = ALLOCATION.hostBegin();
        try {
            cells.pass = 0;
            host.drawPage(page.target, page.side, page.side, 0f, cells);
            if (page.white != null) {
                cells.pass = 1;
                host.drawPage(page.white, page.side, page.side, 1f, cells);
            }
        } finally {
            ALLOCATION.hostEnd(mark);
        }
        cells.finish(page, host, frame);
        cells.clear();
    }

    // A size's cell on a page of its alignment: the first it fits, else one repacked without what was given back, else
    // a new one
    private static boolean place(Host host, CgHostPicture.Size s) {
        int w = CgHostPicture.cellWidth(s), h = CgHostPicture.cellHeight(s);
        int side = Math.min(PAGE_SIDE, host.maxTargetSize());
        int align = Math.max(1, host.cellAlign(s.scale));
        try {
            if (w > side || h > side) {
                Page own = new Page(host, Page.units(Math.max(w, h), align) * align, align);
                PAGES.add(own);
                return own.insert(s);
            }
            for (int i = 0; i < PAGES.size(); i++) {
                Page page = PAGES.get(i);
                if (page.side == side && page.align == align && page.insert(s)) return true;
            }
            for (int i = 0; i < PAGES.size(); i++) {
                Page page = PAGES.get(i);
                if (page.side == side && page.align == align && page.freed > 0 && page.repack(s)) return true;
            }
            Page page = new Page(host, side, align);
            PAGES.add(page);
            return page.insert(s);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            fail(s.owner, t);
            return false;
        }
    }

    // A picture whose draw threw: missing until a resource reload, logged once
    private static void fail(CgHostPicture p, Throwable t) {
        boolean log;
        synchronized (p) {
            p.failed();
            log = !p.logged;
            p.logged = true;
        }
        BUDGET.failed();
        if (log) LOGGER.error("[cg] picture '{}' failed to draw ({}): it shows the missing texture until a resource "
                + "reload", p.name(), p.drawnBy().getClass().getName(), t);
    }

    /**
     * A host texture behind {@code handle} (a GL name, a device texture) as a texture the graph reads: what a Minecraft
     * host's {@link Host#texture} answers. Render thread. Give it back with {@link #forget}.
     */
    public static CgTexture imported(Object handle) {
        return CgTexture2D.borrowed(CgHostImports.get().texture(handle));
    }

    /** Forgets what {@link #imported} made; the host's texture is untouched. */
    public static void forget(@Nullable CgTexture imported) {
        if (imported instanceof CgTexture2D t) t.delete();
    }

    /** Joins {@code batch} to every draw point from now on. */
    public static void join(Batch batch) {
        BATCHES.add(batch);
    }

    private static boolean batchDue() {
        for (int i = 0; i < BATCHES.size(); i++) if (BATCHES.get(i).due()) return true;
        return false;
    }

    /** The last draw point's numbers. Any thread; the draw point publishes them while something reads them. */
    public static Stats stats() {
        statsRead = CgFrameClock.frameIndex();
        return stats;
    }

    /** A picture asks to be held: shown, and not held already. Any thread. */
    static void hold(CgHostPicture picture) {
        ASKED.add(picture);
    }

    /** Gives a size's cell back a few frames from now. Any thread. */
    static void release(CgHostPicture.Size size) {
        RELEASED.add(new Release(size, CgFrameClock.frameIndex()));
    }

    private static void releaseDue(Host host, long frame) {
        for (Release r; (r = RELEASED.poll()) != null; ) RELEASING.add(r);
        while (!RELEASING.isEmpty() && RELEASING.peekFirst().frame <= frame - RELEASE_AFTER) {
            CgHostPicture.Size s = RELEASING.pollFirst().size;
            Page page = s.page;
            if (page == null) continue;
            page.remove(s);
            if (page.sizes.isEmpty()) {
                page.delete(host);
                PAGES.remove(page);
            }
        }
    }

    private static void refuse(String what) {
        if (check() == 2) throw new IllegalStateException(what);
        if (!leakLogged) LOGGER.warn("[cg] {}", what);
        leakLogged = true;
    }

    private static void leaked(String what) {
        refuse("pictures.leak: a draw point's batch left Minecraft's state changed: " + what);
    }

    private static int check() {
        return Check.VALUE;
    }

    /** The mode, read at its first use: a restart flag, and the loader is known by then. */
    private static final class Check {
        static final int VALUE = read();

        private static int read() {
            String m = CHECK.get().isEmpty() ? (CgModLoader.development() ? "throw" : "off") : CHECK.get();
            return m.equals("throw") ? 2 : m.equals("log") ? 1 : 0;
        }
    }

    /**
     * A page of cells: the host's target, its white twin where the GUI writes alpha raw, and the sizes packed on it.
     * Render thread.
     */
    static final class Page {
        final int side;
        /** Every cell here starts on a multiple of this many pixels and is sized up to one: the packer counts in them. */
        final int align;
        final Object target;
        @Nullable final Object white;
        final CgTexture targetTexture;
        @Nullable final CgTexture whiteTexture;
        final List<CgHostPicture.Size> sizes = new ArrayList<>();
        /** This batch's due sizes placed here. */
        final List<CgHostPicture.Size> due = new ArrayList<>();
        private MaxRectsPacker packer;
        /** Sizes given back since the last pack: space the packer still counts as used. */
        int freed;

        Page(Host host, int side, int align) {
            this.side = side;
            this.align = align;
            this.packer = packer();
            this.target = host.createTarget(side, side, 0f);
            this.white = host.twoClears() ? host.createTarget(side, side, 1f) : null;
            this.targetTexture = host.texture(target);
            this.whiteTexture = white == null ? null : host.texture(white);
        }

        static int units(int pixels, int align) {
            return (pixels + align - 1) / align;
        }

        private MaxRectsPacker packer() {
            return new MaxRectsPacker(side / align, side / align);
        }

        // s's rect in the packer's units, its position kept in pixels
        @Nullable
        private MaxRectsPacker.PackedRect pack(MaxRectsPacker into, CgHostPicture.Size s) {
            return into.insert(units(CgHostPicture.cellWidth(s), align), units(CgHostPicture.cellHeight(s), align),
                    units(GUTTER, align), s);
        }

        boolean insert(CgHostPicture.Size s) {
            MaxRectsPacker.PackedRect r = pack(packer, s);
            if (r == null) return false;
            s.page = this;
            s.x = r.x() * align;
            s.y = r.y() * align;
            sizes.add(s);
            return true;
        }

        // Packs what lives here afresh, largest first, with extra: every size that moved draws this batch. False keeps
        // the page as it was.
        boolean repack(CgHostPicture.Size extra) {
            List<CgHostPicture.Size> all = new ArrayList<>(sizes);
            all.add(extra);
            all.sort(Comparator.comparingInt((CgHostPicture.Size s) -> CgHostPicture.cellWidth(s) * CgHostPicture.cellHeight(s))
                    .reversed());
            MaxRectsPacker fresh = packer();
            int[] at = new int[all.size() * 2];
            for (int i = 0; i < all.size(); i++) {
                MaxRectsPacker.PackedRect r = pack(fresh, all.get(i));
                if (r == null) return false;
                at[2 * i] = r.x() * align;
                at[2 * i + 1] = r.y() * align;
            }
            packer = fresh;
            freed = 0;
            for (int i = 0; i < all.size(); i++) {
                CgHostPicture.Size s = all.get(i);
                if (s != extra && (s.x != at[2 * i] || s.y != at[2 * i + 1])) s.moved = true;
                s.x = at[2 * i];
                s.y = at[2 * i + 1];
            }
            extra.page = this;
            sizes.add(extra);
            return true;
        }

        void remove(CgHostPicture.Size s) {
            sizes.remove(s);
            due.remove(s);
            s.page = null;
            freed++;
        }

        boolean anyMoved() {
            for (int i = 0; i < sizes.size(); i++) if (sizes.get(i).moved) return true;
            return false;
        }

        // Size s's image: its cell of the page (and of the white twin), its bleed outside the rect
        CgPicture.Image image(CgHostPicture.Size s, boolean bottomUp) {
            float u0 = (float) s.x / side, u1 = (float) (s.x + CgHostPicture.cellWidth(s)) / side;
            float top = (float) s.y / side, bottom = (float) (s.y + CgHostPicture.cellHeight(s)) / side;
            float v0 = bottomUp ? 1f - top : top, v1 = bottomUp ? 1f - bottom : bottom;
            CgPicture.Image image = whiteTexture == null ? CgPicture.Image.of(targetTexture, u0, v0, u1, v1)
                    : CgPicture.Image.matted(targetTexture, whiteTexture, u0, v0, u1, v1);
            if (s.pad > 0) {
                float ox = (float) s.pad / s.width, oy = (float) s.pad / s.height;
                image = image.outset(ox, oy, ox, oy);
            }
            return image;
        }

        void delete(Host host) {
            host.deleteTarget(target, targetTexture);
            if (white != null) host.deleteTarget(white, whiteTexture);
        }
    }

    private record Release(CgHostPicture.Size size, long frame) {
    }
}
