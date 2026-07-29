package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural parsing and validation helpers for CrystalShader {@code .shader} source.
 * Handles brace matching, {@code Pass { }} block extraction, per-pass section extraction
 * (v2f body, globalDecls, vertex/fragment blocks), preamble directive passthrough,
 * {@code #type} parsing, and all {@code validate*} format checks.
 *
 * <p>All methods are static. No GL calls — pure string processing. Every structural
 * format violation throws {@link CgShaderParseException} with a {@code [resourcePath]}
 * prefix.</p>
 *
 * <p>Tag parsing ({@code Tags { }}) is handled by {@link CgTagParser}.</p>
 */
final class CgStructureParser {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static final Pattern V2F_FIELD_PATTERN =
            Pattern.compile("^\\s*(float|vec[234]|mat[34])\\s+(\\w+)\\s*;\\s*$");

    static final Pattern INTEGER_TYPE_PATTERN =
            Pattern.compile("^\\s*(int|uint|[iu]vec[234])\\b");

    private static final Pattern OLD_RENDER_QUEUE_PATTERN =
            Pattern.compile("(?m)^\\s*RenderQueue\\s+\\S");

    /** Matches a {@code Pass} keyword followed by optional whitespace and an opening brace. */
    private static final Pattern PASS_BLOCK_PATTERN = Pattern.compile("\\bPass\\s*\\{");

    static final String[] RESERVED_PREFIXES = {"cg_", "CG_", "_v2f_"};

    private CgStructureParser() {}

    // ── Block extraction ──────────────────────────────────────────────────────

    /**
     * Finds the matching closing brace for the opening brace at {@code openIdx}.
     * Uses a counter (not regex) to handle nested braces correctly.
     */
    static int matchBrace(String source, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        throw new CgShaderParseException("Unmatched '{' at position " + openIdx + " — missing closing '}'");
    }

    /** Strips {@code // ...} single-line comments from each line. */
    static String stripLineComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            int idx = line.indexOf("//");
            if (idx >= 0) line = line.substring(0, idx);
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Extracts the raw content (between the outer braces) of every
     * {@code Pass { ... }} block in the source, in declaration order.
     *
     * <p>Uses brace-counter matching ({@link #matchBrace}) to correctly handle
     * nested {@code RenderState { }}, {@code Tags { }}, and function bodies.</p>
     *
     * <p>Does <em>not</em> validate the pass count — that is the caller's
     * ({@link CgShaderParser}'s) responsibility. An empty list is returned when
     * no {@code Pass} blocks are found.</p>
     *
     * @param source       the full {@code .shader} source
     * @param resourcePath used in exception messages
     * @return unmodifiable ordered list of raw pass body strings (content between the outer braces);
     *         empty if no {@code Pass} blocks found
     */
    static List<String> extractPassBlocks(String source, String resourcePath) {
        List<String> blocks = new ArrayList<>();
        Matcher m = PASS_BLOCK_PATTERN.matcher(source);
        while (m.find()) {
            // The pattern ends with '\{', so m.end() - 1 is the index of '{'
            int braceOpen = m.end() - 1;
            int braceClose = matchBrace(source, braceOpen);
            blocks.add(source.substring(braceOpen + 1, braceClose));
        }
        return Collections.unmodifiableList(blocks);
    }

    /**
     * Finds and parses a top-level {@code struct v2f { }} block that exists
     * <em>outside</em> all {@code Pass { }} blocks (shared v2f).
     *
     * <p>Returns an empty string when no top-level {@code struct v2f} is found.
     * When present, validates all field declarations (type, reserved-prefix, no integer types)
     * using the same rules as per-pass parsing.</p>
     *
     * @param source       the full {@code .shader} source
     * @param resourcePath used in exception messages
     * @return body content between the outer braces of the top-level {@code struct v2f},
     *         or {@code ""} if absent; never {@code null}
     * @throws CgShaderParseException if the struct body contains invalid field declarations
     */
    static String parseSharedV2f(String source, String resourcePath) {
        // Only search before the first Pass block
        Matcher passM = PASS_BLOCK_PATTERN.matcher(source);
        String searchRegion = passM.find() ? source.substring(0, passM.start()) : source;

        int start = searchRegion.indexOf("struct v2f {");
        if (start == -1) start = searchRegion.indexOf("struct v2f{");
        if (start == -1) return "";

        int braceOpen  = searchRegion.indexOf('{', start);
        int braceClose = matchBrace(searchRegion, braceOpen);
        String body = searchRegion.substring(braceOpen + 1, braceClose);
        validateV2fBody(body, resourcePath);
        return body;
    }

    /**
     * Parses the v2f struct body for a specific pass body, falling back to
     * {@code sharedV2f} if the pass does not declare its own {@code struct v2f}.
     *
     * <p>Per-pass {@code struct v2f} declarations override the shared top-level one.
     * When no v2f is found in {@code passBody}, returns {@code sharedV2f} as-is
     * (which may itself be empty if no shared v2f was declared).</p>
     *
     * @param passBody     raw content of a single {@code Pass { }} block (without outer braces)
     * @param sharedV2f    the top-level shared v2f body from {@link #parseSharedV2f};
     *                     used as fallback when the pass has no v2f
     * @param resourcePath used in exception messages
     * @return the v2f struct body to use for this pass; never {@code null}
     * @throws CgShaderParseException if the pass-local v2f body has invalid fields
     */
    static String parsePassV2fBody(String passBody, String sharedV2f, String resourcePath) {
        int start = passBody.indexOf("struct v2f {");
        if (start == -1) start = passBody.indexOf("struct v2f{");
        if (start == -1) return sharedV2f; // Inherit from shared top-level v2f

        int braceOpen  = passBody.indexOf('{', start);
        int braceClose = matchBrace(passBody, braceOpen);
        String body = passBody.substring(braceOpen + 1, braceClose);
        validateV2fBody(body, resourcePath);
        return body;
    }

    /**
     * Extracts the global declarations section for a pass: everything between the closing
     * {@code };} of {@code struct v2f} (if present) and {@code void vertex(}.
     *
     * <p>Analogous to the old top-level {@code parseGlobalDecls} but scoped to
     * a single pass body.</p>
     *
     * @param passBody     raw content of a single {@code Pass { }} block (without outer braces)
     * @param resourcePath used in exception messages
     * @return global declarations text for this pass; {@code ""} if absent; never {@code null}
     */
    static String parsePassGlobalDecls(String passBody, String resourcePath) {
        // globalDecls starts after all dedicated-parser-owned structural sections.
        // Tags and RenderState are already consumed by CgTagParser / CgRenderStateParser;
        // struct v2f is consumed by parsePassV2fBody. Find the rightmost end of any of
        // these sections so we never accidentally include their content in the GLSL output.
        int regionStart = 0;
        regionStart = Math.max(regionStart, endOfNamedBlock(passBody, "Tags"));
        regionStart = Math.max(regionStart, endOfNamedBlock(passBody, "RenderState"));

        int v2fStart = passBody.indexOf("struct v2f {");
        if (v2fStart == -1) v2fStart = passBody.indexOf("struct v2f{");
        if (v2fStart >= 0) {
            int braceOpen  = passBody.indexOf('{', v2fStart);
            int braceClose = matchBrace(passBody, braceOpen);
            int semi       = passBody.indexOf(';', braceClose);
            regionStart    = Math.max(regionStart, semi >= 0 ? semi + 1 : braceClose + 1);
        }

        int vertexStart = passBody.indexOf("void vertex(");
        if (vertexStart == -1) return "";
        if (regionStart >= vertexStart) return "";

        return passBody.substring(regionStart, vertexStart).trim();
    }

    private static int endOfNamedBlock(String text, String blockName) {
        int search = 0;
        while (search <= text.length() - blockName.length()) {
            int found = text.indexOf(blockName, search);
            if (found == -1) return 0;
            int j = found + blockName.length();
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
            if (j < text.length() && text.charAt(j) == '{') {
                return matchBrace(text, j) + 1;
            }
            search = found + 1;
        }
        return 0;
    }

    /**
     * Validates a pass body for structural constraints that are hard errors (not silent-fail):
     * no {@code #version} directive, no {@code main()} function.
     *
     * @param passBody     raw content of a single {@code Pass { }} block
     * @param passName     pass name for error messages
     * @param resourcePath used in exception messages
     * @throws CgShaderParseException if any forbidden construct is found
     */
    static void validatePassBody(String passBody, String passName, String resourcePath) {
        for (String rawLine : passBody.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#version")) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Pass '" + passName + "': #version directive is forbidden in Pass blocks. "
                        + "The compiler injects the version header automatically. "
                        + "Remove the '#version' line from this pass.");
            }
        }
        validateNoMainFunction(passBody, "Pass '" + passName + "'", resourcePath);
    }

    static String extractBlock(String source, String signature, String name, String resourcePath) {
        int sigStart = source.indexOf(signature);
        if (sigStart == -1) {
            throw new CgShaderParseException("[" + resourcePath + "] '" + signature + "...' function not found");
        }
        int braceOpen = source.indexOf('{', sigStart);
        if (braceOpen == -1) {
            throw new CgShaderParseException("[" + resourcePath + "] Opening brace for '" + name + "()' not found");
        }
        int braceClose = matchBrace(source, braceOpen);
        return source.substring(braceOpen + 1, braceClose);
    }

    static String parseShaderType(String source, String resourcePath) {
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (line.startsWith("#type")) {
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    throw new CgShaderParseException("[" + resourcePath + "] #type directive has no type name");
                }
                String typeName = parts[1].trim();
                // Validate against the live registry — any CgVertexFormat built with a key name is valid
                CgVertexFormat format = CgVertexFormat.forShaderType(typeName);
                if (format == null) {
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] Unknown #type '" + typeName + "'. "
                            + "Registered types: " + CgVertexFormat.registeredShaderTypes());
                }
                return typeName;
            }
            throw new CgShaderParseException(
                    "[" + resourcePath + "] #type declaration must be the first non-blank, non-comment line. " +
                            "Found: '" + line + "'");
        }
        throw new CgShaderParseException("[" + resourcePath + "] #type declaration missing");
    }

    /**
     * Collects preamble {@code #}-directive lines (between {@code #type} and the first
     * structural section) for passthrough into global declarations.
     *
     * <p>Stop conditions: {@code Properties {/}, {@code struct v2f {/},
     * {@code void vertex(}, {@code Pass {/}, {@code Pass{}}.
     * {@code #pragma cg_feature} lines are silently consumed here and never forwarded.</p>
     */
    static String parsePreambleDirectives(String source, String resourcePath) {
        StringBuilder result = new StringBuilder();
        boolean seenType = false;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!seenType) {
                if (line.startsWith("#type")) seenType = true;
                continue;
            }
            // Stop at first structural section or Pass block
            if (line.startsWith("Properties {") || line.startsWith("Properties{")
                    || line.startsWith("struct v2f {") || line.startsWith("struct v2f{")
                    || line.startsWith("void vertex(")
                    || line.startsWith("Pass {") || line.startsWith("Pass{")) break;
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#type")) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#pragma cg_feature")) continue;
                if (line.startsWith("#pragma cg_use")) continue;
                if (result.length() > 0) result.append('\n');
                result.append(line);
            }
        }
        return result.toString();
    }

    /**
     * Scans the preamble region (between {@code #type} and the first structural section
     * or {@code Pass { }} block) for {@code #pragma cg_feature <NAME>} lines and returns
     * the feature names in declaration order.
     *
     * <p>Stop conditions include {@code Pass {} and {@code Pass{} in addition to the
     * standard structural section markers, so that feature pragmas inside a Pass body
     * are not mistakenly collected as material-level features.</p>
     *
     * <p>Validations (all throw {@link CgShaderParseException}):</p>
     * <ul>
     *   <li>Max 8 feature names — throws on 9+.</li>
     *   <li>Feature name must match {@code [A-Za-z_][A-Za-z0-9_]*} — throws on invalid identifier.</li>
     *   <li>Duplicate declaration — throws on second occurrence of the same name.</li>
     * </ul>
     *
     * <p>{@code #pragma once} is silently ignored (not confused with cg_feature).</p>
     *
     * @param source       the full {@code .shader} source
     * @param resourcePath used in exception messages
     * @return unmodifiable ordered list of feature names; empty if none declared
     * @throws CgShaderParseException on any validation failure
     */
    static List<String> parseFeaturePragmas(String source, String resourcePath) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean seenType = false;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!seenType) {
                if (line.startsWith("#type")) seenType = true;
                continue;
            }
            // Stop at first structural section or Pass block — do not scan function bodies
            if (line.startsWith("Properties {") || line.startsWith("Properties{")
                    || line.startsWith("struct v2f {") || line.startsWith("struct v2f{")
                    || line.startsWith("void vertex(")
                    || line.startsWith("Pass {") || line.startsWith("Pass{")) {
                break;
            }
            // Only process #pragma cg_feature lines
            if (!line.startsWith("#pragma cg_feature")) continue;
            // Extract the name token after the pragma keyword
            String rest = line.substring("#pragma cg_feature".length()).trim();
            if (rest.isEmpty()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_feature: missing feature name");
            }
            // Validate identifier: [A-Za-z_][A-Za-z0-9_]*
            if (!rest.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_feature: invalid feature name '"
                        + rest + "' — must match [A-Za-z_][A-Za-z0-9_]*");
            }
            // Reject duplicates
            if (seen.contains(rest)) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_feature: duplicate feature name '" + rest + "'");
            }
            // Enforce max 8 declarations
            if (names.size() >= 8) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_feature: maximum of 8 feature names exceeded"
                        + " (found '" + rest + "' as 9th declaration)");
            }
            names.add(rest);
            seen.add(rest);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Scans the preamble for {@code #pragma cg_use <token>} lines — a shader declaring that it reads
     * an engine-provided shader buffer (see {@code CgEngineBufferRegistry}).
     *
     * <p>Same preamble region and stop conditions as {@link #parseFeaturePragmas}: material-level
     * only, never scanned out of a {@code Pass} body.</p>
     *
     * <p>Validations (all throw {@link CgShaderParseException}): missing token, invalid identifier,
     * duplicate declaration, and — checked by the caller, which has the full source — an unknown
     * token or a shader that uses an engine buffer's symbols without declaring it.</p>
     *
     * @return unmodifiable ordered list of tokens; empty if none declared
     */
    static List<String> parseUsePragmas(String source, String resourcePath) {
        List<String> tokens = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean seenType = false;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!seenType) {
                if (line.startsWith("#type")) seenType = true;
                continue;
            }
            if (line.startsWith("Properties {") || line.startsWith("Properties{")
                    || line.startsWith("struct v2f {") || line.startsWith("struct v2f{")
                    || line.startsWith("void vertex(")
                    || line.startsWith("Pass {") || line.startsWith("Pass{")) {
                break;
            }
            if (!line.startsWith("#pragma cg_use")) continue;

            String rest = line.substring("#pragma cg_use".length()).trim();
            if (rest.isEmpty()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_use: missing buffer token");
            }
            if (!rest.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_use: invalid token '" + rest
                        + "' — must match [A-Za-z_][A-Za-z0-9_]*");
            }
            if (!seen.add(rest)) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #pragma cg_use: duplicate token '" + rest + "'");
            }
            tokens.add(rest);
        }
        return Collections.unmodifiableList(tokens);
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    static void validateNoVersionDirective(String source, String resourcePath) {
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#version")) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #version directive is forbidden in .shader files. "
                        + "The compiler injects the version header automatically. "
                        + "Remove the '#version' line from your shader.");
            }
        }
    }

    static void validateNoMainFunction(String body, String stage, String resourcePath) {
        if (body.contains("void main(") || body.contains("void main (")) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] main() is forbidden in the " + stage + " body. "
                    + "The compiler generates void main() automatically. "
                    + "Rename your entry point to '" + stage + "(...)'.");
        }
    }

    static void validateNotReservedPrefix(String name, String context, String resourcePath) {
        for (String prefix : RESERVED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Name '" + name + "' in " + context
                        + " uses engine-reserved prefix '" + prefix
                        + "'. Engine-reserved prefixes (cg_, CG_, _v2f_) must not be used in authored symbols.");
            }
        }
    }

    static void validateNoOldRenderQueue(String source, String resourcePath) {
        // Only scan the pre-body portion to avoid false positives inside GLSL function bodies
        int bodyStart = source.indexOf("void vertex(");
        String preBody = bodyStart >= 0 ? source.substring(0, bodyStart) : source;
        if (OLD_RENDER_QUEUE_PATTERN.matcher(preBody).find()) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Unknown keyword 'RenderQueue'. "
                    + "Use 'Queue = \"Name\"' instead, e.g.: Queue = \"Transparent\"");
        }
    }

    static int countMarker(String source, String marker) {
        int count = 0, idx = 0;
        while ((idx = source.indexOf(marker, idx)) != -1) { count++; idx += marker.length(); }
        return count;
    }

    static int firstOf(String source, String primary, String secondary) {
        int a = source.indexOf(primary), b = source.indexOf(secondary);
        if (a == -1) return b;
        if (b == -1) return a;
        return Math.min(a, b);
    }

    static void warnNonAscii(String source, String path) {
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c > 127) {
                if (details.length() > 0) details.append(", ");
                int line = 1, col = 1;
                for (int j = 0; j < i; j++) {
                    if (source.charAt(j) == '\n') { line++; col = 1; } else { col++; }
                }
                details.append('\'').append(c).append("' (U+")
                       .append(String.format("%04X", (int) c))
                       .append(") at ").append(line).append(':').append(col);
            }
        }
        if (details.length() > 0) {
            LOGGER.warn("[" + path + "] Non-ASCII character(s) in .shader file — invisible unicode silently breaks GLSL: " + details);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Validates all field lines in a {@code struct v2f} body.
     * Throws {@link CgShaderParseException} on any invalid field.
     */
    private static void validateV2fBody(String body, String resourcePath) {
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (INTEGER_TYPE_PATTERN.matcher(line).find()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Integer types forbidden in struct v2f. Found: '" + line + "'");
            }
            Matcher m = V2F_FIELD_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Invalid v2f field: '" + line + "'.");
            }
            validateNotReservedPrefix(m.group(2), "struct v2f", resourcePath);
        }
    }
}
