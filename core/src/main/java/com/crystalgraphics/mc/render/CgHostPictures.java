package com.crystalgraphics.mc.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

/**
 * The draw point: where Minecraft draws every {@link CgHostPicture} shown, in one batch at the head of each GUI firing,
 * before the engine's device section opens, so Minecraft's code runs where it always runs and with its own state.
 * Unreal's {@code FAssetThumbnailPool::Tick}.
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
     * What each era fills: targets of the host's own and Minecraft's GUI pointed at one. Its absent value draws nothing,
     * and every picture stays pending.
     *
     * <pre>{@code
     * final class PicturesModern implements CgHostPictures.Host {
     *     public Object createTarget(int w, int h) { return new TextureTarget(w, h, true, Minecraft.ON_OSX); }
     *     public void draw(Object target, int w, int h, float scale, float clear, CgPictureLighting light,
     *                      CgPictureRenderer drawer) { ...bind it, draw, put everything back... }
     *     ...
     * }
     * }</pre>
     */
    public interface Host {

        /** Why pictures do not draw here, or null where they do. */
        @Nullable
        String absent();

        /**
         * Whether Minecraft's GUI writes alpha raw here (every immediate-mode GUI, 1.7.10 to 1.21.5): each picture
         * draws twice, over black and over white, and its alpha is taken from the two.
         */
        boolean twoClears();

        /** Whether a target's rows run bottom-up (GL). */
        boolean bottomUp();

        /** A target of the host's own, colour and depth, {@code width} by {@code height} pixels. */
        Object createTarget(int width, int height);

        /**
         * {@code target}'s colour as a texture the graph reads, asked once per target: a host texture is
         * {@link CgHostPictures#imported}.
         */
        CgTexture texture(Object target);

        /** Gives {@code target} back, with what {@link #texture} answered for it (null where it was never asked). */
        void deleteTarget(Object target, @Nullable CgTexture texture);

        /**
         * Draws into {@code target}: clears its colour to {@code (clear, clear, clear, 0)} and its depth, points the GUI
         * at it with {@code width / scale} by {@code height / scale} units, sets up {@code lighting}, runs
         * {@code drawer}, and puts back all it changed, in a {@code finally}.
         */
        void draw(Object target, int width, int height, float scale, float clear, CgPictureLighting lighting,
                  CgPictureRenderer drawer);

        /**
         * As {@link #draw}, keeping what is outside {@code rects}: each of the first {@code count} rects (pixels from the
         * top-left, {@code x, y, w, h} in a row) is cleared to {@code (clear, clear, clear, 0)} and its depth, then
         * {@code drawer} runs over the whole target's units. The rest of the target keeps what it holds: an icon page.
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
            @Override public Object createTarget(int width, int height) { throw new UnsupportedOperationException(); }
            @Override public CgTexture texture(Object target) { throw new UnsupportedOperationException(); }
            @Override public void deleteTarget(Object target, CgTexture texture) {}
            @Override
            public void draw(Object target, int width, int height, float scale, float clear, CgPictureLighting lighting,
                             CgPictureRenderer drawer) {
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
     */
    public record Stats(long frame, int held, int drawn, int firstDraws, int deferred, int failed, long nanos) {
        static final Stats NONE = new Stats(-1L, 0, 0, 0, 0, 0, 0L);
    }

    private static final CgVar.Num FIRST_DRAW_MS = CgVar.number("crystalgraphics.pictures.firstDrawMs", 2f, 0f, 1000f,
            "Time a frame spends drawing pictures for the first time, in ms; the rest land the next frame");
    private static final CgVar.Text CHECK = CgVar.text("crystalgraphics.pictures.check", "", CgVar.DEV | CgVar.RESTART,
            "A draw point inside a device section, and Minecraft state a batch leaves changed: throw, log, or off; a dev run throws");

    /** Frames a size may go unshown before its targets are given back. */
    static final int EVICT_FRAMES = 300;
    /** Frames between a size given back and its targets deleted: a recording may still hold its image. */
    private static final int RELEASE_AFTER = 3;

    private static final Queue<CgHostPicture> ASKED = new ConcurrentLinkedQueue<>();
    private static final Queue<Release> RELEASED = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<Release> RELEASING = new ArrayDeque<>();
    private static final List<CgHostPicture> HELD = new ArrayList<>();
    private static final CgHostPicture.Size[] DUE = new CgHostPicture.Size[CgHostPicture.MAX_SIZES];
    private static final List<Batch> BATCHES = new CopyOnWriteArrayList<>();
    private static final Budget BUDGET = new Budget();
    private static volatile Stats stats = Stats.NONE;
    private static int failedTotal;
    private static boolean leakLogged;
    /** A resource reload came: failed pictures draw again. */
    private static volatile boolean retry;

    static {
        CgAssetReloader.addListener(() -> retry = true);
    }

    private CgHostPictures() {
    }

    /**
     * Draws every picture due: shown for the first time, or due by its refresh. Render thread, at the head of a GUI
     * firing, before {@code CgDeviceSection.begin}. Nothing is due most frames, and then it costs a queue poll.
     */
    public static void drawPoint() {
        for (CgHostPicture p; (p = ASKED.poll()) != null; ) HELD.add(p);
        if (HELD.isEmpty() && RELEASED.isEmpty() && RELEASING.isEmpty() && !batchDue()) return;
        if (CgDeviceSection.open()) {
            refuse("CgHostPictures.drawPoint() inside a device section: Minecraft's code would run with the engine's "
                    + "state bound. Call it before CgDeviceSection.begin()");
            return;
        }
        Host host = CgPlatform.get(HOST);
        long frame = CgFrameClock.frameIndex();
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
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.drawPoint");
        host.beginBatch(check);
        try {
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
                    boolean isFirst = s.image == null;
                    if (isFirst && !budget.firstAllowed()) {
                        budget.deferred(1);
                        continue;
                    }
                    long t = System.nanoTime();
                    if (!draw(host, p, s, frame)) {
                        while (++k < due) DUE[k] = null;
                        break;
                    }
                    if (isFirst) budget.firstDrawn(System.nanoTime() - t, 1);
                    else budget.redrawn(1);
                }
            }
            for (int i = 0; i < BATCHES.size(); i++) {
                Batch b = BATCHES.get(i);
                if (b.due()) b.draw(host, frame, budget);
            }
        } finally {
            String leak = host.endBatch();
            CgTrace.end(zone);
            if (leak != null) leaked(leak);
        }
        long nanos = System.nanoTime() - start;
        stats = new Stats(frame, HELD.size(), budget.drawn, budget.firstDraws, budget.deferred, failedTotal, nanos);
        CgTrace.counter(CgChannels.PICTURES, "pictures-drawn", budget.drawn);
        CgTrace.counter(CgChannels.PICTURES, "pictures-first-draws", budget.firstDraws);
        CgTrace.counter(CgChannels.PICTURES, "pictures-deferred", budget.deferred);
        CgTrace.counter(CgChannels.PICTURES, "pictures-held", HELD.size());
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

    /** The last draw point's numbers. Any thread. */
    public static Stats stats() {
        return stats;
    }

    /** Draws one size; false where the picture failed. */
    private static boolean draw(Host host, CgHostPicture p, CgHostPicture.Size s, long frame) {
        boolean two = host.twoClears();
        try {
            int w = s.width + 2 * s.pad, h = s.height + 2 * s.pad;
            if (s.target == null) s.target = host.createTarget(w, h);
            if (two && s.white == null) s.white = host.createTarget(w, h);
            CgPictureLighting lighting = p.lighting();
            host.draw(s.target, w, h, s.scale, 0f, lighting, p.drawer);
            if (two) host.draw(s.white, w, h, s.scale, 1f, lighting, p.drawer);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            boolean log;
            synchronized (p) {
                p.failed();
                log = !p.logged;
                p.logged = true;
            }
            BUDGET.failed();
            if (log) LOGGER.error("[cg] picture '{}' failed to draw ({}): it shows the missing texture until a resource "
                    + "reload", p.name(), p.drawnBy().getClass().getName(), t);
            return false;
        }
        CgPicture.Image image = s.image;
        if (image == null) {
            CgTexture black = s.targetTexture = host.texture(s.target);
            CgTexture white = two ? s.whiteTexture = host.texture(s.white) : null;
            float v0 = host.bottomUp() ? 1f : 0f, v1 = 1f - v0;
            image = white == null ? CgPicture.Image.of(black, 0f, v0, 1f, v1)
                    : CgPicture.Image.matted(black, white, 0f, v0, 1f, v1);
            if (s.pad > 0) {
                float ox = (float) s.pad / s.width, oy = (float) s.pad / s.height;
                image = image.outset(ox, oy, ox, oy);
            }
        }
        synchronized (p) {
            p.drawn(s, frame, image);
        }
        return true;
    }

    /** A picture asks to be held: shown, and not held already. Any thread. */
    static void hold(CgHostPicture picture) {
        ASKED.add(picture);
    }

    /** Gives a size's targets back a few frames from now. Any thread. */
    static void release(CgHostPicture.Size size) {
        if (size.target != null || size.white != null) RELEASED.add(new Release(size, CgFrameClock.frameIndex()));
    }

    private static void releaseDue(Host host, long frame) {
        for (Release r; (r = RELEASED.poll()) != null; ) RELEASING.add(r);
        while (!RELEASING.isEmpty() && RELEASING.peekFirst().frame <= frame - RELEASE_AFTER) {
            CgHostPicture.Size s = RELEASING.pollFirst().size;
            if (s.target != null) host.deleteTarget(s.target, s.targetTexture);
            if (s.white != null) host.deleteTarget(s.white, s.whiteTexture);
            s.target = s.white = null;
            s.targetTexture = s.whiteTexture = null;
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

    private record Release(CgHostPicture.Size size, long frame) {
    }
}
