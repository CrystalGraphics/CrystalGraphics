package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.compute.lower.CgLoweredEmitter;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates complete GLSL vertex and fragment source strings from a {@link CgParsedShader}
 * and a specific {@link CgParsedPass}.
 *
 * <p>This is the bridge between the structural parser ({@link CgShaderParser}) and the
 * GLSL compilation backend ({@link CgShaderFactory}).
 * It injects the {@code #version} directive, the {@code CG_VERTEX_STAGE} / {@code CG_USE_SSBO}
 * defines, the {@code cg_env.glsl} include, property uniform declarations, the v2f struct
 * and its flat-packed varying declarations, global declarations, the user-authored functions,
 * and the generated {@code void main()} wrappers.</p>
 *
 * <h3>Shadow auto-generation</h3>
 * <p>The shadow fields {@code cg_ShadowViewProjMatrix}, {@code cg_LightDirection}, and
 * {@code cg_ShadowParams} are declared in the {@code CgFrameBlock} UBO (injected via
 * {@code cg_env.glsl}). Auto-generated shadow shaders reference them as plain identifiers —
 * never as standalone {@code uniform} declarations, which would cause GLSL redeclaration
 * conflicts.</p>
 *
 * <h3>Include resolution</h3>
 * <p>The emitted {@code #include "crystalgraphics:shaders/env/cg_env.glsl"} is an absolute
 * resource-location path. {@link CgShaderPreprocessor}
 * resolves it via {@code CgIO.loadSource()} without requiring a base-path context.</p>
 *
 * <h3>Attribute binding</h3>
 * <p>No explicit {@code layout(location=N)} attributes are emitted — attribute locations
 * are bound before link by passing the resolved {@link CgVertexFormat} (from
 * {@link CgVertexFormat#forShaderType(String)}) to
 * {@code CgShaderFactory.fromSource(vert, frag, format)}.
 * The format is carried in {@link CompiledSource#vertexFormat()} for the caller's use.</p>
 *
 * <h3>v2f varying naming</h3>
 * <p>Varying names are prefixed with {@code _v2f_} (engine-reserved) to avoid collisions
 * with user-declared uniforms or globals.</p>
 */
public final class CgMaterialShaderCompiler {

    /** Immutable pair of complete GLSL source strings produced by {@link #compile}.
     * @param vertexSource
     Complete GLSL vertex shader source, starting with {@code #version} and containing
     all generated declarations, user property uniforms, v2f varying outputs, global
     declarations, and the {@code main()} wrapper.
     * @param fragmentSource
     Complete GLSL fragment shader source, starting with {@code #version} and containing
     all generated declarations, user property uniforms, v2f varying inputs, global
     declarations, and the {@code main()} wrapper.
     * @param vertexFormat
     The resolved {@link CgVertexFormat} for this compiled source, derived from
     {@code shader.shaderType()} at compile time. Used by {@code CgMaterialShader} to
     pass the correct format to {@code CgShaderFactory.fromSource()} for attribute binding.*/
    @Desugar
    public record CompiledSource(String vertexSource, String fragmentSource, CgVertexFormat vertexFormat) {
    }

    /**
     * Configuration for a single shader compilation pass.
     *
     * <p>Specifies the set of active {@code #pragma cg_feature} keyword names to inject as
     * {@code #define NAME 1} directives before user preamble lines.</p>
     *
     * <p>{@link #activeKeywords()} is always an unmodifiable copy normalised at construction.</p>
     *
     * @param activeKeywords active feature-flag keyword names; copied and made unmodifiable
     */
    @Desugar
    public record CompileConfig(Set<String> activeKeywords) {
        /** Normalises {@code activeKeywords} to an unmodifiable, insertion-ordered copy. */
        public CompileConfig {
            activeKeywords = Collections.unmodifiableSet(new LinkedHashSet<>(activeKeywords));
        }

        /** Default config: no active keywords. */
        public static final CompileConfig DEFAULT = new CompileConfig(Collections.emptySet());
    }

    private static final String ENV_INCLUDE = "crystalgraphics:shaders/env/cg_env.glsl";
    /** The world's lights a program carrying its own lighting is handed: {@code CgParsedShader.handedLights()}. */
    private static final String HANDED_LIGHTS_INCLUDE = "crystalgraphics:shaders/env/handed_lights.glsl";
    /** The sun's shadow handed to one: {@code CgParsedShader.handedSun()}. */
    private static final String HANDED_SUN_INCLUDE = "crystalgraphics:shaders/env/handed_sun.glsl";
    /** The scene textures handed to a post material: {@code CgParsedShader.handedScene()}. */
    private static final String HANDED_SCENE_INCLUDE = "crystalgraphics:shaders/env/handed_scene.glsl";

    /**
     * The engine's keyword for a multi-draw's variant of any pass: {@code cg_env.glsl} takes a draw's bases from its
     * command rather than from uniforms. Defined whether or not the shader declares it.
     */
    public static final String MULTI_DRAW = "CG_MULTI_DRAW";

    /**
     * The keyword every {@code #pragma cg_use particle} program takes without declaring it (the parser adds it): its
     * strip variant, where {@code particle_factory.glsl}'s strip factory places each particle's strip from
     * {@code CG_VERTEX_ID}, as Unreal compiles a material for its beam-and-trail vertex factory. What a beam draws a
     * ported material with.
     */
    public static final String STRIP = "FX_ON_RIBBONS";

    /**
     * The engine's keyword for the skinned vertex factory's variant of any pass of a {@code #type spatial} shader: the
     * vertex is skinned by its draw's bone palette ({@code env/buffer/bones.glsl}) before the material's code runs, which
     * reads it as {@code cg_Position} and {@code cg_Normal}, as Unreal compiles a material for its GPU skin vertex
     * factory. The world renderer asks for it for a {@code SKINNED} or {@code SKINNED_WIDE} mesh.
     */
    public static final String SKINNED = "CG_SKINNED";

    /** The skinned factory's environment, after the bone palettes' declaration. */
    private static final String BONES_ENV = "crystalgraphics:shaders/env/buffer/bones.glsl";

    /**
     * The engine's keyword for the overdraw view's variant of any pass: the pass's own vertex stage, depth test (as a
     * discard behind the scene's depth) and {@code discard}, its colour replaced by a count of 1 in red.
     */
    public static final String DEBUG_OVERDRAW = "CG_DEBUG_OVERDRAW";

    /**
     * The engine's keyword for a Forward pass that also writes its Emissive pass's glow at location 1, into a target's
     * second attachment, in the same draw. Compiled only where {@link #emissionMerge} is not {@code NONE}.
     */
    public static final String EMISSION_TARGET = "CG_EMISSION_TARGET";

    /**
     * The engine's keyword for a Forward pass drawn into an emission target alone: black at its colour's alpha, hidden
     * by the scene's depth as an Emissive pass is, so its blend covers the glows drawn behind it there.
     */
    public static final String EMISSION_COVER = "CG_EMISSION_COVER";

    /**
     * The engine's keyword for a Forward pass drawn into the HDR scene with its Emissive pass folded in: the glow, times
     * {@code CG_SCENE_GLOW}, added to the decoded colour under the pass's own blend, in one draw. Compiled only where
     * {@link #sceneFolds} holds.
     */
    public static final String SCENE_FOLD = "CG_SCENE_FOLD";

    /**
     * The engine's keyword for a {@code "Depth" = "Prepass"} material's Forward pass drawn into depth alone: its
     * fragment runs until {@code cg_Clip}, which discards below the threshold and otherwise returns there.
     */
    public static final String DEPTH_PREPASS = "CG_DEPTH_PREPASS";

    /**
     * The engine's keyword for a Forward pass drawn into the world renderer's volume target: what its fragment names
     * with {@code CG_VOLUME_DEPTH(eye)} written at location 1, the depth its temporal resolve reprojects it by.
     */
    public static final String VOLUME_TARGET = "CG_VOLUME_TARGET";

    /**
     * The engine's keyword for a Forward pass drawn into the world renderer's opaque pass with a normal target beside
     * it, Unreal's G-buffer normal: the vertex normal, through the normal matrix, written at location 1 as
     * {@code n * 0.5 + 0.5} with the fragment's eye depth as alpha, flipped on a back face; (0, 0, 0, -eye depth) for a
     * mesh with no normals. What the world lights read in place of a normal rebuilt from depth, and which pixels are
     * ours: those whose depth still matches.
     */
    public static final String NORMAL_TARGET = "CG_NORMAL_TARGET";

    /**
     * A single-output Forward pass writing, in place of its colour, what its fragment names with
     * {@code CG_CUSTOM_DATA_OUT} and {@code CG_SHADING_MODEL_OUT}, packed into two exact floats (Unreal's GBufferD and
     * GBufferB's shading model): what {@code CgWorldRenderer} draws custom data with, read back by
     * {@code handed_scene.glsl}.
     */
    public static final String CUSTOM_DATA_TARGET = "CG_CUSTOM_DATA_TARGET";

    /** A fragment naming the depth it marched to ({@code CG_VOLUME_DEPTH(eye)}). */
    private static final Pattern NAMES_VOLUME_DEPTH = Pattern.compile("\\bCG_VOLUME_DEPTH\\b");

    /** Code applying {@code CG_EMISSION} itself; not {@code CG_EMISSION_TARGET}. */
    private static final Pattern READS_EMISSION = Pattern.compile("\\bCG_EMISSION\\b");

    /** A fragment naming its glow ({@code CG_GLOW(rgb)}), which then replaces the colour in every glow it makes. */
    private static final Pattern NAMES_GLOW = Pattern.compile("\\bCG_GLOW\\b");
    /** A fragment naming its emissive ({@code CG_SURFACE_EMISSIVE(rgb)}), added to its colour after lighting. */
    private static final Pattern SURFACE_EMISSIVE = Pattern.compile("\\bCG_SURFACE_EMISSIVE\\b");

    /** Whether, and how, a shader's Emissive pass folds into its Forward draw as a second output. */
    public enum EmissionMerge {
        /** It stays a draw of its own. */
        NONE,
        /** Its blend is the Forward pass's: the glow is written with the colour's alpha, as that pass writes it. */
        SAME_BLEND,
        /**
         * It adds (ONE ONE) under a premultiplied Forward pass (ONE, ONE_MINUS_SRC_ALPHA): written with an alpha of 0,
         * ONE_MINUS_SRC_ALPHA is 1 and the shared blend adds.
         */
        ADDED,
        /**
         * No glow of its own merges: black at the colour's alpha, so the shared blend covers the glow beneath as it
         * covers the colour. What a Forward pass compiled with {@link #EMISSION_TARGET} writes when
         * {@link #emissionMerge} answers {@code NONE}; never answered by it.
         */
        COVER
    }

    /**
     * How {@code shader}'s Emissive pass merges into its first Forward pass. Only a codeless Emissive pass merges (the
     * Forward pass's own code), never one the shared code tells apart by {@code CG_EMISSIVE_PASS}; and only where one
     * blend serves both outputs, since below GL 4.0 every draw buffer shares the draw's blend.
     */
    public static EmissionMerge emissionMerge(CgParsedShader shader) {
        CgParsedPass emissive = shader.getPassByLightMode(CgParsedPass.LIGHT_MODE_EMISSIVE);
        CgParsedPass forward = shader.getPassByLightMode(CgParsedPass.LIGHT_MODE_FORWARD);
        if (emissive == null || forward == null || forward.fragOutput().isMrt()) return EmissionMerge.NONE;
        // A codeless pass holds the Forward pass's own strings (CgShaderParser step 7e').
        if (emissive.fragmentBody() != forward.fragmentBody() || emissive.vertexBody() != forward.vertexBody()) {
            return EmissionMerge.NONE;
        }
        if (forward.fragmentBody().contains("CG_EMISSIVE_PASS") || forward.vertexBody().contains("CG_EMISSIVE_PASS")
                || forward.globalDecls().contains("CG_EMISSIVE_PASS")) return EmissionMerge.NONE;
        CgRenderState f = forward.renderState(), e = emissive.renderState();
        // A cull the Emissive pass leaves unset is whatever its pass leaves; the merged draw takes the Forward pass's.
        boolean sameCull = e.getCull() == null || e.getCull().equals(f.getCull());
        if (f.writesNothing() || !sameCull || !sameTest(f.getDepth(), e.getDepth())) return EmissionMerge.NONE;
        CgBlendState fb = f.getBlend(), eb = e.getBlend();
        if (fb == null || !fb.enabled()) return EmissionMerge.NONE;   // an opaque Forward pass would overwrite the glow beneath
        if (fb.equals(eb)) return EmissionMerge.SAME_BLEND;
        boolean premultiplied = fb.srcRgb() == CgGL.GL_ONE && fb.dstRgb() == CgGL.GL_ONE_MINUS_SRC_ALPHA
                && fb.blendEquationRgb() == CgGL.GL_FUNC_ADD;
        boolean adds = eb != null && eb.enabled() && eb.srcRgb() == CgGL.GL_ONE && eb.dstRgb() == CgGL.GL_ONE
                && eb.blendEquationRgb() == CgGL.GL_FUNC_ADD;
        return premultiplied && adds ? EmissionMerge.ADDED : EmissionMerge.NONE;
    }

    /**
     * Whether {@code shader}'s Emissive pass folds into its Forward draw under the HDR scene ({@link #SCENE_FOLD}): it
     * merges, and the Forward blend adds its source scaled by ONE or SRC_ALPHA, so a glow added to the colour lands as
     * the target-plus-emission picture does.
     */
    public static boolean sceneFolds(CgParsedShader shader) {
        if (emissionMerge(shader) == EmissionMerge.NONE) return false;
        CgBlendState blend = shader.getPassByLightMode(CgParsedPass.LIGHT_MODE_FORWARD).renderState().getBlend();
        return blend.blendEquationRgb() == CgGL.GL_FUNC_ADD
                && (blend.srcRgb() == CgGL.GL_ONE || blend.srcRgb() == CgGL.GL_SRC_ALPHA);
    }

    /**
     * Whether the Forward pass's hardware test hides what the Emissive pass's scene-depth discard hides: both test, or
     * neither. An Emissive pass discards unless it says {@code DepthTest ALWAYS}; a Forward pass with no depth state
     * takes the transparent pass's, which tests.
     */
    private static boolean sameTest(CgDepthState forward, CgDepthState emissive) {
        boolean forwardTests = forward == null || forward.test() && forward.compareFunc() != CgGL.GL_ALWAYS;
        boolean emissiveTests = emissive == null || !(emissive.test() && emissive.compareFunc() == CgGL.GL_ALWAYS);
        return forwardTests == emissiveTests;
    }

    private CgMaterialShaderCompiler() {
        throw new AssertionError("CgMaterialShaderCompiler is not instantiable");
    }

    // ── Canonical 5-arg compile entry point ──────────────────────────────────

    /**
     * Compiles a single {@link CgParsedPass} (from the given {@link CgParsedShader}) into
     * GLSL vertex + fragment source strings.
     *
     * <p>This is the canonical compile entry point. All other {@code compile} overloads
     * delegate here. Material-level data (properties, featureNames) is read from
     * {@code shader}; per-pass data (v2fStructBody, globalDecls, vertexBody,
     * fragmentBody, fragOutput) is read from {@code pass}.</p>
     *
     * @param shader          material-level parse result (properties, featureNames)
     * @param pass            per-pass parse result (v2f, vertex/fragment bodies, render state)
     * @param attachedBuffers user-defined SSBO/TBO and UBO buffers to inject (may be empty)
     * @param matPropsEntry   engine-managed material properties UBO entry, or {@code null}
     * @param config          active-keyword config; use {@link CompileConfig#DEFAULT}
     *                        for the no-keyword path
     * @return a {@link CompiledSource} holding the complete vertex and fragment sources
     * @throws IllegalArgumentException if {@code ShaderBufferPath} is {@code NONE}
     * @throws CgPreprocessorException  if a TBO-path buffer contains incompatible field types
     */
    public static CompiledSource compile(CgParsedShader shader,
                                         CgParsedPass pass,
                                         List<CgAttachedBuffer> attachedBuffers,
                                         CgUniformBuffer matPropsEntry,
                                         CompileConfig config) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE)
            throw new IllegalArgumentException("Cannot compile material shader: ShaderBufferPath is NONE (GL 3.3+ required)");

        boolean useSsbo = (path == CgCapabilities.ShaderBufferPath.SSBO_GL43
                || path == CgCapabilities.ShaderBufferPath.SSBO_ARB);

        Set<String> requiredExtensions = new LinkedHashSet<>();
        for (CgAttachedBuffer ab : attachedBuffers) {
            for (int i = 0; i < ab.getBuffer().getFormat().getFieldCount(); i++) {
                String ext = ab.getBuffer().getFormat().getField(i).getType().requiredExtension();
                if (ext != null) requiredExtensions.add(ext);
            }
        }
        if (!requiredExtensions.isEmpty()) {
            CgCapabilities caps = CgCapabilities.detect();
            for (String ext : requiredExtensions) {
                if ("GL_ARB_gpu_shader_int64".equals(ext) && !caps.isGpuShaderInt64()) {
                    throw new CgPreprocessorException(
                            "Buffer format requires extension '" + ext + "' (type INT64 or UINT64), "
                            + "but the current GPU/driver does not support it.",
                            "<material>", 0);
                }
            }
        }

        List<CgMaterialProperty> properties = shader.properties();
        List<CgShaderParser.V2fField> v2fFields = CgShaderParser.parseV2fFields(pass);

        // Resolve the vertex format from the #type registry. Validation already ran in
        // CgStructureParser.parseShaderType(), so null here is a defense-in-depth guard.
        CgVertexFormat vertexFormat = CgVertexFormat.forShaderType(shader.shaderType());
        if (vertexFormat == null) {
            throw new IllegalArgumentException(
                    "Cannot compile: unresolvable #type '" + shader.shaderType()
                    + "'. Known types: " + CgVertexFormat.registeredShaderTypes());
        }
        // A sprite variant takes the sprite factory, which places quads from CG_VERTEX_ID: it draws CgMesh.quads, and a
        // vertex input it declared would be one no mesh provides (a Vulkan pipeline refuses it).
        if (spriteVariant(shader, config) || stripVariant(shader, config)) vertexFormat = CgVertexFormat.NONE;
        // The skinned factory reads the skinned format's attributes; its indices are a uvec4 at either width.
        if (skinnedVariant(config)) {
            if (!vertexFormat.equals(CgVertexFormat.SPATIAL)) {
                throw new IllegalArgumentException("[" + path + "] " + SKINNED + " skins a #type spatial shader, not #type "
                        + shader.shaderType());
            }
            vertexFormat = CgVertexFormat.SKINNED;
        }

        String vertexSource = buildVertexSource(shader, pass, properties, v2fFields, useSsbo,
                path, attachedBuffers, requiredExtensions, matPropsEntry, config, vertexFormat);
        String fragmentSource = buildFragmentSource(shader, pass, properties, v2fFields, useSsbo,
                path, attachedBuffers, requiredExtensions, matPropsEntry, config);

        return new CompiledSource(vertexSource, fragmentSource, vertexFormat);
    }

    /** Whether this variant places its vertices with the strip factory, reading no vertex data. */
    private static boolean stripVariant(CgParsedShader shader, CompileConfig config) {
        return config.activeKeywords().contains(STRIP) && shader.engineBuffers().contains("particle");
    }

    /** Whether this variant skins its vertices: {@link #SKINNED}. */
    private static boolean skinnedVariant(CompileConfig config) {
        return config.activeKeywords().contains(SKINNED);
    }

    /**
     * The skinned factory, after the engine buffers: the bone palettes' declaration and environment, then
     * {@code cg_Position} and {@code cg_Normal} redefined as the vertex skinned by its bones. The function reading the
     * attributes comes before the macros, so it reads the mesh's; {@code main} calls it first. Unreal's
     * {@code SkinPosition} and {@code SkinTangents}: position by the blended 3x4, normal by its rotation, renormalised.
     */
    private static void appendSkinnedFactory(StringBuilder sb, CgCapabilities.ShaderBufferPath path) {
        appendAttachedBuffers(sb, List.of(CgAttachedBuffer.of(CgBonePalettes.buffer(), CgBonePalettes.MACRO_NAME)), path);
        sb.append("#include \"").append(BONES_ENV).append("\"\n");
        sb.append("vec3 _cg_skinPosition;\nvec3 _cg_skinNormal;\n")
          .append("void _cg_skin() {\n")
          .append("  vec4 r0, r1, r2;\n")
          .append("  cg_BoneBlend(cg_BoneIndices, cg_BoneWeights, r0, r1, r2);\n")
          .append("  vec4 p = vec4(cg_Position, 1.0), n = vec4(cg_Normal, 0.0);\n")
          .append("  _cg_skinPosition = vec3(dot(r0, p), dot(r1, p), dot(r2, p));\n")
          .append("  vec3 skinned = vec3(dot(r0, n), dot(r1, n), dot(r2, n));\n")
          .append("  _cg_skinNormal = dot(skinned, skinned) > 0.0 ? normalize(skinned) : cg_Normal;\n")
          .append("}\n")
          .append("#define cg_Position _cg_skinPosition\n#define cg_Normal _cg_skinNormal\n");
    }

    /** Whether this variant places its vertices with the particle sprite factory, reading no vertex data. */
    private static boolean spriteVariant(CgParsedShader shader, CompileConfig config) {
        return config.activeKeywords().contains("FX_ON_SPRITES") && shader.featureNames().contains("FX_ON_SPRITES")
                && shader.engineBuffers().contains("particle");
    }

    // ── Convenience overloads (delegate to canonical 5-arg via passes().get(0)) ──

    /**
     * Compiles a {@link CgParsedShader} using its first pass and no active keywords.
     * Convenience overload — delegates to {@link #compile(CgParsedShader, CgParsedPass, List, CgUniformBuffer, CompileConfig)}.
     */
    public static CompiledSource compile(CgParsedShader parsed, List<CgAttachedBuffer> attachedBuffers) {
        return compile(parsed, parsed.passes().get(0), attachedBuffers, null, CompileConfig.DEFAULT);
    }

    /**
     * Compiles a {@link CgParsedShader} using its first pass, with a material properties UBO.
     * Convenience overload — delegates to {@link #compile(CgParsedShader, CgParsedPass, List, CgUniformBuffer, CompileConfig)}.
     */
    public static CompiledSource compile(CgParsedShader parsed,
                                         CgCapabilities.ShaderBufferPath shaderBufferPath,
                                         List<CgAttachedBuffer> attachedBuffers,
                                         CgUniformBuffer matPropsEntry) {
        return compile(parsed, parsed.passes().get(0), attachedBuffers, matPropsEntry, CompileConfig.DEFAULT);
    }

    /**
     * Compiles a {@link CgParsedShader} using its first pass, with a material properties UBO
     * and an active keyword config. Convenience overload — delegates to the canonical 5-arg.
     */
    public static CompiledSource compile(CgParsedShader parsed,
                                         List<CgAttachedBuffer> attachedBuffers,
                                         CgUniformBuffer matPropsEntry,
                                         CompileConfig config) {
        return compile(parsed, parsed.passes().get(0), attachedBuffers, matPropsEntry, config);
    }

    /**
     * Auto-generates a depth-prepass program from an existing Forward pass.
     *
     * <p>Three cases driven by {@link #isSimpleVertex(CgParsedPass)} and presence of
     * {@code discard} in the fragment body:</p>
     * <ol>
     *   <li><strong>Simple vertex, solid</strong>: minimal position-only transform using the
     *       camera MVP matrix from the frame UBO — identical to the shared engine depth shader.</li>
     *   <li><strong>Complex vertex, solid</strong>: forward vertex body copied verbatim, preserving
     *       vertex animation so depth matches the forward pass exactly.</li>
     *   <li><strong>Alpha-clip</strong>: forward vertex body + forward fragment body, so the
     *       {@code discard} in the fragment fires and only surviving fragments write depth.</li>
     * </ol>
     *
     * <p>Uses camera view+projection (not light space — this is a depth prepass, not a shadow map).
     * No depth bias is applied.</p>
     *
     * <p>The forward pass's {@code v2fStructBody} and {@code globalDecls} are reused in the
     * synthetic depth pass for interface-block parity between vertex and fragment stages.</p>
     *
     * @param shader          material-level parse result (properties, featureNames)
     * @param forwardPass     the Forward pass to derive the depth variant from; must not be null
     * @param attachedBuffers user-defined SSBO/TBO and UBO buffers to inject
     * @param matPropsUbo     engine-managed material properties UBO, or {@code null}
     * @param config          active-keyword config (typically {@link CompileConfig#DEFAULT})
     * @return a {@link CompiledSource} holding the complete depth vertex and fragment sources
     * @throws IllegalArgumentException if {@code ShaderBufferPath} is {@code NONE}
     */
    public static CompiledSource compileDepthAutoGen(CgParsedShader shader,
                                                      CgParsedPass forwardPass,
                                                      List<CgAttachedBuffer> attachedBuffers,
                                                      CgUniformBuffer matPropsUbo,
                                                      CompileConfig config) {
        final String depthVertexBody;
        // The shortcut transforms cg_Position: a type with no vertex data places its vertices in its own body.
        CgVertexFormat format = CgVertexFormat.forShaderType(shader.shaderType());
        boolean positioned = format != null && format.indexOf("cg_Position") >= 0;
        if (positioned && isSimpleVertex(forwardPass) && !readsBuffers(shader, forwardPass)
                && shader.depthMode() != CgParsedShader.DepthMode.PREPASS) {
            depthVertexBody =
                    "    // Auto-generated depth prepass -- minimal position transform\n"
                    + "    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n";
        } else {
            depthVertexBody =
                    "    // Auto-generated depth prepass -- preserves vertex animation\n"
                    + forwardPass.vertexBody() + "\n";
        }

        final String depthFragmentBody;
        if (clips(forwardPass)) {
            depthFragmentBody = forwardPass.fragmentBody();
        } else {
            depthFragmentBody = "    // Depth-only prepass -- rasterizer writes depth automatically.\n";
        }

        CgFragOutputParser.FragOutput depthFragOutput = CgFragOutputParser.FragOutput.singleOutput("fragColor");
        CgParsedPass depthPass = new CgParsedPass(
                CgParsedPass.LIGHT_MODE_DEPTH,
                CgParsedPass.LIGHT_MODE_DEPTH,
                CgRenderState.DEFAULT,
                forwardPass.v2fStructBody(),
                forwardPass.globalDecls(),
                depthVertexBody,
                depthFragmentBody,
                depthFragOutput);

        return compile(shader, depthPass, attachedBuffers, matPropsUbo, config);
    }

    /**
     * Returns {@code true} when this pass is "simple" enough to share a single auto-generated
     * shadow material with other simple-geometry drawables (analogue of Godot's
     * {@code uses_shared_shadow_material}).
     *
     * <p>A pass is considered simple when ALL of the following hold:</p>
     * <ol>
     *   <li>The vertex body does not reference user-defined shader properties
     *       (names starting with {@code _} followed by an uppercase letter — e.g. {@code _MainTex}).
     *       This heuristic detects vertex animation or UV-offset vertex transforms.</li>
     *   <li>The vertex body does not reference engine time or per-object custom data
     *       ({@code cg_Time}, {@code CG_OBJECT_CUSTOM0}–{@code CG_OBJECT_CUSTOM3}).
     *       Time-driven displacement is indistinguishable from property-driven animation
     *       and must never fall through to the minimal position-only transform.</li>
     *   <li>The fragment body contains no {@code discard} keyword (no alpha-clip).</li>
     *   <li>Face culling is not {@link CgCullState#NONE} (backface culling is active —
     *       required so the shadow pass covers both faces correctly).</li>
     * </ol>
     *
     * <p>No GL calls — pure string inspection.</p>
     *
     * @param pass the pass to evaluate; must not be null
     * @return {@code true} if a minimal position-only shadow vertex can be used
     */
    /** Whether its fragment cuts pixels away: a {@code discard} or a {@code cg_Clip}. */
    /** Whether the pass's fragment body or declarations name {@code symbol}. */
    private static boolean names(CgParsedPass pass, String symbol) {
        return pass.fragmentBody().contains(symbol) || pass.globalDecls().contains(symbol);
    }

    private static boolean clips(CgParsedPass pass) {
        return pass.fragmentBody().contains("discard") || pass.fragmentBody().contains("cg_Clip");
    }

    public static boolean isSimpleVertex(CgParsedPass pass) {
        String vertexBody = pass.vertexBody();
        // Check 1: vertex body has no user property references (_UpperCaseName pattern)
        boolean noUserProps = !vertexBody.matches("(?s).*\\b_[A-Z]\\w+.*");
        // Check 2: vertex body has no engine time or per-object custom slot references
        boolean noEngineAnimation = !vertexBody.contains("cg_Time")
                && !vertexBody.contains("CG_OBJECT_CUSTOM0")
                && !vertexBody.contains("CG_OBJECT_CUSTOM1")
                && !vertexBody.contains("CG_OBJECT_CUSTOM2")
                && !vertexBody.contains("CG_OBJECT_CUSTOM3");
        // Check 3: fragment body has no discard (no alpha-clip)
        boolean noDiscard = !clips(pass);
        // Check 4: culling is not OFF (NONE means double-sided — shadow pass must cull correctly)
        CgCullState cull = pass.renderState().getCull();
        boolean cullingIsOff = (cull != null && cull == CgCullState.NONE);
        return noUserProps && noEngineAnimation && noDiscard && !cullingIsOff;
    }

    /** Whether the pass's vertex body names a buffer of the material's: it places vertices from a kernel's output. */
    private static boolean readsBuffers(CgParsedShader shader, CgParsedPass pass) {
        for (CgBufferDecl b : shader.buffers()) {
            if (pass.vertexBody().matches("(?s).*\\b" + b.name() + "\\b.*")) return true;
        }
        return false;
    }

    // ── Vertex shader builder ─────────────────────────────────────────────────

    private static String buildVertexSource(CgParsedShader shader,
                                             CgParsedPass pass,
                                             List<CgMaterialProperty> properties,
                                             List<CgShaderParser.V2fField> v2fFields,
                                             boolean useSsbo,
                                             CgCapabilities.ShaderBufferPath shaderBufferPath,
                                             List<CgAttachedBuffer> attachedBuffers,
                                             Set<String> requiredExtensions,
                                             CgUniformBuffer matPropsEntry,
                                             CompileConfig config,
                                             CgVertexFormat vertexFormat) {
        StringBuilder sb = new StringBuilder(1024);

        // Step 1: #version
        int version = glslVersion(useSsbo, config);
        sb.append("#version ").append(version).append(" core\n");

        // Keyword #define injection — in featureNames declaration order, before user #-lines
        appendKeywordDefines(sb, shader.featureNames(), config.activeKeywords());
        // Before any code, which a hoisted #include may already be: cg_env.glsl reads a multi-draw's bases from it.
        if (version < 460 && config.activeKeywords().contains(MULTI_DRAW)) {
            sb.append("#extension GL_ARB_shader_draw_parameters : require\n");
        }

        // Step 2: CG_VERTEX_STAGE define.
        //
        // MUST precede the user directive block below. partitionGlobalDecls hoists every '#' line
        // — including material-scope #includes — into BOTH stages, so an included lib is the only
        // place a stage guard can live. Emitting this define after those #includes made every such
        // guard evaluate identically in both stages, i.e. do nothing: that is how sdf.glsl's
        // fwidth() reached the vertex shader, which NVIDIA accepted and AMD correctly rejected.
        sb.append("#define CG_VERTEX_STAGE 1\n");
        appendPassDefine(sb, shader, pass);

        String[] gd = partitionGlobalDecls(pass.globalDecls());
        String directiveLines = gd[0];
        String codeLines = gd[1];
        if (!directiveLines.isEmpty()) sb.append(directiveLines).append('\n');
        appendAutoRequiredExtensions(sb, requiredExtensions, directiveLines);

        if (useSsbo) {
            sb.append("#define CG_USE_SSBO 1\n");
        }

        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");

        // Inject vertex attribute declarations for this format immediately after cg_env.glsl.
        // Known limitation: compileDepthAutoGen generates vertex bodies
        // that reference cg_Position by literal name — this only works correctly for formats
        // where the position attribute is named cg_Position (i.e. SPATIAL and SPATIAL-derived
        // formats). Custom formats with a differently-named position attribute must author an
        // explicit Depth pass to bypass auto-generation.
        sb.append(CgGlslEmitter.emitVertexInputs(vertexFormat));
        // A strip variant draws no mesh: its #type's attributes stand at their defaults for a port naming one directly.
        if (stripVariant(shader, config)) {
            CgVertexFormat declared = CgVertexFormat.forShaderType(shader.shaderType());
            for (int i = 0; declared != null && i < declared.getAttributeCount(); i++) {
                CgVertexAttribute a = declared.getAttribute(i);
                sb.append("#define ").append(a.getName()).append(' ').append(a.getGlslType())
                        .append(a.getName().equals("cg_Color") ? "(1.0)\n" : "(0.0)\n");
            }
        }

        // Property uniform declarations (sampler types only; non-sampler go into CgMaterialBlock UBO)
        appendPropertyUniforms(sb, properties);

        // Material properties UBO (CgMaterialBlock) — emitted before user-attached buffers
        if (matPropsEntry != null) {
            sb.append(CgGlslEmitter.emitUbo(matPropsEntry)).append('\n');
        }

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        appendEngineBufferEnv(sb, shader.engineBuffers());
        if (skinnedVariant(config)) appendSkinnedFactory(sb, shaderBufferPath);
        appendPropertyAccessors(sb, properties, shader.engineBuffers().contains(CgShaderParser.ROWS));

        appendMaterialBuffers(sb, shader, useSsbo);
        appendHanded(sb, shader);

        // v2f struct
        appendV2fStruct(sb, pass.v2fStructBody());

        // Compiler-wired flat int varying for instance ID bridging (vertex → fragment)
        sb.append("flat out int cg_InstanceId;\n");

        // v2f varying outputs: the normal target's normal rides in the block, matched with it on every device
        boolean normals = writesNormals(pass, config);
        appendV2fVaryingOutputs(sb, v2fFields, normals);

        // Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // Its prepass and its colour are two programs over one vertex body: invariance lands them on the same depth.
        // A custom-data writer's redraw is tested against its colour draw's depth, so it needs the same.
        if (shader.depthMode() == CgParsedShader.DepthMode.PREPASS || shader.writesCustomData()) {
            sb.append("invariant gl_Position;\n");
        }

        // User vertex function
        sb.append("// User vertex function\n");
        sb.append("void vertex(out v2f o) {\n")
          .append(pass.vertexBody()).append("\n")
          .append("}\n");

        // Generated main()
        appendVertexMain(sb, v2fFields);
        if (skinnedVariant(config)) {
            int open = sb.indexOf("{", sb.lastIndexOf("void main()")) + 1;
            sb.insert(open, "\n  _cg_skin();");
        }
        if (normals) {
            // At the end of the generated main itself: a backend may rewrite main, so no macro renames it.
            String normal = vertexFormat.indexOf("cg_Normal") >= 0 ? "CG_NORMAL_MATRIX * cg_Normal" : "vec3(0.0)";
            sb.insert(sb.lastIndexOf("}"), "  _cg_v2f._cg_normalWs = " + normal + ";\n");
        }

        return sb.toString();
    }

    /** Whether a pass compiled under {@code config} writes the normal target ({@link #NORMAL_TARGET}). */
    private static boolean writesNormals(CgParsedPass pass, CompileConfig config) {
        return config.activeKeywords().contains(NORMAL_TARGET) && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode())
                && !pass.fragOutput().isMrt();
    }

    // ── Fragment shader builder ───────────────────────────────────────────────

    private static String buildFragmentSource(CgParsedShader shader,
                                               CgParsedPass pass,
                                               List<CgMaterialProperty> properties,
                                               List<CgShaderParser.V2fField> v2fFields,
                                               boolean useSsbo,
                                               CgCapabilities.ShaderBufferPath shaderBufferPath,
                                               List<CgAttachedBuffer> attachedBuffers,
                                               Set<String> requiredExtensions,
                                               CgUniformBuffer matPropsEntry,
                                               CompileConfig config) {
        StringBuilder sb = new StringBuilder(1024);

        // Step 1: #version
        sb.append("#version ").append(glslVersion(useSsbo, config)).append(" core\n");

        // Keyword #define injection — in featureNames declaration order, before user #-lines
        appendKeywordDefines(sb, shader.featureNames(), config.activeKeywords());

        // Stage define — the symmetric counterpart of CG_VERTEX_STAGE, and like it, emitted before
        // the user directive block so a guard inside an included lib can actually see it.
        sb.append("#define CG_FRAGMENT_STAGE 1\n");
        appendPassDefine(sb, shader, pass);
        appendClip(sb, shader, pass, config);
        sb.append("#define CG_FOG_MODE ").append(fogMode(pass)).append('\n');

        String[] gd = partitionGlobalDecls(pass.globalDecls());
        String directiveLines = gd[0];
        String codeLines = gd[1];
        if (!directiveLines.isEmpty()) sb.append(directiveLines).append('\n');
        appendAutoRequiredExtensions(sb, requiredExtensions, directiveLines);

        // CG_USE_SSBO (SSBO path only; fragment never defines CG_VERTEX_STAGE)
        if (useSsbo) {
            sb.append("#define CG_USE_SSBO 1\n");
        }

        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");

        // Property uniform declarations (sampler types only; non-sampler go into CgMaterialBlock UBO)
        appendPropertyUniforms(sb, properties);

        // Material properties UBO (CgMaterialBlock) — emitted before user-attached buffers
        if (matPropsEntry != null) {
            sb.append(CgGlslEmitter.emitUbo(matPropsEntry)).append('\n');
        }

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        appendEngineBufferEnv(sb, shader.engineBuffers());
        appendPropertyAccessors(sb, properties, shader.engineBuffers().contains(CgShaderParser.ROWS));

        appendMaterialBuffers(sb, shader, useSsbo);
        appendHanded(sb, shader);

        // v2f struct
        appendV2fStruct(sb, pass.v2fStructBody());

        // v2f varying inputs
        boolean normals = writesNormals(pass, config);
        appendV2fVaryingInputs(sb, v2fFields, normals);

        // Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // Fragment output declarations (single-output or MRT)
        EmissionMerge merge = EmissionMerge.NONE;
        if (config.activeKeywords().contains(EMISSION_TARGET) && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode())) {
            merge = emissionMerge(shader);
            if (merge == EmissionMerge.NONE && !pass.fragOutput().isMrt()) merge = EmissionMerge.COVER;
        }
        boolean volume = config.activeKeywords().contains(VOLUME_TARGET) && merge == EmissionMerge.NONE
                && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode()) && !pass.fragOutput().isMrt();
        appendFragmentOutputDeclarations(sb, pass, merge);
        if (volume) sb.append("layout(location = 1) out vec4 _cg_volumeDepth;\n");
        normals &= !volume && merge == EmissionMerge.NONE;
        if (normals) sb.append("layout(location = 1) out vec4 _cg_normalOut;\n");

        // User fragment function
        sb.append("// User fragment function\n");
        appendVolumeDepth(sb, pass, volume);
        appendFragmentUserFunction(sb, pass);

        // Generated main()
        boolean cover = config.activeKeywords().contains(EMISSION_COVER) && merge == EmissionMerge.NONE
                && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode()) && !pass.fragOutput().isMrt();
        boolean fold = config.activeKeywords().contains(SCENE_FOLD) && merge == EmissionMerge.NONE && !cover
                && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode()) && sceneFolds(shader);
        appendFragmentMain(sb, v2fFields, pass, shader, config.activeKeywords().contains(DEBUG_OVERDRAW), merge, cover, fold);
        if (volume) sb.append("#undef main\nvoid main() {\n  _cg_volumeEye = 0.0;\n  _cg_main();\n  _cg_volumeDepth = vec4(_cg_volumeEye, 0.0, 0.0, _cg_volumeEye > 0.0 ? 1.0 : 0.0);\n}\n");
        if (normals) {
            // First in the generated main, before any return in it: a pixel discarded later writes nothing anyway.
            int open = sb.indexOf("{", sb.lastIndexOf("void main()")) + 1;
            sb.insert(open, "\n  vec3 _cg_n = _cg_v2f._cg_normalWs;\n"
                    + "  float _cg_eye = cg_LinearEyeDepth(gl_FragCoord.z);\n"
                    + "  _cg_normalOut = dot(_cg_n, _cg_n) > 1.0e-12 ? vec4(normalize(gl_FrontFacing ? _cg_n : -_cg_n) * 0.5 + 0.5, _cg_eye)"
                    + " : vec4(0.0, 0.0, 0.0, -_cg_eye);\n");
        }
        if (config.activeKeywords().contains(CUSTOM_DATA_TARGET) && !normals && !volume && merge == EmissionMerge.NONE
                && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode()) && !pass.fragOutput().isMrt()) {
            // Last in the generated main, over the colour: x the ID byte and the model and type above it, y the base
            // colour's three bytes, each an integer a float holds exactly.
            int close = sb.lastIndexOf("}");
            sb.insert(close, "  _cg_fragColor = vec4(round(clamp(_cg_customData.a, 0.0, 1.0) * 255.0)"
                    + " + 256.0 * float(min(_cg_shadingModel.x, 31u) + 32u * min(_cg_shadingModel.y, 3u)),"
                    + " dot(round(clamp(_cg_customData.rgb, 0.0, 1.0) * 255.0), vec3(65536.0, 256.0, 1.0)), 0.0, 1.0);\n");
        }

        return sb.toString();
    }

    /**
     * {@code CG_VOLUME_DEPTH(eye)} for a fragment naming it: under {@link #VOLUME_TARGET} it keeps the eye distance the
     * generated {@code main} writes at location 1, elsewhere it does nothing. The volume variant's own {@code main}
     * wraps the generated one, renamed {@code _cg_main}.
     */
    private static void appendVolumeDepth(StringBuilder sb, CgParsedPass pass, boolean volume) {
        if (volume) {
            sb.append("float _cg_volumeEye;\n#define CG_VOLUME_DEPTH(eye) _cg_volumeEye = (eye)\n#define main _cg_main\n");
        } else if (NAMES_VOLUME_DEPTH.matcher(pass.fragmentBody()).find()) {
            sb.append("#define CG_VOLUME_DEPTH(eye)\n");
        }
    }

    // ── Shared section builders ───────────────────────────────────────────────

    private static String[] partitionGlobalDecls(String globalDecls) {
        if (globalDecls == null || globalDecls.trim().isEmpty()) return new String[]{"", ""};
        StringBuilder directives = new StringBuilder();
        StringBuilder code = new StringBuilder();
        String[] lines = globalDecls.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#")) {
                appendLine(code, lines[i]);
                continue;
            }
            int end = line.startsWith("#if") ? endOfConditional(lines, i) : i;
            // A conditional around code stays with it, whole: hoisted, its #if would leave the code unguarded.
            boolean holdsCode = false;
            for (int k = i; k <= end && !holdsCode; k++) {
                String t = lines[k].trim();
                holdsCode = !t.isEmpty() && !t.startsWith("#");
            }
            for (int k = i; k <= end; k++) appendLine(holdsCode ? code : directives, lines[k]);
            i = end;
        }
        return new String[]{directives.toString(), code.toString()};
    }

    /** The {@code #endif} closing the conditional opened at {@code from}; the last line if none does. */
    private static int endOfConditional(String[] lines, int from) {
        int depth = 0;
        for (int k = from; k < lines.length; k++) {
            String t = lines[k].trim();
            if (t.startsWith("#if")) depth++;
            else if (t.startsWith("#endif") && --depth == 0) return k;
        }
        return lines.length - 1;
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(line);
    }

    private static void appendAutoRequiredExtensions(StringBuilder sb, Set<String> required,
                                                      String existingDirectives) {
        for (String ext : required) {
            if (!existingDirectives.contains(ext)) {
                sb.append("#extension ").append(ext).append(" : enable\n");
            }
        }
    }

    /**
     * 430 with storage blocks, 330 without; a multi-draw variant at the context's own above that, since glslang
     * declares the draw parameters from 450 and 4.6 has them in core.
     */
    private static int glslVersion(boolean useSsbo, CompileConfig config) {
        if (!useSsbo) return 330;
        return config.activeKeywords().contains(MULTI_DRAW) ? Math.max(430, CgCapabilities.detect().glslVersion()) : 430;
    }

    private static void appendKeywordDefines(StringBuilder sb, List<String> featureNames,
                                              Set<String> activeKeywords) {
        if (activeKeywords.isEmpty()) return;
        for (String name : featureNames) {
            if (activeKeywords.contains(name)) {
                sb.append("#define ").append(name).append(" 1\n");
            }
        }
        if (activeKeywords.contains(MULTI_DRAW)) sb.append("#define ").append(MULTI_DRAW).append(" 1\n");
        if (activeKeywords.contains(DEBUG_OVERDRAW)) sb.append("#define ").append(DEBUG_OVERDRAW).append(" 1\n");
        if (activeKeywords.contains(DEPTH_PREPASS)) sb.append("#define ").append(DEPTH_PREPASS).append(" 1\n");
        if (activeKeywords.contains(VOLUME_TARGET)) sb.append("#define ").append(VOLUME_TARGET).append(" 1\n");
        if (activeKeywords.contains(CUSTOM_DATA_TARGET)) sb.append("#define ").append(CUSTOM_DATA_TARGET).append(" 1\n");
    }

    /**
     * Includes each declared engine buffer's own GLSL environment, right after its declaration.
     *
     * <p>{@code CG_QUAD_*} is to {@code QUAD_DATA} what a header is to a struct, and it used to live in
     * {@code cg_env.glsl} — where every shader paid for it, including the three quarters that draw
     * through neither renderer. It lives beside the buffer now and arrives only with the pragma.</p>
     *
     * <p><b>After the declaration, not before</b>: the macros read the struct. And an {@code #include}
     * rather than inlined text, so {@code #pragma once} still collapses a buffer named by both stages
     * of the same shader.</p>
     */
    private static void appendEngineBufferEnv(StringBuilder sb, java.util.List<String> tokens) {
        for (String token : tokens) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            if (provider == null) continue;
            if (provider.envPath() != null) sb.append("#include \"").append(provider.envPath()).append("\"").append("\n");
            if (provider.generated() != null) sb.append(provider.generated().get());
        }
    }

    /**
     * The accessors a property read through its own name needs: an array's {@code _Name(i)}, and under
     * {@code cg_use rows} each number's macro choosing the draw's row ({@code _cg_at<name>} at or above 0) over the
     * material's value. Before any user code, after the block and the rows buffer they read.
     *
     * <pre>{@code
     * #define _Radius (_cg_at_Radius < 0 ? _cgm_Radius : CG_ROW(CG_PROPERTY_ROW + _cg_at_Radius).x)
     * vec4 _cg_arr_Bands(int i) { ... }
     * #define _Bands(i) _cg_arr_Bands(i)
     * }</pre>
     */
    /** A line's end in emitted source. */
    private static final char NL = 10;

    /** Elements a table reads through one local array: a longer table branches to its chunk of this many first. */
    static final int TABLE_CHUNK = 16;

    /**
     * Element {@code k} of array {@code p}'s {@code n} block fields. A table past {@link #TABLE_CHUNK} reads the chunk
     * holding {@code k}: a local array of every field would be n vec4s of registers a read, where GLSL 3.30 indexes a
     * block's separate fields only through one.
     */
    static void appendTableRead(StringBuilder sb, CgMaterialProperty p, int n) {
        for (int from = 0; from < n; from += TABLE_CHUNK) {
            int m = Math.min(TABLE_CHUNK, n - from);
            boolean last = from + m >= n;
            String pad = last && from == 0 ? "    " : "        ";
            if (!last) sb.append("    if (k < ").append(from + m).append(") {").append(NL);
            else if (from > 0) sb.append("    {").append(NL);
            sb.append(pad).append("vec4 a[").append(m).append("] = vec4[").append(m).append("](");
            for (int i = 0; i < m; i++) sb.append(i == 0 ? "" : ", ").append(p.blockName(from + i));
            sb.append(");").append(NL).append(pad).append("return a[k - ").append(from).append("];").append(NL);
            if (!last || from > 0) sb.append("    }").append(NL);
        }
    }

    static void appendPropertyAccessors(StringBuilder sb, List<CgMaterialProperty> properties, boolean rows) {
        for (CgMaterialProperty p : properties) {
            if (p.getType().isSampler() || p.isHidden()) continue;
            String name = p.getName(), slot = CgMaterialProperty.rowSlotName(name);
            if (p.isArray()) {
                int n = p.getCount();
                sb.append("vec4 _cg_arr").append(name).append("(int i) {").append(NL)
                        .append("    int k = clamp(i, 0, ").append(n - 1).append(");").append(NL);
                if (p.isRows()) {
                    sb.append("    if (").append(slot).append(" >= 0) return CG_ROW(CG_PROPERTY_ROW + ").append(slot)
                            .append(" + k);").append(NL);
                }
                appendTableRead(sb, p, n);
                sb.append('}').append(NL);
                sb.append("#define ").append(name).append("(i) _cg_arr").append(name).append("(i)").append(NL);
            } else if (rows && p.isRows()) {
                String swizzle = p.getType().getComponents() == 1 ? ".x" : p.getType().getComponents() == 2 ? ".xy" : "";
                sb.append("#define ").append(name).append(" (").append(slot).append(" < 0 ? ").append(p.blockName())
                        .append(" : CG_ROW(CG_PROPERTY_ROW + ").append(slot).append(")").append(swizzle).append(')')
                        .append(NL);
            }
        }
    }

    /**
     * The material's {@code Buffers { }}: the structs they hold, then each as a storage block, or below the SSBO path as
     * a buffer texture on the unit after the samplers and the buffers before it.
     */
    private static void appendMaterialBuffers(StringBuilder sb, CgParsedShader shader, boolean useSsbo) {
        if (shader.buffers().isEmpty()) return;
        if (!useSsbo && CgBindingPoints.isInitialized()) {
            int samplers = 0;
            for (CgMaterialProperty p : shader.properties()) if (p.getType().isSampler()) samplers++;
            int free = CgBindingPoints.LOCAL_LIGHTS_TEXTURE_UNIT;   // the lowest reserved unit
            if (samplers + shader.buffers().size() > free) {
                throw new CgShaderParseException("'Buffers': " + samplers + " samplers and " + shader.buffers().size()
                        + " buffers read as textures need " + (samplers + shader.buffers().size())
                        + " texture units, and this context leaves a material " + free);
            }
        }
        sb.append("// Buffers { }\n").append(shader.bufferStructs());
        for (CgBufferDecl b : shader.buffers()) CgLoweredEmitter.reader(sb, b, useSsbo);
    }

    /** What the world hands a program carrying its own lighting, after the buffers and properties it reads through. */
    private static void appendHanded(StringBuilder sb, CgParsedShader shader) {
        if (shader.handedLights()) sb.append("#include \"").append(HANDED_LIGHTS_INCLUDE).append("\"\n");
        if (shader.handedSun()) sb.append("#include \"").append(HANDED_SUN_INCLUDE).append("\"\n");
        if (shader.handedScene()) sb.append("#include \"").append(HANDED_SCENE_INCLUDE).append("\"\n");
    }

    private static void appendAttachedBuffers(StringBuilder sb,
                                               List<CgAttachedBuffer> attachedBuffers,
                                               CgCapabilities.ShaderBufferPath path) {
        boolean useSsbo = (path == CgCapabilities.ShaderBufferPath.SSBO_GL43
                || path == CgCapabilities.ShaderBufferPath.SSBO_ARB);
        for (CgAttachedBuffer ab : attachedBuffers) {
            if (ab.isUbo()) {
                sb.append(CgGlslEmitter.emitUbo(ab.getBuffer().getFormat(), ab.getBuffer().getName())).append('\n');
            } else {
                String block = useSsbo
                        ? CgGlslEmitter.emitSsbo(ab)
                        : CgGlslEmitter.emitTbo(ab);
                sb.append(block).append('\n');
            }
        }
    }

    private static void appendPropertyUniforms(StringBuilder sb, List<CgMaterialProperty> properties) {
        boolean hasSamplers = false;
        for (CgMaterialProperty p : properties) {
            if (p.getType().isSampler()) {
                if (!hasSamplers) {
                    sb.append("// Sampler properties\n");
                    hasSamplers = true;
                }
                sb.append("uniform ").append(p.getGlslType()).append(' ').append(p.getName()).append(";\n");
            }
        }
    }

    private static void appendV2fStruct(StringBuilder sb, String v2fStructBody) {
        sb.append("// v2f struct\n");
        sb.append("struct v2f {\n");
        sb.append(v2fStructBody).append("\n");
        sb.append("};\n");
    }

    private static void appendV2fVaryingOutputs(StringBuilder sb, List<CgShaderParser.V2fField> fields, boolean normals) {
        appendV2fBlock(sb, "out", fields, normals);
    }

    private static void appendV2fVaryingInputs(StringBuilder sb, List<CgShaderParser.V2fField> fields, boolean normals) {
        appendV2fBlock(sb, "in", fields, normals);
    }

    /** The v2f interface block; with {@code normals}, the normal target's normal last in it. */
    private static void appendV2fBlock(StringBuilder sb, String direction, List<CgShaderParser.V2fField> fields, boolean normals) {
        if (fields.isEmpty() && !normals) return;
        sb.append(direction).append(" _CgV2fBlock {\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("    ").append(f.type()).append(" ").append(f.name()).append(";\n");
        }
        if (normals) sb.append("    vec3 _cg_normalWs;\n");
        sb.append("} _cg_v2f;\n");
    }

    private static void appendGlobalDecls(StringBuilder sb, String globalDecls) {
        if (globalDecls != null && !globalDecls.trim().isEmpty()) {
            sb.append("// Global declarations\n");
            sb.append(globalDecls).append("\n");
        }
    }

    private static void appendVertexMain(StringBuilder sb, List<CgShaderParser.V2fField> fields) {
        sb.append("void main() {\n");
        sb.append("  cg_InstanceId = CG_INSTANCE_ID;\n");
        sb.append("  v2f _v2f_local;\n");
        sb.append("  vertex(_v2f_local);\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("  _cg_v2f.").append(f.name())
              .append(" = _v2f_local.").append(f.name()).append(";\n");
        }
        sb.append("}\n");
    }

    private static void appendFragmentOutputDeclarations(StringBuilder sb, CgParsedPass pass, EmissionMerge merge) {
        if (merge != EmissionMerge.NONE) {
            sb.append("layout(location = 0) out vec4 _cg_fragColor;\nlayout(location = 1) out vec4 _cg_emission;\n");
        } else if (!pass.fragOutput().isMrt()) {
            sb.append("out vec4 _cg_fragColor;\n");
        } else {
            List<String> fieldNames = pass.fragOutput().fieldNames();
            List<Integer> locations = pass.fragOutput().locations();
            for (int i = 0; i < fieldNames.size(); i++) {
                int loc = locations.get(i);
                sb.append("layout(location = ").append(loc).append(") out vec4 _cg_RT").append(loc).append(";\n");
            }
        }
    }

    private static void appendFragmentUserFunction(StringBuilder sb, CgParsedPass pass) {
        if (NAMES_GLOW.matcher(pass.fragmentBody()).find()) {
            sb.append("vec3 _cg_glowRgb = vec3(0.0);\n#define CG_GLOW(rgb) _cg_glowRgb = (rgb)\n");
        }
        if (CgShaderParser.SURFACE_NORMAL.matcher(pass.fragmentBody()).find()) {
            sb.append("vec3 _cg_surfaceNormal = vec3(0.0, 1.0, 0.0);\n#define CG_SURFACE_NORMAL(n) _cg_surfaceNormal = (n)\n");
        }
        if (SURFACE_EMISSIVE.matcher(pass.fragmentBody()).find()) {
            sb.append("vec3 _cg_surfaceEmissive = vec3(0.0);\n#define CG_SURFACE_EMISSIVE(rgb) _cg_surfaceEmissive = (rgb)\n");
        }
        if (!pass.fragOutput().isMrt()) {
            sb.append("void fragment(in v2f i, out vec4 ")
              .append(pass.fragOutput().outParamName()).append(") {\n")
              .append(pass.fragmentBody()).append("\n")
              .append("}\n");
        } else {
            sb.append("void fragment(in v2f i, out ").append(pass.fragOutput().mrtStructName())
              .append(" ").append(pass.fragOutput().outParamName()).append(") {\n")
              .append(pass.fragmentBody()).append("\n")
              .append("}\n");
        }
    }

    /**
     * {@code CG_EMISSIVE_PASS} in an Emissive pass, so a body it shares with the Forward pass can tell them apart; and
     * in every pass {@code CG_EMISSION}, the glow's multiplier: {@code _EmissionColor.rgb} (a color property) times
     * {@code _EmissionStrength} (a float) where the shader declares them, times the draw's {@code CG_OBJECT_EMISSION};
     * in an Emissive pass, times {@code CG_SCENE_GLOW} too, the world's gain on glows drawn into the HDR scene.
     */
    private static void appendPassDefine(StringBuilder sb, CgParsedShader shader, CgParsedPass pass) {
        // The vertex factory an engine buffer's env places vertices for: CG_TYPE_NONE, CG_TYPE_SPATIAL.
        sb.append("#define CG_TYPE_").append(shader.shaderType().toUpperCase(Locale.ROOT)).append(" 1\n");
        if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode())) sb.append("#define CG_EMISSIVE_PASS 1\n");
        if (CgParsedPass.LIGHT_MODE_DISTORTION.equals(pass.lightMode())) sb.append("#define CG_DISTORTION_PASS 1\n");
        // cg_env.glsl's cg_LitSurface exists only where it is called: it reads a volume no other material binds.
        if (shader.surfaceLit()) sb.append("#define CG_SURFACE_LIT 1\n");
        if (shader.handedLights()) sb.append("#define CG_HANDED_LIGHTS 1\n");
        if (shader.handedSun()) sb.append("#define CG_HANDED_SUN 1\n");
        sb.append("#define CG_EMISSION (vec3(1.0)");
        if (hasProperty(shader, "_EmissionColor", 4)) sb.append(" * _EmissionColor.rgb");
        if (hasProperty(shader, "_EmissionStrength", 1)) sb.append(" * _EmissionStrength");
        if (shader.readsObjectRecord()) sb.append(" * CG_OBJECT_EMISSION");
        if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode())) sb.append(" * CG_SCENE_GLOW");
        sb.append(")\n");
    }

    /**
     * {@code CG_CLIP_THRESHOLD}, the threshold ({@code _Clip} where the shader declares it, else 0.5), and {@code cg_Clip(v)}
     * for this pass: in a depth pass (a Depth pass, or {@link #DEPTH_PREPASS}) a discard below it and a return at it,
     * so nothing after it runs; in a {@code "Depth" = "Prepass"} material's other passes nothing; else the discard.
     * Only where it can be called: a material with a {@code "Depth"} tag, or a pass naming it. A material that does not
     * clip (no tag, or a state's blend without its clip) gets nothing in every pass: Unreal applies the opacity mask
     * only to a Masked material, so a translucent instance of a masked parent cuts nothing.
     */
    private static void appendClip(StringBuilder sb, CgParsedShader shader, CgParsedPass pass, CompileConfig config) {
        if (shader.depthMode() == CgParsedShader.DepthMode.NONE && !names(pass, "cg_Clip")) return;
        sb.append("#define CG_CLIP_THRESHOLD ").append(hasProperty(shader, "_Clip", 1) ? "_Clip" : "0.5").append('\n');
        if (shader.depthMode() == CgParsedShader.DepthMode.NONE) {
            sb.append("#define cg_Clip(v)\n");
        } else if (CgParsedPass.LIGHT_MODE_DEPTH.equals(pass.lightMode()) || config.activeKeywords().contains(DEPTH_PREPASS)) {
            sb.append("#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; return; }\n");
        } else if (shader.depthMode() == CgParsedShader.DepthMode.PREPASS) {
            sb.append("#define cg_Clip(v)\n");
        } else {
            sb.append("#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; }\n");
        }
    }

    private static boolean hasProperty(CgParsedShader shader, String name, int components) {
        for (CgMaterialProperty p : shader.properties()) {
            if (p.getName().equals(name) && p.getType().getComponents() == components) return true;
        }
        return false;
    }

    /** How {@code cg_Fog} treats the pass's output: mixed toward the fog (0), premultiplied (1), added (2). */
    private static int fogMode(CgParsedPass pass) {
        if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode())) return 2;   // added onto the scene by bloom
        CgBlendState blend = pass.renderState().getBlend();
        if (blend == null || !blend.enabled()) return 0;
        if (blend.dstRgb() == CgGL.GL_ONE) return 2;
        if (blend.srcRgb() == CgGL.GL_DST_COLOR && blend.dstRgb() == CgGL.GL_ZERO) return 3;   // modulate
        return blend.srcRgb() == CgGL.GL_ONE ? 1 : 0;
    }

    /**
     * Decodes a Forward colour, authored as sRGB, where the pass draws into the linear scene. A blend over what is
     * behind also has its coverage remapped ({@code cg_SceneCoverage}), so it hides as much as it did encoded; a
     * premultiplied colour is decoded unpremultiplied and multiplied by that coverage.
     */
    private static void appendSceneDecode(StringBuilder sb, CgParsedPass pass) {
        CgBlendState blend = pass.renderState().getBlend();
        boolean over = blend != null && blend.enabled() && blend.dstRgb() == CgGL.GL_ONE_MINUS_SRC_ALPHA;
        if (over && blend.srcRgb() == CgGL.GL_ONE) {
            sb.append("  if (CG_LINEAR_SCENE) {\n")
              .append("    float _cg_cover = cg_SceneCoverage(_cg_fragColor.a);\n")
              .append("    _cg_fragColor.rgb = _cg_fragColor.a > 0.0")
              .append(" ? cg_SceneDecode(_cg_fragColor.rgb / _cg_fragColor.a) * _cg_cover")
              .append(" : cg_SceneDecode(_cg_fragColor.rgb);\n")
              .append("    _cg_fragColor.a = _cg_cover;\n  }\n");
        } else if (over && blend.srcRgb() == CgGL.GL_SRC_ALPHA) {
            sb.append("  if (CG_LINEAR_SCENE) _cg_fragColor = vec4(cg_SceneDecode(_cg_fragColor.rgb),")
              .append(" cg_SceneCoverage(_cg_fragColor.a));\n");
        } else {
            sb.append("  if (CG_LINEAR_SCENE) _cg_fragColor.rgb = cg_SceneDecode(_cg_fragColor.rgb);\n");
        }
    }

    /**
     * Whether a pass is dimmed by the smoke and gas in front of it ({@code cg_Media}): a transparent world material's
     * ({@link CgParsedShader#mediated}), not a volume's, which is the smoke itself.
     */
    static boolean dimsInMedia(CgParsedShader shader, CgParsedPass pass) {
        return shader.mediated() && !NAMES_VOLUME_DEPTH.matcher(pass.fragmentBody()).find();
    }

    /**
     * Dims the pass's output by what gets through the world's media between the eye and the fragment, as its blend
     * takes it: light added or premultiplied whole, a colour blended by its alpha through that alpha.
     */
    private static void appendMediaDim(StringBuilder sb, CgParsedPass pass, boolean emissive, EmissionMerge merge) {
        CgBlendState blend = pass.renderState().getBlend();
        boolean whole = emissive || blend != null && blend.enabled() && blend.srcRgb() == CgGL.GL_ONE;
        boolean alpha = !emissive && blend != null && blend.enabled() && blend.srcRgb() == CgGL.GL_SRC_ALPHA;
        if (!whole && !alpha) return;
        sb.append("  float _cg_media = cg_MediaTransmit(gl_FragCoord.xy / CG_RESOLUTION, cg_LinearEyeDepth(gl_FragCoord.z));\n");
        sb.append(whole ? "  _cg_fragColor *= _cg_media;\n" : "  _cg_fragColor.a *= _cg_media;\n");
        if (merge == EmissionMerge.SAME_BLEND || merge == EmissionMerge.ADDED) sb.append("  _cg_emission.rgb *= _cg_media;\n");
    }

    /**
     * An {@code "Output" = "EmissiveOpacity"} fragment's (emissive, opacity) as Unreal's base pass composes it for the
     * blend (BasePassPixelShader.usf): an additive pass adds emissive times opacity, alpha 0; every other blend draws
     * them as written, its fog mode doing the rest.
     */
    private static void appendEmissiveOpacity(StringBuilder sb, CgParsedShader shader, CgParsedPass pass) {
        if (!shader.emissiveOpacity() || CgParsedPass.LIGHT_MODE_DISTORTION.equals(pass.lightMode())) return;
        CgBlendState blend = pass.renderState().getBlend();
        if (blend != null && blend.enabled() && blend.srcRgb() == CgGL.GL_ONE && blend.dstRgb() == CgGL.GL_ONE) {
            sb.append("  _cg_fragColor = vec4(_cg_fragColor.rgb * _cg_fragColor.a, 0.0);\n");
        }
    }

    private static void appendFragmentMain(StringBuilder sb, List<CgShaderParser.V2fField> fields,
                                            CgParsedPass pass, CgParsedShader shader, boolean overdraw,
                                            EmissionMerge merge, boolean cover, boolean fold) {
        sb.append("void main() {\n");
        sb.append("  v2f _v2f_local;\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("  _v2f_local.").append(f.name())
              .append(" = _cg_v2f.").append(f.name()).append(";\n");
        }
        if (shader.readsObjectRecord()) sb.append("  cg_Light = CG_OBJECT_LIGHT;\n");
        boolean emissive = CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode());
        boolean distortion = CgParsedPass.LIGHT_MODE_DISTORTION.equals(pass.lightMode());
        CgDepthState depth = pass.renderState().getDepth();
        // The overdraw view's target has no depth either: its test is the pass's own, as a discard.
        boolean overdrawTested = overdraw && (depth == null || depth.test() && depth.compareFunc() != CgGL.GL_ALWAYS);
        if ((emissive || distortion || cover) && !(depth != null && depth.test() && depth.compareFunc() == CgGL.GL_ALWAYS)
                || overdrawTested) {
            // The bloom and distortion targets have no depth, so this is the pass's depth test: the scene's depth, a copy at its own size
            // read by uv. "DepthTest ALWAYS" leaves occlusion to the shader, as a volume drawn on its back faces needs.
            sb.append("  if (cg_LinearEyeDepth(gl_FragCoord.z) > CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION)"
                    + " * CG_EMISSIVE_DEPTH_SLACK + CG_EMISSIVE_DEPTH_BIAS) discard;\n");
        }
        if (overdraw) {
            // The fragment runs for its discard; what it wrote is replaced by one count.
            if (!pass.fragOutput().isMrt()) {
                sb.append("  fragment(_v2f_local, _cg_fragColor);\n  _cg_fragColor = vec4(1.0, 0.0, 0.0, 0.0);\n");
            } else {
                sb.append("  ").append(pass.fragOutput().mrtStructName()).append(" _cg_mrtOut;\n");
                sb.append("  fragment(_v2f_local, _cg_mrtOut);\n");
                for (int loc : pass.fragOutput().locations()) {
                    sb.append("  _cg_RT").append(loc).append(" = vec4(1.0, 0.0, 0.0, 0.0);\n");
                }
            }
        } else if (!pass.fragOutput().isMrt()) {
            sb.append("  fragment(_v2f_local, _cg_fragColor);\n");
            appendEmissiveOpacity(sb, shader, pass);
            boolean named = NAMES_GLOW.matcher(pass.fragmentBody()).find();
            String glow = named ? "vec4(_cg_glowRgb, _cg_fragColor.a)" : "_cg_fragColor";
            if (emissive && named) sb.append("  _cg_fragColor.rgb = _cg_glowRgb;\n");
            if (merge != EmissionMerge.NONE && merge != EmissionMerge.COVER) {
                // What the Emissive pass would write: unlit, faded by fog as an added colour is.
                sb.append("  _cg_emission = ").append(glow).append(";\n");
                if (!READS_EMISSION.matcher(pass.fragmentBody()).find()) sb.append("  _cg_emission.rgb *= CG_EMISSION;\n");
                if (shader.fogged()) sb.append("  _cg_emission.rgb *= 1.0 - cg_FogAmount(cg_FragmentDistance());\n");
                if (merge == EmissionMerge.ADDED) sb.append("  _cg_emission.a = 0.0;\n");
            }
            if (fold) {
                // What the Emissive pass would add into the scene: unlit, times the scene's gain, faded by fog.
                sb.append("  vec4 _cg_glow = ").append(glow).append(";\n");
                if (!READS_EMISSION.matcher(pass.fragmentBody()).find()) sb.append("  _cg_glow.rgb *= CG_EMISSION;\n");
                sb.append("  _cg_glow.rgb *= CG_SCENE_GLOW;\n");
                if (shader.fogged()) sb.append("  _cg_glow.rgb *= 1.0 - cg_FogAmount(cg_FragmentDistance());\n");
            }
            // Code that reads CG_EMISSION has applied it already.
            if (emissive && !READS_EMISSION.matcher(pass.fragmentBody()).find()) sb.append("  _cg_fragColor.rgb *= CG_EMISSION;\n");
            // A world material is lit and fogged as Minecraft's own things are, unless tagged otherwise. Emitted
            // light is not lit, only faded by the fog.
            boolean forward = CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode());
            if (forward && shader.lit()) {
                sb.append(shader.surfaceLit() ? "  _cg_fragColor = cg_LitSurface(_cg_fragColor, _cg_surfaceNormal);\n"
                        : "  _cg_fragColor = cg_Lit(_cg_fragColor);\n");
            }
            // Unreal's base pass adds the emissive after the lit colour, unlit (BasePassPixelShader.usf: Color += Emissive).
            if (forward && SURFACE_EMISSIVE.matcher(pass.fragmentBody()).find()) {
                sb.append("  _cg_fragColor.rgb += _cg_surfaceEmissive;\n");
            }
            if ((forward || emissive) && shader.fogged()) sb.append("  _cg_fragColor = cg_Fog(_cg_fragColor);\n");
            if (merge == EmissionMerge.COVER) sb.append("  _cg_emission = vec4(0.0, 0.0, 0.0, _cg_fragColor.a);\n");
            if (cover) sb.append("  _cg_fragColor = vec4(0.0, 0.0, 0.0, _cg_fragColor.a);\n");
            if (forward && !shader.linearColor() && merge == EmissionMerge.NONE && !cover) {
                appendSceneDecode(sb, pass);
                // Held at white, as the 8-bit target holds it: its glow carries the light past, or it counts twice.
                if (shader.getPassByLightMode(CgParsedPass.LIGHT_MODE_EMISSIVE) != null) {
                    sb.append("  if (CG_LINEAR_SCENE) _cg_fragColor.rgb = min(_cg_fragColor.rgb, vec3(1.0));\n");
                }
            }
            if (fold && pass.renderState().getBlend().srcRgb() == CgGL.GL_SRC_ALPHA) {
                // Weighed by the alpha the Emissive pass would blend with, not the coverage the decode remapped it to.
                sb.append("  if (_cg_fragColor.a > 0.0) _cg_fragColor.rgb += _cg_glow.rgb * (_cg_glow.a / _cg_fragColor.a);\n");
            } else if (fold) {
                sb.append("  _cg_fragColor.rgb += _cg_glow.rgb;\n");
            }
            if ((forward || emissive) && dimsInMedia(shader, pass)) appendMediaDim(sb, pass, emissive, merge);
        } else {
            sb.append("  ").append(pass.fragOutput().mrtStructName()).append(" _cg_mrtOut;\n");
            sb.append("  fragment(_v2f_local, _cg_mrtOut);\n");
            List<String> fieldNames = pass.fragOutput().fieldNames();
            List<Integer> locations = pass.fragOutput().locations();
            for (int i = 0; i < fieldNames.size(); i++) {
                int loc = locations.get(i);
                sb.append("  _cg_RT").append(loc).append(" = _cg_mrtOut.").append(fieldNames.get(i)).append(";\n");
            }
        }
        sb.append("}\n");
    }
}
