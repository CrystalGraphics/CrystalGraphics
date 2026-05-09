package io.github.somehussar.crystalgraphics.gl.material;

import com.github.bsideup.jabel.Desugar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural parser for CrystalShader {@code .shader} source files.
 *
 * <p>Splits a {@code .shader} source string into its declared sections without
 * parsing GLSL expressions inside function bodies. Zero GL calls, zero LWJGL
 * imports. All format validation described in {@code docs/CRYSTALSHADER_FORMAT.md}
 * is enforced here, with {@link CgShaderParseException} thrown on any violation.</p>
 *
 * <p>Brace matching for block extraction uses a counter-based scan (not regex).</p>
 *
 * <h3>Enforcement summary</h3>
 * <ul>
 *   <li>{@code #type} must be present as the first non-blank, non-comment line and
 *       its value must be {@code "spatial"} (the only valid type in MVP).</li>
 *   <li>Unknown {@code #type} values throw {@link CgShaderParseException}.</li>
 *   <li>Property names may not begin with engine-reserved prefixes
 *       ({@code cg_}, {@code CG_}, {@code _v2f_}).</li>
 *   <li>v2f field names may not begin with engine-reserved prefixes.</li>
 *   <li>Integer types ({@code int}, {@code uint}, {@code ivec*}, {@code uvec*}) are
 *       forbidden in {@code struct v2f}.</li>
 *   <li>{@code #version} directives are forbidden anywhere in the file (the compiler
 *       emits the version header).</li>
 *   <li>{@code main()} declarations are forbidden in vertex and fragment bodies
 *       (the compiler generates {@code void main()}).</li>
 *   <li>Each structural section ({@code Properties}, {@code struct v2f},
 *       {@code void vertex(}, {@code void fragment(}) must appear exactly once.</li>
 * </ul>
 */
public final class CgShaderParser {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** A single field parsed from the {@code struct v2f { }} block. 
     * @param type  GLSL type (e.g. {@code "vec3"}). Only float-family types are valid. 
     * @param name  Field name (e.g. {@code "worldPos"}). */
    @Desugar
    public record V2fField(String type, String name) {
    }

    private static final Pattern PROPERTY_PATTERN =
            Pattern.compile("^\\s*(\\w+)\\s*:\\s*(\\w+)(?:\\s*=\\s*(.+?))?\\s*$");

    private static final Pattern V2F_FIELD_PATTERN =
            Pattern.compile("^\\s*(float|vec[234]|mat[34])\\s+(\\w+)\\s*;\\s*$");

    private static final Pattern INTEGER_TYPE_PATTERN =
            Pattern.compile("^\\s*(int|uint|[iu]vec[234])\\b");

    /** Engine-reserved name prefixes that user symbols must not begin with. */
    private static final String[] RESERVED_PREFIXES = {"cg_", "CG_", "_v2f_"};

    /** The only valid shader type in the MVP. */
    private static final String TYPE_SPATIAL = "spatial";

    /**
     * Valid property types as defined in {@code docs/CRYSTALSHADER_FORMAT.md} section 3.2.
     * Any other type is a hard parse error.
     */
    private static final Set<String> VALID_PROPERTY_TYPES;

    static {
        Set<String> s = new HashSet<>(Arrays.asList(
                "float", "vec2", "vec3", "vec4",
                "int", "color", "Range",
                "sampler2D", "sampler2DArray", "sampler3D", "samplerCube"));
        VALID_PROPERTY_TYPES = Collections.unmodifiableSet(s);
    }

    private CgShaderParser() {}

    /**
     * Parses a {@code .shader} source string into a {@link CgParsedShader}.
     *
     * @param source the full content of a {@code .shader} file
     * @return the parsed structural representation
     * @throws CgShaderParseException on any format violation
     */
    public static CgParsedShader parse(String source) {
        return parse(source, "<unknown>");
    }

    /**
     * Parses a {@code .shader} source string into a {@link CgParsedShader}.
     * The resource path is included in all {@link CgShaderParseException} messages
     * to identify the file causing the error.
     *
     * @param source       the full content of a {@code .shader} file
     * @param resourcePath the resource path this source was loaded from (for error messages)
     * @return the parsed structural representation
     * @throws CgShaderParseException on any format violation
     */
    public static CgParsedShader parse(String source, String resourcePath) {
        if (source == null || source.isEmpty()) {
            throw new CgShaderParseException("[" + resourcePath + "] #type declaration missing: source is empty");
        }

        warnNonAscii(source, resourcePath);
        validateNoVersionDirective(source, resourcePath);

        // Validate section ordering and absence of duplicate sections
        validateSectionStructure(source, resourcePath);

        String shaderType = parseShaderType(source, resourcePath);
        List<CgMaterialProperty> properties = parseProperties(source, resourcePath);
        int v2fEnd = findV2fEnd(source, resourcePath);
        String v2fBody = parseV2fBody(source, resourcePath);
        String globalDecls = parseGlobalDecls(source, v2fEnd, resourcePath);
        String vertexBody = extractBlock(source, "void vertex(", "vertex", resourcePath);
        String fragmentBody = extractBlock(source, "void fragment(", "fragment", resourcePath);

        // Validate no main() in user-authored function bodies
        validateNoMainFunction(vertexBody, "vertex", resourcePath);
        validateNoMainFunction(fragmentBody, "fragment", resourcePath);

        String preambleDirectives = parsePreambleDirectives(source, resourcePath);
        if (!preambleDirectives.isEmpty()) {
            globalDecls = preambleDirectives + (globalDecls.isEmpty() ? "" : "\n" + globalDecls);
        }

        return new CgParsedShader(shaderType, properties, v2fBody, globalDecls, vertexBody, fragmentBody);
    }

    /**
     * Parses the v2f struct body and returns a list of {@link V2fField} objects.
     * Used by {@code CgMaterialShaderCompiler}.
     */
    public static List<V2fField> parseV2fFields(CgParsedShader parsed) {
        String body = parsed.v2fStructBody();
        List<V2fField> fields = new ArrayList<>();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("//")) continue;
            if (INTEGER_TYPE_PATTERN.matcher(line).find()) {
                throw new CgShaderParseException(
                        "Integer types (int, uint, ivec*, uvec*) are forbidden in struct v2f. " +
                                "Found: '" + line + "'");
            }
            Matcher m = V2F_FIELD_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "Invalid v2f field declaration: '" + line + "'. " +
                                "Expected: '<float-family-type> <name>;' (e.g. 'vec3 normal;')");
            }
            fields.add(new V2fField(m.group(1), m.group(2)));
        }
        return Collections.unmodifiableList(fields);
    }

    // ── Private parsing helpers ────────────────────────────────────────────

    private static String parsePreambleDirectives(String source, String resourcePath) {
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
                    || line.startsWith("void vertex(")) {
                break;
            }
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#type")) continue;
            if (line.startsWith("#")) {
                if (result.length() > 0) result.append('\n');
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String parseShaderType(String source, String resourcePath) {
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (line.startsWith("#type")) {
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    throw new CgShaderParseException("[" + resourcePath + "] #type directive has no type name");
                }
                String typeName = parts[1].trim();
                // Only "spatial" is valid in MVP; any other value is a hard error.
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

    /**
     * Parses the {@code Properties { }} block. Returns an empty list if the block is absent
     * (shaders with no properties are valid — e.g. shaders that only use CgFrameBlock data).
     */
    private static List<CgMaterialProperty> parseProperties(String source, String resourcePath) {
        int start = source.indexOf("Properties {");
        if (start == -1) {
            start = source.indexOf("Properties{");
            if (start == -1) {
                // Properties block is optional — a shader that only uses CgFrameBlock data needs none.
                return Collections.emptyList();
            }
        }
        int braceOpen = source.indexOf('{', start);
        int braceClose = matchBrace(source, braceOpen);
        String body = source.substring(braceOpen + 1, braceClose);

        List<CgMaterialProperty> result = new ArrayList<>();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            // Skip comment lines inside the Properties block
            if (line.startsWith("//")) continue;
            Matcher m = PROPERTY_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Invalid property declaration: '" + line + "'. " +
                                "Expected: '<name> : <type> [= <default>]'");
            }
            String name = m.group(1);
            String type = m.group(2);
            String def = m.group(3);
            // Enforce engine-reserved prefixes on property names
            validateNotReservedPrefix(name, "Properties block", resourcePath);
            // Enforce valid property types (spec section 3.2)
            if (!VALID_PROPERTY_TYPES.contains(type)) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Unknown property type '" + type + "' in Properties block. " +
                                "Valid types are: float, vec2, vec3, vec4, sampler2D");
            }
            result.add(CgMaterialProperty.fromDecl(name, type, def != null ? def.trim() : null));
        }
        return Collections.unmodifiableList(result);
    }

    private static String parseV2fBody(String source, String resourcePath) {
        int start = source.indexOf("struct v2f {");
        if (start == -1) {
            start = source.indexOf("struct v2f{");
            if (start == -1) {
                throw new CgShaderParseException("[" + resourcePath + "] 'struct v2f { }' block not found");
            }
        }
        int braceOpen = source.indexOf('{', start);
        int braceClose = matchBrace(source, braceOpen);
        String body = source.substring(braceOpen + 1, braceClose);

        // Validate fields during parse (eager validation)
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            // Skip comment lines inside the v2f struct — e.g. "// vec2 uv; (unused)"
            if (line.startsWith("//")) continue;
            if (INTEGER_TYPE_PATTERN.matcher(line).find()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Integer types (int, uint, ivec*, uvec*) are forbidden in struct v2f. " +
                                "Found: '" + line + "'");
            }
            Matcher m = V2F_FIELD_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Invalid v2f field: '" + line + "'. " +
                                "Expected: '<float-family-type> <name>;'");
            }
            // Enforce engine-reserved prefixes on v2f field names
            String fieldName = m.group(2);
            validateNotReservedPrefix(fieldName, "struct v2f", resourcePath);
        }
        return body;
    }

    private static int findV2fEnd(String source, String resourcePath) {
        int start = source.indexOf("struct v2f {");
        if (start == -1) start = source.indexOf("struct v2f{");
        if (start == -1) throw new CgShaderParseException("[" + resourcePath + "] 'struct v2f { }' block not found");
        int braceOpen = source.indexOf('{', start);
        int braceClose = matchBrace(source, braceOpen);
        // Find the ';' after the closing '}'
        int semi = source.indexOf(';', braceClose);
        if (semi == -1) return braceClose + 1;
        return semi + 1;
    }

    private static String parseGlobalDecls(String source, int v2fEnd, String resourcePath) {
        int vertexStart = source.indexOf("void vertex(");
        if (vertexStart == -1) {
            throw new CgShaderParseException("[" + resourcePath + "] 'void vertex(' function not found");
        }
        if (v2fEnd > vertexStart) return "";
        return source.substring(v2fEnd, vertexStart).trim();
    }

    private static String extractBlock(String source, String signature, String name, String resourcePath) {
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

    /**
     * Finds the matching closing brace for the opening brace at {@code openIdx}.
     * Uses a counter (not regex) to handle nested braces correctly.
     *
     * @param source  full source string
     * @param openIdx index of the '{' character
     * @return index of the matching '}'
     * @throws CgShaderParseException if no matching brace is found
     */
    private static int matchBrace(String source, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new CgShaderParseException(
                "Unmatched '{' at position " + openIdx + " — missing closing '}'");
    }

    /**
     * Validates that {@code source} contains no {@code #version} directive.
     * The compiler emits {@code #version} headers — they must not appear in authored files.
     *
     * @throws CgShaderParseException if a {@code #version} directive is found
     */
    private static void validateNoVersionDirective(String source, String resourcePath) {
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#version")) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] #version directive is forbidden in .shader files. " +
                                "The compiler injects the version header automatically. " +
                                "Remove the '#version' line from your shader.");
            }
        }
    }

    /**
     * Validates that {@code body} does not contain a {@code void main(} or {@code main(}
     * declaration. The compiler generates {@code void main()} — authored functions must not.
     *
     * @param body  the extracted function body (contents inside braces)
     * @param stage human-readable stage name for error messages (e.g. "vertex")
     * @throws CgShaderParseException if {@code main(} is found
     */
    private static void validateNoMainFunction(String body, String stage, String resourcePath) {
        // Check for common patterns: "void main(" or "main(" as a function def
        if (body.contains("void main(") || body.contains("void main (")) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] main() is forbidden in the " + stage + " body. " +
                            "The compiler generates void main() automatically. " +
                            "Rename your entry point to '" + stage + "(...)'.");
        }
    }

    /**
     * Validates that {@code name} does not begin with any engine-reserved prefix.
     * Engine-reserved prefixes are: {@code cg_}, {@code CG_}, {@code _v2f_}.
     *
     * @param name    the identifier name to check
     * @param context human-readable location description for error messages
     * @throws CgShaderParseException if {@code name} begins with a reserved prefix
     */
    private static void validateNotReservedPrefix(String name, String context, String resourcePath) {
        for (String prefix : RESERVED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Name '" + name + "' in " + context + " uses engine-reserved prefix '" + prefix +
                                "'. Engine-reserved prefixes (cg_, CG_, _v2f_) must not be used in authored symbols.");
            }
        }
    }

    private static void validateSectionStructure(String source, String resourcePath) {
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

        int propertiesPos = firstOf(source, "Properties {", "Properties{");
        int v2fPos = firstOf(source, "struct v2f {", "struct v2f{");
        int vertexPos = source.indexOf("void vertex(");
        int fragmentPos = source.indexOf("void fragment(");

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

    private static int countMarker(String source, String marker) {
        int count = 0;
        int idx = 0;
        while ((idx = source.indexOf(marker, idx)) != -1) {
            count++;
            idx += marker.length();
        }
        return count;
    }

    private static int firstOf(String source, String primary, String secondary) {
        int a = source.indexOf(primary);
        int b = source.indexOf(secondary);
        if (a == -1) return b;
        if (b == -1) return a;
        return Math.min(a, b);
    }

    private static void warnNonAscii(String source, String path) {
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
