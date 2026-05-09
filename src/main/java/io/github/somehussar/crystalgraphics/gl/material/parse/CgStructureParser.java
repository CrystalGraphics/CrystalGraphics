package io.github.somehussar.crystalgraphics.gl.material.parse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural parsing and validation helpers for CrystalShader {@code .shader} source.
 * Handles brace matching, section extraction, v2f validation, and format constraint enforcement.
 *
 * <p>All methods are static. No GL calls — pure string processing. Every format violation
 * throws {@link CgShaderParseException} with a {@code [resourcePath]} prefix.</p>
 */
final class CgStructureParser {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static final Pattern V2F_FIELD_PATTERN =
            Pattern.compile("^\\s*(float|vec[234]|mat[34])\\s+(\\w+)\\s*;\\s*$");

    static final Pattern INTEGER_TYPE_PATTERN =
            Pattern.compile("^\\s*(int|uint|[iu]vec[234])\\b");

    private static final Pattern OLD_RENDER_QUEUE_PATTERN =
            Pattern.compile("(?m)^\\s*RenderQueue\\s+\\S");

    static final String[] RESERVED_PREFIXES = {"cg_", "CG_", "_v2f_"};
    static final String TYPE_SPATIAL = "spatial";

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
                if (!TYPE_SPATIAL.equals(typeName)) {
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] Unknown #type '" + typeName + "'. Only '" + TYPE_SPATIAL +
                                    "' is valid in the MVP. (No pass variants, compute, or canvas types yet.)");
                }
                return typeName;
            }
            throw new CgShaderParseException(
                    "[" + resourcePath + "] #type declaration must be the first non-blank, non-comment line. " +
                            "Found: '" + line + "'");
        }
        throw new CgShaderParseException("[" + resourcePath + "] #type declaration missing");
    }

    static String parseV2fBody(String source, String resourcePath) {
        int start = source.indexOf("struct v2f {");
        if (start == -1) start = source.indexOf("struct v2f{");
        if (start == -1) throw new CgShaderParseException("[" + resourcePath + "] 'struct v2f { }' block not found");
        int braceOpen  = source.indexOf('{', start);
        int braceClose = matchBrace(source, braceOpen);
        String body = source.substring(braceOpen + 1, braceClose);
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
        return body;
    }

    static int findV2fEnd(String source, String resourcePath) {
        int start = source.indexOf("struct v2f {");
        if (start == -1) start = source.indexOf("struct v2f{");
        if (start == -1) throw new CgShaderParseException("[" + resourcePath + "] 'struct v2f { }' block not found");
        int braceOpen  = source.indexOf('{', start);
        int braceClose = matchBrace(source, braceOpen);
        int semi = source.indexOf(';', braceClose);
        return semi >= 0 ? semi + 1 : braceClose + 1;
    }

    static String parseGlobalDecls(String source, int v2fEnd, String resourcePath) {
        int vertexStart = source.indexOf("void vertex(");
        if (vertexStart == -1) {
            throw new CgShaderParseException("[" + resourcePath + "] 'void vertex(' function not found");
        }
        if (v2fEnd > vertexStart) return "";
        return source.substring(v2fEnd, vertexStart).trim();
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

    static String parsePreambleDirectives(String source, String resourcePath) {
        StringBuilder result = new StringBuilder();
        boolean seenType = false;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!seenType) {
                if (line.startsWith("#type")) seenType = true;
                continue;
            }
            if (line.startsWith("Properties {") || line.startsWith("Properties{")
                    || line.startsWith("struct v2f {") || line.startsWith("struct v2f{")
                    || line.startsWith("void vertex(")) break;
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#type")) continue;
            if (line.startsWith("#")) {
                if (result.length() > 0) result.append('\n');
                result.append(line);
            }
        }
        return result.toString();
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

    static void validateSectionStructure(String source, String resourcePath) {
        int propsCount = countMarker(source, "Properties {") + countMarker(source, "Properties{");
        if (propsCount > 1) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] 'Properties { }' block appears more than once; each section must appear exactly once");
        }

        int v2fCount = countMarker(source, "struct v2f {") + countMarker(source, "struct v2f{");
        if (v2fCount > 1) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] 'struct v2f { }' block appears more than once; each section must appear exactly once");
        }

        int vertexCount = countMarker(source, "void vertex(");
        if (vertexCount > 1) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] 'void vertex(' function appears more than once; each section must appear exactly once");
        }

        int fragCount = countMarker(source, "void fragment(");
        if (fragCount > 1) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] 'void fragment(' function appears more than once; each section must appear exactly once");
        }

        // Duplicate RenderState block is an error
        int rsCount = countMarker(source, "RenderState {") + countMarker(source, "RenderState{");
        if (rsCount > 1) {
            throw new CgShaderParseException("[" + resourcePath + "] Duplicate RenderState block");
        }

        int propertiesPos = firstOf(source, "Properties {", "Properties{");
        int v2fPos        = firstOf(source, "struct v2f {",  "struct v2f{");
        int vertexPos     = source.indexOf("void vertex(");
        int fragmentPos   = source.indexOf("void fragment(");

        if (propertiesPos >= 0 && v2fPos >= 0 && propertiesPos > v2fPos) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Section ordering violation: 'struct v2f' appears before 'Properties { }'. " +
                            "Required order: #type, Properties, struct v2f, vertex(), fragment()");
        }
        if (v2fPos >= 0 && vertexPos >= 0 && v2fPos > vertexPos) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Section ordering violation: 'void vertex(' appears before 'struct v2f'. " +
                            "Required order: #type, Properties, struct v2f, vertex(), fragment()");
        }
        if (vertexPos >= 0 && fragmentPos >= 0 && vertexPos > fragmentPos) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Section ordering violation: 'void fragment(' appears before 'void vertex('. " +
                            "Required order: #type, Properties, struct v2f, vertex(), fragment()");
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
            LOGGER.warn("[{}] Non-ASCII character(s) in .shader file — invisible unicode silently breaks GLSL: {}",
                    path, details);
        }
    }
}
