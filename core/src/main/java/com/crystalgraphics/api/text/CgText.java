package com.crystalgraphics.api.text;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.text.render.CgTextBatch;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * Text to draw: what it says, in which font, size and style, and where it wraps. A value: made once, shared between
 * threads, compared by value, and measured on any thread. A canvas draws it where it is told.
 *
 * <pre>{@code
 * static final CgText TITLE = CgText.of("Spawn").font(font).size(24).style(HEADING);
 *
 * canvas.text(TITLE, 20, 40);                                      // white unless the style says otherwise
 * canvas.text(TITLE.text("Respawn"), 20, 40, 0xFFFFD040);          // another string, tinted
 * float wide = TITLE.measure().totalWidth();                       // the document's thread
 * }</pre>
 *
 * <p>Wrapping, in the canvas's units: under a scaled canvas it wraps where {@link #measure} does, its glyphs rasterised
 * at the size on screen.</p>
 *
 * <pre>{@code
 * CgText note = CgText.of(body).family(ui).size(14).maxWidth(320);
 * float tall = note.measure().totalHeight();
 * }</pre>
 *
 * <ul>
 *   <li>One of {@link #text}, {@link #paragraph} or {@link #layout}: a layout draws as built and ignores the rest but
 *       its style.</li>
 *   <li>A font: {@link #family} over {@link #font} over the style's own; a non-empty layout names its own.</li>
 *   <li>{@link #size} resizes the font; unset ({@code -1}), the font must already have a size.</li>
 *   <li>{@code maxWidth} and {@code maxHeight} of 0 are unbounded.</li>
 *   <li>Contained, the default, moves the text in by its style's outlines and shadow offsets, as Slate keeps them in
 *       the box; {@code contained(false)} draws at the point given and lets them overflow, as CSS does.</li>
 * </ul>
 *
 * @param size      the pixel size its font is drawn at, {@code -1} for the font's own
 * @param maxWidth  where it wraps, in the canvas's units; 0 for never
 * @param maxHeight where it stops; 0 for never
 * @param contained whether its style's outlines and shadow offsets stay inside its box
 */
public record CgText(@Nullable String text, @Nullable CgShapedParagraph paragraph, @Nullable CgTextLayout layout,
                     @Nullable CgFont font, @Nullable CgFontFamily family, int size, CgTextStyle style,
                     float maxWidth, float maxHeight, boolean contained) {

    public CgText {
        Objects.requireNonNull(style, "style");
        if (text == null && paragraph == null && layout == null) {
            throw new IllegalArgumentException("text needs a string, a paragraph or a layout");
        }
        if (size <= 0 && size != -1) throw new IllegalArgumentException("size must be positive, or -1 for the font's own: " + size);
    }

    /** {@code text}, laid out when drawn or measured: in the plain style, needing a font. */
    public static CgText of(String text) {
        return new CgText(Objects.requireNonNull(text, "text"), null, null, null, null, -1, CgTextStyle.PLAIN, 0f, 0f, true);
    }

    /** A shaped paragraph, wrapped at {@link #maxWidth} each time it is drawn or measured. */
    public static CgText of(CgShapedParagraph paragraph) {
        return new CgText(null, Objects.requireNonNull(paragraph, "paragraph"), null, null, null, -1, CgTextStyle.PLAIN,
                0f, 0f, true);
    }

    /** A layout, drawn as built: its fonts, size and wrapping are its own. */
    public static CgText of(CgTextLayout layout) {
        return new CgText(null, null, Objects.requireNonNull(layout, "layout"), null, null, -1, CgTextStyle.PLAIN, 0f, 0f, true);
    }

    /** The same, saying {@code text}. */
    public CgText text(String text) {
        return new CgText(Objects.requireNonNull(text, "text"), null, null, font, family, size, style, maxWidth, maxHeight, contained);
    }

    /** In {@code font}. Under {@link #family}. */
    public CgText font(CgFont font) {
        return new CgText(text, paragraph, layout, font, family, size, style, maxWidth, maxHeight, contained);
    }

    /** In {@code family}, with its fallbacks. Over {@link #font}. */
    public CgText family(CgFontFamily family) {
        return new CgText(text, paragraph, layout, font, family, size, style, maxWidth, maxHeight, contained);
    }

    /** At {@code px} pixels. */
    public CgText size(int px) {
        return new CgText(text, paragraph, layout, font, family, px, style, maxWidth, maxHeight, contained);
    }

    /** In {@code style}: its layers, spacing and skew, and its font where none is named. */
    public CgText style(CgTextStyle style) {
        return new CgText(text, paragraph, layout, font, family, size, style, maxWidth, maxHeight, contained);
    }

    /** Wrapping at {@code width}; 0 never wraps. */
    public CgText maxWidth(float width) {
        return new CgText(text, paragraph, layout, font, family, size, style, width, maxHeight, contained);
    }

    /** Stopping at {@code height}; 0 never stops. */
    public CgText maxHeight(float height) {
        return new CgText(text, paragraph, layout, font, family, size, style, maxWidth, height, contained);
    }

    /** Whether its style's outlines and shadow offsets stay inside its box; {@code false} lets them overflow. */
    public CgText contained(boolean contained) {
        return new CgText(text, paragraph, layout, font, family, size, style, maxWidth, maxHeight, contained);
    }

    /** Its layout, as a canvas draws it at scale 1: its size, lines and glyph positions. Any thread. */
    public CgTextLayout measure() {
        return CgTextBatch.measure(this);
    }
}
