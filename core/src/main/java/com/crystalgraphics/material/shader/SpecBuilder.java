package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.shader.syntax.CgCodeScan;
import com.crystalgraphics.material.shader.syntax.CgKeywordGrammar;
import com.crystalgraphics.material.shader.syntax.CgPropertyGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderError;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.util.io.CgIO;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What every domain reader finishes the same way: its keywords and their permutations, the engine buffers its passes
 * read, its properties in binding order, its material buffers, what the world hands it, and every refusal the whole
 * material decides. A domain sets what it read, adds its passes, and calls {@link #build()}.
 */
final class SpecBuilder {

    /** The most keywords a shader declares: a material's keyword mask is an int's 31 bits. */
    static final int MAX_FEATURES = 31;

    private static final String[] HANDED_LIGHTS = {"cg_NearestLights", "CG_DRAW_POINT_LIGHT_"};
    private static final String[] HANDED_SUN = {"CG_DIRECTIONAL_SHADOW"};
    private static final String[] HANDED_SCENE = {"CG_CUSTOM_STENCIL(", "CG_SCENE_WORLD_NORMAL(", "CG_CUSTOM_DATA(",
            "CG_SHADING_MODEL_ID("};
    private static final String[] WRITES_CUSTOM_DATA = {"CG_CUSTOM_DATA_OUT", "CG_SHADING_MODEL_OUT"};

    /** Where the light grid a handed program reads lies this firing ({@code CgWorldLights.hand}). */
    private static final String HANDED_LIGHT_PROPERTIES = """
            _cg_LightGrid ("Cells across, up and deep, and 1 while the grid holds lights", vec4) = (1, 1, 1, 0)
            _cg_LightTile ("A tile's share of the target across and up", vec4) = (1, 1, 0, 0)
            _cg_LightZ ("The slices' B, O and S", vec4) = (1, 1, 1, 0)
            _cg_LightRays ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
            _cg_LightView0 ("Eye-relative world to the grid's view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
            _cg_LightView1 ("The second row", vec4) = (0, 1, 0, 0)
            _cg_LightView2 ("The third row", vec4) = (0, 0, 1, 0)
            _cg_LightEye ("The eye the lights are placed from, camera-relative", vec4) = (0, 0, 0, 0)
            """;

    /** The sun's shadow map a handed program reads this firing. */
    private static final String HANDED_SUN_PROPERTIES = """
            _cg_SunMap ("The sun's cascades, a quadrant each: our casters' depth from the light", sampler2D) = "white"
            _cg_SunRows ("Each cascade's uv, depth and range rows", vec4[16]) = (0, 0, 0, 0)
            _cg_Sun ("1 while the map holds this firing's casters", vec4) = (0, 0, 0, 0)
            _cg_SunEye ("The eye the map is placed from, camera-relative", vec4) = (0, 0, 0, 0)
            """;

    /** The scene textures a post material reads, bound by {@code CgPostMaterial} from the firing's keys. */
    private static final String HANDED_SCENE_PROPERTIES = """
            _cg_CustomStencil ("CustomStencil, CustomDepth's stencil as an unsigned integer", usampler2D) = "zero"
            _cg_WorldNormal ("Our opaque surfaces' world normals, n * 0.5 + 0.5, alpha the eye depth", sampler2D) = "black"
            _cg_CustomData ("Custom data and the shading model, packed as env/handed/scene.glsl reads them", sampler2D) = "black"
            """;

    /** The world's light, as the light list holds it ({@code world/lights/lights.glsl}). */
    private static final String LIGHT_STRUCT = "vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; "
            + "vec4 ambient; vec4 air;";

    /** The light grid's buffers a program handed the world's lights reads ({@code env/handed/lights.glsl}). */
    private static final String HANDED_BUFFERS = """
            WORLD_LIGHTS ("The world's lights", CgLight, readonly)
            WORLD_LIGHT_CELLS ("Each light-grid cell's count and where its list starts", uint, readonly)
            WORLD_LIGHT_INDICES ("The cells' light indices", uint, readonly)
            """;

    // ── What a domain sets ────────────────────────────────────────────────────────

    final CgShaderSyntax file;
    final String path;
    final CgMaterialDomain domain;
    String vertexType = "none";
    final List<String> uses = new ArrayList<>();
    /** The template's properties, written as entries, and whose they are, for an error. */
    String templateProperties = "", templateOwner = "";
    int queue = CgRenderQueue.GEOMETRY;
    String renderType = "Opaque";
    CgShaderSpec.Lighting lighting = CgShaderSpec.Lighting.LIT;
    CgShaderSpec.ColorSpace colorSpace = CgShaderSpec.ColorSpace.SRGB;
    boolean fog = true, media = true, separateTranslucency;
    CgShaderSpec.DepthMode depthMode = CgShaderSpec.DepthMode.NONE;
    @Nullable String sceneColorMarginText;
    @Nullable String maskClip, postLocation;
    @Nullable CgShaderSpec.Tube tube;
    final List<CgShaderSpec.PostTarget> postTargets = new ArrayList<>();
    final List<CgShaderPass> passes = new ArrayList<>();
    /** All the passes' GLSL as one text: what the preprocessor searches for what the world hands it. */
    final StringBuilder text = new StringBuilder();

    private final List<String> features = new ArrayList<>();
    private final List<List<String>> dimensions = new ArrayList<>();
    private final Set<String> usageFeatures = new LinkedHashSet<>();

    // ── What it finishes ──────────────────────────────────────────────────────────

    List<CgMaterialProperty> properties;
    List<String> implicitFeatures = List.of(), engineBuffers;
    CgShaderPermutations permutations;
    float sceneColorMargin = Float.NaN;
    boolean handedLights, handedSun, handedScene, writesCustomData;
    List<CgBufferDecl> buffers = List.of();
    String bufferStructs = "";

    SpecBuilder(CgShaderSyntax file, CgMaterialDomain domain) {
        this.file = file;
        this.path = file.text().path();
        this.domain = domain;
    }

    /** A combined factory set's usage keyword, declared once however many factories name it. */
    void usage(String keyword) {
        if (usageFeatures.add(keyword)) features.add(keyword);
    }

    /** Keywords: a template's ({@code dedupe}: one a usage declared already is not declared again) or the material's. */
    void keywords(List<CgKeywordGrammar.Keyword> keywords, boolean dedupe) {
        for (CgKeywordGrammar.Keyword k : keywords) {
            for (String f : k.features()) {
                if (dedupe && usageFeatures.contains(f)) continue;
                features.add(f);
                if (dedupe) usageFeatures.add(f);
            }
            if (k.dimension()) dimensions.add(k.features());
        }
    }

    /** The material's own {@code Keywords { }}. */
    void materialKeywords() {
        CgShaderSyntax.Block b = file.block("Keywords");
        if (b != null) keywords(CgKeywordGrammar.read(file.tokens(), b.open() + 1, b.close()), false);
    }

    void pass(CgShaderPass pass) {
        passes.add(pass);
    }

    CgShaderSpec build() {
        CgShaderTokens t = file.tokens();
        // Keywords
        Set<String> seen = new LinkedHashSet<>();
        for (String f : features) {
            if (!seen.add(f)) throw CgShaderError.of(path, "'" + f + "' is a keyword twice: its own and its template's");
        }
        if (features.size() > MAX_FEATURES) {
            throw CgShaderError.of(path, features.size() + " keywords, past " + MAX_FEATURES + ": a material's keyword mask"
                    + " is an int's bits");
        }
        // Engine buffers, and the variants a particle program takes undeclared
        for (String u : uses) {
            if (CgEngineBufferRegistry.get(u) == null) {
                throw CgShaderError.of(path, "'" + u + "' is no engine buffer: " + CgEngineBufferRegistry.tokens());
            }
        }
        engineBuffers = Collections.unmodifiableList(CgEngineBufferRegistry.withRequirements(new ArrayList<>(uses)));
        List<String> all = new ArrayList<>(features);
        if (engineBuffers.contains("particle")) {
            List<String> added = new ArrayList<>(List.of(CgEngineKeywords.STRIP, CgEngineKeywords.STRIP_BEAM,
                    CgEngineKeywords.STRIP_ANIM));
            boolean usage = false;
            for (CgMaterialUsage u : CgMaterialUsage.values()) {
                if (u.keyword() != null && u != CgMaterialUsage.RIBBONS && features.contains(u.keyword())) usage = true;
            }
            if (!usage) added.add(CgMaterialUsage.SPRITES.keyword());
            List<String> implicit = new ArrayList<>();
            for (String k : added) {
                if (all.contains(k)) continue;
                all.add(k);
                implicit.add(k);
            }
            implicitFeatures = Collections.unmodifiableList(implicit);
        }
        Set<String> inDimension = new LinkedHashSet<>();
        for (List<String> d : dimensions) {
            for (String k : d) if (!inDimension.add(k)) throw CgShaderError.of(path, "'" + k + "' is in two permutation dimensions");
        }
        permutations = dimensions.isEmpty() ? CgShaderPermutations.NONE : new CgShaderPermutations(dimensions);
        if (permutations.size() > CgShaderPermutations.MAX_VARIANTS) {
            throw CgShaderError.of(path, permutations.size() + " permutations, past " + CgShaderPermutations.MAX_VARIANTS
                    + ": keep the switched keywords and one-ofs to those a renderer switches");
        }
        refuseUnboundBuffers();
        for (String token : engineBuffers) {
            CgEngineBufferRegistry.Provider p = CgEngineBufferRegistry.get(token);
            if (p != null && p.generated() != null) CgIO.noteRead(p.generatedPath());
        }

        // Properties: the template's, the material's, then the engine's
        List<CgMaterialProperty> template = templateProperties.isBlank() ? List.of()
                : CgPropertyGrammar.read(templateProperties, path, false);
        CgShaderSyntax.Block own = file.block("Properties");
        List<CgMaterialProperty> mine = own == null ? List.of() : CgPropertyGrammar.read(t, own.open() + 1, own.close(), false);
        Set<String> theirs = new LinkedHashSet<>();
        for (CgMaterialProperty p : template) theirs.add(p.getName());
        for (CgMaterialProperty p : mine) {
            if (theirs.contains(p.getName())) {
                throw t.error(own.open(), "'" + p.getName() + "' is " + templateOwner + "'s: the engine binds it");
            }
        }
        List<CgMaterialProperty> props = new ArrayList<>(template);
        props.addAll(mine);
        for (String token : engineBuffers) {
            CgEngineBufferRegistry.Provider p = CgEngineBufferRegistry.get(token);
            if (p != null && p.properties() != null) props.addAll(CgPropertyGrammar.read(p.properties(), "cg_use " + token, true));
        }
        if (engineBuffers.contains(CgShaderSpec.ROWS)) {
            List<CgMaterialProperty> slots = new ArrayList<>();
            for (CgMaterialProperty p : props) {
                if (!p.isRowable()) continue;
                p.markRows();
                slots.add(CgMaterialProperty.rowSlot(p.getName()));
            }
            props.addAll(slots);
        }
        readBuffers();
        String source = text.toString();
        handedLights = new CgShaderPreprocessor().mentions(source, path, HANDED_LIGHTS);
        if (handedLights) {
            withHandedLights();
            props.addAll(CgPropertyGrammar.read(HANDED_LIGHT_PROPERTIES, "handed lights", true));
        }
        handedSun = new CgShaderPreprocessor().mentions(source, path, HANDED_SUN);
        if (handedSun) props.addAll(CgPropertyGrammar.read(HANDED_SUN_PROPERTIES, "handed sun", true));
        handedScene = new CgShaderPreprocessor().mentions(source, path, HANDED_SCENE);
        if (handedScene) props.addAll(CgPropertyGrammar.read(HANDED_SCENE_PROPERTIES, "handed scene textures", true));
        writesCustomData = new CgShaderPreprocessor().mentions(source, path, WRITES_CUSTOM_DATA);
        properties = Collections.unmodifiableList(props);

        // What the whole material decides
        if (sceneColorMarginText != null) {
            try {
                sceneColorMargin = Float.parseFloat(sceneColorMarginText.trim());
            } catch (NumberFormatException e) {
                throw CgShaderError.of(path, "SceneColorMargin '" + sceneColorMarginText + "' is not a number");
            }
            if (!(sceneColorMargin > 0f)) {
                throw CgShaderError.of(path, "SceneColorMargin must be above 0, a share of the target's height");
            }
        }
        if (depthMode == CgShaderSpec.DepthMode.PREPASS && queue < CgRenderQueue.TRANSPARENT_THRESHOLD) {
            throw CgShaderError.of(path, "DepthPrepass is for a transparent material: an opaque one's depth is drawn first"
                    + " already");
        }
        if (lighting == CgShaderSpec.Lighting.SURFACE && queue < CgRenderQueue.TRANSPARENT_THRESHOLD) {
            throw CgShaderError.of(path, "TranslucencyLighting Surface is for a transparent queue: an opaque material is"
                    + " lit by the world's lights already");
        }
        if (maskClip != null && maskClip.startsWith("_")) {
            boolean found = false;
            for (CgMaterialProperty p : properties) {
                found |= p.getName().equals(maskClip) && p.getType().getComponents() == 1 && !p.getType().isSampler();
            }
            if (!found) {
                throw CgShaderError.of(path, "'MaskClip " + maskClip + "' names no float parameter: declare it, e.g. "
                        + maskClip + " (\"Cut below\", float, range(0, 1)) = 0.5");
            }
        }
        List<CgShaderPass> made = new ArrayList<>(passes.size());
        for (CgShaderPass p : passes) {
            if (p.variant() == CgRenderPassVariant.FORWARD && depthMode != CgShaderSpec.DepthMode.NONE) {
                CgRenderState rs = p.state();
                CgDepthState depth = rs.getDepth() != null ? rs.getDepth() : CgDepthState.TEST_WRITE;
                p = p.withState(rs.withDepth(new CgDepthState(depth.test(), depthMode == CgShaderSpec.DepthMode.CLIP,
                        depth.compareFunc())));
            }
            made.add(p);
        }
        features.clear();
        features.addAll(all);
        return new CgShaderSpec(this, made, depthMode);
    }

    /** The keywords it compiles under, implicit ones included: read by the spec. */
    List<String> featuresView() {
        return Collections.unmodifiableList(new ArrayList<>(features));
    }

    /**
     * The engine buffers its passes bind and the ones their code reads agree: a read of a buffer nothing binds fails
     * here, naming it, rather than as an undefined GLSL symbol the frame it is first drawn.
     */
    private void refuseUnboundBuffers() {
        CgCodeScan code = CgCodeScan.of(text.toString());
        for (String token : CgEngineBufferRegistry.tokens()) {
            if (engineBuffers.contains(token)) continue;
            String macro = CgEngineBufferRegistry.get(token).macroName(), family = SpecReader.family(macro);
            if (code.names(macro) || code.namesPrefix(family)) {
                throw CgShaderError.of(path, "uses '" + macro + "'/'" + family + "*', which nothing it is drawn by binds:"
                        + " a factory or primitive naming '" + token + "' in its EngineBuffers does");
            }
        }
    }

    /** {@code Buffers { }}, read in a kernel's grammar, with the structs its elements name declared before it. */
    private void readBuffers() {
        CgShaderSyntax.Block block = file.block("Buffers");
        if (block == null) return;
        CgShaderTokens t = file.tokens();
        Set<String> elements = SpecReader.bufferElements(file);
        Map<String, String> bodies = new LinkedHashMap<>();
        Map<String, String> declarations = new HashMap<>();
        for (CgShaderSyntax.Item i : file.items()) {
            if (i instanceof CgShaderSyntax.Struct s && elements.contains(s.name())) {
                bodies.put(s.name(), s.close() > s.open() + 1 ? t.plain(s.open() + 1, s.close() - 1) : "");
                declarations.put(s.name(), t.plain(s.first(), s.last()));
            }
        }
        String body = block.close() > block.open() + 1 ? t.plain(block.open() + 1, block.close() - 1) : "";
        List<CgBufferDecl> read;
        try {
            read = CgComputeParser.materialBuffers(body, bodies, path);
        } catch (CgShaderError e) {
            throw e.placed(file.text(), t.start(block.first()));
        }
        if (read.size() > CgBindingModel.MATERIAL_BUFFER_COUNT) {
            throw t.error(block.first(), "'Buffers' declares " + read.size() + " buffers: a material reads at most "
                    + CgBindingModel.MATERIAL_BUFFER_COUNT);
        }
        StringBuilder structs = new StringBuilder();
        for (String name : bodies.keySet()) {
            for (CgBufferDecl b : read) {
                if (b.element().equals(name)) {
                    structs.append(declarations.get(name)).append('\n');
                    break;
                }
            }
        }
        buffers = Collections.unmodifiableList(read);
        bufferStructs = structs.toString();
    }

    /** Its buffers followed by the light grid's three, for a program handed the world's lights. */
    private void withHandedLights() {
        if (bufferStructs.contains("struct CgLight")) {
            throw CgShaderError.of(path, "declares a struct CgLight, which the world's lights it reads (cg_NearestLights,"
                    + " CG_DRAW_POINT_LIGHT_*) declare");
        }
        List<CgBufferDecl> out = new ArrayList<>(buffers);
        for (CgBufferDecl b : CgComputeParser.materialBuffers(HANDED_BUFFERS, Map.of("CgLight", LIGHT_STRUCT), path)) {
            for (CgBufferDecl mine : buffers) {
                if (mine.name().equals(b.name())) {
                    throw CgShaderError.of(path, "declares buffer '" + b.name() + "', which the world's lights it reads declare");
                }
            }
            out.add(new CgBufferDecl(b.name(), b.display(), b.element(), b.access(), b.stride(), b.lowerable(), b.scalar(),
                    out.size(), b.fields()));
        }
        if (out.size() > CgBindingModel.MATERIAL_BUFFER_COUNT) {
            throw CgShaderError.of(path, "'Buffers' declares " + buffers.size() + " buffers, and the world's lights it reads"
                    + " take 3 more: a material reads at most " + CgBindingModel.MATERIAL_BUFFER_COUNT);
        }
        buffers = Collections.unmodifiableList(out);
        bufferStructs = bufferStructs + "struct CgLight { " + LIGHT_STRUCT + " };\n";
    }
}
