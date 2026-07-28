package com.crystalgraphics.text.atlas.packing;

import com.crystalgraphics.util.profiling.CgProfiler;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Pure-Java 2D bin packing using the MaxRects algorithm with Best Short-Side Fit
 * (BSSF) heuristic.
 *
 * <p>Packs rectangles into a fixed-size bin for texture atlas use. This class has
 * <strong>zero GL, LWJGL, or FreeType dependencies</strong> — it is strictly a
 * computational geometry helper.</p>
 *
 * <h3>Algorithm (MaxRects BSSF)</h3>
 * <ol>
 *   <li>Maintain a list of free rectangles, initially the full bin.</li>
 *   <li>For each insert: find the free rect that fits the requested size with the
 *       minimum short-side remainder ({@code min(freeW - w, freeH - h)}).</li>
 *   <li>Split <em>every</em> free rect the placement overlaps into up to 4 remainder rects
 *       (left, right, above, below) — not just the chosen one.</li>
 *   <li>Prune free rects that are fully contained within other free rects.</li>
 * </ol>
 *
 * <h3>Removal</h3>
 * <p>When a previously packed rect is removed (e.g., LRU eviction), its area is
 * returned to the free list and a containment prune is performed to merge space.</p>
 *
 * <p>Reference: Jukka Jylänki, "A Thousand Ways to Pack the Bin" (2010), Section 3.1.</p>
 *
 * @see PackedRect
 */
public class MaxRectsPacker implements CgPackingStrategy {

    private static final Logger LOGGER = Logger.getLogger(MaxRectsPacker.class.getName());

    /**
     * Defensive threshold for the free-rect list size. {@link #pruneContained}
     * is O(n^2) in free-rect count, so a page/glyph-density combination that
     * fragments far more than expected degrades quietly with no signal — this
     * logs once per packer instance instead of leaving it silent.
     */
    private static final int FREE_RECT_WARN_THRESHOLD = 2048;

    /** Width of the bin in pixels. */
    @Getter
    private final int binWidth;

    /** Height of the bin in pixels. */
    @Getter
    private final int binHeight;

    /** List of free rectangles available for packing. Mutually non-contained by invariant. */
    private final List<Rect> freeRects;

    /**
     * Rects created by the current {@link #splitFreeRects} call, staged here until
     * {@code pruneContained} has vetted them. Reused across inserts so a steady stream of
     * packing allocates nothing after warmup.
     */
    private final List<Rect> pendingRects = new ArrayList<>();

    /** List of packed rectangles currently occupying space. */
    private final List<PackedRect> packedRects;

    /** Whether {@link #FREE_RECT_WARN_THRESHOLD} has already been logged once. */
    private boolean warnedAboutFreeRectCount;

    /**
     * Creates a new packer with the given bin dimensions.
     *
     * @param binWidth  bin width in pixels (must be positive)
     * @param binHeight bin height in pixels (must be positive)
     * @throws IllegalArgumentException if either dimension is not positive
     */
    public MaxRectsPacker(int binWidth, int binHeight) {
        if (binWidth <= 0 || binHeight <= 0)
            throw new IllegalArgumentException("Bin dimensions must be positive, got: " + binWidth + "x" + binHeight);
        
        this.binWidth = binWidth;
        this.binHeight = binHeight;
        this.freeRects = new ArrayList<>();
        this.packedRects = new ArrayList<>();
        this.freeRects.add(new Rect(0, 0, binWidth, binHeight));
    }

    /**
     * Attempts to pack a rectangle of the given size into the bin.
     *
     * <p>Uses Best Short-Side Fit (BSSF): among all free rects that can contain
     * the requested size, the one with the smallest
     * {@code min(freeW - width, freeH - height)} is chosen.</p>
     *
     * @param width  rectangle width (must be positive)
     * @param height rectangle height (must be positive)
     * @param id     caller-provided identifier (e.g., CgGlyphKey)
     * @return the packed rectangle with its position, or {@code null} if it does not fit
     * @throws IllegalArgumentException if width or height is not positive
     */
    public PackedRect insert(int width, int height, Object id) {
        return insert(width, height, DEFAULT_SPACING_PX, id);
    }

    @Override
    public PackedRect insert(int width, int height, int spacing, Object id) {
        try (CgProfiler.Scope ignored = CgProfiler.scope("packer.insert")) {
            return insertInternal(width, height, spacing, id);
        }
    }

    private PackedRect insertInternal(int width, int height, int spacing, Object id) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Rectangle dimensions must be positive, got: " + width + "x" + height);
        if (spacing < 0) throw new IllegalArgumentException("spacing must be >= 0, got: " + spacing);
        

        int packedWidth = width + spacing;
        int packedHeight = height + spacing;

        // Find best free rect using BSSF heuristic
        int bestIndex = -1;
        int bestShortSide = Integer.MAX_VALUE;
        int bestLongSide = Integer.MAX_VALUE;

        for (int i = 0; i < freeRects.size(); i++) {
            Rect r = freeRects.get(i);
            if (packedWidth <= r.width && packedHeight <= r.height) {
                int leftoverX = r.width - packedWidth;
                int leftoverY = r.height - packedHeight;
                int shortSide = Math.min(leftoverX, leftoverY);
                int longSide = Math.max(leftoverX, leftoverY);

                if (shortSide < bestShortSide
                        || (shortSide == bestShortSide && longSide < bestLongSide)) {
                    bestIndex = i;
                    bestShortSide = shortSide;
                    bestLongSide = longSide;
                }
            }
        }

        if (bestIndex == -1) {
            return null; // Does not fit
        }

        Rect chosen = freeRects.get(bestIndex);
        PackedRect packed = new PackedRect(chosen.x, chosen.y, width, height, id);
        PackedRect allocatorRect = new PackedRect(chosen.x, chosen.y, packedWidth, packedHeight, id);

        // Split free rects that overlap with the newly placed rectangle
        splitFreeRects(allocatorRect);

        // Prune contained free rects
        pruneContained();

        packedRects.add(packed);
        freeExtentsDirty = true;
        // n for the O(n^2) prune below — the number that decides whether MaxRects is affordable
        // here. Bounded by per-PAGE occupancy, not by total glyphs in the atlas.
        CgProfiler.sample("packer.freeRectCount", freeRects.size());
        CgProfiler.sample("packer.packedCount", packedRects.size());
        return packed;
    }

    /**
     * Largest free-rect width and height currently available, cached and recomputed lazily.
     * {@code -1} means dirty. See {@link #mayFit}.
     */
    private int maxFreeWidth = -1;
    private int maxFreeHeight = -1;
    private boolean freeExtentsDirty = true;

    /**
     * Rejects a rectangle only when <em>no</em> free rect is wide enough or <em>none</em> is
     * tall enough — both are necessary conditions, so this never rejects something placeable.
     *
     * <p>It is deliberately not a sufficient test: width and height are tracked independently,
     * so a bin holding one 200x10 rect and one 10x200 rect reports "may fit" for 90x90 even
     * though neither can take it. That costs one wasted {@code insert} scan and nothing else,
     * whereas a tighter-but-unsound test would strand space permanently.</p>
     *
     * <p>Spacing is deliberately excluded: {@link #insert} pads by it, so testing the unpadded
     * size errs toward "may fit", which is the safe direction.</p>
     */
    @Override
    public boolean mayFit(int width, int height) {
        if (freeExtentsDirty) {
            maxFreeWidth = 0;
            maxFreeHeight = 0;
            for (int i = 0; i < freeRects.size(); i++) {
                Rect r = freeRects.get(i);
                if (r.width > maxFreeWidth) maxFreeWidth = r.width;
                if (r.height > maxFreeHeight) maxFreeHeight = r.height;
            }
            freeExtentsDirty = false;
        }
        return width <= maxFreeWidth && height <= maxFreeHeight;
    }

    @Override
    public int countFreeRegionsFitting(int width, int height) {
        int count = 0;
        for (int i = 0; i < freeRects.size(); i++) {
            Rect r = freeRects.get(i);
            if (width <= r.width && height <= r.height) count++;
        }
        return count;
    }

    @Override
    public int[][] describeFreeRegions() {
        int[][] out = new int[freeRects.size()][];
        for (int i = 0; i < freeRects.size(); i++) {
            Rect r = freeRects.get(i);
            out[i] = new int[]{r.x, r.y, r.width, r.height};
        }
        java.util.Arrays.sort(out, (a, b) -> Long.compare((long) b[2] * b[3], (long) a[2] * a[3]));
        return out;
    }

    @Override
    public long totalFreeArea() {
        // Note: MaxRects free rects OVERLAP by design, so this over-counts and is only a rough
        // upper bound. countFreeRegionsFitting is the trustworthy signal.
        long area = 0;
        for (int i = 0; i < freeRects.size(); i++) {
            Rect r = freeRects.get(i);
            area += (long) r.width * r.height;
        }
        return area;
    }


    /**
     * Returns the utilization ratio of the bin.
     *
     * <p>Calculated as the total area of all packed rectangles divided by the
     * total bin area. Returns a value in {@code [0.0, 1.0]}.</p>
     *
     * @return utilization ratio
     */
    public float utilization() {
        long usedArea = 0;
        for (int i = 0; i < packedRects.size(); i++) {
            PackedRect r = packedRects.get(i);
            usedArea += (long) r.width() * r.height();
        }
        return (float) usedArea / ((long) binWidth * binHeight);
    }

    /**
     * Returns the number of currently packed rectangles.
     *
     * @return count of packed rects
     */
    public int getPackedCount() {
        return packedRects.size();
    }

    // ---------------------------------------------------------------
    //  Internal: MaxRects split + prune
    // ---------------------------------------------------------------

    /**
     * Splits all free rects that overlap with the placed rectangle.
     *
     * <p>For each free rect that intersects the placed rect, it is removed and
     * replaced with up to 4 non-overlapping remainder rects (top, bottom, left,
     * right of the placed rect within the free rect).</p>
     */
    private void splitFreeRects(PackedRect placed) {
        pendingRects.clear();
        int px = placed.x();
        int py = placed.y();
        int pw = placed.width();
        int ph = placed.height();
        int px2 = px + pw;
        int py2 = py + ph;

        for (int i = freeRects.size() - 1; i >= 0; i--) {
            Rect free = freeRects.get(i);
            int fx2 = free.x + free.width;
            int fy2 = free.y + free.height;

            // Check if placed rect overlaps this free rect
            if (px >= fx2 || px2 <= free.x || py >= fy2 || py2 <= free.y) continue; // No overlap

            // Remove overlapping free rect
            removeFreeRectAt(i);

            // Left remainde
            if (px > free.x) pendingRects.add(new Rect(free.x, free.y, px - free.x, free.height));
            // Right remainde
            if (px2 < fx2)   pendingRects.add(new Rect(px2, free.y, fx2 - px2, free.height));
            // Top remainder (above the placed rect)
            if (py > free.y) pendingRects.add(new Rect(free.x, free.y, free.width, py - free.y));
            // Bottom remainder (below the placed rect)
            if (py2 < fy2)   pendingRects.add(new Rect(free.x, py2, free.width, fy2 - py2));
            
        }
    }

    /**
     * Removes free rects that are fully contained within another free rect.
     *
     * <p>This is the key merge step of MaxRects: rather than explicitly merging adjacent free
     * rects, we rely on the split step producing overlapping free rects and then pruning the
     * smaller ones that are fully covered.</p>
     *
     * <p><strong>Cost.</strong> O(n²) in free-rect count, run once per {@link #insert}, so filling
     * a page is O(n³) in glyphs packed. That is the dominant cost of packing and is left as-is
     * deliberately: at observed densities (~340 glyphs on a 1024² page) it has never appeared in a
     * profile, and {@link #FREE_RECT_WARN_THRESHOLD} exists to say so loudly if the density
     * assumptions ever change. Rewriting a measured-good packer for a cost that has not shown up
     * in measurement is the trap, not the fix.</p>
     */
    private void pruneContained() {
        try (CgProfiler.Scope ignored = CgProfiler.scope("packer.pruneContained")) {
            pruneContainedInternal();
        }
    }

    private void pruneContainedInternal() {
        // Only the rects this insert created need checking.
        //
        // The rects already in freeRects are mutually non-contained -- that is the invariant this
        // method restores, and it held before this insert too, so re-comparing them against each
        // other is pure waste. The original implementation did exactly that: every free rect
        // against every other, on every insert. Measured on a real warmup it cost 1106 ms of a
        // 1129 ms total packing budget (98%), peaking at 4.4 ms for a single insert on a page
        // holding ~1200 rects with ~790 free regions -- a quarter of a 60 fps frame, on the
        // render thread.
        //
        // Comparing only new-vs-new and new-vs-existing is O(pending x n) instead of O(n^2), and
        // pending is a handful (up to 4 per overlapped free rect) rather than hundreds.

        // 1. New against new. Uses index-ordered comparison so that two identical rects drop
        //    exactly one of the pair rather than both.
        for (int i = 0; i < pendingRects.size(); i++) {
            for (int j = i + 1; j < pendingRects.size(); j++) {
                if (contains(pendingRects.get(j), pendingRects.get(i))) {
                    pendingRects.remove(i);
                    i--;
                    break;
                }
                if (contains(pendingRects.get(i), pendingRects.get(j))) {
                    pendingRects.remove(j);
                    j--;
                }
            }
        }

        // 2. New contained in something that already existed -> the new one is redundant.
        for (int i = pendingRects.size() - 1; i >= 0; i--) {
            Rect pending = pendingRects.get(i);
            for (int j = 0; j < freeRects.size(); j++) {
                if (contains(freeRects.get(j), pending)) {
                    pendingRects.remove(i);
                    break;
                }
            }
        }

        // 3. Existing contained in a new one -> the existing one is now redundant.
        for (int i = freeRects.size() - 1; i >= 0; i--) {
            Rect existing = freeRects.get(i);
            for (int j = 0; j < pendingRects.size(); j++) {
                if (contains(pendingRects.get(j), existing)) {
                    removeFreeRectAt(i);
                    break;
                }
            }
        }

        // 4. Commit the survivors. freeRects is mutually non-contained again.
        freeRects.addAll(pendingRects);
        pendingRects.clear();

        if (!warnedAboutFreeRectCount && freeRects.size() > FREE_RECT_WARN_THRESHOLD) {
            warnedAboutFreeRectCount = true;
            LOGGER.warning("MaxRectsPacker free-rect list exceeded " + FREE_RECT_WARN_THRESHOLD
                    + " entries (" + freeRects.size() + ") for a " + binWidth + "x" + binHeight + " bin - packing may be noticeably slower than expected. This usually means " + "the page size/glyph density assumptions this packer was tuned for no " + "longer hold.");
        }
    }

    /**
     * Removes the free rect at {@code index} in O(1) by swapping it with the
     * last element instead of shifting every subsequent element down
     * ({@link List#remove(int)} on an {@code ArrayList} is O(n)). Safe here
     * because neither {@link #pruneContained} nor {@link #splitFreeRects}
     * depend on free-rect array order — both scan every remaining element
     * regardless of position, so reordering during removal cannot change
     * which rects survive.
     */
    private void removeFreeRectAt(int index) {
        int lastIndex = freeRects.size() - 1;
        if (index != lastIndex) freeRects.set(index, freeRects.get(lastIndex));
        freeRects.remove(lastIndex);
    }

    /**
     * Returns {@code true} if rect {@code outer} fully contains rect {@code inner}.
     */
    private static boolean contains(Rect outer, Rect inner) {
        return inner.x >= outer.x && inner.y >= outer.y 
                && inner.x + inner.width <= outer.x + outer.width
                && inner.y + inner.height <= outer.y + outer.height;
    }

    // ---------------------------------------------------------------
    //  Internal rect representation
    // ---------------------------------------------------------------

    /**
     * Lightweight immutable rectangle used only for the internal free-rect list. Not exposed in
     * the public API.
     */
    record Rect(int x, int y, int width, int height) {}

    /**
     * Immutable value representing a rectangle packed into a bin atlas.
     *
     * <p>Stores the position and dimensions of the packed rectangle within the bin, along with a
     * caller-provided identifier (e.g. a {@code CgGlyphKey}) for associating the packed region with
     * its source data.</p>
     *
     * <p><strong>Reports the requested size, not the allocated size.</strong> When a rectangle is
     * inserted with spacing the packer reserves {@code width + spacing}, but this record still carries
     * the original {@code width}/{@code height}, so UVs and plane bounds stay tied to the visible glyph
     * box rather than to the allocator's padded footprint.</p>
     *
     * <p>Instances are created by {@link CgPackingStrategy#insert} and should not be constructed
     * directly by callers.</p>
     *
     * @param x      X position of the top-left corner within the bin, in pixels
     * @param y      Y position of the top-left corner within the bin, in pixels
     * @param width  width of the packed rectangle in pixels, as requested
     * @param height height of the packed rectangle in pixels, as requested
     * @param id     caller-provided identifier, e.g. a {@code CgGlyphKey}
     * @see CgPackingStrategy
     */
    public record PackedRect(int x, int y, int width, int height, Object id) {}
}
