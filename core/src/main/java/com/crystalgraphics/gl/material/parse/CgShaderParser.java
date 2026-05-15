package com.crystalgraphics.gl.material.parse;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Public facade for parsing CrystalShader {@code .shader} source files.
 *
 * <p>Splits a {@code .shader} source string into its {@link CgParsedShader} representation —
 * including one {@link CgParsedPass} per {@code Pass { }} block — without parsing GLSL
 * expressions inside function bodies. Zero GL calls at parse time (all GL constants come
 * from LWJGL's static final int fields, not from GL calls). All format validation described
 * in {@code docs/CRYSTALSHADER_FORMAT.md} is enforced here, with
 * {@link CgShaderParseException} thrown on any violation.</p>
 *
 * <p>Delegates structural concerns to {@link CgStructureParser}, render-state parsing to
 * {@link CgRenderStateParser}, property parsing to {@link CgPropertiesParser}, and queue
 * keyword parsing to {@link CgQueueParser}.</p>
 *
 * <p>Brace matching for block extraction uses a counter-based scan (not regex).</p>
 *
 * <h3>Pass parsing contract</h3>
 * <ul>
 *   <li>At least one {@code Pass { }} block is required; the parser throws when none are found.</li>
 *   <li>Each pass may declare {@code Tags { "LightMode" = "..." "Name" = "..." }}.</li>
 *   <li>Unknown or absent {@code LightMode} values default to {@code "Forward"} with a warning.</li>
 *   <li>Duplicate {@code ShadowCaster} or {@code Depth} passes: first is kept, subsequent
 *       are dropped with a warning.</li>
 *   <li>Pass names are auto-assigned when absent: Forward passes → {@code "Pass0"}, {@code "Pass1"}, …;
 *       ShadowCaster → {@code "ShadowCaster"}; Depth → {@code "Depth"}.</li>
 * </ul>
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
 *   <li>Old keywords ({@code ZTest}, {@code ZWrite}, {@code BlendOp}) inside {@code RenderState}
 *       throw with a migration hint. {@code RenderQueue} at top level throws with a hint.</li>
 *   <li>Sampler property defaults must be quoted strings (e.g. {@code = "white"}).</li>
 * </ul>
 */
public final class CgShaderParser {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

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
     * @return the parsed structural representation containing one {@link CgParsedPass} per
     *         {@code Pass { }} block
     * @throws CgShaderParseException on any format violation
     */
    public static CgParsedShader parse(String source, String resourcePath) {
        if (source == null || source.isEmpty()) {
            throw new CgShaderParseException("[" + resourcePath + "] #type declaration missing: source is empty");
        }

        // ── Step 1: top-level validations ──────────────────────────────────
        CgStructureParser.warnNonAscii(source, resourcePath);
        CgStructureParser.validateNoVersionDirective(source, resourcePath);
        // Check for old top-level RenderQueue syntax before any structural parsing
        CgStructureParser.validateNoOldRenderQueue(source, resourcePath);

        // ── Step 2: material-level declarations ────────────────────────────
        String shaderType              = CgStructureParser.parseShaderType(source, resourcePath);
        List<String> featureNames      = CgStructureParser.parseFeaturePragmas(source, resourcePath);
        List<CgMaterialProperty> props = CgPropertiesParser.parse(source, resourcePath);
        int renderQueue                = CgQueueParser.parse(source, resourcePath);

        // ── Step 3: top-level Tags block → RenderType, CastShadows ────────
        Map<String, String> topTags = CgTagParser.parseTopLevelTags(source, resourcePath);

        String renderType = topTags.getOrDefault("RenderType", "Opaque");
        if (!topTags.containsKey("RenderType")) {
            LOGGER.warn("[{}] No 'RenderType' tag found in top-level Tags block. Defaulting to 'Opaque'. "
                    + "Add: Tags {{ \"RenderType\" = \"Opaque\" }} before your first Pass block.", resourcePath);
        }

        // CastShadows is opt-out: absent or any value other than "Off" means true
        boolean castShadows = !"Off".equalsIgnoreCase(topTags.getOrDefault("CastShadows", "On"));

        // ── Step 4: extract Pass blocks (at least one required) ───────────
        List<String> passBlocks = CgStructureParser.extractPassBlocks(source, resourcePath);
        if (passBlocks.isEmpty()) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] No Pass {} blocks found. "
                    + "A .shader file must contain at least one Pass { } block. "
                    + "Wrap your vertex/fragment code in: Pass { void vertex(...) {...} void fragment(...) {...} }");
        }

        // ── Step 5: shared (top-level) v2f — inherited by passes that don't declare their own
        String sharedV2f = CgStructureParser.parseSharedV2f(source, resourcePath);

        // ── Step 6: top-level preamble #-directives (prepended to each pass's globalDecls)
        String preamble = CgStructureParser.parsePreambleDirectives(source, resourcePath);

        // ── Step 7: parse each Pass block ─────────────────────────────────
        List<CgParsedPass> passes = new ArrayList<>();
        // Track seen unique-only light modes (ShadowCaster, Depth) for dedup warning
        Set<String> seenUniqueLightModes = new HashSet<>();
        int forwardPassIndex = 0;

        for (int i = 0; i < passBlocks.size(); i++) {
            String passBody = passBlocks.get(i);

            // 7a. Parse pass-level Tags block
            Map<String, String> passTags = CgTagParser.parseTagsBlock(passBody, resourcePath);

            // 7b. Resolve lightMode — default to Forward if absent or unrecognised
            String rawLightMode = passTags.get("LightMode");
            String lightMode;
            if (rawLightMode == null) {
                lightMode = CgParsedPass.LIGHT_MODE_FORWARD;
                LOGGER.warn("[" + resourcePath + "] Pass #" + i + " has no 'LightMode' tag. Defaulting to '" + CgParsedPass.LIGHT_MODE_FORWARD + "'. "
                        + "Add: Tags {{ \"LightMode\" = \"Forward\" }} inside the Pass block.");
            } else if (CgParsedPass.LIGHT_MODE_FORWARD.equals(rawLightMode)
                    || CgParsedPass.LIGHT_MODE_SHADOW_CASTER.equals(rawLightMode)
                    || CgParsedPass.LIGHT_MODE_DEPTH.equals(rawLightMode)) {
                lightMode = rawLightMode;
            } else {
                lightMode = CgParsedPass.LIGHT_MODE_FORWARD;
                LOGGER.warn("[" + resourcePath + "] Pass #" + i + " has unrecognised LightMode '" + rawLightMode + "'. Defaulting to '" + CgParsedPass.LIGHT_MODE_FORWARD + "'. "
                        + "Valid values: Forward, ShadowCaster, Depth.");
            }

            // 7c. Deduplicate ShadowCaster and Depth — only one of each is allowed
            if (!CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode)) {
                if (seenUniqueLightModes.contains(lightMode)) {
                    LOGGER.warn("[" + resourcePath + "] Duplicate '" + lightMode + "' pass found (Pass #" + i + "). Only the first occurrence is used. "
                            + "Remove the duplicate Pass block.");
                    continue; // skip this pass entirely
                }
                seenUniqueLightModes.add(lightMode);
            }

            // 7d. Resolve pass name — use authored "Name" tag or auto-assign
            String passName;
            if (passTags.containsKey("Name") && !passTags.get("Name").isEmpty()) {
                passName = passTags.get("Name");
            } else if (CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode)) {
                passName = "Pass" + forwardPassIndex;
            } else {
                // ShadowCaster and Depth use their lightMode as default name
                passName = lightMode;
            }

            // Increment forward-pass counter regardless of authored name
            if (CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode)) {
                forwardPassIndex++;
            }

            // 7e. Parse render state for this pass
            CgRenderState renderState = CgRenderStateParser.parse(passBody, resourcePath);

            // 7f. Resolve v2f body (per-pass override or shared fallback)
            String v2fBody = CgStructureParser.parsePassV2fBody(passBody, sharedV2f, resourcePath);

            // 7g. Extract global declarations for this pass
            String globalDecls = CgStructureParser.parsePassGlobalDecls(passBody, resourcePath);
            // Prepend top-level preamble #-directives (e.g. #extension lines before first Pass)
            if (!preamble.isEmpty()) {
                globalDecls = preamble + (globalDecls.isEmpty() ? "" : "\n" + globalDecls);
            }

            // 7h. Extract vertex and fragment function bodies
            String vertexBody   = CgStructureParser.extractBlock(passBody, "void vertex(",   "vertex",   resourcePath);
            String fragmentBody = CgStructureParser.extractBlock(passBody, "void fragment(", "fragment", resourcePath);

            CgStructureParser.validateNoMainFunction(vertexBody,   "vertex",   resourcePath);
            CgStructureParser.validateNoMainFunction(fragmentBody, "fragment", resourcePath);

            // 7i. Parse fragment output descriptor
            CgFragOutputParser.FragOutput fragOutput = CgFragOutputParser.parse(passBody, resourcePath);

            // 7j. Normalise MRT struct declaration in globalDecls (strip ': RTN' annotations
            //     so the struct body emitted by the compiler matches the clean parsed form)
            if (fragOutput.isMrt()) {
                String annotatedPattern = "struct " + fragOutput.mrtStructName() + " {";
                int structStart = globalDecls.indexOf(annotatedPattern);
                if (structStart >= 0) {
                    int braceOpen  = globalDecls.indexOf('{', structStart);
                    int braceClose = CgStructureParser.matchBrace(globalDecls, braceOpen);
                    int semiIdx    = globalDecls.indexOf(';', braceClose);
                    int declEnd    = semiIdx >= 0 ? semiIdx + 1 : braceClose + 1;
                    String cleanStruct = "struct " + fragOutput.mrtStructName() + " {\n"
                            + fragOutput.mrtStructBody() + "};";
                    globalDecls = globalDecls.substring(0, structStart) + cleanStruct
                            + globalDecls.substring(declEnd);
                }
            }

            // 7k. Structural pass-body validations
            CgStructureParser.validatePassBody(passBody, passName, resourcePath);

            passes.add(new CgParsedPass(lightMode, passName, renderState,
                    v2fBody, globalDecls, vertexBody, fragmentBody, fragOutput));
        }

        // ── Step 8: return assembled parsed shader ─────────────────────────
        return new CgParsedShader(shaderType, props, featureNames, renderQueue,
                renderType, castShadows, Collections.unmodifiableList(passes));
    }

    /**
     * Parses the v2f struct body from a {@link CgParsedPass} and returns a list of
     * {@link V2fField} objects. Used by {@link CgMaterialShaderCompiler}.
     *
     * @param pass the parsed pass whose {@code v2fStructBody()} to parse
     * @return unmodifiable ordered list of v2f fields; empty if v2f body is empty
     * @throws CgShaderParseException on integer types or malformed field declarations
     */
    public static List<V2fField> parseV2fFields(CgParsedPass pass) {
        String body = pass.v2fStructBody();
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
