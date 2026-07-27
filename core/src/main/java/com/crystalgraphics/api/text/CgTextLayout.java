package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.text.layout.CgTextLayoutEngine;
import com.crystalgraphics.text.richtext.CgMarkupParser;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * Immutable result of the text layout pipeline.
 *
 * <p>A {@code CgTextLayout} contains the final laid-out text: lines of shaped runs
 * in visual order, total dimensions, the font metrics used for layout, and a
 * {@link CgBakedGlyphs baked flat glyph structure} pre-computed once by the layout
 * engine. This is the output consumed by the text renderer for drawing.</p>
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
 *   <li>{@code baked} — flat per-glyph pen positions/font identity/line metadata;
 *       see {@link CgBakedGlyphs}. Consumers that need to draw or measure every glyph
 *       should read this instead of re-walking {@link #lines}.</li>
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
 * <h3>Building a layout — {@link #of}/{@link Request}</h3>
 * <p>{@link #of} is the public entry point for the text layout pipeline — replaces the
 * old {@code CgTextLayoutEngine}/{@code CgTextLayoutBuilder} {@code layout(...)} overload
 * family outright (that family is deleted, not kept alongside this):
 * <pre>
 * CgTextLayout layout = CgTextLayout.of("Hello, world!", font)
 *         .maxWidth(400)
 *         .align(CgTextAlign.CENTER)
 *         .build();
 * </pre>
 *
 * <h3>{@code shape()} vs. {@code build()}</h3>
 * <p>{@link Request#build()} is {@code shape().layout(maxWidth(), maxHeight())} — the only
 * thing most callers need. {@link Request#shape()} returns a retained
 * {@link CgShapedParagraph} for callers that need to re-wrap the same parsed/shaped content
 * at a different width later without re-running BiDi/style-span splitting or HarfBuzz
 * shaping — see that type's javadoc.</p>
 *
 * @see CgShapedRun
 * @see CgBakedGlyphs
 * @param lines  Lines of shaped runs in visual order. Outer = lines, inner = runs.
 * @param totalWidth  Width of the widest line in logical layout pixels.
 * @param totalHeight  Total height of all lines in logical layout pixels.
 * @param metrics  Font metrics used during layout.
 * @param baked  Flat, per-glyph baked layout data; see {@link CgBakedGlyphs}.
 */
public record CgTextLayout(List<List<CgShapedRun>> lines, float totalWidth, float totalHeight, CgFontMetrics metrics,
                           CgBakedGlyphs baked) {

    /**
     * Full constructor including baked glyph data for render-time consumption.
     *
     * @param lines       lines of shaped runs in visual order
     * @param totalWidth  width of the widest line in logical layout pixels
     * @param totalHeight total height of all lines in logical layout pixels
     * @param metrics     font metrics used during layout
     * @param baked       flat per-glyph baked layout data
     */
    public CgTextLayout {}

    /**
     * Convenience constructor that defaults {@link #baked} to {@link CgBakedGlyphs#EMPTY}.
     *
     * <p>Use this for test fixtures or metrics-only queries that never read baked
     * glyph data.</p>
     */
    public CgTextLayout(List<List<CgShapedRun>> lines, float totalWidth, float totalHeight, CgFontMetrics metrics) {
        this(lines, totalWidth, totalHeight, metrics, CgBakedGlyphs.EMPTY);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  REQUEST — fluent entry point into the layout pipeline
    // ══════════════════════════════════════════════════════════════════════════════════════════

    public static Request of(String text, CgFontFamily family) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        return new Request(text, null, family, null);
    }

    public static Request of(String text, CgFont font) {
        if (font == null) throw new IllegalArgumentException("font must not be null");
        return of(text, CgFontFamily.of(font));
    }

    public static Request of(CgStyledText text, CgFontFamilyGroup group) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        return new Request(null, text, null, group);
    }

    /**
     * Raw text against a style group — the form to use with {@link Request#markup(CgMarkupParser)},
     * since resolving a bold/italic span needs the group's per-style families.
     */
    public static Request of(String text, CgFontFamilyGroup group) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        return new Request(text, null, null, group);
    }

    /**
     * Fluent builder for a {@link CgTextLayout} — see the class javadoc's "Building a layout"
     * section. Construct via one of {@link CgTextLayout}'s {@code of(...)} overloads, chain
     * the knob setters below, then call {@link #build()} (or {@link #shape()} to retain a
     * re-wrappable {@link CgShapedParagraph} instead).
     */
    public static final class Request {

        private final String plainText;
        private final CgStyledText styledText;
        private final CgFontFamily family;
        private final CgFontFamilyGroup group;

        /**
         * When set, {@link #plainText} is markup to be parsed at {@link #shape()} time rather than
         * literal text — see {@link #markup(CgMarkupParser)}. Kept deferred instead of parsing
         * eagerly in {@code parser(...)} so the call order of the builder chain doesn't matter.
         */
        private CgMarkupParser parser;

        @Setter
        @Accessors(fluent = true, chain = true)
        private float maxWidth;
        @Setter
        @Accessors(fluent = true, chain = true)
        private float maxHeight;
        @Setter
        @Accessors(fluent = true, chain = true)
        private int maxLines;
        /** {@code null} (the default) means truncated content is dropped with no marker. */
        @Setter
        @Accessors(fluent = true, chain = true)
        private String ellipsis;
        @Setter
        @Accessors(fluent = true, chain = true)
        private CgTextAlign align = CgTextAlign.LEFT;
        @Setter
        @Accessors(fluent = true, chain = true)
        private CgTextDirection direction = CgTextDirection.AUTO;
        /** {@code <= 0} (the default) uses each line's real computed height. */
        @Setter
        @Accessors(fluent = true, chain = true)
        private float lineHeightOverride;
        /** {@code <= 0} (the default) disables tab-stop expansion. See {@link CgParagraphKnobs#tabStopWidth()}. */
        @Setter
        @Accessors(fluent = true, chain = true)
        private float tabStopWidth;

        private Request(String plainText, CgStyledText styledText, CgFontFamily family, CgFontFamilyGroup group) {
            this.plainText = plainText;
            this.styledText = styledText;
            this.family = family;
            this.group = group;
        }

        /**
         * Parses this request's text as markup with {@code parser} instead of treating it as
         * literal text, replacing the {@code parse(...)}-then-{@code obtain(...)} two-step:
         *
         * <pre>
         * // before
         * CgTextLayout.of(new CgHtmlLikeMarkupParser().parse(src), group)
         * // after
         * CgTextLayout.of(src, group).markup(CgMarkupParser.HTML)
         * </pre>
         *
         * <p>Only meaningful for the {@code String}-text {@code of} overloads; a request
         * already built from a {@link CgStyledText} is parsed by definition.</p>
         */
        public Request markup(CgMarkupParser parser) {
            if (styledText != null) throw new IllegalStateException("markup(...) is meaningless on an already-parsed CgStyledText request");
            this.parser = parser;
            return this;
        }

        /** {@link #markup(CgMarkupParser)} by registered name — see {@link CgMarkupParser#require}. */
        public Request markup(String parserName) {
            return markup(CgMarkupParser.require(parserName));
        }

        /** The "built, not yet wrapped" checkpoint — see {@link CgShapedParagraph}. */
        public CgShapedParagraph shape() {
            CgParagraphKnobs knobs = new CgParagraphKnobs(direction, tabStopWidth, align, maxLines, ellipsis, lineHeightOverride);
            CgStyledText resolved = styledText != null ? styledText : parser != null ? parser.parse(plainText) : null;
            if (resolved != null) {
                if (group == null) throw new IllegalStateException("markup(...) needs a CgFontFamilyGroup — use of(text, group), not of(text, family/font)");
                
                return CgTextLayoutEngine.shapeStyled(resolved, group, knobs);
            }
            return CgTextLayoutEngine.shape(plainText, family, knobs);
        }

        /** {@code shape().layout(maxWidth(), maxHeight())} — the common case. */
        public CgTextLayout build() {
            return shape().layout(maxWidth, maxHeight);
        }
    }
}
