package io.github.somehussar.crystalgraphics.text.atlas;

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
 * @see CgGuillotinePacker
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
    PackedRect insert(int width, int height, Object id);

    /**
     * Attempts to pack a rectangle while reserving trailing spacing in allocator space.
     * The returned rectangle still reports the original glyph width/height so UVs
     * and plane bounds remain tied to the visible glyph box.
     */
    PackedRect insert(int width, int height, int spacing, Object id);

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
