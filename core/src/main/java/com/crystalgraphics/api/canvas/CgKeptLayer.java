package com.crystalgraphics.api.canvas;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;

/**
 * A picture kept across frames: what is drawn in its walk lands in a texture of its own, and a frame that does not
 * change it composites the picture and records nothing for it. A window, a panel, a minimised window's preview. DWM's
 * redirection surface, ported from CrystalGUI's {@code Surface}.
 *
 * <pre>{@code
 * static final CgKeptLayer WINDOW = new CgKeptLayer().reserve(1280, 800);    // once, kept: it owns its texture
 *
 * try (CgKeptLayer.Walk walk = canvas.kept(WINDOW, x, y, w, h, revision)) {  // composited when it closes
 *     if (walk.draws()) {                    // false: its picture stands and nothing is recorded for it
 *         ... draw the window's contents ...
 *         walk.damage(cx, cy, 2, 18);        // only what changed; damage nothing for the whole picture
 *     }
 * }
 * canvas.thumbnail(WINDOW, tx, ty, 160, 100);   // its picture elsewhere, filtered down
 * }</pre>
 *
 * <ul>
 *   <li><b>Held</b> while the revision you pass and the size in device pixels stay as the last walk drew them. A move by
 *       translation is held: the picture lands at the new place, on whole device pixels.</li>
 *   <li><b>Drawn whole</b> the first time, after a resize and after {@link #invalidate}; otherwise only what the walk
 *       damages executes. Draw everything the picture holds on every walk that draws: the damage only limits where
 *       it lands.</li>
 *   <li>Glass inside a walk draws flat ({@code CgBackdropFilter.fallback}): a kept picture of glass would be stale.</li>
 *   <li>A canvas frees a layer nobody asked for in 300 frames, unless {@link #keep}; the next ask draws it whole.
 *       {@link #delete} at a context's teardown.</li>
 *   <li>One canvas walks it at a time.</li>
 * </ul>
 */
public final class CgKeptLayer {

    /**
     * A piece of drawing kept in a layer, told when a walk no longer reaches it, so it can damage where it was.
     * {@link Placement} is the one most callers want.
     */
    public interface Tenant {
        /** The walk at {@code frame} did not reach {@code part}: damage its old place in {@code layer}, if it was there. */
        void vacate(CgKeptLayer layer, int part, long frame);
    }

    /**
     * Something drawn into a kept layer that repaints where it was once it moves or is no longer drawn there, so no
     * ghost of it stays: a sprite, a caret, a panel within the window. One per piece, kept across frames.
     *
     * <pre>{@code
     * static final CgKeptLayer.Placement CARET = new CgKeptLayer.Placement();
     * if (walk.draws()) {
     *     CARET.place(walk, cx, cy, 2, 18);     // where it is now; its last place is damaged too
     *     canvas.fill(cx, cy, 2, 18, caret);
     * }
     * }</pre>
     */
    public static final class Placement implements Tenant {
        @Nullable private CgKeptLayer in;
        private float x0, y0, x1, y1;
        private long frame;

        /** Places it in {@code walk}'s layer over a rect in the canvas's units now: that place and its last are damaged. */
        public void place(Walk walk, float x, float y, float w, float h) {
            place(walk, x, y, w, h, true);
        }

        /**
         * As {@link #place(Walk, float, float, float, float)}; with {@code changed} false, a piece drawn as last walk
         * (a kept chunk replayed) and where it was damages nothing, and is only kept from vacating.
         *
         * <pre>{@code
         * PANEL.place(walk, x, y, w, h, !canvas.replay(panelChunk));
         * }</pre>
         */
        public void place(Walk walk, float x, float y, float w, float h, boolean changed) {
            if (!walk.draws) return;
            float[] r = walk.canvas.keptLayers.rect(walk, x, y, w, h);
            CgKeptLayer layer = walk.layer();
            if (!changed && in == layer && x0 == r[0] && y0 == r[1] && x1 == r[2] && y1 == r[3]) {
                frame = layer.walkFrame;
                layer.visit(this, 0);
                return;
            }
            place(layer, r[0], r[1], r[2], r[3], layer.walkFrame);
        }

        /** As {@link #place(Walk, float, float, float, float)}, in {@code parent}'s texels, top-down. */
        void place(CgKeptLayer parent, float x0, float y0, float x1, float y1, long frame) {
            parent.damage(x0, y0, x1, y1);
            if (in == parent) parent.damage(this.x0, this.y0, this.x1, this.y1);
            in = parent;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.frame = frame;
            parent.visit(this, 0);
        }

        @Override
        public void vacate(CgKeptLayer layer, int part, long frame) {
            if (in != layer || this.frame == frame) return;
            layer.damage(x0, y0, x1, y1);
            in = null;
        }
    }

    /**
     * A walk of a kept layer, opened by {@code canvas.kept(...)} and composited when it closes. Reused: one per layer.
     */
    public final class Walk implements AutoCloseable {
        CgCanvas canvas;
        boolean draws, live;
        /** Where its picture lands, in the device pixels of the node it was opened under; its size is the layer's. */
        int x0, y0;
        float opacity = 1f;
        long revision;

        /** Where its picture's top-left lands, in the device pixels of the node it was opened under. */
        public int x() {
            return x0;
        }

        /** @see #x() */
        public int y() {
            return y0;
        }

        /** Whether this walk draws: false when the picture is held, and whatever is drawn is not recorded. */
        public boolean draws() {
            return draws;
        }

        /** Marks a rect in the canvas's units now as changed: only damaged rects execute on a walk that is not whole. */
        public Walk damage(float x, float y, float w, float h) {
            if (!draws) return this;
            float[] r = canvas.keptLayers.rect(this, x, y, w, h);
            CgKeptLayer.this.damage(r[0], r[1], r[2], r[3]);
            return this;
        }

        /** The picture composited at {@code opacity}: a window's fade, which keeps the picture. */
        public Walk opacity(float opacity) {
            this.opacity = Math.max(0f, Math.min(1f, opacity));
            return this;
        }

        /** The layer it walks. */
        public CgKeptLayer layer() {
            return CgKeptLayer.this;
        }

        @Override
        public void close() {
            if (live) canvas.keptLayers.close(this);
        }
    }

    final Walk walk = new Walk();

    // Owner's promises
    int reserveWidth, reserveHeight;
    boolean keep;

    // The picture: its texture, and what it holds
    @Nullable CgGraphTexture texture;
    int pictureWidth, pictureHeight;
    long revision = NONE;
    long lastFrame;
    /** The canvas that frees it when idle. */
    @Nullable CgCanvas sweeper;
    static final long NONE = Long.MIN_VALUE;

    // The walk's damage in texels, top-down, and its tenants
    long walkFrame;
    boolean full;
    float damageX0, damageY0, damageX1, damageY1;
    private Tenant[] visited = new Tenant[16], held = new Tenant[16];
    private int[] visitedParts = new int[16], heldParts = new int[16];
    private int visitedCount, heldCount;
    /** Where it lands in a kept layer it is composited into. */
    final Placement placement = new Placement();

    // The thumbnail
    @Nullable CgGraphTexture thumbnail;
    int thumbnailWidth, thumbnailHeight;
    long drawCount, thumbnailDrawn = -1L, thumbnailAt;
    /** The part of the picture the thumbnail was filtered from. */
    float thumbnailX, thumbnailY, thumbnailW, thumbnailH;
    boolean thumbnailStale;

    /**
     * The largest size in device pixels it will need, or 0 for no promise: a texture is made that large at once, so
     * growing toward it never remakes it mid-animation.
     */
    public CgKeptLayer reserve(int width, int height) {
        reserveWidth = Math.max(0, width);
        reserveHeight = Math.max(0, height);
        return this;
    }

    /** Whether to keep its picture while nobody draws it: a minimised window whose preview still shows. */
    public CgKeptLayer keep(boolean keep) {
        this.keep = keep;
        return this;
    }

    /** Forgets what it holds: the next walk draws it whole. */
    public void invalidate() {
        revision = NONE;
    }

    /** Whether it holds a picture: drawn at least once, and not freed. */
    public boolean hasPicture() {
        return texture != null && revision != NONE;
    }

    /**
     * Its texture, the picture in its top-left corner ({@link #pictureWidth} x {@link #pictureHeight}, row 0 the
     * bottom), premultiplied RGBA16F; null when it holds none. For a material of your own that samples the picture.
     */
    @Nullable
    public CgGraphTexture texture() {
        return hasPicture() ? texture : null;
    }

    /** Its picture's size in device pixels, as last drawn. */
    public int pictureWidth() {
        return pictureWidth;
    }

    /** @see #pictureWidth */
    public int pictureHeight() {
        return pictureHeight;
    }

    /** Whether its kept thumbnail trails the picture: draw it again soon. */
    public boolean thumbnailStale() {
        return thumbnailStale;
    }

    /** Frees its textures with no frame to record a release into: a context's teardown. */
    public void delete() {
        if (texture != null) CgExecutor.freeStorage(texture);
        if (thumbnail != null) CgExecutor.freeStorage(thumbnail);
        texture = thumbnail = null;
        revision = NONE;
        thumbnailDrawn = -1L;
    }

    /** Whether the last walk drew it at {@code revision} and this size, so it may be composited as it is. */
    boolean holds(long revision, int width, int height) {
        return hasPicture() && this.revision == revision && pictureWidth == width && pictureHeight == height;
    }

    /** Starts a walk at {@code frame}, damaged everywhere with {@code full}. */
    void beginWalk(long frame, boolean full) {
        walkFrame = frame;
        this.full = full;
        damageX0 = damageY0 = Float.MAX_VALUE;
        damageX1 = damageY1 = -Float.MAX_VALUE;
        visitedCount = 0;
    }

    /** Marks texels {@code (x0, y0)-(x1, y1)}, top-down, as drawn again this walk. */
    void damage(float x0, float y0, float x1, float y1) {
        if (x1 <= x0 || y1 <= y0) return;
        damageX0 = Math.min(damageX0, x0);
        damageY0 = Math.min(damageY0, y0);
        damageX1 = Math.max(damageX1, x1);
        damageY1 = Math.max(damageY1, y1);
    }

    /** Whether this walk damaged anything. */
    boolean damaged() {
        return damageX1 > damageX0 && damageY1 > damageY0;
    }

    /** Records that this walk reached {@code tenant}'s {@code part}. */
    void visit(Tenant tenant, int part) {
        if (visitedCount == visited.length) {
            visited = Arrays.copyOf(visited, visitedCount * 2);
            visitedParts = Arrays.copyOf(visitedParts, visitedCount * 2);
        }
        visited[visitedCount] = tenant;
        visitedParts[visitedCount++] = part;
    }

    /** Ends the walk: everything the last one reached and this one did not vacates. */
    void endWalk() {
        for (int i = 0; i < heldCount; i++) held[i].vacate(this, heldParts[i], walkFrame);
        Arrays.fill(held, 0, heldCount, null);
        Tenant[] tenants = held;
        int[] parts = heldParts;
        held = visited;
        heldParts = visitedParts;
        heldCount = visitedCount;
        visited = tenants;
        visitedParts = parts;
        visitedCount = 0;
    }
}
