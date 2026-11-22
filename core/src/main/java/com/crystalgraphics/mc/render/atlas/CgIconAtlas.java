package com.crystalgraphics.mc.render.atlas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.api.texture.CgTexture;

/**
 * The icons of one cell size, in device pixels: a uniform grid on one page the host draws into, cells keyed by what
 * they look like. Mojang's {@code GuiItemAtlas}. Internal to {@code mc.render}'s kinds; render thread only.
 *
 * <ul>
 *   <li>The grid is laid out in GUI units: a cell's frame ({@link #ICON}, or {@link #OVERLAY} for what a slot's
 *       decorations draw past it) with one unit of gutter each side, cleared with it, so filtering never reaches the
 *       next cell. Every slot starts on a whole unit at any scale, where vanilla draws a slot's item.</li>
 *   <li>Space comes from free slots, then from cells not shown this frame; past that the page is remade at 1.5 times
 *       what the frame needs, to a power of two, up to the host's largest target, and every cell draws again.</li>
 * </ul>
 */
public final class CgIconAtlas {

    /** One look's cell: drawn into its slot when it has none, and every frame it shows when animated. */
    public static final class Cell {
        public final Object key;
        /** What draws it: the host's stack of the first icon that asked. */
        public final Object drawn;
        public final boolean animated;
        @Nullable public final String why;
        int slot = -1;
        long shownFrame = Long.MIN_VALUE, drawnFrame = Long.MIN_VALUE;
        int users;
        boolean failed;
        @Nullable CgPicture.Image image;

        Cell(Object key, Object drawn, boolean animated, @Nullable String why) {
            this.key = key;
            this.drawn = drawn;
            this.animated = animated;
            this.why = why;
        }

        /** What a canvas composites: null until drawn, {@link CgPicture.Image#MISSING} once its draw threw. */
        @Nullable
        public CgPicture.Image image() {
            return failed ? CgPicture.Image.MISSING : slot < 0 ? null : image;
        }

        public long drawnFrame() {
            return drawnFrame;
        }

        public boolean failed() {
            return failed;
        }

        /** It draws this frame; render thread. */
        public void shown(long frame) {
            shownFrame = frame;
        }

        public void use() {
            users++;
        }

        public void release() {
            users--;
        }

        /** Its draw threw: it shows the missing texture until {@link #retry}. */
        public void fail() {
            failed = true;
        }

        /** Drawn again the next time it shows: after a resource reload, failed or not. */
        public void retry() {
            failed = false;
            drawnFrame = Long.MIN_VALUE;
            image = null;
        }
    }

    /** An icon's frame in units: the slot alone. */
    public static final int ICON = 16;
    /** An overlay's frame in units: the slot and 4 more each side, where count text and its shadow reach. */
    public static final int OVERLAY = 24;

    /** The slot's side in pixels; pixels a unit; pixels from one cell to the next. */
    public final int cell;
    public final float scale, stride;
    /** The frame in units, units from one cell to the next (the frame and a unit of gutter each side), and where the
     *  slot starts inside a cell. */
    public final int frame, strideUnits, slotOffset;
    private final Map<Object, Cell> byKey = new HashMap<>();
    private Cell[] slots = new Cell[0];
    private int side, perRow;
    @Nullable private Object page, white;
    @Nullable private CgTexture pageTexture, whiteTexture;
    private long shownFrame = Long.MIN_VALUE;

    /** @param cell the slot's side in device pixels @param frame {@link #ICON} or {@link #OVERLAY} */
    public CgIconAtlas(int cell, int frame) {
        this.cell = cell;
        this.frame = frame;
        this.scale = cell / 16f;
        this.strideUnits = frame + 2;
        this.slotOffset = 1 + (frame - 16) / 2;
        this.stride = strideUnits * scale;
    }

    /** The cell of {@code key}, made for {@code drawn} if new. */
    public Cell cell(Object key, Object drawn, boolean animated, @Nullable String why) {
        Cell c = byKey.get(key);
        if (c == null) byKey.put(key, c = new Cell(key, drawn, animated, why));
        return c;
    }

    /** Forgets cells nobody uses, giving their slots back. */
    public void sweep() {
        if (byKey.isEmpty()) return;
        Iterator<Cell> it = byKey.values().iterator();
        while (it.hasNext()) {
            Cell c = it.next();
            if (c.users > 0) continue;
            if (c.slot >= 0) slots[c.slot] = null;
            it.remove();
        }
    }

    /** Whether any cell showed at or after {@code frame}. */
    public boolean shownSince(long frame) {
        return shownFrame >= frame;
    }

    /** When its animated cells were last drawn, nanoseconds. */
    private long animatedAt = Long.MIN_VALUE / 2;

    public int cells() {
        return byKey.size();
    }

    /** {@link #place(long, int, long, long, List)}, its animated cells drawn every frame they show. */
    public boolean place(long frame, int maxSide, List<Cell> due) {
        return place(frame, maxSide, 0L, 0L, due);
    }

    /**
     * Gives every cell shown at {@code frame} a slot, remaking the page when they do not fit; the cells that must draw
     * this frame into {@code due}: new to a slot, never drawn, or animated and their last drawing at least
     * {@code animateEvery} nanoseconds before {@code now} (0 draws them every frame). True where the page was remade,
     * so the host's targets change; {@code maxSide} is the host's largest target.
     */
    public boolean place(long frame, int maxSide, long now, long animateEvery, List<Cell> due) {
        int shown = 0;
        for (Cell c : byKey.values()) if (c.shownFrame == frame) shown++;
        if (shown == 0) return false;
        shownFrame = frame;
        // The page's animated cells move together, at the pace asked: a glint at the game's tick rate is one redraw in three.
        // A fixed step, as Minecraft's tick timer, so uneven frames average out; a stall of more than four periods resyncs rather than draws
        // every frame to catch up.
        boolean animate = animateEvery <= 0L || now - animatedAt >= animateEvery;
        if (animate) animatedAt = animateEvery <= 0L || now - animatedAt > 4 * animateEvery ? now : animatedAt + animateEvery;
        boolean remade = false;
        int free = 0;
        for (Cell s : slots) if (s == null || s.shownFrame != frame) free++;
        int unplaced = 0;
        for (Cell c : byKey.values()) if (c.shownFrame == frame && c.slot < 0) unplaced++;
        if (unplaced > free || slots.length == 0) {
            // 1.5 times what the frame needs, to a power of two, as Mojang's atlas grows
            int need = (int) Math.ceil(shown * 1.5);
            int perSide = (int) Math.ceil(Math.sqrt(need));
            int pixels = (int) Math.ceil(perSide * stride);
            int next = Integer.highestOneBit(Math.max(1, pixels - 1)) << 1;
            next = Math.min(Math.max(next, (int) Math.ceil(stride)), maxSide);
            int across = (int) (next / stride);
            int fits = across * across;
            if (fits < shown) {
                throw new IllegalStateException(shown + " item icons of " + cell + " px in one frame; a page of "
                        + maxSide + " px holds " + fits);
            }
            side = next;
            perRow = across;
            slots = new Cell[perRow * perRow];
            for (Cell c : byKey.values()) {
                c.slot = -1;
                c.image = null;
            }
            remade = true;
        }
        int next = 0;
        for (Cell c : byKey.values()) {
            if (c.shownFrame != frame) continue;
            if (c.slot < 0) {
                while (slots[next] != null && slots[next].shownFrame == frame) next++;
                Cell old = slots[next];
                if (old != null) {
                    old.slot = -1;
                    old.image = null;
                }
                slots[next] = c;
                c.slot = next;
                c.image = null;
            }
            if (!c.failed && (c.image == null || c.animated && animate)) due.add(c);
        }
        return remade;
    }

    /** Every cell shown at {@code frame} that has a slot and has not failed, into {@code out}: a page drawn whole. */
    public void placedShown(long frame, List<Cell> out) {
        for (Cell c : byKey.values()) if (c.shownFrame == frame && c.slot >= 0 && !c.failed) out.add(c);
    }

    /** The page's side in pixels; 0 before the first {@link #place}. */
    public int side() {
        return side;
    }

    @Nullable
    public Object page() {
        return page;
    }

    @Nullable
    public Object white() {
        return white;
    }

    /** The targets the host made for the page's present side; the old ones are the caller's to give back. */
    public void targets(Object page, @Nullable Object white, CgTexture pageTexture, @Nullable CgTexture whiteTexture) {
        this.page = page;
        this.white = white;
        this.pageTexture = pageTexture;
        this.whiteTexture = whiteTexture;
        for (Cell c : byKey.values()) c.image = null;
    }

    @Nullable
    public CgTexture pageTexture() {
        return pageTexture;
    }

    @Nullable
    public CgTexture whiteTexture() {
        return whiteTexture;
    }

    /**
     * The rect of {@code c} on the page with its gutter, in pixels from the top-left, {@code x, y, w, h} into
     * {@code out}: rounded so neighbours share their edges.
     */
    public void rect(Cell c, int[] out, int at) {
        int col = c.slot % perRow, row = c.slot / perRow;
        int x0 = Math.round(col * stride), y0 = Math.round(row * stride);
        out[at] = x0;
        out[at + 1] = y0;
        out[at + 2] = Math.round((col + 1) * stride) - x0;
        out[at + 3] = Math.round((row + 1) * stride) - y0;
    }

    /** The slot's top-left in the page's units: a whole unit, where the host draws the item. */
    public int unitsX(Cell c) {
        return (c.slot % perRow) * strideUnits + slotOffset;
    }

    /** @see #unitsX */
    public int unitsY(Cell c) {
        return (c.slot / perRow) * strideUnits + slotOffset;
    }

    /**
     * {@code c} drew at {@code frame}: its image made against the page's textures, rows bottom-up where they run so; an
     * overlay's covers its frame, the margin past the slot as the image's outset.
     */
    public void drawn(Cell c, long frame, boolean bottomUp) {
        c.drawnFrame = frame;
        if (c.image != null || pageTexture == null) return;
        int margin = (this.frame - 16) / 2;
        float s = side;
        float x0 = unitsX(c) - margin, y0 = unitsY(c) - margin;
        float u0 = x0 * scale / s, u1 = (x0 + this.frame) * scale / s;
        float top = y0 * scale / s, bottom = (y0 + this.frame) * scale / s;
        float v0 = bottomUp ? 1f - top : top, v1 = bottomUp ? 1f - bottom : bottom;
        CgPicture.Image image = whiteTexture == null ? CgPicture.Image.of(pageTexture, u0, v0, u1, v1)
                : CgPicture.Image.matted(pageTexture, whiteTexture, u0, v0, u1, v1);
        float out = margin / 16f;
        c.image = margin == 0 ? image : image.outset(out, out, out, out);
    }

    /** Every cell, for a sweep over all of them. */
    public List<Cell> all() {
        return new ArrayList<>(byKey.values());
    }
}
