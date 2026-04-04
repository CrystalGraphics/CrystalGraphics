package io.github.somehussar.crystalgraphics.text;

/**
 * Callback for re-shaping a sub-range of a {@link CgShapedRun}'s source text.
 *
 * <p>Used by {@link CgLineBreaker} to split a run at word boundaries without
 * slicing glyph arrays directly. The implementation delegates to HarfBuzz
 * through the shaper, producing correctly shaped fragments.</p>
 */
public interface RunReshaper {

    /**
     * Re-shape a sub-range of the given run's source text.
     *
     * @param run       original run that carries source-text context
     * @param subStart  start index (inclusive) into the run's source text segment
     * @param subEnd    end index (exclusive) into the run's source text segment
     * @return newly shaped run for the sub-range, or {@code null} if re-shaping is unavailable
     */
    CgShapedRun reshape(CgShapedRun run, int subStart, int subEnd);
}
