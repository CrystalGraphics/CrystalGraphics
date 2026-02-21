package io.github.somehussar.crystalgraphics.api.shader;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * V1 GLSL shader preprocessor: header text and {@code #define} injection only.
 *
 * <p>This preprocessor supports two injection modes:</p>
 * <ul>
 *   <li><strong>Header text</strong>: an optional block of text (e.g. copyright, pragma)
 *       prepended to the shader source.</li>
 *   <li><strong>{@code #define NAME VALUE}</strong>: zero or more preprocessor defines
 *       injected in deterministic (alphabetical) order.</li>
 * </ul>
 *
 * <h3>{@code #version} Safety</h3>
 * <p>The GLSL specification requires {@code #version} to appear as the very first
 * non-comment, non-whitespace statement. This preprocessor respects that rule:</p>
 * <ul>
 *   <li>If the shader source contains a {@code #version} line, the injected prelude
 *       (header + defines) is inserted <em>immediately after</em> that line.</li>
 *   <li>If no {@code #version} line is found, the prelude is inserted at the top
 *       of the source.</li>
 * </ul>
 *
 * <h3>Determinism</h3>
 * <p>For the same input source and define set, the output is always identical.
 * Defines are sorted alphabetically by name (and value is irrelevant to ordering).
 * This is important for cache key correctness&mdash;identical inputs produce
 * identical preprocessed source, so a hash-based cache key computed from the
 * define set will match.</p>
 *
 * <h3>V1 Scope</h3>
 * <p>This preprocessor intentionally does <strong>not</strong> support:</p>
 * <ul>
 *   <li>{@code #include} directives</li>
 *   <li>GLSL AST transformations</li>
 *   <li>Conditional preprocessing ({@code #ifdef} evaluation)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are immutable and safe for concurrent use.</p>
 *
 * @see CgShaderCacheKey
 */
public final class CgShaderPreprocessor {

    private final String headerText;
    private final TreeMap<String, String> defines;

    public CgShaderPreprocessor(String headerText, Map<String, String> defines) {
        this.headerText = headerText;
        this.defines = new TreeMap<String, String>();
        if (defines != null) {
            this.defines.putAll(defines);
        }
    }

    public CgShaderPreprocessor(Map<String, String> defines) {
        this(null, defines);
    }

    public CgShaderPreprocessor() {
        this(null, null);
    }

    /** Returns an unmodifiable, alphabetically-sorted view of the defines. */
    public Map<String, String> getDefines() {
        return Collections.unmodifiableMap(defines);
    }

    /**
     * Returns the header text, or {@code null} if none.
     */
    public String getHeaderText() {
        return headerText;
    }

    /**
     * Returns {@code true} if nothing will be injected.
     */
    public boolean isEmpty() {
        return (headerText == null || headerText.isEmpty()) && defines.isEmpty();
    }

    /**
     * Applies preprocessing to the given GLSL source.
     *
     * <p>Builds a prelude from header text and defines, then inserts it
     * into the source at the correct position (after {@code #version} if
     * present, otherwise at top).</p>
     *
     * @param source the raw GLSL source code; must not be {@code null}
     * @return the preprocessed source with header and defines injected
     * @throws NullPointerException if source is null
     */
    public String process(String source) {
        if (source == null) {
            throw new NullPointerException("source must not be null");
        }

        if (isEmpty()) {
            return source;
        }

        String prelude = buildPrelude();
        int insertionIndex = findVersionLineEnd(source);

        if (insertionIndex == -1) {
            return prelude + source;
        } else {
            return source.substring(0, insertionIndex) + prelude + source.substring(insertionIndex);
        }
    }

    private String buildPrelude() {
        StringBuilder sb = new StringBuilder();

        if (headerText != null && !headerText.isEmpty()) {
            sb.append(headerText);
            if (!headerText.endsWith("\n")) {
                sb.append('\n');
            }
        }

        for (Map.Entry<String, String> entry : defines.entrySet()) {
            sb.append("#define ").append(entry.getKey());
            String value = entry.getValue();
            if (value != null && !value.isEmpty()) {
                sb.append(' ').append(value);
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Finds the end index of the {@code #version} line, or -1 if absent.
     *
     * <p>Per GLSL spec, {@code #version} must be the first non-whitespace,
     * non-comment statement. Scans lines from the top, skipping blanks and
     * {@code //} comments. Returns the index after the version line's newline,
     * or -1 if no {@code #version} is found before another statement.</p>
     */
    private static int findVersionLineEnd(String source) {
        int length = source.length();
        int pos = 0;

        while (pos < length) {
            int lineEnd = source.indexOf('\n', pos);
            boolean hasNewline = (lineEnd != -1);
            if (!hasNewline) {
                lineEnd = length;
            }

            String line = source.substring(pos, lineEnd).trim();

            if (line.isEmpty()) {
                pos = hasNewline ? lineEnd + 1 : length;
                continue;
            }

            if (line.startsWith("//")) {
                pos = hasNewline ? lineEnd + 1 : length;
                continue;
            }

            if (line.startsWith("#version")) {
                return hasNewline ? lineEnd + 1 : length;
            }

            // Non-empty, non-comment, non-#version: #version cannot appear later
            return -1;
        }

        return -1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CgShaderPreprocessor{");
        if (headerText != null && !headerText.isEmpty()) {
            sb.append("header=").append(headerText.length()).append("chars");
        }
        if (!defines.isEmpty()) {
            if (headerText != null && !headerText.isEmpty()) {
                sb.append(", ");
            }
            sb.append("defines=").append(defines.keySet());
        }
        sb.append('}');
        return sb.toString();
    }
}
