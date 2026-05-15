package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.github.bsideup.jabel.Desugar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code void fragment()} signature to determine the fragment output structure,
 * and owns the {@link FragOutput} descriptor that captures the result.
 *
 * <p>Single-output path: {@code out vec4 fragColor} → {@link FragOutput#isMrt()} is false.<br>
 * MRT path: {@code out GBuffer o} → looks up {@code struct GBuffer}, parses field names,
 * resolves {@code : RTN} location annotations or falls back to positional assignment.</p>
 *
 * <p>All validation rules: all-or-none annotations, no duplicates, location range 0–7,
 * maximum 8 fields, all fields must be {@code vec4}. Violations throw
 * {@link CgShaderParseException} (never {@link CgPreprocessorException}).</p>
 */
final class CgFragOutputParser {

    private static final Pattern FRAGMENT_SIG_PATTERN = Pattern.compile("void\\s+fragment\\s*\\(([^)]+)\\)");
    private static final Pattern STRUCT_BODY_PATTERN = Pattern.compile("struct\\s+(\\w+)\\s*\\{([^}]*)\\}");
    private static final Pattern VEC4_FIELD_PATTERN = Pattern.compile(
            "^\\s*vec4\\s+(\\w+)(?:\\s*:\\s*RT(\\d+))?\\s*;\\s*$");
    private static final Pattern NON_VEC4_FIELD_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s+(\\w+)\\s*(?::\\s*RT\\d+)?\\s*;\\s*$");

    private CgFragOutputParser() {}

    // ── Nested descriptor ─────────────────────────────────────────────────────

    /**
     * Describes the fragment shader output structure parsed from a {@code .shader} file.
     *
     * <p>For single-output shaders ({@code void fragment(in v2f i, out vec4 fragColor)}):
     * {@link #mrtStructName()} is {@code null}, {@link #fieldNames()} is a singleton list
     * containing the param name, and {@link #locations()} is {@code [0]}.
     *
     * <p>For MRT output-struct shaders ({@code void fragment(in v2f i, out GBuffer o)}):
     * {@link #mrtStructName()} is the struct type name (e.g. {@code "GBuffer"}),
     * {@link #fieldNames()} lists struct field names in declaration order,
     * {@link #locations()} holds the resolved layout locations (from {@code : RTN} annotations
     * or positional fallback), and {@link #mrtStructBody()} is the annotation-free struct body
     * ready for GLSL emission.
     *
     * <p>All list fields are unmodifiable.
     *
     * @param mrtStructName  MRT output struct type name, or {@code null} for the single-output path.
     * @param outParamName   Name of the {@code out} parameter in {@code void fragment()} —
     *                       e.g. {@code "o"} for a struct or {@code "fragColor"} for single-output.
     *                       Never null.
     * @param fieldNames     Ordered field names. For MRT: struct fields. For single-output: singleton
     *                       containing {@code outParamName}. Unmodifiable, never null, never empty.
     * @param locations      Resolved layout locations, parallel to {@link #fieldNames()}.
     *                       For single-output: {@code [0]}. Unmodifiable, never null.
     * @param mrtStructBody  Annotation-free struct field content (e.g. {@code "    vec4 albedo;\n"}),
     *                       or {@code null} for the single-output path.
     */
    @Desugar
    public record FragOutput(String mrtStructName, String outParamName, List<String> fieldNames,
                             List<Integer> locations, String mrtStructBody) {

        /** Convenience: true when this is an MRT output-struct path. */
        public boolean isMrt() {
            return mrtStructName != null;
        }

        /** The number of render targets. */
        public int rtCount() {
            return fieldNames.size();
        }

        /** Factory for the single-output case. */
        public static FragOutput singleOutput(String outParamName) {
            return new FragOutput(null, outParamName,
                    Collections.singletonList(outParamName),
                    Collections.singletonList(0),
                    null);
        }
    }

    // ── Parse entry point ─────────────────────────────────────────────────────

    static FragOutput parse(String source, String resourcePath) {
        Matcher sigMatcher = FRAGMENT_SIG_PATTERN.matcher(source);
        if (!sigMatcher.find()) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] 'void fragment(' function not found");
        }
        String paramList = sigMatcher.group(1);

        String outType = null;
        String outName = null;
        for (String param : paramList.split(",")) {
            String p = param.trim();
            if (p.startsWith("out ")) {
                String rest = p.substring(4).trim();
                String[] parts = rest.split("\\s+");
                if (parts.length >= 2) {
                    outType = parts[0];
                    outName = parts[1];
                }
            }
        }
        if (outType == null || outName == null) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] 'void fragment()' must have exactly one 'out' parameter");
        }

        if ("vec4".equals(outType)) {
            return FragOutput.singleOutput(outName);
        }

        Matcher structMatcher = STRUCT_BODY_PATTERN.matcher(source);
        String structBody = null;
        while (structMatcher.find()) {
            if (outType.equals(structMatcher.group(1))) {
                structBody = structMatcher.group(2);
                break;
            }
        }
        if (structBody == null) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] MRT struct 'struct " + outType + " { }' not found in shader source");
        }

        List<String> fieldNames = new ArrayList<>();
        List<Integer> annotatedLocs = new ArrayList<>();
        boolean anyAnnotated = false;
        boolean anyUnannotated = false;
        StringBuilder cleanBody = new StringBuilder();

        for (String rawLine : structBody.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            Matcher vec4Match = VEC4_FIELD_PATTERN.matcher(line);
            if (!vec4Match.matches()) {
                Matcher anyField = NON_VEC4_FIELD_PATTERN.matcher(line);
                if (anyField.matches()) {
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] MRT struct '" + outType
                                    + "' field '" + anyField.group(2)
                                    + "' has type '" + anyField.group(1)
                                    + "' — all MRT output fields must be vec4");
                }
                throw new CgShaderParseException(
                        "[" + resourcePath + "] MRT struct '" + outType
                                + "' contains unrecognized field line: '" + line + "'");
            }

            String fieldName = vec4Match.group(1);
            String locStr = vec4Match.group(2);

            fieldNames.add(fieldName);
            cleanBody.append("    vec4 ").append(fieldName).append(";\n");

            if (locStr != null) {
                annotatedLocs.add(Integer.parseInt(locStr));
                anyAnnotated = true;
            } else {
                annotatedLocs.add(null);
                anyUnannotated = true;
            }
        }

        if (fieldNames.isEmpty()) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] MRT struct '" + outType + "' has no fields");
        }

        if (fieldNames.size() > 8) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] MRT struct '" + outType + "' has " + fieldNames.size()
                            + " fields — maximum is 8");
        }

        if (anyAnnotated && anyUnannotated) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] MRT struct '" + outType
                            + "' has mixed annotated/unannotated fields — all-or-none: "
                            + "either all fields must have ': RTN' annotations or none must");
        }

        List<Integer> resolvedLocs = new ArrayList<>();
        if (anyAnnotated) {
            boolean[] seen = new boolean[8];
            for (int i = 0; i < annotatedLocs.size(); i++) {
                int loc = annotatedLocs.get(i);
                if (loc < 0 || loc > 7) {
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] MRT struct '" + outType
                                    + "' field '" + fieldNames.get(i)
                                    + "' has location " + loc + " — valid range is 0–7");
                }
                if (seen[loc]) {
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] MRT struct '" + outType
                                    + "' has duplicate location " + loc);
                }
                seen[loc] = true;
                resolvedLocs.add(loc);
            }
        } else {
            for (int i = 0; i < fieldNames.size(); i++) {
                resolvedLocs.add(i);
            }
        }

        return new FragOutput(
                outType,
                outName,
                Collections.unmodifiableList(fieldNames),
                Collections.unmodifiableList(resolvedLocs),
                cleanBody.toString());
    }
}
