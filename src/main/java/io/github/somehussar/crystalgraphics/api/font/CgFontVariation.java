package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Immutable OpenType variable-font axis selection.
 *
 * <p>The {@code tag} is the four-character OpenType axis tag such as
 * {@code wght}, {@code wdth}, or {@code opsz}. The {@code value} is the
 * requested design-space coordinate for that axis.</p>
 */
@Value
public class CgFontVariation {

    String tag;
    float value;

    public CgFontVariation(String tag, float value) {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }
        String normalized = tag.trim();
        if (normalized.length() != 4) {
            throw new IllegalArgumentException(
                    "Variation axis tag must be exactly 4 characters, got: '" + tag + "'");
        }
        this.tag = normalized;
        this.value = value;
    }
}
