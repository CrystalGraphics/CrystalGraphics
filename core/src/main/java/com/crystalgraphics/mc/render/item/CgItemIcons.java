package com.crystalgraphics.mc.render.item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.atlas.CgIconAtlas;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceHints;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * Item icons, as Minecraft draws them: one atlas page per size in device pixels, a cell per look. Mojang's
 * {@code GuiItemAtlas}; Unreal's {@code FAssetThumbnailPool}.
 *
 * <pre>{@code
 * CgItemIcon icon = CgItemIcons.of(stack);              // any thread
 * canvas.picture(icon, x, y, 16, 16);
 *
 * CgItemIcons.prewarm(menu.slots(), Math.round(16 * scale));   // a screen opening: its icons draw at once
 * }</pre>
 *
 * <ul>
 *   <li><b>Keyed by look</b>: stacks that look alike share a cell. The look is the host's: from 1.21.6 Mojang's own model
 *       identity and animated flag; below, the game's hash of item and data with its resolved model, so a clock's frame
 *       changes the look.</li>
 *   <li><b>Drawn when it changes</b>: a cell draws when it gets a slot, and every frame it shows while animated (a
 *       glint, a mod's renderer, an animated sprite). A look the host cannot classify counts as animated: never
 *       wrong, only slower.</li>
 *   <li>First draws share the draw point's budget ({@code crystalgraphics.pictures.firstDrawMs}); {@link #prewarm}
 *       draws past it.</li>
 *   <li>{@link #stats} and the {@code icons-*} counters on {@code crystalgraphics.pictures}; a frame that redraws
 *       icons names one in the trace's {@code ICON-REDRAWN} hint.</li>
 * </ul>
 */
public final class CgItemIcons {

    /**
     * How often an animated icon (a glint, a clock, a mod's renderer) is drawn again, a second: the game's tick rate by
     * default, as a clock and a compass change only on a tick; 0 draws it every frame it shows. Set from the command line
     * ({@code -Dcrystalgraphics.icons.animatedHz=60}) or in code:
     * <pre>{@code
     * CgItemIcons.ANIMATED_HZ.set(0f);   // every frame: a pixel comparison against vanilla drawn this frame
     * float hz = CgItemIcons.ANIMATED_HZ.get();
     * }</pre>
     */
    public static final CgVar.Num ANIMATED_HZ = CgVar.number("crystalgraphics.icons.animatedHz", 20f, 0f, 1000f,
            "How often an animated item icon is drawn again, a second; 0 every frame");

    private static final Logger LOGGER = LogManager.getLogger("CgItemIcons");

    /**
     * What a stack looks like: equal for stacks Minecraft draws alike; whether it animates, and why.
     *
     * @param key      compared with {@code equals}: Mojang's model identity, or the stack's stamp with its model
     * @param animated drawn again while it shows, at {@link #ANIMATED_HZ}
     * @param why      what animates it ({@code "glint"}, {@code "custom renderer"}, {@code "animated sprite"},
     *                 {@code "unknown"}), named in the trace's hint; null when still
     */
    public record Look(Object key, boolean animated, @Nullable String why) {

        /** Still, under {@code key}. */
        public static Look still(Object key) {
            return new Look(key, false, null);
        }
    }

    /**
     * What each era fills: its stacks, their looks, and drawing one as the inventory does.
     *
     * <pre>{@code
     * final class ItemIconsModern implements CgItemIcons.Host {
     *     public Look look(Object stack) { ...the model it resolves to, its foil, its renderer... }
     *     public void draw(CgHostDrawContext ctx, Object stack, int x, int y) {
     *         ((GuiGraphics) ctx.gui()).renderItem((ItemStack) stack, x, y);
     *     }
     * }
     * }</pre>
     */
    public interface Host {

        /** Why icons do not draw here, or null where they do. */
        @Nullable
        String absent();

        /** The host's stack for {@code stack}; null where the game does not know its item. */
        @Nullable
        default Object toHost(CgItemStack stack) {
            return CgItems.toHost(stack);
        }

        /** What {@code hostStack} looks like now. Render thread, at the draw point. */
        Look look(Object hostStack);

        /**
         * Draws {@code hostStack}'s icon, without count or bar, with its top-left at {@code (x, y)} in {@code ctx}'s units,
         * 16 a side, as the inventory draws a slot's item. Render thread, inside a {@link CgHostPictures.Host} draw.
         */
        void draw(CgHostDrawContext ctx, Object hostStack, int x, int y);

        /**
         * What {@code hostStack}'s decorations look like: its count, durability bar, cooldown and mods' decorators; null
         * where it draws none. Render thread, at the draw point.
         */
        @Nullable
        Look overlayLook(Object hostStack);

        /**
         * Draws {@code hostStack}'s decorations alone, as the inventory draws them over a slot's item, the slot's top-left
         * at {@code (x, y)}. Render thread, inside a {@link CgHostPictures.Host} draw.
         */
        void drawOverlay(CgHostDrawContext ctx, Object hostStack, int x, int y);

        /** Draws nothing. */
        Host NONE = new Host() {
            @Override public String absent() { return "no host draws item icons here"; }
            @Override public Object toHost(CgItemStack stack) { return null; }
            @Override public Look look(Object hostStack) { throw new UnsupportedOperationException(); }
            @Override public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
                throw new UnsupportedOperationException();
            }
            @Override public Look overlayLook(Object hostStack) { throw new UnsupportedOperationException(); }
            @Override public void drawOverlay(CgHostDrawContext ctx, Object hostStack, int x, int y) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** The era's host; {@link Host#NONE} until one is provided. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:item_icons", Host.NONE);

    /**
     * A draw point's numbers for icons.
     *
     * @param frame      the frame it ran in
     * @param icons      icons held: shown recently enough to keep
     * @param cells      cells across every page
     * @param pages      pages, one per size in device pixels
     * @param firstDraws cells drawn for the first time this frame
     * @param redraws    cells drawn again this frame, as animated
     * @param deferred   first draws left for the next frame by the budget
     * @param laidTotal  cells drawn into a slot new to them since the game started, first draws and cells laid again
     *                   after their slot was reclaimed or the page grew: a reader a frame apart sees every one
     * @param redrawn    one icon drawn again this frame and why ({@code "minecraft:clock (animated sprite)"}); null
     *                   when none was
     */
    public record Stats(long frame, int icons, int cells, int pages, int firstDraws, int redraws, int deferred,
                        long laidTotal, @Nullable String redrawn) {
        static final Stats NONE = new Stats(-1L, 0, 0, 0, 0, 0, 0, 0L, null);
    }

    /** Frames a size may go unshown before its cell is given back; a page unshown as long is freed. */
    static final int EVICT_FRAMES = 300;
    /** Frames between a page given back and its targets deleted: a recording may still hold its image. */
    private static final int RELEASE_AFTER = 3;
    /** First draws per host call while the budget is measured: the budget is checked between groups. */
    private static final int GROUP = 16;

    private static final Map<CgItemStack, CgItemIcon> ICONS = new ConcurrentHashMap<>();
    private static final Queue<CgItemIcon> ASKED = new ConcurrentLinkedQueue<>();
    private static final List<CgItemIcon> HELD = new ArrayList<>();
    /**
     * Pages by {@link #atlasKey}: a size, icons or overlays, and where the host clears a page whole, whether its looks
     * animate, so a glint's redraw never redraws a still icon.
     */
    private static final Map<Integer, CgIconAtlas> ATLASES = new HashMap<>();
    private static final Queue<CgIconAtlas.Cell> DROPPED = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<Release> RELEASING = new ArrayDeque<>();
    private static final Set<Object> LOGGED = new HashSet<>();
    private static volatile Stats stats = Stats.NONE;
    private static long laidTotal;
    private static volatile boolean retry;
    /** An icon drawn again this frame as animated, for the hint: its item, and why. */
    private static volatile String redrawnExample;

    private static final CgHostPictures.Batch BATCH = new CgHostPictures.Batch() {
        @Override
        public boolean due() {
            return !HELD.isEmpty() || !ASKED.isEmpty() || !RELEASING.isEmpty() || !DROPPED.isEmpty();
        }

        @Override
        public void draw(CgHostPictures.Host pictures, long frame, CgHostPictures.Budget budget) {
            drawPoint(pictures, frame, budget);
        }
    };

    static {
        CgHostPictures.join(BATCH);
        CgAssetReloader.addListener(() -> retry = true);
        CgTraceHints.register((frame, zones, counters, out) -> {
            long redraws = counters.getOrDefault("icons-redraws", 0L);
            String example = redrawnExample;
            if (redraws > 0 && example != null) {
                out.add(new CgTraceHints.Hint("ICON-REDRAWN", redraws + " item icons drawn again this frame as animated; "
                        + "one is " + example, CgTraceHints.Hint.counter("icons-redraws")));
            }
        });
    }

    private CgItemIcons() {
    }

    /** The icon of {@code stack}: the same handle for an equal stack while it is in use. Any thread. */
    public static CgItemIcon of(CgItemStack stack) {
        return ICONS.computeIfAbsent(stack, CgItemIcon::new);
    }

    /** Draws {@code stacks}' icons at {@code devicePixels} at the next draw point, all of them, whatever the budget. */
    public static void prewarm(Iterable<CgItemStack> stacks, int devicePixels) {
        for (CgItemStack s : stacks) of(s).prewarm(devicePixels);
    }

    /** The last draw point's numbers. Any thread. */
    public static Stats stats() {
        return stats;
    }

    static void hold(CgItemIcon icon) {
        ASKED.add(icon);
    }

    /** A size an icon dropped: its cell is given back at the next draw point. Any thread, under the icon's lock. */
    static void dropped(CgItemIcon.Size size) {
        if (size.cell != null) DROPPED.add(size.cell);
        size.cell = null;
    }

    // ── The draw point's side ────────────────────────────────────────────

    private static final List<CgIconAtlas.Cell> DUE = new ArrayList<>();
    private static final List<CgIconAtlas.Cell> FIRST = new ArrayList<>();
    private static final List<CgIconAtlas.Cell> DRAW = new ArrayList<>();
    private static final Set<CgIconAtlas.Cell> PREWARM = new HashSet<>();
    private static int[] rects = new int[64];

    private static void drawPoint(CgHostPictures.Host pictures, long frame, CgHostPictures.Budget budget) {
        Host host = CgPlatform.get(HOST);
        for (CgItemIcon i; (i = ASKED.poll()) != null; ) HELD.add(i);
        for (CgIconAtlas.Cell d; (d = DROPPED.poll()) != null; ) d.release();
        releaseDue(pictures, frame);
        if (host.absent() != null) return;
        if (retry) {
            retry = false;
            for (CgIconAtlas a : ATLASES.values()) for (CgIconAtlas.Cell c : a.all()) c.retry();
            LOGGED.clear();
        }
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.icons");
        int firstDraws = 0, redraws = 0, deferred = 0;
        try {
            resolve(host, frame, !pictures.clearsRects());
            for (Map.Entry<Integer, CgIconAtlas> e : ATLASES.entrySet()) {
                CgIconAtlas atlas = e.getValue();
                DUE.clear();
                float hz = ANIMATED_HZ.get();
                long every = hz <= 0f ? 0L : (long) (1e9f / hz);
                if (atlas.place(frame, pictures.maxTargetSize(), System.nanoTime(), every, DUE)) remake(pictures, atlas, frame);
                if (DUE.isEmpty()) continue;
                for (CgIconAtlas.Cell c : DUE) if (c.image() == null) laidTotal++;
                if (!pictures.clearsRects()) {
                    // The host clears the page whole: every cell it shows draws with what is due, in one call
                    int first = 0;
                    for (CgIconAtlas.Cell c : DUE) if (c.drawnFrame() == Long.MIN_VALUE) first++;
                    DUE.clear();
                    atlas.placedShown(frame, DUE);
                    long t = System.nanoTime();
                    draw(pictures, host, atlas, DUE, frame, budget);
                    budget.firstDrawn(System.nanoTime() - t, first);
                    budget.redrawn(DUE.size() - first);
                    firstDraws += first;
                    redraws += DUE.size() - first;
                    continue;
                }
                FIRST.clear();
                DRAW.clear();
                for (CgIconAtlas.Cell c : DUE) {
                    if (c.drawnFrame() == Long.MIN_VALUE) FIRST.add(c);
                    else DRAW.add(c);
                }
                // Redraws first, unbudgeted; then first draws in groups while the budget lasts, prewarmed ones regardless
                if (!DRAW.isEmpty()) {
                    draw(pictures, host, atlas, DRAW, frame, budget);
                    budget.redrawn(DRAW.size());
                    redraws += DRAW.size();
                    CgIconAtlas.Cell example = DRAW.get(0);
                    redrawnExample = example.drawn + (example.why != null ? " (" + example.why + ")" : "");
                }
                for (int from = 0; from < FIRST.size(); ) {
                    int to = Math.min(FIRST.size(), from + GROUP);
                    boolean forced = PREWARM.contains(FIRST.get(from));
                    if (!forced && !budget.firstAllowed()) {
                        int left = FIRST.size() - from;
                        budget.deferred(left);
                        deferred += left;
                        break;
                    }
                    long t = System.nanoTime();
                    List<CgIconAtlas.Cell> group = FIRST.subList(from, to);
                    draw(pictures, host, atlas, group, frame, budget);
                    budget.firstDrawn(System.nanoTime() - t, group.size());
                    firstDraws += group.size();
                    from = to;
                }
            }
            noteDrawn();
            sweep(pictures, frame);
        } finally {
            PREWARM.clear();
            CgTrace.end(zone);
        }
        int cells = 0, pages = 0;
        for (CgIconAtlas a : ATLASES.values()) {
            cells += a.cells();
            if (a.page() != null) pages++;
        }
        stats = new Stats(frame, HELD.size(), cells, pages, firstDraws, redraws, deferred, laidTotal,
                redraws == 0 ? null : redrawnExample);
        CgTrace.counter(CgChannels.PICTURES, "icons-cells", cells);
        CgTrace.counter(CgChannels.PICTURES, "icons-pages", pages);
        CgTrace.counter(CgChannels.PICTURES, "icons-first-draws", firstDraws);
        CgTrace.counter(CgChannels.PICTURES, "icons-redraws", redraws);
        if (redraws == 0) redrawnExample = null;
    }

    // Every held icon's shown sizes into cells: its host stack, its look now, the cell of that look
    private static void resolve(Host host, long frame, boolean split) {
        for (int i = HELD.size() - 1; i >= 0; i--) {
            CgItemIcon icon = HELD.get(i);
            synchronized (icon) {
                for (int k = icon.sizeCount - 1; k >= 0; k--) {
                    CgItemIcon.Size s = icon.sizes[k];
                    if (s.shownFrame < frame - EVICT_FRAMES) {
                        if (s.cell != null) s.cell.release();
                        s.cell = null;
                        icon.sizes[k] = icon.sizes[--icon.sizeCount];
                        icon.sizes[icon.sizeCount] = null;
                        continue;
                    }
                    if (s.shownFrame < frame - 1) continue;
                    if (icon.hostStack == null && !icon.unknown) {
                        icon.hostStack = toHost(host, icon);
                        icon.unknown = icon.hostStack == null;
                    }
                    if (icon.unknown) continue;
                    Look look = icon.decorations ? overlayLook(host, icon) : look(host, icon);
                    if (look == null) {
                        // No decorations: nothing to draw, nothing shown
                        if (s.cell != null) s.cell.release();
                        s.cell = null;
                        continue;
                    }
                    CgIconAtlas atlas = ATLASES.computeIfAbsent(atlasKey(s.pixels, icon.decorations,
                            split && look.animated()), key -> new CgIconAtlas(key >> 2,
                            (key & 2) != 0 ? CgIconAtlas.OVERLAY : CgIconAtlas.ICON));
                    CgIconAtlas.Cell cell = atlas.cell(look.key(), icon.hostStack, look.animated(), look.why());
                    if (s.cell != cell) {
                        if (s.cell != null) s.cell.release();
                        cell.use();
                        s.cell = cell;
                        s.drawnFrame = Long.MIN_VALUE;
                    }
                    cell.shown(frame);
                    if (icon.prewarm) PREWARM.add(cell);
                }
                icon.prewarm = false;
                if (icon.sizeCount == 0) {
                    icon.held = false;
                    HELD.set(i, HELD.get(HELD.size() - 1));
                    HELD.remove(HELD.size() - 1);
                    ICONS.remove(icon.stack(), icon);
                }
            }
        }
    }

    private static int atlasKey(int pixels, boolean overlay, boolean animated) {
        return pixels << 2 | (overlay ? 2 : 0) | (animated ? 1 : 0);
    }

    @Nullable
    private static Object toHost(Host host, CgItemIcon icon) {
        try {
            return host.toHost(icon.stack());
        } catch (RuntimeException e) {
            if (LOGGED.add(icon.stack().item())) LOGGER.warn("[cg] item icon {}: the game cannot make its stack", icon.stack(), e);
            return null;
        }
    }

    @Nullable
    private static Look overlayLook(Host host, CgItemIcon icon) {
        try {
            return host.overlayLook(icon.hostStack);
        } catch (RuntimeException e) {
            if (LOGGED.add(icon.stack().item() + ":overlay")) LOGGER.warn("[cg] item overlay {}: its look could not be read", icon.stack(), e);
            return new Look(icon, true, "unknown");
        }
    }

    private static Look look(Host host, CgItemIcon icon) {
        try {
            return host.look(icon.hostStack);
        } catch (RuntimeException e) {
            // Unknown is animated: drawn while shown, never wrong
            if (LOGGED.add(icon.stack().item() + ":look")) LOGGER.warn("[cg] item icon {}: its look could not be read", icon.stack(), e);
            return new Look(icon, true, "unknown");
        }
    }

    // A page remade at its new side: new targets, the old given back a few frames from now
    private static void remake(CgHostPictures.Host pictures, CgIconAtlas atlas, long frame) {
        Object oldPage = atlas.page(), oldWhite = atlas.white();
        if (oldPage != null) RELEASING.add(new Release(oldPage, atlas.pageTexture(), frame));
        if (oldWhite != null) RELEASING.add(new Release(oldWhite, atlas.whiteTexture(), frame));
        int side = atlas.side();
        Object page = pictures.createTarget(side, side, 0f);
        Object white = pictures.twoClears() ? pictures.createTarget(side, side, 1f) : null;
        atlas.targets(page, white, pictures.texture(page), white == null ? null : pictures.texture(white));
    }

    // One host call per clear: every cell of the group drawn into its slot, over black and, on two-clear eras, white
    private static void draw(CgHostPictures.Host pictures, Host host, CgIconAtlas atlas, List<CgIconAtlas.Cell> cells,
                             long frame, CgHostPictures.Budget budget) {
        int n = cells.size();
        if (rects.length < n * 4) rects = new int[Math.max(n * 4, rects.length * 2)];
        for (int i = 0; i < n; i++) atlas.rect(cells.get(i), rects, i * 4);
        float scale = atlas.scale;
        int side = atlas.side();
        long hostMark = budget.hostBegin();
        try {
            pictures.drawCells(atlas.page(), side, side, scale, 0f, rects, n, CgPictureLighting.ITEMS_3D,
                    ctx -> drawCells(host, atlas, cells, ctx));
            if (atlas.white() != null) {
                pictures.drawCells(atlas.white(), side, side, scale, 1f, rects, n, CgPictureLighting.ITEMS_3D,
                        ctx -> drawCells(host, atlas, cells, ctx));
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            // The host's own drawing failed, not one item's: every cell of the group shows the missing texture
            for (CgIconAtlas.Cell c : cells) c.fail();
            if (LOGGED.add("host")) LOGGER.error("[cg] item icons failed to draw: they show the missing texture until a resource reload", t);
            return;
        } finally {
            budget.hostEnd(hostMark);
        }
        boolean bottomUp = pictures.bottomUp();
        for (CgIconAtlas.Cell c : cells) if (!c.failed()) atlas.drawn(c, frame, bottomUp);
    }

    private static void drawCells(Host host, CgIconAtlas atlas, List<CgIconAtlas.Cell> cells, CgHostDrawContext ctx) {
        for (int i = 0, n = cells.size(); i < n; i++) {
            CgIconAtlas.Cell c = cells.get(i);
            if (c.failed()) continue;
            try {
                if (atlas.frame == CgIconAtlas.OVERLAY) host.drawOverlay(ctx, c.drawn, atlas.unitsX(c), atlas.unitsY(c));
                else host.draw(ctx, c.drawn, atlas.unitsX(c), atlas.unitsY(c));
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                c.fail();
                if (LOGGED.add(c.key)) {
                    LOGGER.error("[cg] item icon {} failed to draw: it shows the missing texture until a resource reload",
                            c.drawn, t);
                }
            }
        }
    }

    // An icon whose cell drew again, failed, or changed moves its version: a kept layer holding it walks again
    private static void noteDrawn() {
        for (CgItemIcon icon : HELD) {
            boolean moved = false;
            synchronized (icon) {
                for (int k = 0; k < icon.sizeCount; k++) {
                    CgItemIcon.Size s = icon.sizes[k];
                    if (s.cell == null) continue;
                    long mark = s.cell.failed() ? Long.MAX_VALUE : s.cell.drawnFrame();
                    if (mark != s.drawnFrame) {
                        s.drawnFrame = mark;
                        moved = true;
                    }
                }
            }
            if (moved) icon.moved();
        }
    }

    // Cells nobody uses leave their pages; a page unshown for EVICT_FRAMES is freed
    private static void sweep(CgHostPictures.Host pictures, long frame) {
        if ((frame & 63) != 0) return;
        ATLASES.values().removeIf(a -> {
            a.sweep();
            if (a.shownSince(frame - EVICT_FRAMES) || a.cells() > 0) return false;
            if (a.page() != null) RELEASING.add(new Release(a.page(), a.pageTexture(), frame));
            if (a.white() != null) RELEASING.add(new Release(a.white(), a.whiteTexture(), frame));
            return true;
        });
    }

    private static void releaseDue(CgHostPictures.Host pictures, long frame) {
        while (!RELEASING.isEmpty() && RELEASING.peekFirst().frame <= frame - RELEASE_AFTER) {
            Release r = RELEASING.pollFirst();
            pictures.deleteTarget(r.target, r.texture);
        }
    }

    private record Release(Object target, @Nullable CgTexture texture, long frame) {
    }
}
