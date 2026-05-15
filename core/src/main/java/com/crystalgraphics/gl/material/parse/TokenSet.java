package com.crystalgraphics.gl.material.parse;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Package-private immutable lookup table mapping canonical keyword strings to typed values.
 *
 * <p>Keys are stored in lower-case; {@link #parse} normalises the incoming token to lower-case
 * before lookup, making all matches case-insensitive. The display string ({@link #validValues()})
 * uses the upper-case canonical form for readable error messages.</p>
 *
 * <p>Instances are created via {@link Builder} and are immutable after construction.
 * All {@code TokenSet} instances live as {@code static final} constants on
 * {@link CgShaderKeywords} — no other class should instantiate them.</p>
 *
 * @param <V> the value type stored per token (e.g. {@code Integer} for GL constants,
 *            {@code Boolean} for ON/OFF)
 */
final class TokenSet<V> {

    private final Map<String, V> table;
    private final String validStr;

    private TokenSet(Map<String, V> table, String validStr) {
        this.table    = table;
        this.validStr = validStr;
    }

    /**
     * Looks up {@code token} (case-insensitive). Returns the associated value on success.
     *
     * @param token   the raw token from the shader source
     * @param context human-readable keyword name used in the error message
     * @throws CgShaderParseException if the token is not in this set
     */
    V parse(String token, String context) throws CgShaderParseException {
        if (token == null) {
            throw new CgShaderParseException(
                    "Expected value for '" + context + "' but got nothing. Valid: " + validStr);
        }
        V value = table.get(token.toLowerCase(Locale.ROOT));
        if (value == null) {
            throw new CgShaderParseException(
                    "Invalid value '" + token + "' for " + context + ". Expected one of: " + validStr);
        }
        return value;
    }

    /**
     * Returns {@code true} if {@code token} is in this set (case-insensitive).
     * Never throws.
     */
    boolean accepts(String token) {
        return token != null && table.containsKey(token.toLowerCase(Locale.ROOT));
    }

    String validValues() {
        return validStr;
    }

    static <V> Builder<V> builder() {
        return new Builder<>();
    }

    static final class Builder<V> {
        // LinkedHashMap preserves insertion order for the validStr display string
        private final Map<String, V> table = new LinkedHashMap<>();
        private final StringBuilder  display = new StringBuilder();

        /**
         * Adds a canonical keyword (stored lower-case) mapped to {@code value}.
         * The upper-case form of {@code canonical} is appended to the display string.
         */
        Builder<V> put(String canonical, V value) {
            table.put(canonical.toLowerCase(Locale.ROOT), value);
            if (display.length() > 0) display.append(" | ");
            display.append(canonical.toUpperCase(Locale.ROOT));
            return this;
        }

        TokenSet<V> build() {
            return new TokenSet<>(new LinkedHashMap<>(table), display.toString());
        }
    }
}
