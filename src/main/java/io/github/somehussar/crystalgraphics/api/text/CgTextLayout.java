package io.github.somehussar.crystalgraphics.api.text;

import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of the text layout pipeline.
 *
 * <p>A {@code CgTextLayout} contains the final laid-out text: lines of shaped runs
 * in visual order, total dimensions, and the font metrics used for layout. This is
 * the output consumed by the text renderer for drawing.</p>
 *
 * <h3>Structure</h3>
 * <ul>
 *   <li>{@code lines} — outer list = lines (top to bottom), inner list = runs in visual
 *       order (left to right after BiDi reordering). Each {@link CgShapedRun} contains
 *       glyph IDs, advances, and offsets in pixels.</li>
 *   <li>{@code totalWidth} — width of the widest line in pixels.</li>
 *   <li>{@code totalHeight} — sum of all line heights in pixels.</li>
 *   <li>{@code metrics} — the font metrics used during layout (ascender, descender,
 *       line height).</li>
 * </ul>
 *
 * <h3>Immutability</h3>
 * <p>This class is immutable (Lombok {@code @Value}). The inner lists should not be
 * modified after construction — defensive copies are recommended if the caller retains
 * mutable references.</p>
 *
 * <h3>Public Text API</h3>
 * <p>This type lives in {@code api/text} because it is a <strong>public domain
 * concept</strong> — the canonical output of text layout that external callers
 * receive and pass to the renderer. Internal pipeline machinery (shaping,
 * line-breaking, layout engine) produces this type but does not own it.</p>
 *
 * <h3>Known API-boundary leak: {@code resolvedFontsByKey}</h3>
 * <p>The {@link #resolvedFontsByKey} field exposes a {@code Map<CgFontKey, CgFont>}
 * that maps each font key used in this layout's runs to its resolved
 * {@link io.github.somehussar.crystalgraphics.api.font.CgFont CgFont} handle.
 * {@code CgFont} is a heavyweight object carrying native FreeType/HarfBuzz state,
 * so this map leaks internal font-management concerns into what is otherwise a
 * pure layout-result DTO.</p>
 *
 * <p><strong>Why it is exposed today:</strong> the text renderer needs the resolved
 * font handles to look up glyph atlas entries at draw time, and no intermediate
 * render-context object exists yet to carry them separately. A convenience
 * constructor that defaults this field to an empty map is provided for callers
 * that do not need font resolution (e.g., metrics-only queries).</p>
 *
 * <p><strong>Future direction:</strong> once a dedicated render-context or
 * font-resolver service is introduced, this field should migrate off the public
 * layout result. Callers should avoid depending on the {@code CgFont} values
 * for anything beyond passing them back into the renderer.</p>
 *
 * @see CgShapedRun
 * @see CgTextConstraints
 */
@Value
public class CgTextLayout {

    /** Lines of shaped runs in visual order. Outer = lines, inner = runs. */
    List<List<CgShapedRun>> lines;

    /** Width of the widest line in logical layout pixels. */
    float totalWidth;

    /** Total height of all lines in logical layout pixels. */
    float totalHeight;

    /** Font metrics used during layout. */
    CgFontMetrics metrics;

    /**
     * Resolved font handles keyed by {@link CgFontKey}, needed by the renderer to
     * look up atlas entries at draw time.
     *
     * <p><strong>API-boundary note:</strong> this field is an acknowledged leak of
     * internal font-management state. {@link CgFont} carries native FreeType/HarfBuzz
     * resources and should not be retained beyond the current render pass. See the
     * class-level Javadoc for rationale and future direction.</p>
     */
    Map<CgFontKey, CgFont> resolvedFontsByKey;

    /**
     * Full constructor including resolved font handles for render-time atlas lookup.
     *
     * @param lines              lines of shaped runs in visual order
     * @param totalWidth         width of the widest line in logical layout pixels
     * @param totalHeight        total height of all lines in logical layout pixels
     * @param metrics            font metrics used during layout
     * @param resolvedFontsByKey resolved font handles needed by the renderer;
     *                           see the class-level Javadoc for API-boundary caveats
     */
    public CgTextLayout(List<List<CgShapedRun>> lines,
                        float totalWidth,
                        float totalHeight,
                        CgFontMetrics metrics,
                        Map<CgFontKey,CgFont> resolvedFontsByKey) {
        this.lines = lines;
        this.totalWidth = totalWidth;
        this.totalHeight = totalHeight;
        this.metrics = metrics;
        this.resolvedFontsByKey = resolvedFontsByKey;
    }

    /**
     * Convenience constructor that defaults {@link #resolvedFontsByKey} to an empty map.
     *
     * <p>Use this when font handles are not needed (e.g., metrics-only queries or
     * test fixtures). Runs in the resulting layout will still carry their
     * {@link CgShapedRun#getFontKey() fontKey} for identification.</p>
     */
    public CgTextLayout(List<List<CgShapedRun>> lines,
                        float totalWidth,
                        float totalHeight,
                        CgFontMetrics metrics) {
        this(lines, totalWidth, totalHeight, metrics,
                Collections.emptyMap());
    }
}
