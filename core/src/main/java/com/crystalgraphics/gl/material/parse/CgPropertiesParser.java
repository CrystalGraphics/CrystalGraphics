package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code Properties { }} block from a CrystalShader {@code .shader} source into
 * {@link CgMaterialProperty} instances. Supports both old-style ({@code _Name : type}) and
 * new-style ({@code _Name ("Display Name", type)}) declarations.
 *
 * <p>Returns an empty list if the block is absent.</p>
 */
final class CgPropertiesParser {

    /** Old-style: {@code _Name : type [= default]} */
    private static final Pattern PROPERTY_PATTERN =
            Pattern.compile("^\\s*(\\w+)\\s*:\\s*(\\w+)(?:\\s*=\\s*(.+?))?\\s*$");

    /**
     * Detects the new-style property declaration: {@code _Name ("Display Name", type) [= default]}.
     * Group 1 = name, group 2 = display name. The rest (type + optional default) is parsed
     * manually by {@link #parseNewStyleTypeAndDefault} to correctly handle types like
     * {@code Range(0,10)} whose inner parentheses would confuse a pure-regex approach.
     */
    private static final Pattern NEW_PROPERTY_PATTERN =
            Pattern.compile("^\\s*(\\w+)\\s*\\(\"([^\"]+)\",\\s*(.*)$");

    /**
     * Detects new-style syntax where the required quoted display name is missing.
     * Matches {@code _Name (something_without_quote...}.
     */
    private static final Pattern MISSING_DISPLAY_NAME_PATTERN =
            Pattern.compile("^\\s*(\\w+)\\s*\\([^\"(]");

    /** Valid property types accepted in the {@code Properties { }} block. */
    private static final Set<String> VALID_PROPERTY_TYPES;

    static {
        Set<String> s = new HashSet<>(Arrays.asList(
                "float", "vec2", "vec3", "vec4",
                "int", "boolean", "color", "Range",
                "sampler2D", "sampler2DArray", "sampler3D", "samplerCube"));
        VALID_PROPERTY_TYPES = Collections.unmodifiableSet(s);
    }

    private CgPropertiesParser() {}

    /**
     * Parses the {@code Properties { }} block. Returns an empty list if the block is absent.
     */
    static List<CgMaterialProperty> parse(String source, String resourcePath) {
        int start = source.indexOf("Properties {");
        if (start == -1) start = source.indexOf("Properties{");
        if (start == -1) return Collections.emptyList();

        int braceOpen  = source.indexOf('{', start);
        int braceClose = CgStructureParser.matchBrace(source, braceOpen);
        String body    = source.substring(braceOpen + 1, braceClose);

        List<CgMaterialProperty> result = new ArrayList<>();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            // ── New-style: _Name ("Display Name", type) [= default] ──────
            Matcher newM = NEW_PROPERTY_PATTERN.matcher(line);
            if (newM.matches()) {
                String name        = newM.group(1);
                String displayName = newM.group(2);
                String[] typeAndDef = parseNewStyleTypeAndDefault(newM.group(3), resourcePath, line);
                String typeRaw = typeAndDef[0];
                String defRaw  = typeAndDef[1];
                CgStructureParser.validateNotReservedPrefix(name, "Properties block", resourcePath);
                String typeBase = resolvePropertyTypeBase(typeRaw, resourcePath, line);
                defRaw = validateAndStripSamplerDefault(typeBase, defRaw, resourcePath, line);
                result.add(buildProperty(name, displayName, typeBase, typeRaw, defRaw));
                continue;
            }

            // ── Detect new-style attempt with missing quoted display name ─
            if (MISSING_DISPLAY_NAME_PATTERN.matcher(line).find()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Property declaration missing a quoted display name. "
                        + "New-style syntax: '_Name (\"Display Name\", type) [= default]'. Got: '" + line + "'");
            }

            // ── Old-style: _Name : type [= default] ──────────────────────
            Matcher m = PROPERTY_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Invalid property declaration: '" + line + "'. "
                        + "Expected: '<name> : <type> [= <default>]'");
            }
            String name    = m.group(1);
            String typeRaw = m.group(2);
            String defRaw  = m.group(3) != null ? m.group(3).trim() : null;
            CgStructureParser.validateNotReservedPrefix(name, "Properties block", resourcePath);
            String typeBase = resolvePropertyTypeBase(typeRaw, resourcePath, line);
            defRaw = validateAndStripSamplerDefault(typeBase, defRaw, resourcePath, line);
            result.add(buildProperty(name, name, typeBase, typeRaw, defRaw));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Parses the suffix of a new-style property declaration that follows the display-name comma.
     * Input looks like {@code "color) = (1,0,0,1)"} or {@code "Range(0,10)) = 5.0"} or
     * {@code "vec4)"}.  Finds the closing {@code )} of the outer declaration group by tracking
     * paren depth left-to-right (starting at depth=1 for the already-open outer paren), then
     * splits out the optional {@code = default} portion.
     *
     * @return {@code [typeRaw, defRaw]} where {@code defRaw} may be {@code null}.
     */
    private static String[] parseNewStyleTypeAndDefault(String suffix, String resourcePath, String line) {
        // Walk left-to-right; we are already inside the outer '(' so depth starts at 1.
        int depth = 1;
        int closeIdx = -1;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) { closeIdx = i; break; }
            }
        }
        if (closeIdx < 0) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Malformed property declaration (missing closing ')'): '" + line + "'");
        }
        String typeRaw = suffix.substring(0, closeIdx).trim();
        String rest    = suffix.substring(closeIdx + 1).trim();
        String defRaw  = null;
        if (rest.startsWith("=")) {
            defRaw = rest.substring(1).trim();
            if (defRaw.isEmpty()) defRaw = null;
        }
        return new String[]{typeRaw, defRaw};
    }

    /**
     * Resolves the raw type token to a base type name recognized by
     * {@link CgMaterialProperty.Type}. Handles {@code Range(min, max)} → {@code "Range"}.
     */
    private static String resolvePropertyTypeBase(String typeRaw, String resourcePath, String line) {
        String base = typeRaw;
        if (typeRaw.startsWith("Range(") || typeRaw.startsWith("Range (")) {
            base = "Range";
        }
        if (!VALID_PROPERTY_TYPES.contains(base)) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Unknown property type '" + base + "' in Properties block. "
                    + "Valid types: float, vec2, vec4, int, color, Range, sampler2D, "
                    + "sampler2DArray, sampler3D, samplerCube. (vec3 is not supported — use vec4.) "
                    + "In: '" + line + "'");
        }
        // Hard-ban vec3: STD140 pads vec3 to 16 bytes but the GLSL compiler places the next
        // field 12 bytes later, causing a 4-byte offset mismatch. Use vec4 instead.
        if ("vec3".equals(base)) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] vec3 is not supported as a material property type "
                    + "due to STD140 alignment ambiguity — use vec4 instead. In: '" + line + "'");
        }
        return base;
    }

    /**
     * For sampler types, validates that the default value is a quoted string, strips the
     * enclosing quotes, and returns the bare value. For non-sampler types returns {@code defRaw}
     * unchanged.
     */
    private static String validateAndStripSamplerDefault(
            String typeBase, String defRaw, String resourcePath, String line) {
        if (defRaw == null || !typeBase.startsWith("sampler")) return defRaw;
        if (!defRaw.startsWith("\"") || !defRaw.endsWith("\"") || defRaw.length() < 2) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Sampler default must be a quoted string "
                    + "(e.g. = \"white\"), got: '" + defRaw + "' in: '" + line + "'");
        }
        return defRaw.substring(1, defRaw.length() - 1);
    }

    /** Creates a {@link CgMaterialProperty} and sets Range bounds from the raw type token if applicable. */
    private static CgMaterialProperty buildProperty(
            String name, String displayName, String typeBase, String typeRaw, String def) {
        CgMaterialProperty prop = CgMaterialProperty.fromDecl(name, displayName, typeBase, def);
        if ("Range".equals(typeBase) && typeRaw.contains("(")) {
            try {
                int lp = typeRaw.indexOf('(');
                int rp = typeRaw.lastIndexOf(')');
                if (lp >= 0 && rp > lp) {
                    String[] parts = typeRaw.substring(lp + 1, rp).split(",");
                    if (parts.length == 2) {
                        prop.setRange(Float.parseFloat(parts[0].trim()),
                                      Float.parseFloat(parts[1].trim()));
                        prop.set(prop.getFloatValue()[0]);
                    }
                }
            } catch (NumberFormatException ignored) {
                // Malformed range bounds — leave as 0,0
            }
        }
        return prop;
    }
}
