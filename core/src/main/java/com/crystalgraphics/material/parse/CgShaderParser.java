package com.crystalgraphics.material.parse;

import com.crystalgraphics.platform.gl.driver.GLC;
import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.util.io.CgIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a {@code .shader} in the material format ({@code docs/MATERIAL_FORMAT.md}) into the {@link CgParsedShader} the
 * compiler builds programs from. No GL call is made.
 *
 * <pre>{@code
 * CgParsedShader parsed = CgShaderParser.parse(CgIO.loadSource(path), path);
 * List<String> keywords = parsed.featureNames();      // its Keywords { }
 * }</pre>
 *
 * <ul>
 *   <li>A source must open with its {@code #domain} line; any other fails naming it.</li>
 *   <li>The format lowers onto passes ({@link CgMaterialFormat}): the passes the engine draws a material in, which no
 *       file is written in. The passes' own checks then run, so a format error and a GLSL-shape error both fail here,
 *       placed on the line they name ({@link CgShaderParseException}).</li>
 * </ul>
 */
public final class CgShaderParser {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    /**
     * An Emissive pass with no RenderState block: added into the bloom target, which has no depth. ONE ONE, not
     * {@link CgBlendState#ADDITIVE}'s SRC_ALPHA ONE: emitted light is written with an alpha of 0.
     */
    private static final CgRenderState EMISSIVE_STATE = CgRenderState.builder()
            .blend(new CgBlendState(true, GLC.GL_ONE, GLC.GL_ONE, GLC.GL_ONE, GLC.GL_ONE,
                    GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD))
            .depth(CgDepthState.NONE).cull(CgCullState.BACK).build();

    /**
     * Every Distortion pass's blend, whatever its RenderState says, since it is the target's encoding: offsets and
     * split add, alpha keeps the nearest haze's closeness.
     */
    private static final CgBlendState DISTORTION_BLEND = new CgBlendState(true, GLC.GL_ONE, GLC.GL_ONE,
            GLC.GL_ONE, GLC.GL_ONE, GLC.GL_FUNC_ADD, GLC.GL_MAX);

    /** A Distortion pass with no RenderState block adds into the distortion target, which has no depth either. */
    private static final CgRenderState DISTORTION_STATE = EMISSIVE_STATE.withBlend(DISTORTION_BLEND);

    /** A fragment naming the normal a {@code "Surface"}-lit material is lit by. */
    static final Pattern SURFACE_NORMAL = Pattern.compile("\\bCG_SURFACE_NORMAL\\b");

    /** What a program reading the world's lights it is handed names, itself or in a file it includes. */
    private static final String[] HANDED_LIGHTS = {"cg_NearestLights", "CG_DRAW_POINT_LIGHT_"};

    /** What a program reading the sun's shadow it is handed names, itself or in a file it includes. */
    private static final String[] HANDED_SUN = {"CG_DIRECTIONAL_SHADOW"};

    /** The sun's shadow map a handed program reads this firing ({@code CgWorldLights.hand}). */
    private static final String HANDED_SUN_PROPERTIES = """
            _cg_SunMap ("The sun's cascades, a quadrant each: our casters' depth from the light", sampler2D) = "white"
            _cg_SunRows ("Each cascade's uv, depth and range rows", vec4[16]) = (0, 0, 0, 0)
            _cg_Sun ("1 while the map holds this firing's casters", vec4) = (0, 0, 0, 0)
            _cg_SunEye ("The eye the map is placed from, camera-relative", vec4) = (0, 0, 0, 0)
            """;

    /** What a post material reading the scene textures it is handed names, itself or in a file it includes. */
    private static final String[] HANDED_SCENE = {"CG_CUSTOM_STENCIL(", "CG_SCENE_WORLD_NORMAL(", "CG_CUSTOM_DATA(",
            "CG_SHADING_MODEL_ID("};

    /** The scene textures a post material reads, bound by {@code CgPostMaterial} from the firing's keys. */
    private static final String HANDED_SCENE_PROPERTIES = """
            _cg_CustomStencil ("CustomStencil, CustomDepth's stencil as an unsigned integer", usampler2D) = "zero"
            _cg_WorldNormal ("Our opaque surfaces' world normals, n * 0.5 + 0.5, alpha the eye depth", sampler2D) = "black"
            _cg_CustomData ("Custom data and the shading model, packed as handed_scene.glsl reads them", sampler2D) = "black"
            """;

    /** What a program writing Unreal's custom data or shading model names, itself or in a file it includes. */
    private static final String[] WRITES_CUSTOM_DATA = {"CG_CUSTOM_DATA_OUT", "CG_SHADING_MODEL_OUT"};

    /** Where the light grid a handed program reads lies this firing ({@code CgWorldLights.hand}). */
    private static final String HANDED_PROPERTIES = """
            _cg_LightGrid ("Cells across, up and deep, and 1 while the grid holds lights", vec4) = (1, 1, 1, 0)
            _cg_LightTile ("A tile's share of the target across and up", vec4) = (1, 1, 0, 0)
            _cg_LightZ ("The slices' B, O and S", vec4) = (1, 1, 1, 0)
            _cg_LightRays ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
            _cg_LightView0 ("Eye-relative world to the grid's view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
            _cg_LightView1 ("The second row", vec4) = (0, 1, 0, 0)
            _cg_LightView2 ("The third row", vec4) = (0, 0, 1, 0)
            _cg_LightEye ("The eye the lights are placed from, camera-relative", vec4) = (0, 0, 0, 0)
            """;

    /** A single field parsed from the {@code struct v2f { }} block.
     * @param type          GLSL type (e.g. {@code "vec3"}); an integer type only {@code flat}
     * @param name          Field name (e.g. {@code "worldPos"})
     * @param interpolation {@code "flat"}, {@code "noperspective"}, {@code "smooth"} or {@code ""}, as written */
    @Desugar
    public record V2fField(String type, String name, String interpolation) {}

    private CgShaderParser() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * The {@code Properties { }} block of any source in the shader family, {@code .compute} included: what a material
     * or a kernel exposes. Empty when there is none.
     */
    public static List<CgMaterialProperty> parseProperties(String source, String resourcePath) {
        return CgPropertiesParser.parse(source, resourcePath);
    }

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
        if (source == null || !CgMaterialFormat.isDomainFile(source)) {
            throw new CgShaderParseException("[" + resourcePath + "] a .shader opens with its #domain line (surface,"
                    + " post, ui or global): docs/MATERIAL_FORMAT.md");
        }
        return parseLowered(source, resourcePath);
    }

    /**
     * Parses the passes the material format lowers onto: the format's own route, and what a test of the passes'
     * mechanics writes directly. No file is read in this form.
     */
    static CgParsedShader parseLowered(String source) {
        return parseLowered(source, "<unknown>");
    }

    /** {@link #parseLowered(String)}, naming where the source came from in its errors. */
    static CgParsedShader parseLowered(String source, String resourcePath) {
        try {
            return parseLocating(source, resourcePath);
        } catch (CgShaderParseException failed) {
            // PLACED ON THE WAY OUT, once, rather than at sixty-odd throw sites. Nearly every message
            // quotes the token it choked on, so the message itself is the anchor -- and a parser that has
            // already failed has no cursor to offer instead. An unplaced exception is what every caller
            // got before this existed, so this can only add.
            throw locate(failed, source);
        }
    }

    /**
     * The first quoted token in a parser message, used to find the offending line.
     *
     * <p>Every message in this package that names a construct quotes it — {@code 'void fragment('},
     * {@code '#type'}, {@code 'Blend'} — which makes one pattern enough for all of them and means no
     * throw site had to change.</p>
     */
    private static final Pattern QUOTED = Pattern.compile("'([^']{1,80})'");

    private static CgShaderParseException locate(CgShaderParseException failed, String source) {
        if (failed.hasPosition() || source == null) return failed;
        String message = failed.getMessage();
        if (message == null) return failed;
        java.util.regex.Matcher quoted = QUOTED.matcher(message);
        while (quoted.find()) {
            CgShaderParseException placed = failed.locate(source, quoted.group(1));
            if (placed.hasPosition()) return placed;
        }
        return failed;
    }

    /** The engine buffer whose shaders read their numbers from a draw's property row ({@code CgPropertyRows}). */
    public static final String ROWS = "rows";

    /**
     * Under {@code cg_use rows}: every number, vector, colour and array read through its row accessor, and after them
     * the hidden {@code _cg_at<name>} ints saying where a draw's row holds each, -1 for the material's own value.
     */
    private static List<CgMaterialProperty> withRowSlots(List<CgMaterialProperty> props) {
        List<CgMaterialProperty> out = new ArrayList<>(props);
        for (CgMaterialProperty p : props) {
            if (!p.isRowable()) continue;
            p.markRows();
            out.add(CgMaterialProperty.rowSlot(p.getName()));
        }
        return Collections.unmodifiableList(out);
    }

    private static CgParsedShader parseLocating(String source, String resourcePath) {
        if (source == null || source.isEmpty()) {
            throw new CgShaderParseException("[" + resourcePath + "] #type declaration missing: source is empty");
        }
        if (CgMaterialFormat.isDomainFile(source)) {
            // Lowered onto passes, then parsed as them.
            CgMaterialFormat.Lowered lowered = CgMaterialFormat.lower(source, resourcePath);
            CgParsedShader parsed = parseLocating(lowered.source(), resourcePath);
            requireMaskClip(parsed.properties(), lowered.format().maskClip(), resourcePath);
            return parsed.inFormat(lowered.format());
        }

        // ── Step 1: top-level validations ──────────────────────────────────
        CgStructureParser.warnNonAscii(source, resourcePath);
        CgStructureParser.validateNoVersionDirective(source, resourcePath);
        // Check for old top-level RenderQueue syntax before any structural parsing
        CgStructureParser.validateNoOldRenderQueue(source, resourcePath);

        // ── Step 2: material-level declarations ────────────────────────────
        String shaderType              = CgStructureParser.parseShaderType(source, resourcePath);
        List<String> featureNames      = CgStructureParser.parseFeaturePragmas(source, resourcePath);
        List<String> engineBuffers     = CgEngineBufferRegistry.withRequirements(
                CgStructureParser.parseUsePragmas(source, resourcePath));
        // Every particle program takes the strip variant, undeclared: a beam draws a ported material through it. One
        // declaring no usage keyword takes the sprite factory's too, which a #type spatial one would otherwise never get.
        List<String> implicitFeatures = List.of();
        if (engineBuffers.contains("particle")) {
            List<String> withStrip = new ArrayList<>(featureNames), implicit = new ArrayList<>();
            List<String> added = new ArrayList<>(List.of(CgMaterialShaderCompiler.STRIP, CgMaterialShaderCompiler.STRIP_BEAM,
                    CgMaterialShaderCompiler.STRIP_ANIM));
            boolean usageKeyword = false;
            for (CgMaterialUsage u : CgMaterialUsage.values()) {
                if (u.keyword() != null && u != CgMaterialUsage.RIBBONS && featureNames.contains(u.keyword())) usageKeyword = true;
            }
            if (!usageKeyword) added.add(CgMaterialUsage.SPRITES.keyword());
            for (String k : added) {
                if (withStrip.contains(k)) continue;
                withStrip.add(k);
                implicit.add(k);
            }
            featureNames = Collections.unmodifiableList(withStrip);
            implicitFeatures = Collections.unmodifiableList(implicit);
        }
        CgPermutationDomain permutations = CgPermutationDomain.parse(source, featureNames, resourcePath);
        validateEngineBufferUsage(source, engineBuffers, resourcePath);
        noteGeneratedReads(engineBuffers);
        List<CgMaterialProperty> props = withEngineProperties(CgPropertiesParser.parse(source, resourcePath), engineBuffers);
        if (engineBuffers.contains(ROWS)) props = withRowSlots(props);
        String uncommented = stripGlslComments(source);
        CgStructureParser.validateNoLooseCode(uncommented, resourcePath);
        CgStructureParser.MaterialBuffers buffers = CgStructureParser.parseBuffers(uncommented, resourcePath);
        boolean handedLights = new CgShaderPreprocessor().mentions(uncommented, resourcePath, HANDED_LIGHTS);
        if (handedLights) {
            buffers = CgStructureParser.withHandedLights(buffers, resourcePath);
            List<CgMaterialProperty> withGrid = new ArrayList<>(props);
            withGrid.addAll(CgPropertiesParser.parseEngine(HANDED_PROPERTIES, "handed lights"));
            props = Collections.unmodifiableList(withGrid);
        }
        boolean handedSun = new CgShaderPreprocessor().mentions(uncommented, resourcePath, HANDED_SUN);
        if (handedSun) {
            List<CgMaterialProperty> withSun = new ArrayList<>(props);
            withSun.addAll(CgPropertiesParser.parseEngine(HANDED_SUN_PROPERTIES, "handed sun"));
            props = Collections.unmodifiableList(withSun);
        }
        boolean handedScene = new CgShaderPreprocessor().mentions(uncommented, resourcePath, HANDED_SCENE);
        if (handedScene) {
            List<CgMaterialProperty> withScene = new ArrayList<>(props);
            withScene.addAll(CgPropertiesParser.parseEngine(HANDED_SCENE_PROPERTIES, "handed scene textures"));
            props = Collections.unmodifiableList(withScene);
        }
        boolean writesCustomData = new CgShaderPreprocessor().mentions(uncommented, resourcePath, WRITES_CUSTOM_DATA);
        int renderQueue               = CgQueueParser.parse(source, resourcePath);

        // ── Step 3: top-level Tags block → RenderType and the rest ────────
        Map<String, String> topTags = CgTagParser.parseTopLevelTags(source, resourcePath);

        String renderType = topTags.getOrDefault("RenderType", "Opaque");
        if (!topTags.containsKey("RenderType")) {
            LOGGER.warn("[{}] No 'RenderType' tag found in top-level Tags block. Defaulting to 'Opaque'. "
                    + "Add: Tags {{ \"RenderType\" = \"Opaque\" }} before your first Pass block.", resourcePath);
        }

        if (topTags.containsKey("CastShadows")) {
            throw new CgShaderParseException("[" + resourcePath + "] CastShadows is gone: no material draws a shadow pass, "
                    + "the world's shadows are traced from each pixel. Remove the tag.");
        }
        float sceneColorMargin = sceneColorMargin(topTags.get("SceneColorMargin"), resourcePath);
        String lighting = topTags.getOrDefault("Lighting", "Lit");
        if (!"Lit".equals(lighting) && !"Unlit".equals(lighting) && !"Surface".equals(lighting)) {
            throw new CgShaderParseException("[" + resourcePath + "] Lighting is \"Lit\", \"Unlit\" or \"Surface\", not \""
                    + lighting + "\"");
        }
        String fog = topTags.getOrDefault("Fog", "On");
        if (!"On".equals(fog) && !"Off".equals(fog)) {
            throw new CgShaderParseException("[" + resourcePath + "] Fog is \"On\" or \"Off\", not \"" + fog + "\"");
        }
        String media = topTags.getOrDefault("Media", "On");
        if (!"On".equals(media) && !"Off".equals(media)) {
            throw new CgShaderParseException("[" + resourcePath + "] Media is \"On\" or \"Off\", not \"" + media + "\"");
        }
        String colorSpace = topTags.getOrDefault("ColorSpace", "sRGB");
        if (!"sRGB".equals(colorSpace) && !"Linear".equals(colorSpace) && !"Target".equals(colorSpace)) {
            throw new CgShaderParseException("[" + resourcePath + "] ColorSpace is \"sRGB\", \"Linear\" or \"Target\", not \""
                    + colorSpace + "\"");
        }
        String output = topTags.getOrDefault("Output", "Color");
        if (!"Color".equals(output) && !"EmissiveOpacity".equals(output)) {
            throw new CgShaderParseException("[" + resourcePath + "] Output is \"Color\" or \"EmissiveOpacity\", not \""
                    + output + "\"");
        }
        CgParsedShader.DepthMode depthMode = depthMode(topTags.get("Depth"), renderQueue, resourcePath);
        String tubeTag = topTags.get("Tube");
        CgParsedShader.Tube tube = null;
        if ("Volume".equals(tubeTag)) tube = CgParsedShader.Tube.VOLUME;
        else if ("Closed".equals(tubeTag)) tube = CgParsedShader.Tube.CLOSED;
        else if (tubeTag != null) {
            throw new CgShaderParseException("[" + resourcePath + "] Tube is \"Volume\" or \"Closed\", not \"" + tubeTag + "\"");
        }

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
        List<CgParsedShader.PostTarget> postTargets = new ArrayList<>();
        // Track seen unique-only light modes (Depth, Emissive, Distortion) for dedup warning
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
            } else if ("ShadowCaster".equals(rawLightMode)) {
                throw new CgShaderParseException("[" + resourcePath + "] Pass #" + i + " is a ShadowCaster: that pass is gone, "
                        + "the world's shadows are traced from each pixel. Remove the Pass block.");
            } else if (CgParsedPass.LIGHT_MODE_FORWARD.equals(rawLightMode)
                    || CgParsedPass.LIGHT_MODE_DEPTH.equals(rawLightMode)
                    || CgParsedPass.LIGHT_MODE_EMISSIVE.equals(rawLightMode)
                    || CgParsedPass.LIGHT_MODE_DISTORTION.equals(rawLightMode)) {
                lightMode = rawLightMode;
            } else {
                lightMode = CgParsedPass.LIGHT_MODE_FORWARD;
                LOGGER.warn("[" + resourcePath + "] Pass #" + i + " has unrecognised LightMode '" + rawLightMode + "'. Defaulting to '" + CgParsedPass.LIGHT_MODE_FORWARD + "'. "
                        + "Valid values: Forward, Depth, Emissive, Distortion.");
            }

            // 7c. Deduplicate Depth, Emissive and Distortion — only one of each is allowed
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
            if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(lightMode) || CgParsedPass.LIGHT_MODE_DISTORTION.equals(lightMode)) {
                passName = lightMode;   // the world renderer finds it by its LightMode
            } else if (passTags.containsKey("Name") && !passTags.get("Name").isEmpty()) {
                passName = passTags.get("Name");
            } else if (CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode)) {
                passName = "Pass" + forwardPassIndex;
            } else {
                // Depth uses its lightMode as default name
                passName = lightMode;
            }

            // Increment forward-pass counter regardless of authored name
            if (CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode)) {
                forwardPassIndex++;
            }

            // 7e'. An Emissive pass with no code of its own draws the Forward pass's: an unlit effect's colour is its light.
            if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(lightMode)
                    && !passBody.contains("void vertex(") && !passBody.contains("void fragment(")) {
                CgParsedPass forward = null;
                for (CgParsedPass p : passes) {
                    if (CgParsedPass.LIGHT_MODE_FORWARD.equals(p.lightMode())) {
                        forward = p;
                        break;
                    }
                }
                if (forward == null) {
                    throw new CgShaderParseException("[" + resourcePath + "] An Emissive pass with no vertex() and "
                            + "fragment() draws the Forward pass's, and no Forward pass comes before it");
                }
                passes.add(new CgParsedPass(lightMode, passName,
                        CgRenderStateParser.parse(passBody, resourcePath, forward.renderState()),
                        forward.v2fStructBody(), forward.globalDecls(), forward.vertexBody(), forward.fragmentBody(),
                        forward.fragOutput()));
                continue;
            }

            // 7e''. A Distortion pass with no vertex() bends from where the Forward pass draws: its v2f, declarations
            // and vertex function, with this pass's own declarations and fragment after them.
            if (CgParsedPass.LIGHT_MODE_DISTORTION.equals(lightMode) && !passBody.contains("void vertex(")) {
                CgParsedPass forward = null;
                for (CgParsedPass p : passes) {
                    if (CgParsedPass.LIGHT_MODE_FORWARD.equals(p.lightMode())) {
                        forward = p;
                        break;
                    }
                }
                if (forward == null) {
                    throw new CgShaderParseException("[" + resourcePath + "] A Distortion pass with no vertex() takes the "
                            + "Forward pass's, and no Forward pass comes before it");
                }
                String fragmentBody = CgStructureParser.extractBlock(passBody, "void fragment(", "fragment", resourcePath);
                CgStructureParser.validateNoMainFunction(fragmentBody, "fragment", resourcePath);
                CgFragOutputParser.FragOutput fragOutput = CgFragOutputParser.parse(passBody, resourcePath);
                if (fragOutput.isMrt()) {
                    throw new CgShaderParseException("[" + resourcePath + "] A Distortion pass writes one vec4: "
                            + "'void fragment(in v2f i, out vec4 offset)'");
                }
                String own = CgStructureParser.parsePassGlobalDecls(passBody, "void fragment(", resourcePath);
                CgStructureParser.validatePassBody(passBody, passName, resourcePath);
                passes.add(new CgParsedPass(lightMode, passName,
                        CgRenderStateParser.parse(passBody, resourcePath, DISTORTION_STATE).withBlend(DISTORTION_BLEND),
                        forward.v2fStructBody(),
                        own.isEmpty() ? forward.globalDecls() : forward.globalDecls() + "\n" + own, forward.vertexBody(),
                        fragmentBody, fragOutput));
                continue;
            }

            // 7e. Parse render state for this pass
            CgRenderState renderState = CgRenderStateParser.parse(passBody, resourcePath,
                    CgParsedPass.LIGHT_MODE_EMISSIVE.equals(lightMode) ? EMISSIVE_STATE
                            : CgParsedPass.LIGHT_MODE_DISTORTION.equals(lightMode) ? DISTORTION_STATE : CgRenderState.DEFAULT);
            if (CgParsedPass.LIGHT_MODE_DISTORTION.equals(lightMode)) renderState = renderState.withBlend(DISTORTION_BLEND);

            // 7f. Resolve v2f body (per-pass override or shared fallback)
            String v2fBody = CgStructureParser.parsePassV2fBody(passBody, sharedV2f, resourcePath);

            // 7g. Extract global declarations for this pass
            String globalDecls = CgStructureParser.parsePassGlobalDecls(passBody, resourcePath);
            // Helpers written between the two stage functions are declarations too, emitted ahead of both.
            String between = CgStructureParser.declsBetweenStages(passBody, resourcePath);
            if (!between.isEmpty()) globalDecls = globalDecls.isEmpty() ? between : globalDecls + "\n" + between;
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

            if (CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode) && depthMode != CgParsedShader.DepthMode.NONE) {
                CgDepthState depth = renderState.getDepth() != null ? renderState.getDepth() : CgDepthState.TEST_WRITE;
                renderState = renderState.withDepth(new CgDepthState(depth.test(), depthMode == CgParsedShader.DepthMode.CLIP,
                        depth.compareFunc()));
            }

            passes.add(new CgParsedPass(lightMode, passName, renderState,
                    v2fBody, globalDecls, vertexBody, fragmentBody, fragOutput));
            String target = passTags.get("Target");
            if (target != null) {
                if (!CgParsedPass.LIGHT_MODE_FORWARD.equals(lightMode)) {
                    throw new CgShaderParseException("[" + resourcePath + "] Pass '" + passName + "' is a " + lightMode
                            + " pass: only a Forward pass draws into a \"Target\"");
                }
                postTargets.add(new CgParsedShader.PostTarget(passName, target, targetScale(passTags, passName, resourcePath),
                        targetFormat(passTags, passName, resourcePath)));
            } else if (passTags.containsKey("Scale") || passTags.containsKey("Format")) {
                throw new CgShaderParseException("[" + resourcePath + "] Pass '" + passName + "' has a \""
                        + (passTags.containsKey("Scale") ? "Scale" : "Format") + "\" and no \"Target\"");
            }
        }
        validatePostTargets(props, passes, postTargets, resourcePath);
        if ("Surface".equals(lighting)) validateSurfaceLit(passes, renderQueue, resourcePath);
        String emissionInput = topTags.get("Emission");
        if (emissionInput != null) requireSampler(props, emissionInput, "the \"Emission\" tag names", resourcePath);

        // ── Step 8: return assembled parsed shader ─────────────────────────
        return new CgParsedShader(shaderType, props, featureNames, permutations, engineBuffers, renderQueue,
                renderType, sceneColorMargin, "Unlit".equals(lighting), "Surface".equals(lighting), handedLights, handedSun,
                handedScene, writesCustomData,
                "Off".equals(fog),
                !"sRGB".equals(colorSpace), "Linear".equals(colorSpace), "Off".equals(media),
                "EmissiveOpacity".equals(output), depthMode, tube,
                Collections.unmodifiableList(passes),
                buffers.buffers(),
                buffers.structs(),
                Collections.unmodifiableList(postTargets),
                emissionInput, implicitFeatures, null);
    }

    /** A {@code MaskClip} naming a parameter names a float one the file declares. */
    private static void requireMaskClip(List<CgMaterialProperty> props, String maskClip, String resourcePath) {
        if (maskClip == null || !maskClip.startsWith("_")) return;
        for (CgMaterialProperty p : props) {
            if (p.getName().equals(maskClip) && p.getType().getComponents() == 1 && !p.getType().isSampler()) return;
        }
        throw new CgShaderParseException("[" + resourcePath + "] 'MaskClip " + maskClip + "' names no float parameter:"
                + " declare it, e.g. " + maskClip + " (\"Cut below\", float, range(0, 1)) = 0.5");
    }

    /**
     * A {@code "Surface"}-lit material is translucent, as Unreal's translucency lighting modes are, and every Forward
     * fragment names the normal it is lit by.
     */
    private static void validateSurfaceLit(List<CgParsedPass> passes, int renderQueue, String resourcePath) {
        if (renderQueue < CgRenderQueue.TRANSPARENT_THRESHOLD) {
            throw new CgShaderParseException("[" + resourcePath + "] \"Lighting\" = \"Surface\" is for a transparent queue:"
                    + " an opaque material is lit by the world's lights already");
        }
        for (CgParsedPass pass : passes) {
            if (CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode())
                    && !SURFACE_NORMAL.matcher(pass.fragmentBody()).find()) {
                throw new CgShaderParseException("[" + resourcePath + "] \"Lighting\" = \"Surface\": pass '" + pass.name()
                        + "' names no CG_SURFACE_NORMAL(n), the normal it is lit by");
            }
        }
    }

    /** {@code name} must be a declared {@code sampler2D}: what a target or the emission is bound to. */
    private static void requireSampler(List<CgMaterialProperty> props, String name, String what, String resourcePath) {
        for (CgMaterialProperty p : props) {
            if (p.getName().equals(name) && p.getType() == CgMaterialProperty.Type.SAMPLER2D) return;
        }
        throw new CgShaderParseException("[" + resourcePath + "] " + what + " \"" + name + "\", which is no sampler2D property:"
                + " declare it, e.g. " + name + " (\"...\", sampler2D) = \"black\"");
    }

    /** A target's {@code "Format"}: a colour {@code CgFormat}'s name, {@code RGBA16_FLOAT} when absent. */
    static CgFormat targetFormat(Map<String, String> tags, String pass, String resourcePath) {
        String raw = tags.get("Format");
        if (raw == null) return CgFormat.RGBA16_FLOAT;
        try {
            CgFormat f = CgFormat.valueOf(raw);
            if (!f.hasDepth() && !f.hasStencil() && !f.compressed()) return f;
        } catch (IllegalArgumentException ignored) {
            // named below
        }
        throw new CgShaderParseException("[" + resourcePath + "] Pass '" + pass + "' has \"Format\" = \"" + raw
                + "\": a colour CgFormat's name, as RGBA16_FLOAT");
    }

    /** A target's {@code "Scale"}: its share of the post target on each side, in (0, 1], 1 when absent. */
    private static float targetScale(Map<String, String> tags, String pass, String resourcePath) {
        String raw = tags.get("Scale");
        if (raw == null) return 1f;
        float scale;
        try {
            scale = Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            scale = Float.NaN;
        }
        if (!(scale > 0f && scale <= 1f)) {
            throw new CgShaderParseException("[" + resourcePath + "] Pass '" + pass + "' has \"Scale\" = \"" + raw
                    + "\": a share of the screen, above 0 and at most 1");
        }
        return scale;
    }

    /**
     * A multi-pass post material's shape: each target a declared {@code sampler2D}, named once, and every Forward pass
     * but the last drawing into one, the last into the scene.
     */
    private static void validatePostTargets(List<CgMaterialProperty> props, List<CgParsedPass> passes,
                                            List<CgParsedShader.PostTarget> targets, String resourcePath) {
        if (targets.isEmpty()) return;
        List<String> named = new ArrayList<>();
        for (CgParsedShader.PostTarget t : targets) {
            requireSampler(props, t.property(), "Pass '" + t.pass() + "' draws into", resourcePath);
            if (named.contains(t.property())) {
                throw new CgShaderParseException("[" + resourcePath + "] two passes draw into \"" + t.property() + "\"");
            }
            named.add(t.property());
        }
        List<CgParsedPass> forward = new ArrayList<>();
        for (CgParsedPass p : passes) if (CgParsedPass.LIGHT_MODE_FORWARD.equals(p.lightMode())) forward.add(p);
        for (int i = 0; i < forward.size(); i++) {
            boolean targeted = false;
            for (CgParsedShader.PostTarget t : targets) targeted |= t.pass().equals(forward.get(i).name());
            boolean last = i == forward.size() - 1;
            if (targeted == last) {
                throw new CgShaderParseException("[" + resourcePath + "] Pass '" + forward.get(i).name() + "': "
                        + (last ? "the last Forward pass draws over the scene, so it takes no \"Target\""
                        : "every Forward pass but the last draws into a \"Target\""));
            }
        }
    }

    /** The {@code "Depth"} tag: {@code "Clip"}, or {@code "Prepass"} for a transparent material; NONE when absent. */
    private static CgParsedShader.DepthMode depthMode(String tag, int renderQueue, String resourcePath) {
        if (tag == null) return CgParsedShader.DepthMode.NONE;
        if ("Clip".equals(tag)) return CgParsedShader.DepthMode.CLIP;
        if (!"Prepass".equals(tag)) {
            throw new CgShaderParseException("[" + resourcePath + "] Depth is \"Clip\" or \"Prepass\", not \"" + tag + "\"");
        }
        if (renderQueue < CgRenderQueue.TRANSPARENT_THRESHOLD) {
            throw new CgShaderParseException("[" + resourcePath + "] Depth \"Prepass\" is for a transparent material: "
                    + "an opaque one's depth is drawn first already. Give it Queue = \"Transparent\", or Depth \"Clip\"");
        }
        return CgParsedShader.DepthMode.PREPASS;
    }

    /** The {@code "SceneColorMargin"} tag: a share of the target's height, above 0; NaN when absent. */
    private static float sceneColorMargin(String tag, String resourcePath) {
        if (tag == null) return Float.NaN;
        float margin;
        try {
            margin = Float.parseFloat(tag.trim());
        } catch (NumberFormatException e) {
            throw new CgShaderParseException("[" + resourcePath + "] SceneColorMargin '" + tag + "' is not a number");
        }
        if (!(margin > 0f)) {
            throw new CgShaderParseException("[" + resourcePath + "] SceneColorMargin must be above 0, a share of the target's height");
        }
        return margin;
    }

    /** {@code authored} and after them every declared engine buffer's own properties, in declaration order. */
    private static List<CgMaterialProperty> withEngineProperties(List<CgMaterialProperty> authored, List<String> declared) {
        List<CgMaterialProperty> out = null;
        for (String token : declared) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            if (provider == null || provider.properties() == null) continue;
            if (out == null) out = new ArrayList<>(authored);
            out.addAll(CgPropertiesParser.parseEngine(provider.properties(), token));
        }
        return out == null ? authored : Collections.unmodifiableList(out);
    }

    /** A buffer whose GLSL is generated is read as its generated path, so what generates it can recompile this shader. */
    private static void noteGeneratedReads(List<String> declared) {
        for (String token : declared) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            if (provider != null && provider.generated() != null) CgIO.noteRead(provider.generatedPath());
        }
    }

    /**
     * The engine buffers a program binds (its factory's or primitive's {@code EngineBuffers}) and the ones its code reads
     * agree, both ways: a read of a buffer nothing binds fails here, naming it, rather than as an undefined GLSL symbol
     * the frame it is first drawn unbound. Textual, on the comment-stripped source: the macro family
     * ({@code CG_QUAD_}) and the raw macro ({@code QUAD_DATA}) both count.
     */
    private static void validateEngineBufferUsage(String source, List<String> declared, String resourcePath) {
        for (String token : declared) {
            if (CgEngineBufferRegistry.get(token) == null) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] EngineBuffers: unknown buffer token '" + token
                        + "' — registered tokens: " + CgEngineBufferRegistry.tokens());
            }
        }
        String code = stripGlslComments(source);
        for (String token : CgEngineBufferRegistry.tokens()) {
            if (declared.contains(token)) continue;
            String macro = CgEngineBufferRegistry.get(token).macroName();
            // "CG_QUAD_" from "QUAD_DATA" — the convenience-macro prefix that expands to the raw name.
            String family = "CG_" + (macro.endsWith("_DATA") ? macro.substring(0, macro.length() - 5) : macro) + "_";
            if (code.contains(macro) || code.contains(family)) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] uses '" + macro + "'/'" + family
                        + "*', which nothing it is drawn by binds: a factory or primitive naming '" + token
                        + "' in its EngineBuffers does");
            }
        }
    }

    /**
     * Blanks out {@code //} and block comments, preserving newlines so line numbers still line up.
     * Used only for symbol-usage scanning — parsing itself still sees the original source.
     */
    private static String stripGlslComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i++; // land on '/', the loop's i++ steps past it
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
        for (String line : CgStructureParser.v2fDeclarations(body)) {
            if (CgStructureParser.INTEGER_TYPE_PATTERN.matcher(line).find()) {
                throw new CgShaderParseException(
                        "An integer v2f field (int, uint, ivec*, uvec*) is 'flat'. Found: '" + line + "'");
            }
            Matcher m = CgStructureParser.V2F_FIELD_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new CgShaderParseException(
                        "Invalid v2f field declaration: '" + line + "'. Expected: '[flat|noperspective] <type> <name>;'");
            }
            fields.add(new V2fField(m.group(2), m.group(3), m.group(1) == null ? "" : m.group(1)));
        }
        return Collections.unmodifiableList(fields);
    }
}
