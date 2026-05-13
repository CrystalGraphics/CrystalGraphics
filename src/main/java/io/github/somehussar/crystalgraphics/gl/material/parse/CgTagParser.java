package io.github.somehussar.crystalgraphics.gl.material.parse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code Tags { "key" = "value" }} blocks from CrystalShader {@code .shader} source.
 *
 * <p>Handles both material-level (top-level, before the first {@code Pass}) and
 * pass-level {@code Tags { }} blocks. Returns raw string values without semantic
 * validation — callers ({@link CgShaderParser}) are responsible for interpreting values.</p>
 *
 * <p>All methods are static. No GL calls — pure string processing.</p>
 */
final class CgTagParser {

    /** Matches a {@code Tags} keyword followed by optional whitespace and an opening brace. */
    private static final Pattern TAGS_BLOCK_PATTERN = Pattern.compile("\\bTags\\s*\\{");

    /** Matches a {@code Pass} keyword followed by optional whitespace and an opening brace. */
    private static final Pattern PASS_BLOCK_PATTERN = Pattern.compile("\\bPass\\s*\\{");

    /** Matches quoted key-value pairs: {@code "key" = "value"}. */
    private static final Pattern KEY_VALUE_PATTERN =
            Pattern.compile("\"([^\"]+)\"\\s*=\\s*\"([^\"]*)\"");

    private CgTagParser() {}

    /**
     * Parses all {@code "key" = "value"} pairs from a {@code Tags { }} block found
     * anywhere within {@code body}.
     *
     * <p>Returns an empty map when no {@code Tags { }} block is present.
     * Values are case-preserving with surrounding quotes stripped.
     * Does <em>not</em> validate values — unknown values are returned as-is.</p>
     *
     * @param body         the text to search (typically a pass body or full source region)
     * @param resourcePath used in exception messages
     * @return unmodifiable map of tag key → tag value; empty if {@code Tags { }} is absent
     */
    static Map<String, String> parseTagsBlock(String body, String resourcePath) {
        Matcher m = TAGS_BLOCK_PATTERN.matcher(body);
        if (!m.find()) return Collections.emptyMap();
        int braceOpen = m.end() - 1;
        int braceClose = CgStructureParser.matchBrace(body, braceOpen);
        String tagsContent = body.substring(braceOpen + 1, braceClose);
        return parseKeyValuePairs(tagsContent);
    }

    /**
     * Parses the top-level {@code Tags { }} block — one that appears <em>before</em>
     * any {@code Pass { }} block (i.e. at material scope, not pass scope).
     *
     * <p>Returns an empty map when no top-level {@code Tags { }} block is present.
     * Typical use: extracting {@code "RenderType"} and {@code "CastShadows"}.</p>
     *
     * @param source       the full {@code .shader} source
     * @param resourcePath used in exception messages
     * @return unmodifiable map of top-level tag key → tag value; empty if absent
     */
    static Map<String, String> parseTopLevelTags(String source, String resourcePath) {
        // Limit search region to text before the first Pass block
        Matcher passM = PASS_BLOCK_PATTERN.matcher(source);
        String searchRegion = passM.find() ? source.substring(0, passM.start()) : source;

        Matcher tagsM = TAGS_BLOCK_PATTERN.matcher(searchRegion);
        if (!tagsM.find()) return Collections.emptyMap();
        int braceOpen = tagsM.end() - 1;
        int braceClose = CgStructureParser.matchBrace(searchRegion, braceOpen);
        String tagsContent = searchRegion.substring(braceOpen + 1, braceClose);
        return parseKeyValuePairs(tagsContent);
    }

    /**
     * Parses all {@code "key" = "value"} pairs from a raw string.
     * Returns an unmodifiable, insertion-ordered map.
     */
    private static Map<String, String> parseKeyValuePairs(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = KEY_VALUE_PATTERN.matcher(content);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return Collections.unmodifiableMap(result);
    }
}
