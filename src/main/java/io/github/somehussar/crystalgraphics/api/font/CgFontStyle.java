package io.github.somehussar.crystalgraphics.api.font;

/**
 * Font style variants for font registration.
 *
 * <p>Each style corresponds to a distinct font face or synthetic variant.
 * When registering a font via {@link CgFontKey}, the style determines
 * which face within the font family is selected.</p>
 */
public enum CgFontStyle {

    /** Normal (regular) weight, upright. */
    REGULAR,

    /** Bold weight, upright. */
    BOLD,

    /** Normal weight, italic. */
    ITALIC,

    /** Bold weight, italic. */
    BOLD_ITALIC
}
