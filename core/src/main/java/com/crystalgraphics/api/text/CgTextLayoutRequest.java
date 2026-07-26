package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgTextLayoutBuilder;
import com.crystalgraphics.text.richtext.CgMarkupParser;

/**
 * The public entry point for the text layout pipeline — replaces the old
 * {@code CgTextLayoutEngine}/{@code CgTextLayoutBuilder} {@code layout(...)} overload
 * family outright (that family is deleted, not kept alongside this).
 *
 * <h3>Usage</h3>
 * <pre>
 * CgTextLayout layout = CgTextLayoutRequest.obtain("Hello, world!", font)
 *         .maxWidth(400)
 *         .align(CgTextAlign.CENTER)
 *         .build();
 * </pre>
 *
 * <h3>{@code shape()} vs. {@code build()}</h3>
 * <p>{@link #build()} is {@code shape().layout(maxWidth(), maxHeight())} — the only thing
 * most callers need. {@link #shape()} returns a retained {@link CgShapedParagraph} for
 * callers that need to re-wrap the same parsed/shaped content at a different width later
 * without re-running BiDi/style-span splitting or HarfBuzz shaping — see that type's
 * javadoc.</p>
 */
public final class CgTextLayoutRequest {

    private static final CgTextLayoutBuilder BUILDER = new CgTextLayoutBuilder();

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

    private float maxWidth;
    private float maxHeight;
    private int maxLines;
    private String ellipsisMarker;
    private CgTextAlign align = CgTextAlign.LEFT;
    private CgTextDirection direction = CgTextDirection.AUTO;
    private float lineHeightOverride;
    private float tabStopWidth;

    private CgTextLayoutRequest(String plainText, CgStyledText styledText, CgFontFamily family, CgFontFamilyGroup group) {
        this.plainText = plainText;
        this.styledText = styledText;
        this.family = family;
        this.group = group;
    }

    public static CgTextLayoutRequest of(String text, CgFontFamily family) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        return new CgTextLayoutRequest(text, null, family, null);
    }

    public static CgTextLayoutRequest of(String text, CgFont font) {
        if (font == null) throw new IllegalArgumentException("font must not be null");
        return of(text, CgFontFamily.of(font));
    }

    public static CgTextLayoutRequest of(CgStyledText text, CgFontFamilyGroup group) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        return new CgTextLayoutRequest(null, text, null, group);
    }

    /**
     * Raw text against a style group — the form to use with {@link #markup(CgMarkupParser)},
     * since resolving a bold/italic span needs the group's per-style families.
     */
    public static CgTextLayoutRequest of(String text, CgFontFamilyGroup group) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        return new CgTextLayoutRequest(text, null, null, group);
    }

    /**
     * Parses this request's text as markup with {@code parser} instead of treating it as
     * literal text, replacing the {@code parse(...)}-then-{@code obtain(...)} two-step:
     *
     * <pre>
     * // before
     * CgTextLayoutRequest.obtain(new CgHtmlLikeMarkupParser().parse(src), group)
     * // after
     * CgTextLayoutRequest.obtain(src, group).parser(CgMarkupParser.HTML)
     * </pre>
     *
     * <p>Only meaningful for the {@code String}-text {@code obtain} overloads; a request
     * already built from a {@link CgStyledText} is parsed by definition.</p>
     */
    public CgTextLayoutRequest markup(CgMarkupParser parser) {
        if (styledText != null) {
            throw new IllegalStateException("parser(...) is meaningless on an already-parsed CgStyledText request");
        }
        this.parser = parser;
        return this;
    }

    /** {@link #markup(CgMarkupParser)} by registered name — see {@link CgMarkupParser#require}. */
    public CgTextLayoutRequest markup(String parserName) {
        return markup(CgMarkupParser.require(parserName));
    }

    public CgTextLayoutRequest maxWidth(float w) {
        this.maxWidth = w;
        return this;
    }

    public CgTextLayoutRequest maxHeight(float h) {
        this.maxHeight = h;
        return this;
    }

    public CgTextLayoutRequest maxLines(int n) {
        this.maxLines = n;
        return this;
    }

    /** {@code null} (the default) means truncated content is dropped with no marker. */
    public CgTextLayoutRequest ellipsis(String marker) {
        this.ellipsisMarker = marker;
        return this;
    }

    public CgTextLayoutRequest align(CgTextAlign align) {
        this.align = align;
        return this;
    }

    public CgTextLayoutRequest direction(CgTextDirection direction) {
        this.direction = direction;
        return this;
    }

    /** {@code <= 0} (the default) uses each line's real computed height. */
    public CgTextLayoutRequest lineHeightOverride(float px) {
        this.lineHeightOverride = px;
        return this;
    }

    /** {@code <= 0} (the default) disables tab-stop expansion. See {@link CgParagraphKnobs#tabStopWidth()}. */
    public CgTextLayoutRequest tabStopWidth(float px) {
        this.tabStopWidth = px;
        return this;
    }

    /** The "built, not yet wrapped" checkpoint — see {@link CgShapedParagraph}. */
    public CgShapedParagraph shape() {
        CgParagraphKnobs knobs = new CgParagraphKnobs(direction, tabStopWidth, align, maxLines, ellipsisMarker, lineHeightOverride);
        CgStyledText resolved = styledText != null ? styledText
                : parser != null ? parser.parse(plainText)
                : null;
        if (resolved != null) {
            if (group == null) {
                throw new IllegalStateException(
                        "markup(...) needs a CgFontFamilyGroup — use obtain(text, group), not obtain(text, family/font)");
            }
            return BUILDER.shapeStyled(resolved, group, knobs);
        }
        return BUILDER.shape(plainText, family, knobs);
    }

    /** {@code shape().layout(maxWidth(), maxHeight())} — the common case. */
    public CgTextLayout build() {
        return shape().layout(maxWidth, maxHeight);
    }
}
