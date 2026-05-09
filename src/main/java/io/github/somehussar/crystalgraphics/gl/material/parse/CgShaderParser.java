package io.github.somehussar.crystalgraphics.gl.material.parse;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Public facade for parsing CrystalShader {@code .shader} source files.
 *
 * <p>Splits a {@code .shader} source string into its declared sections without
 * parsing GLSL expressions inside function bodies. Zero GL calls at parse time
 * (all GL constants come from LWJGL's static final int fields, not from GL calls).
 * All format validation described in {@code docs/CRYSTALSHADER_FORMAT.md} is enforced
 * here, with {@link CgShaderParseException} thrown on any violation.</p>
 *
 * <p>Delegates structural concerns to {@link CgStructureParser}, render-state parsing
 * to {@link CgRenderStateParser}, property parsing to {@link CgPropertiesParser}, and
 * queue keyword parsing to {@link CgQueueParser}.</p>
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
 *   <li>{@code #version} directives are forbidden anywhere in the file.</li>
 *   <li>{@code main()} declarations are forbidden in vertex and fragment bodies.</li>
 *   <li>Each structural section must appear exactly once; {@code RenderState { }} at most once.</li>
 *   <li>Old keywords ({@code ZTest}, {@code ZWrite}, {@code BlendOp}) inside {@code RenderState}
 *       throw with a migration hint. {@code RenderQueue} at top level throws with a hint.</li>
 *   <li>Sampler property defaults must be quoted strings (e.g. {@code = "white"}).</li>
 * </ul>
 */
public final class CgShaderParser {

    /** A single field parsed from the {@code struct v2f { }} block.
     * @param type  GLSL type (e.g. {@code "vec3"}). Only float-family types are valid.
     * @param name  Field name (e.g. {@code "worldPos"}). */
    @Desugar
    public record V2fField(String type, String name) {}

    private CgShaderParser() {}

    // ── Public API ────────────────────────────────────────────────────────────

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
     * The resource path is included in all {@link CgShaderParseException} messages.
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

        CgStructureParser.warnNonAscii(source, resourcePath);
        CgStructureParser.validateNoVersionDirective(source, resourcePath);
        // Check for old top-level RenderQueue syntax before structural validation
        CgStructureParser.validateNoOldRenderQueue(source, resourcePath);
        CgStructureParser.validateSectionStructure(source, resourcePath);

        String shaderType   = CgStructureParser.parseShaderType(source, resourcePath);
        List<CgMaterialProperty> properties = CgPropertiesParser.parse(source, resourcePath);
        int    v2fEnd       = CgStructureParser.findV2fEnd(source, resourcePath);
        String v2fBody      = CgStructureParser.parseV2fBody(source, resourcePath);
        String globalDecls  = CgStructureParser.parseGlobalDecls(source, v2fEnd, resourcePath);
        String vertexBody   = CgStructureParser.extractBlock(source, "void vertex(",   "vertex",   resourcePath);
        String fragmentBody = CgStructureParser.extractBlock(source, "void fragment(", "fragment", resourcePath);

        CgStructureParser.validateNoMainFunction(vertexBody,   "vertex",   resourcePath);
        CgStructureParser.validateNoMainFunction(fragmentBody, "fragment", resourcePath);

        String preamble = CgStructureParser.parsePreambleDirectives(source, resourcePath);
        if (!preamble.isEmpty()) {
            globalDecls = preamble + (globalDecls.isEmpty() ? "" : "\n" + globalDecls);
        }

        return new CgParsedShader(shaderType, properties, v2fBody, globalDecls,
                vertexBody, fragmentBody,
                CgRenderStateParser.parse(source, resourcePath),
                CgQueueParser.parse(source, resourcePath));
    }

    /**
     * Parses the v2f struct body and returns a list of {@link V2fField} objects.
     * Used by {@link CgMaterialShaderCompiler}.
     */
    public static List<V2fField> parseV2fFields(CgParsedShader parsed) {
        String body = parsed.v2fStructBody();
        List<V2fField> fields = new ArrayList<>();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (CgStructureParser.INTEGER_TYPE_PATTERN.matcher(line).find()) {
                throw new CgShaderParseException(
                        "Integer types (int, uint, ivec*, uvec*) are forbidden in struct v2f. Found: '" + line + "'");
            }
            Matcher m = CgStructureParser.V2F_FIELD_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "Invalid v2f field declaration: '" + line + "'. Expected: '<float-family-type> <name>;'");
            }
            fields.add(new V2fField(m.group(1), m.group(2)));
        }
        return Collections.unmodifiableList(fields);
    }
}
