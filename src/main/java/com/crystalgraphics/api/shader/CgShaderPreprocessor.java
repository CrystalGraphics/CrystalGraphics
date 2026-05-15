package com.crystalgraphics.api.shader;

import com.crystalgraphics.util.io.CgIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL shader preprocessor: header text, {@code #define} injection,
 * {@code #include} resolution, {@code #pragma once} deduplication,
 * and cycle detection.
 *
 * <h3>{@code #version} Safety</h3>
 * <p>The injected prelude (header + defines) is inserted immediately after
 * the {@code #version} line when one is present, or prepended when absent.
 * This satisfies the GLSL spec requirement that {@code #version} must be
 * the very first non-comment statement.</p>
 *
 * <h3>{@code #include} Resolution</h3>
 * <p>Both {@code #include "path"} and {@code #include <path>} forms are
 * accepted and treated identically. Paths are resolved via
 * {@link CgIO#normalizePath}. Relative paths (no leading {@code /assets/}
 * and no {@code :}) are resolved against the including file's directory.</p>
 *
 * <h3>{@code #pragma once}</h3>
 * <p>Files with {@code #pragma once} are included at most once per
 * {@link #process} call. The directive is stripped from the output.</p>
 *
 * <h3>Cycle Detection</h3>
 * <p>A {@link CgPreprocessorException} is thrown if a file appears in its
 * own ancestor include chain.</p>
 *
 * <h3>{@code #line} Directives</h3>
 * <p>When the system property {@code crystalgraphics.shader.devmode} is set,
 * {@code #line} directives are emitted around each include expansion to aid
 * GLSL compiler error messages.</p>
 *
 * <h3>Source Cache</h3>
 * <p>Loaded source text is cached per preprocessor instance. The cache is
 * disabled when the {@code crystalgraphics.shader.resourceOverrideDir}
 * property is set (hotswap dev mode). Call {@link #invalidateCache()} to
 * clear it explicitly.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Instances should be used on the render thread only.</p>
 */
public final class CgShaderPreprocessor {

    // ── Constants ──────────────────────────────────────────────────────────────

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static final boolean DEV_MODE =
            System.getProperty("crystalgraphics.shader.devmode") != null;

    /** Pattern matching: #include "path" or #include <path> */
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "^#\\s*include\\s+(?:\"([^\"]+)\"|<([^>]+)>)\\s*$"
    );

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final String headerText;
    private final TreeMap<String, String> defines;

    /**
     * Whether caching is enabled. Caching is disabled when the resource
     * override directory property is set (hotswap dev mode).
     */
    private final boolean cachingEnabled;

    /**
     * Source text cache: normalized path → raw GLSL source.
     * Shared across all {@link #process} calls on this instance.
     * Empty when {@link #cachingEnabled} is false.
     */
    private final Map<String, String> sourceCache = new HashMap<String, String>();

    // ── Constructors ───────────────────────────────────────────────────────────

    public CgShaderPreprocessor(String headerText, Map<String, String> defines) {
        this.headerText = headerText;
        this.defines = new TreeMap<String, String>();
        if (defines != null) {
            this.defines.putAll(defines);
        }
        this.cachingEnabled =
                System.getProperty("crystalgraphics.shader.resourceOverrideDir") == null;
    }

    public CgShaderPreprocessor(Map<String, String> defines) {
        this(null, defines);
    }

    public CgShaderPreprocessor() {
        this(null, null);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns an unmodifiable, alphabetically-sorted view of the defines. */
    public Map<String, String> getDefines() {
        return Collections.unmodifiableMap(defines);
    }

    /** Returns the header text, or {@code null} if none was provided. */
    public String getHeaderText() {
        return headerText;
    }

    /**
     * Returns {@code true} if this preprocessor would not modify a source
     * (no header, no defines, and no includes to resolve).
     */
    public boolean isEmpty() {
        return (headerText == null || headerText.isEmpty()) && defines.isEmpty();
    }

    /**
     * Clears the source cache. Call after a resource reload or hotswap so
     * that subsequent {@link #process} calls re-read files from disk.
     */
    public void invalidateCache() {
        sourceCache.clear();
    }

    /**
     * Applies preprocessing to the given GLSL source with no path context.
     *
     * <p>Equivalent to {@link #process(String, String) process(source, null)}.
     * Relative {@code #include} directives in the source will not be resolved
     * (they still work as absolute resource-location includes).</p>
     *
     * @param source the raw GLSL source; must not be {@code null}
     * @return preprocessed source with header, defines, and includes expanded
     * @throws CgPreprocessorException if an include cannot be resolved or a
     *                                  cycle is detected
     */
    public String process(String source) {
        return process(source, null);
    }

    /**
     * Applies preprocessing to the given GLSL source.
     *
     * <ol>
     *   <li>Expand {@code #include} directives recursively (with cycle
     *       detection and {@code #pragma once} support).</li>
     *   <li>Insert the prelude (header text + {@code #define} lines)
     *       immediately after the {@code #version} directive, or at the
     *       top of the source if none is present.</li>
     * </ol>
     *
     * @param source     the raw GLSL source; must not be {@code null}
     * @param sourcePath the path this source was loaded from (used for
     *                   relative include resolution and {@code #line}
     *                   directives); may be {@code null}
     * @return preprocessed source
     * @throws CgPreprocessorException if an include cannot be resolved or a
     *                                  cycle is detected
     * @throws NullPointerException    if {@code source} is {@code null}
     */
    public String process(String source, String sourcePath) {
        if (source == null) {
            throw new NullPointerException("source must not be null");
        }

        // Strip non-ASCII before any processing — invisible unicode characters silently
        // kill GLSL compilation (driver returns GL_TRUE from glLinkStatus but the program
        // is a zombie with 0 active uniforms and nothing renders).
        source = stripNonAscii(source, sourcePath != null ? sourcePath : "<unknown>");

        // Expand #include directives first (before prelude injection so
        // included files are also in scope of the prelude).
        IncludeContext ctx = new IncludeContext();
        String expanded = expandIncludes(source, sourcePath, ctx);

        // Then inject the prelude (header + defines).
        if (isEmpty()) {
            return expanded;
        }

        String prelude = buildPrelude();
        int insertionIndex = findVersionLineEnd(expanded);

        if (insertionIndex == -1) {
            return prelude + expanded;
        } else {
            return expanded.substring(0, insertionIndex)
                    + prelude
                    + expanded.substring(insertionIndex);
        }
    }

    // ── Private: non-ASCII stripping ──────────────────────────────────────────

    private static String stripNonAscii(String source, String path) {
        boolean hasNonAscii = false;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) > 127) { hasNonAscii = true; break; }
        }
        if (!hasNonAscii) return source;

        String displayPath = (path != null && !path.isEmpty()) ? path : "<inline>";
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c > 127) {
                if (details.length() > 0) details.append(", ");
                details.append('\'').append(c).append("' (U+")
                       .append(String.format("%04X", (int) c))
                       .append(") at ").append(toLineCol(source, i));
            }
        }
        LOGGER.warn("[{}] Stripped non-ASCII character(s) — check your editor for invisible unicode: {}",
                displayPath, details);
        return source.replaceAll("[^\\x00-\\x7F]", " ");
    }

    private static String toLineCol(String source, int pos) {
        int line = 1, col = 1;
        for (int i = 0; i < pos; i++) {
            if (source.charAt(i) == '\n') { line++; col = 1; } else { col++; }
        }
        return line + ":" + col;
    }

    // ── Private: include expansion ─────────────────────────────────────────────

    /**
     * Recursively expands {@code #include} directives in {@code src}.
     *
     * @param src      raw GLSL text to process
     * @param fromPath path this text was loaded from (for relative resolution
     *                 and cycle detection); may be {@code null}
     * @param ctx      per-compilation include context (stack + pragma-once set)
     * @return GLSL text with all includes expanded
     */
    private String expandIncludes(String src, String fromPath, IncludeContext ctx) {
        String norm = (fromPath != null) ? CgIO.normalizePath(fromPath) : null;

        // Cycle detection: if this file is already being expanded in the current
        // include chain, throw immediately.
        if (norm != null && ctx.activeStack.contains(norm)) {
            throw new CgPreprocessorException(
                    "Circular #include detected: " + ctx.activeStack + " -> " + norm,
                    norm, 0);
        }

        // #pragma once: if this file was already fully included, skip it.
        if (norm != null && ctx.pragmaOnce.contains(norm)) {
            return "";
        }

        if (norm != null) {
            ctx.activeStack.push(norm);
        }

        try {
            StringBuilder out = new StringBuilder(src.length() + 256);
            boolean hasPragmaOnce = false;
            int lineNo = 1;

            // Split preserving empty trailing lines (-1 limit).
            String[] lines = src.split("\\r?\\n", -1);

            for (int i = 0; i < lines.length; i++) {
                String raw = lines[i];
                String trimmed = raw.trim();

                if (trimmed.equals("#pragma once")) {
                    // Strip the directive from output; record the flag.
                    hasPragmaOnce = true;

                } else {
                    Matcher m = INCLUDE_PATTERN.matcher(trimmed);
                    if (m.matches()) {
                        // Extract the path from whichever capture group matched.
                        String relPath = m.group(1) != null ? m.group(1) : m.group(2);
                        String resolved = resolveIncludePath(relPath, norm);
                        String includedSrc = loadSource(resolved);

                        if (DEV_MODE) {
                            out.append("#line 1\n");
                        }
                        out.append(expandIncludes(includedSrc, resolved, ctx));
                        if (DEV_MODE) {
                            out.append("#line ").append(lineNo + 1).append('\n');
                        }
                    } else {
                        out.append(raw);
                        // Re-add newline for every line except the very last when
                        // the original source had no trailing newline.
                        if (i < lines.length - 1) {
                            out.append('\n');
                        }
                    }
                }
                lineNo++;
            }

            // Mark this file as pragma-once'd only after successful expansion.
            if (hasPragmaOnce && norm != null) {
                ctx.pragmaOnce.add(norm);
            }

            return out.toString();

        } finally {
            if (norm != null) {
                ctx.activeStack.pop();
            }
        }
    }

    /**
     * Resolves a path found inside a {@code #include} directive.
     *
     * <ul>
     *   <li>Absolute paths (start with {@code /assets/} or contain {@code :})
     *       are simply normalized via {@link CgIO#normalizePath}.</li>
     *   <li>Relative paths are resolved against the directory of
     *       {@code fromNorm}.</li>
     * </ul>
     */
    private static String resolveIncludePath(String relPath, String fromNorm) {
        // Absolute: resource-location format (e.g. crystalgraphics:shaders/lib/math.glsl)
        // or already canonical /assets/... path.
        if (relPath.startsWith("/assets/") || relPath.indexOf(':') > 0) {
            return CgIO.normalizePath(relPath);
        }
        // Relative: resolve against the directory of the including file.
        if (fromNorm == null) {
            return CgIO.normalizePath(relPath);
        }
        int lastSlash = fromNorm.lastIndexOf('/');
        String baseDir = (lastSlash >= 0) ? fromNorm.substring(0, lastSlash + 1) : "";
        return CgIO.normalizePath(baseDir + relPath);
    }

    /**
     * Loads raw GLSL source for the given normalized path, using the
     * source cache when caching is enabled.
     *
     * @throws CgPreprocessorException if the source cannot be found or loaded
     */
    private String loadSource(String normalizedPath) {
        if (cachingEnabled) {
            String cached = sourceCache.get(normalizedPath);
            if (cached != null) {
                return cached;
            }
        }

        String src;
        try {
            src = CgIO.loadSource(normalizedPath);
        } catch (Exception e) {
            throw new CgPreprocessorException(
                    "Failed to load shader include: " + normalizedPath,
                    normalizedPath, 0, e);
        }

        if (src == null) {
            throw new CgPreprocessorException(
                    "Shader include not found: " + normalizedPath,
                    normalizedPath, 0);
        }

        if (cachingEnabled) {
            sourceCache.put(normalizedPath, src);
        }
        return src;
    }

    // ── Private: prelude building ──────────────────────────────────────────────

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
     * Finds the end index (exclusive) of the {@code #version} line, or
     * {@code -1} if no {@code #version} line precedes any real statement.
     *
     * <p>Skips blank lines and {@code //} comments when searching for
     * {@code #version}.</p>
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

            // Hit a real non-#version statement — #version cannot appear later.
            return -1;
        }

        return -1;
    }

    // ── Private: per-compilation state ────────────────────────────────────────

    /**
     * Per-{@link #process} invocation state.
     * Fresh instance each call so two shaders that both include the same
     * file each get independent pragma-once tracking.
     */
    private static final class IncludeContext {
        /** Current active include chain (outermost at bottom, innermost at top). */
        final Deque<String> activeStack = new ArrayDeque<>();
        /** Files that have already been included and carry {@code #pragma once}. */
        final Set<String> pragmaOnce = new HashSet<>();
    }

    // ── toString ──────────────────────────────────────────────────────────────

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
