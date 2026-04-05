package io.github.somehussar.crystalgraphics.text.atlas;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
 *   <li>Split the chosen free rect into up to 2 remainder rects after placement.</li>
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

    /** Width of the bin in pixels. */
    private final int binWidth;

    /** Height of the bin in pixels. */
    private final int binHeight;

    /** List of free rectangles available for packing. */
    private final List<Rect> freeRects;

    /** List of packed rectangles currently occupying space. */
    private final List<PackedRect> packedRects;

    /**
     * Creates a new packer with the given bin dimensions.
     *
     * @param binWidth  bin width in pixels (must be positive)
     * @param binHeight bin height in pixels (must be positive)
     * @throws IllegalArgumentException if either dimension is not positive
     */
    public MaxRectsPacker(int binWidth, int binHeight) {
        if (binWidth <= 0 || binHeight <= 0) {
            throw new IllegalArgumentException(
                    "Bin dimensions must be positive, got: " + binWidth + "x" + binHeight);
        }
        this.binWidth = binWidth;
        this.binHeight = binHeight;
        this.freeRects = new ArrayList<Rect>();
        this.packedRects = new ArrayList<PackedRect>();
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
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Rectangle dimensions must be positive, got: " + width + "x" + height);
        }

        // Find best free rect using BSSF heuristic
        int bestIndex = -1;
        int bestShortSide = Integer.MAX_VALUE;
        int bestLongSide = Integer.MAX_VALUE;

        for (int i = 0; i < freeRects.size(); i++) {
            Rect r = freeRects.get(i);
            if (width <= r.width && height <= r.height) {
                int leftoverX = r.width - width;
                int leftoverY = r.height - height;
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

        // Split free rects that overlap with the newly placed rectangle
        splitFreeRects(packed);

        // Prune contained free rects
        pruneContained();

        packedRects.add(packed);
        return packed;
    }

    /**
     * Removes a previously packed rectangle, returning its area to the free list.
     *
     * <p>After removal, the freed space is added back as a free rectangle and a
     * containment prune is performed. This allows same-sized or smaller rects
     * to reuse the space.</p>
     *
     * @param rect the packed rect to remove (must have been returned by {@link #insert})
     * @throws IllegalArgumentException if rect is null
     */
    public void remove(PackedRect rect) {
        if (rect == null) {
            throw new IllegalArgumentException("Cannot remove null rect");
        }

        packedRects.remove(rect);

        // Add the freed area back to the free list
        freeRects.add(new Rect(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight()));

        // Prune contained rects — the new free rect may contain or be contained by others
        pruneContained();
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
            usedArea += (long) r.getWidth() * r.getHeight();
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

    /**
     * Returns the bin width.
     *
     * @return bin width in pixels
     */
    public int getBinWidth() {
        return binWidth;
    }

    /**
     * Returns the bin height.
     *
     * @return bin height in pixels
     */
    public int getBinHeight() {
        return binHeight;
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
        int px = placed.getX();
        int py = placed.getY();
        int pw = placed.getWidth();
        int ph = placed.getHeight();
        int px2 = px + pw;
        int py2 = py + ph;

        for (int i = freeRects.size() - 1; i >= 0; i--) {
            Rect free = freeRects.get(i);
            int fx2 = free.x + free.width;
            int fy2 = free.y + free.height;

            // Check if placed rect overlaps this free rect
            if (px >= fx2 || px2 <= free.x || py >= fy2 || py2 <= free.y) {
                continue; // No overlap
            }

            // Remove overlapping free rect
            freeRects.remove(i);

            // Left remainder
            if (px > free.x) {
                freeRects.add(new Rect(free.x, free.y, px - free.x, free.height));
            }

            // Right remainder
            if (px2 < fx2) {
                freeRects.add(new Rect(px2, free.y, fx2 - px2, free.height));
            }

            // Top remainder (above the placed rect)
            if (py > free.y) {
                freeRects.add(new Rect(free.x, free.y, free.width, py - free.y));
            }

            // Bottom remainder (below the placed rect)
            if (py2 < fy2) {
                freeRects.add(new Rect(free.x, py2, free.width, fy2 - py2));
            }
        }
    }

    /**
     * Removes free rects that are fully contained within another free rect.
     *
     * <p>This is the key merge step of MaxRects: rather than explicitly merging
     * adjacent free rects, we rely on the split step producing overlapping free
     * rects and then pruning the smaller ones that are fully covered.</p>
     */
    private void pruneContained() {
        for (int i = freeRects.size() - 1; i >= 0; i--) {
            Rect a = freeRects.get(i);
            for (int j = freeRects.size() - 1; j >= 0; j--) {
                if (i == j) continue;
                Rect b = freeRects.get(j);
                if (contains(b, a)) {
                    freeRects.remove(i);
                    break;
                }
            }
        }
    }

    /**
     * Returns {@code true} if rect {@code outer} fully contains rect {@code inner}.
     */
    private static boolean contains(Rect outer, Rect inner) {
        return inner.x >= outer.x
                && inner.y >= outer.y
                && inner.x + inner.width <= outer.x + outer.width
                && inner.y + inner.height <= outer.y + outer.height;
    }

    // ---------------------------------------------------------------
    //  Internal rect representation
    // ---------------------------------------------------------------

    /**
     * Lightweight mutable rectangle used only for the internal free-rect list.
     * Not exposed in the public API.
     */
    static final class Rect {
        final int x;
        final int y;
        final int width;
        final int height;

        Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return "Rect{" + x + "," + y + " " + width + "x" + height + "}";
        }
    }
}
