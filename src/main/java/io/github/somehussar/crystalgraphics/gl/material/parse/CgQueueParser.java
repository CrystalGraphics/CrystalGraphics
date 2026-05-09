package io.github.somehussar.crystalgraphics.gl.material.parse;

import io.github.somehussar.crystalgraphics.api.material.CgRenderQueue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code Queue = "Name"} keyword from a CrystalShader {@code .shader} source.
 *
 * <p>Returns {@code CgRenderQueue.GEOMETRY.getValue()} (2000) when the keyword is absent.</p>
 */
final class CgQueueParser {

    /** {@code Queue = "value"} — value must be quoted. */
    private static final Pattern QUEUE_QUOTED_PATTERN =
            Pattern.compile("Queue\\s*=\\s*\"([^\"]+)\"");

    /** {@code Queue = value} where value is NOT quoted — must throw. */
    private static final Pattern QUEUE_UNQUOTED_PATTERN =
            Pattern.compile("Queue\\s*=\\s*(?!\")\\S");

    private CgQueueParser() {}

    /**
     * Parses the optional {@code Queue = "Name"} keyword.
     * Returns {@code CgRenderQueue.GEOMETRY.getValue()} (2000) when absent.
     */
    static int parse(String source, String resourcePath) {
        // Detect unquoted form before attempting the quoted match
        if (QUEUE_UNQUOTED_PATTERN.matcher(source).find()) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Queue value must be a quoted string, e.g.: Queue = \"Transparent\"");
        }
        Matcher m = QUEUE_QUOTED_PATTERN.matcher(source);
        if (!m.find()) return CgRenderQueue.GEOMETRY.getValue();

        String raw = m.group(1).trim();
        // Numeric value?
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {}
        // Named queue — case-insensitive via CgRenderQueue.fromName()
        try {
            return CgRenderQueue.fromName(raw).getValue();
        } catch (IllegalArgumentException e) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Unknown Queue name '" + raw
                    + "'. Valid: Background, Geometry, AlphaTest, Transparent, Overlay, or a numeric value.");
        }
    }
}
