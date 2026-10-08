package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.compute.lower.CgLoweredEmitter;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
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

    /**
     * The engine's keyword for a multi-draw's variant of any pass: {@code cg_env.glsl} takes a draw's bases from its
     * command rather than from uniforms. Defined whether or not the shader declares it.
     */
    public static final String MULTI_DRAW = "CG_MULTI_DRAW";

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

    /** Code applying {@code CG_EMISSION} itself; not {@code CG_EMISSION_TARGET}. */
    private static final Pattern READS_EMISSION = Pattern.compile("\\bCG_EMISSION\\b");

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

        String vertexSource = buildVertexSource(shader, pass, properties, v2fFields, useSsbo,
                path, attachedBuffers, requiredExtensions, matPropsEntry, config, vertexFormat);
        String fragmentSource = buildFragmentSource(shader, pass, properties, v2fFields, useSsbo,
                path, attachedBuffers, requiredExtensions, matPropsEntry, config);

        return new CompiledSource(vertexSource, fragmentSource, vertexFormat);
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

    // ── Shadow auto-generation ────────────────────────────────────────────────

    /**
     * Auto-generates a ShadowCaster program from an existing Forward pass.
     *
     * <p>The shadow vertex shader either uses a minimal position-only transform
     * (when {@link #isSimpleVertex(CgParsedPass)} is true) or re-runs the forward
     * vertex body and overrides {@code gl_Position} with the light-space transform
     * (for complex vertices with animation or custom attributes).</p>
     *
     * <p>The fragment shader is depth-only (empty body; depth written automatically
     * by the rasterizer).</p>
     *
     * <p><strong>⚠ UBO contract — NOT YET SATISFIED.</strong> The generated GLSL references
     * {@code cg_ShadowViewProjMatrix}, {@code cg_LightDirection} and {@code cg_ShadowParams} as plain
     * identifiers, on the assumption that {@code CgFrameBlock} declares them (injected via
     * {@code cg_env.glsl}). <b>It does not, and never has</b> — that block carries view, projection, time
     * and resolution and nothing else, so everything produced here fails to compile with
     * {@code undefined variable}. This javadoc asserted the contract rather than the code establishing it,
     * which is why it went unnoticed for so long.</p>
     *
     * <p>Nothing calls this today: {@code CgMaterialShader.attemptShadowAutoGen} skips shadow generation
     * entirely while {@code CgMaterialShader.SHADOWS_SUPPORTED} is false. Declaring the three uniforms in
     * {@code cg_env.glsl} and flipping that flag are one change, enforced by
     * {@code CgShadowUniformContractTest}.</p>
     *
     * <p>The identifier style is right and worth keeping: plain references, never standalone
     * {@code uniform} declarations, which would collide with the block once it exists.</p>
     *
     * @param shader          material-level parse result (properties, featureNames)
     * @param forwardPass     the Forward pass to derive the shadow vertex from; must not be null
     * @param attachedBuffers user-defined SSBO/TBO and UBO buffers to inject
     * @param matPropsUbo     engine-managed material properties UBO, or {@code null}
     * @param config          active-keyword config (typically {@link CompileConfig#DEFAULT})
     * @return a {@link CompiledSource} holding the complete shadow vertex and depth-only fragment
     * @throws IllegalArgumentException if {@code ShaderBufferPath} is {@code NONE}
     */
    public static CompiledSource compileShadowAutoGen(CgParsedShader shader,
                                                       CgParsedPass forwardPass,
                                                       List<CgAttachedBuffer> attachedBuffers,
                                                       CgUniformBuffer matPropsUbo,
                                                       CompileConfig config) {
        final String shadowVertexBody;
        if (isSimpleVertex(forwardPass) && !readsBuffers(shader, forwardPass)) {
            // Simple path: minimal position-only transform using the frame UBO shadow matrix
            shadowVertexBody =
                    "    // Auto-generated shadow caster -- minimal position transform\n"
                    + "    gl_Position = cg_ShadowViewProjMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);\n"
                    + "    // Depth bias to prevent shadow acne (cg_ShadowParams.z)\n"
                    + "    gl_Position.z += cg_ShadowParams.z * gl_Position.w;\n"
                    + "    // Pancaking: clamp to avoid near-plane clipping on thin objects\n"
                    + "    gl_Position.z = max(gl_Position.z, -gl_Position.w);\n";
        } else {
            // Complex path: run forward vertex body, then override gl_Position with shadow transform
            shadowVertexBody =
                    "    // Auto-generated shadow caster -- complex vertex (has animation or custom attributes)\n"
                    + forwardPass.vertexBody() + "\n"
                    + "    // Override gl_Position with light-space transform\n"
                    + "    gl_Position = cg_ShadowViewProjMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);\n"
                    + "    // Depth bias and pancaking (cg_ShadowParams.z)\n"
                    + "    gl_Position.z += cg_ShadowParams.z * gl_Position.w;\n"
                    + "    gl_Position.z = max(gl_Position.z, -gl_Position.w);\n";
        }

        // Fragment: depth-only — empty body, rasterizer writes depth automatically
        final String shadowFragmentBody =
                "    // Depth-only shadow pass -- rasterizer writes depth automatically.\n";

        // Build synthetic shadow pass reusing forward pass's v2f for interface block consistency
        CgFragOutputParser.FragOutput shadowFragOutput =
                CgFragOutputParser.FragOutput.singleOutput("fragColor");
        CgParsedPass shadowPass = new CgParsedPass(
                CgParsedPass.LIGHT_MODE_SHADOW_CASTER,
                CgParsedPass.LIGHT_MODE_SHADOW_CASTER,
                CgRenderState.DEFAULT,
                forwardPass.v2fStructBody(),
                forwardPass.globalDecls(),
                shadowVertexBody,
                shadowFragmentBody,
                shadowFragOutput);

        return compile(shader, shadowPass, attachedBuffers, matPropsUbo, config);
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
        if (isSimpleVertex(forwardPass) && !readsBuffers(shader, forwardPass)) {
            depthVertexBody =
                    "    // Auto-generated depth prepass -- minimal position transform\n"
                    + "    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n";
        } else {
            depthVertexBody =
                    "    // Auto-generated depth prepass -- preserves vertex animation\n"
                    + forwardPass.vertexBody() + "\n";
        }

        final String depthFragmentBody;
        if (forwardPass.fragmentBody().contains("discard")) {
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
        boolean noDiscard = !pass.fragmentBody().contains("discard");
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
        // Known limitation: compileShadowAutoGen / compileDepthAutoGen generate vertex bodies
        // that reference cg_Position by literal name — this only works correctly for formats
        // where the position attribute is named cg_Position (i.e. SPATIAL and SPATIAL-derived
        // formats). Custom formats with a differently-named position attribute must author an
        // explicit ShadowCaster / Depth pass to bypass auto-generation.
        sb.append(CgGlslEmitter.emitVertexInputs(vertexFormat));

        // Property uniform declarations (sampler types only; non-sampler go into CgMaterialBlock UBO)
        appendPropertyUniforms(sb, properties);

        // Material properties UBO (CgMaterialBlock) — emitted before user-attached buffers
        if (matPropsEntry != null) {
            sb.append(CgGlslEmitter.emitUbo(matPropsEntry)).append('\n');
        }

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        appendEngineBufferEnv(sb, shader.engineBuffers());

        appendMaterialBuffers(sb, shader, useSsbo);

        // v2f struct
        appendV2fStruct(sb, pass.v2fStructBody());

        // Compiler-wired flat int varying for instance ID bridging (vertex → fragment)
        sb.append("flat out int cg_InstanceId;\n");

        // v2f varying outputs
        appendV2fVaryingOutputs(sb, v2fFields);

        // Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // User vertex function
        sb.append("// User vertex function\n");
        sb.append("void vertex(out v2f o) {\n")
          .append(pass.vertexBody()).append("\n")
          .append("}\n");

        // Generated main()
        appendVertexMain(sb, v2fFields);

        return sb.toString();
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

        appendMaterialBuffers(sb, shader, useSsbo);

        // v2f struct
        appendV2fStruct(sb, pass.v2fStructBody());

        // v2f varying inputs
        appendV2fVaryingInputs(sb, v2fFields);

        // Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // Fragment output declarations (single-output or MRT)
        EmissionMerge merge = EmissionMerge.NONE;
        if (config.activeKeywords().contains(EMISSION_TARGET) && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode())) {
            merge = emissionMerge(shader);
            if (merge == EmissionMerge.NONE && !pass.fragOutput().isMrt()) merge = EmissionMerge.COVER;
        }
        appendFragmentOutputDeclarations(sb, pass, merge);

        // User fragment function
        sb.append("// User fragment function\n");
        appendFragmentUserFunction(sb, pass);

        // Generated main()
        boolean cover = config.activeKeywords().contains(EMISSION_COVER) && merge == EmissionMerge.NONE
                && CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode()) && !pass.fragOutput().isMrt();
        appendFragmentMain(sb, v2fFields, pass, shader, config.activeKeywords().contains(DEBUG_OVERDRAW), merge, cover);

        return sb.toString();
    }

    // ── Shared section builders ───────────────────────────────────────────────

    private static String[] partitionGlobalDecls(String globalDecls) {
        if (globalDecls == null || globalDecls.trim().isEmpty()) return new String[]{"", ""};
        StringBuilder directives = new StringBuilder();
        StringBuilder code = new StringBuilder();
        for (String rawLine : globalDecls.split("\n", -1)) {
            if (rawLine.trim().startsWith("#")) {
                if (directives.length() > 0) directives.append('\n');
                directives.append(rawLine);
            } else {
                if (code.length() > 0) code.append('\n');
                code.append(rawLine);
            }
        }
        return new String[]{directives.toString(), code.toString()};
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
            if (provider == null || provider.envPath() == null) continue;
            sb.append("#include \"").append(provider.envPath()).append("\"").append("\n");
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
            int free = CgBindingPoints.LIGHTMAP_TEXTURE_UNIT;   // the lowest reserved unit
            if (samplers + shader.buffers().size() > free) {
                throw new CgShaderParseException("'Buffers': " + samplers + " samplers and " + shader.buffers().size()
                        + " buffers read as textures need " + (samplers + shader.buffers().size())
                        + " texture units, and this context leaves a material " + free);
            }
        }
        sb.append("// Buffers { }\n").append(shader.bufferStructs());
        for (CgBufferDecl b : shader.buffers()) CgLoweredEmitter.reader(sb, b, useSsbo);
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

    private static void appendV2fVaryingOutputs(StringBuilder sb, List<CgShaderParser.V2fField> fields) {
        if (fields.isEmpty()) return;
        sb.append("out _CgV2fBlock {\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("    ").append(f.type()).append(" ").append(f.name()).append(";\n");
        }
        sb.append("} _cg_v2f;\n");
    }

    private static void appendV2fVaryingInputs(StringBuilder sb, List<CgShaderParser.V2fField> fields) {
        if (fields.isEmpty()) return;
        sb.append("in _CgV2fBlock {\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("    ").append(f.type()).append(" ").append(f.name()).append(";\n");
        }
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
        if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode())) sb.append("#define CG_EMISSIVE_PASS 1\n");
        if (CgParsedPass.LIGHT_MODE_DISTORTION.equals(pass.lightMode())) sb.append("#define CG_DISTORTION_PASS 1\n");
        sb.append("#define CG_EMISSION (vec3(1.0)");
        if (hasProperty(shader, "_EmissionColor", 4)) sb.append(" * _EmissionColor.rgb");
        if (hasProperty(shader, "_EmissionStrength", 1)) sb.append(" * _EmissionStrength");
        if (shader.readsObjectRecord()) sb.append(" * CG_OBJECT_EMISSION");
        if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode())) sb.append(" * CG_SCENE_GLOW");
        sb.append(")\n");
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

    private static void appendFragmentMain(StringBuilder sb, List<CgShaderParser.V2fField> fields,
                                            CgParsedPass pass, CgParsedShader shader, boolean overdraw,
                                            EmissionMerge merge, boolean cover) {
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
            if (merge != EmissionMerge.NONE && merge != EmissionMerge.COVER) {
                // What the Emissive pass would write: unlit, faded by fog as an added colour is.
                sb.append("  _cg_emission = _cg_fragColor;\n");
                if (!READS_EMISSION.matcher(pass.fragmentBody()).find()) sb.append("  _cg_emission.rgb *= CG_EMISSION;\n");
                if (shader.fogged()) sb.append("  _cg_emission.rgb *= 1.0 - cg_FogAmount(cg_FragmentDistance());\n");
                if (merge == EmissionMerge.ADDED) sb.append("  _cg_emission.a = 0.0;\n");
            }
            // Code that reads CG_EMISSION has applied it already.
            if (emissive && !READS_EMISSION.matcher(pass.fragmentBody()).find()) sb.append("  _cg_fragColor.rgb *= CG_EMISSION;\n");
            // A world material is lit and fogged as Minecraft's own things are, unless tagged otherwise. Emitted
            // light is not lit, only faded by the fog.
            boolean forward = CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode());
            if (forward && shader.lit()) sb.append("  _cg_fragColor = cg_Lit(_cg_fragColor);\n");
            if ((forward || emissive) && shader.fogged()) sb.append("  _cg_fragColor = cg_Fog(_cg_fragColor);\n");
            if (merge == EmissionMerge.COVER) sb.append("  _cg_emission = vec4(0.0, 0.0, 0.0, _cg_fragColor.a);\n");
            if (cover) sb.append("  _cg_fragColor = vec4(0.0, 0.0, 0.0, _cg_fragColor.a);\n");
            if (forward && !shader.linearColor() && merge == EmissionMerge.NONE && !cover) appendSceneDecode(sb, pass);
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
