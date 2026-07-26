package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.text.CgShapedRun;

/**
 * Callback for re-shaping a sub-range of a paragraph's source text.
 *
 * <p>Used by {@link CgLineBreaker} to split a run at word boundaries without
 * slicing glyph arrays directly. The implementation delegates to HarfBuzz
 * through the shaper, producing correctly shaped fragments.</p>
 */
public interface RunReshaper {

    /**
     * Re-shape a sub-range of the paragraph text carried by {@code context}.
     *
     * @param context   the paragraph's source-text context
     * @param run       original run being split (supplies font key and direction)
     * @param subStart  start index (inclusive) into {@code context.sourceText()}
     * @param subEnd    end index (exclusive) into {@code context.sourceText()}
     * @return newly shaped run for the sub-range, or {@code null} if re-shaping is unavailable
     */
    CgShapedRun reshape(CgReshapeContext context, CgShapedRun run, int subStart, int subEnd);
}
