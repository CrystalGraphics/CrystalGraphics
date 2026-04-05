package io.github.somehussar.crystalgraphics.text.atlas;

import java.util.ArrayList;
import java.util.List;

/**
 * Guillotine-based 2D bin packer modeled after {@code msdf-atlas-gen}'s
 * {@code RectanglePacker}.
 *
 * <p>This packer implements the same algorithm as the upstream
 * {@code RectanglePacker.cpp}: a single-bin guillotine packer that uses
 * a best-fit scoring function ({@code rateFit = min(sw-w, sh-h)}) and
 * splits free spaces into two rectangles after each placement.</p>
 *
 * <h3>Split Strategy</h3>
 * <p>When a rectangle is placed into a free space, the space is split into
 * two remainder rectangles (one below, one to the right). The split favors
 * extending the larger remainder to minimize fragmentation:</p>
 * <ul>
 *   <li>If {@code w * (spaceH - h) < h * (spaceW - w)}, the bottom
 *       remainder extends to full space width.</li>
 *   <li>Otherwise, the right remainder extends to full space height.</li>
 * </ul>
 *
 * <h3>Fit Scoring</h3>
 * <p>For each candidate (space, rect) pair, the score is
 * {@code min(spaceW - rectW, spaceH - rectH)}. A perfect fit (both
 * dimensions match) is detected and chosen immediately. Otherwise the
 * pair with the lowest score (tightest fit) is selected.</p>
 *
 * <p>This packer supports <strong>insert-only</strong> operation. For
 * use cases requiring removal (LRU eviction), use {@link MaxRectsPacker}
 * instead.</p>
 *
 * <p>This class has zero GL dependencies and is purely computational.</p>
 *
 * @see CgPackingStrategy
 * @see MaxRectsPacker
 */
public class CgGuillotinePacker implements CgPackingStrategy {

    /** Sentinel value indicating no valid fit was found. */
    private static final int WORST_FIT = Integer.MAX_VALUE;

    /** Width of the bin in pixels. */
    private final int binWidth;

    /** Height of the bin in pixels. */
    private final int binHeight;

    /** List of free spaces available for packing. */
    private final List<Rect> spaces;

    /** Number of successfully packed rectangles. */
    private int packedCount;

    /** Total area of packed rectangles (for utilization computation). */
    private long packedArea;

    /**
     * Creates a new guillotine packer with the given bin dimensions.
     *
     * @param binWidth  bin width in pixels (must be positive)
     * @param binHeight bin height in pixels (must be positive)
     * @throws IllegalArgumentException if either dimension is not positive
     */
    public CgGuillotinePacker(int binWidth, int binHeight) {
        if (binWidth <= 0 || binHeight <= 0) {
            throw new IllegalArgumentException(
                    "Bin dimensions must be positive, got: " + binWidth + "x" + binHeight);
        }
        this.binWidth = binWidth;
        this.binHeight = binHeight;
        this.spaces = new ArrayList<Rect>();
        this.spaces.add(new Rect(0, 0, binWidth, binHeight));
        this.packedCount = 0;
        this.packedArea = 0;
    }

    /**
     * Attempts to pack a single rectangle into the bin using best-fit
     * guillotine selection.
     *
     * <p>Scans all free spaces to find the tightest fit for the requested
     * dimensions. A perfect fit (both dimensions match exactly) is chosen
     * immediately. Otherwise the space with the lowest
     * {@code min(spaceW - w, spaceH - h)} is selected.</p>
     *
     * @param width  rectangle width (must be positive)
     * @param height rectangle height (must be positive)
     * @param id     caller-provided identifier
     * @return the packed rectangle with its position, or {@code null} if it does not fit
     * @throws IllegalArgumentException if width or height is not positive
     */
    @Override
    public PackedRect insert(int width, int height, Object id) {
        return insert(width, height, DEFAULT_SPACING_PX, id);
    }

    @Override
    public PackedRect insert(int width, int height, int spacing, Object id) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Rectangle dimensions must be positive, got: " + width + "x" + height);
        }
        if (spacing < 0) {
            throw new IllegalArgumentException("spacing must be >= 0, got: " + spacing);
        }

        int packedWidth = width + spacing;
        int packedHeight = height + spacing;

        int bestFit = WORST_FIT;
        int bestSpace = -1;

        for (int i = 0; i < spaces.size(); i++) {
            Rect space = spaces.get(i);
            // Perfect fit — choose immediately
            if (packedWidth == space.w && packedHeight == space.h) {
                bestSpace = i;
                break;
            }
            // Check if rect fits in space
            if (packedWidth <= space.w && packedHeight <= space.h) {
                int fit = rateFit(packedWidth, packedHeight, space.w, space.h);
                if (fit < bestFit) {
                    bestSpace = i;
                    bestFit = fit;
                }
            }
        }

        if (bestSpace < 0) {
            return null; // Does not fit
        }

        Rect space = spaces.get(bestSpace);
        PackedRect packed = new PackedRect(space.x, space.y, width, height, id);

        // Split the chosen space using guillotine strategy
        splitSpace(bestSpace, packedWidth, packedHeight);

        packedCount++;
        packedArea += (long) packedWidth * packedHeight;
        return packed;
    }

    @Override
    public float utilization() {
        return (float) packedArea / ((long) binWidth * binHeight);
    }

    @Override
    public int getPackedCount() {
        return packedCount;
    }

    @Override
    public int getBinWidth() {
        return binWidth;
    }

    @Override
    public int getBinHeight() {
        return binHeight;
    }

    // ── Internal: fit scoring ──────────────────────────────────────────

    /**
     * Upstream-parity fit scoring: {@code min(spaceW - rectW, spaceH - rectH)}.
     *
     * <p>This matches the {@code RectanglePacker::rateFit} in msdf-atlas-gen.
     * Lower scores indicate tighter fits.</p>
     */
    private static int rateFit(int w, int h, int sw, int sh) {
        return Math.min(sw - w, sh - h);
    }

    // ── Internal: guillotine split ─────────────────────────────────────

    /**
     * Splits the free space at the given index after placing a rectangle
     * of dimensions ({@code w}, {@code h}) at its origin.
     *
     * <p>Produces up to two new free spaces:</p>
     * <ul>
     *   <li><strong>a</strong> — below the placed rect: {@code (x, y+h, ?, space.h-h)}</li>
     *   <li><strong>b</strong> — to the right of the placed rect: {@code (x+w, y, space.w-w, ?)}</li>
     * </ul>
     *
     * <p>The split orientation is chosen to maximize the larger remainder's
     * area, matching upstream: if {@code w*(space.h-h) < h*(space.w-w)},
     * then {@code a} extends to full space width; otherwise {@code b}
     * extends to full space height.</p>
     */
    private void splitSpace(int index, int w, int h) {
        Rect space = spaces.get(index);

        // Remove the consumed space using swap-with-last for O(1) removal
        int lastIdx = spaces.size() - 1;
        if (index != lastIdx) {
            spaces.set(index, spaces.get(lastIdx));
        }
        spaces.remove(lastIdx);

        // Compute remainder rectangles
        int aw, bh;
        // Upstream split heuristic: extend the larger remainder
        if ((long) w * (space.h - h) < (long) h * (space.w - w)) {
            aw = space.w;  // bottom remainder extends full width
            bh = h;        // right remainder is only as tall as placed rect
        } else {
            aw = w;            // bottom remainder is only as wide as placed rect
            bh = space.h;      // right remainder extends full height
        }

        // Add non-zero remainders
        int ah = space.h - h;
        if (aw > 0 && ah > 0) {
            spaces.add(new Rect(space.x, space.y + h, aw, ah));
        }

        int bw = space.w - w;
        if (bw > 0 && bh > 0) {
            spaces.add(new Rect(space.x + w, space.y, bw, bh));
        }
    }

    // ── Internal rect representation ──────────────────────────────────

    /**
     * Lightweight immutable rectangle for the internal free-space list.
     * Not exposed in the public API.
     */
    static final class Rect {
        final int x;
        final int y;
        final int w;
        final int h;

        Rect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public String toString() {
            return "Rect{" + x + "," + y + " " + w + "x" + h + "}";
        }
    }
}
