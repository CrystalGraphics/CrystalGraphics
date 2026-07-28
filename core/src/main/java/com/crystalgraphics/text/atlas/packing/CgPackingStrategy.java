package com.crystalgraphics.text.atlas.packing;

/**
 * Strategy interface for 2D rectangle bin packing within a single atlas page.
 *
 * <p>Implementations provide a specific packing heuristic (e.g., guillotine,
 * skyline, MaxRects). The paged atlas system uses one strategy instance per
 * page to allocate glyph boxes.</p>
 *
 * <p>All implementations must be <strong>stateful</strong> — they track
 * allocated space internally. The contract is insert-only for paged atlases
 * (no removal in the default path). Implementations may optionally support
 * removal for backward-compatible LRU eviction flows.</p>
 *
 * @see MaxRectsPacker
 */
public interface CgPackingStrategy {

    int DEFAULT_SPACING_PX = 0;

    /**
     * Attempts to pack a rectangle of the given dimensions into the bin.
     *
     * @param width  rectangle width in pixels (must be positive)
     * @param height rectangle height in pixels (must be positive)
     * @param id     caller-provided identifier (e.g., CgGlyphKey)
     * @return the packed rectangle with position, or {@code null} if it does not fit
     */
    MaxRectsPacker.PackedRect insert(int width, int height, Object id);

    /**
     * Attempts to pack a rectangle while reserving trailing spacing in allocator space.
     * The returned rectangle still reports the original glyph width/height so UVs
     * and plane bounds remain tied to the visible glyph box.
     */
    MaxRectsPacker.PackedRect insert(int width, int height, int spacing, Object id);

    /**
     * Returns the utilization ratio of the bin (packed area / total area).
     *
     * @return utilization in {@code [0.0, 1.0]}
     */
    float utilization();

    /**
     * Returns the number of currently packed rectangles.
     *
     * @return count of packed rects
     */
    int getPackedCount();

    /**
     * Cheap conservative test for whether {@code width x height} could <em>possibly</em> be
     * placed, used to reject a hopeless bin without paying for a full {@link #insert} scan.
     *
     * <p><strong>Must never return {@code false} for something that would actually fit</strong>
     * — a false negative silently strands space forever. Returning {@code true} for something
     * that turns out not to fit is fine and expected; the caller simply falls through to a real
     * {@code insert} attempt.</p>
     *
     * <p>This exists so a multi-page atlas can scan its pages oldest-first (which is what
     * actually refills gaps left in earlier pages) without that scan costing a full free-list
     * walk per page per glyph. The default implementation is the always-safe {@code true},
     * so a strategy that cannot answer cheaply simply opts out.</p>
     *
     * @return {@code false} only when placement is provably impossible
     */
    default boolean mayFit(int width, int height) {
        return true;
    }

    /**
     * Diagnostic: how many free regions could still accept a {@code width x height} rectangle.
     *
     * <p>Unlike {@link #mayFit} this is exact, not conservative — it is for atlas-dump reporting
     * and tests, answering "is the empty space in this page actually usable, or is it slivers
     * too small for any glyph?" A page can look half-empty in a dump image while holding zero
     * usable regions, and the distinction decides whether poor fill is an allocator bug or an
     * inherent packing remainder.</p>
     *
     * @return count of free regions that would accept the given size; {@code -1} if the
     *         strategy cannot report it
     */
    default int countFreeRegionsFitting(int width, int height) {
        return -1;
    }

    /** Diagnostic companion to {@link #countFreeRegionsFitting}: total free area in pixels, or {@code -1}. */
    default long totalFreeArea() {
        return -1;
    }

    /**
     * Diagnostic: the free regions themselves, each as {@code {x, y, width, height}}, largest
     * area first. Counting regions answers "how many fit?"; this answers "what shape is the
     * space?" — the difference between a stranded 82px strip (unusable, inherent) and a 168px
     * one (usable, meaning the allocator failed to place something in it).
     *
     * @return free regions, or an empty array if the strategy cannot report them
     */
    default int[][] describeFreeRegions() {
        return new int[0][];
    }

    /**
     * Returns the bin width in pixels.
     *
     * @return bin width
     */
    int getBinWidth();

    /**
     * Returns the bin height in pixels.
     *
     * @return bin height
     */
    int getBinHeight();
}
