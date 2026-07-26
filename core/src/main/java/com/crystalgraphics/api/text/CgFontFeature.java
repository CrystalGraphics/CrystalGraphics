package com.crystalgraphics.api.text;

/**
 * A single OpenType feature tag + value, mirroring HarfBuzz's {@code hb_feature_t}
 * (minus the character-range fields, which don't apply here — a feature on a
 * {@link CgStyleSpan} already applies to that span's whole range).
 *
 * <p>Lives in {@code api/text} rather than {@code api/font} because its only
 * consumer today is {@link CgStyleSpan} — a text-styling concept — not the font
 * loading/resolution machinery in {@code api/font}.</p>
 *
 * @param tag   exactly 4 ASCII characters, e.g. {@code "smcp"} (small caps),
 *              {@code "tnum"} (tabular figures), {@code "liga"} (ligatures)
 * @param value feature value; {@code 1} enables, {@code 0} disables, some
 *              features (e.g. stylistic sets) accept other positive values
 */
public record CgFontFeature(String tag, int value) {

    public CgFontFeature {
        if (tag == null || tag.length() != 4) {
            throw new IllegalArgumentException("tag must be exactly 4 characters: " + tag);
        }
    }

    /** Convenience for the common enable-this-feature case ({@code value = 1}). */
    public static CgFontFeature enable(String tag) {
        return new CgFontFeature(tag, 1);
    }
}
