package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * are bound before link by passing {@link CgVertexFormat#SPATIAL}
 * to {@code CgShaderFactory.fromSource(vert, frag, format)}.</p>
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
     declarations, and the {@code main()} wrapper.*/
    @Desugar
    public record CompiledSource(String vertexSource, String fragmentSource) {
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

        String vertexSource = buildVertexSource(shader, pass, properties, v2fFields, useSsbo,
                path, attachedBuffers, requiredExtensions, matPropsEntry, config);
        String fragmentSource = buildFragmentSource(shader, pass, properties, v2fFields, useSsbo,
                path, attachedBuffers, requiredExtensions, matPropsEntry, config);

        return new CompiledSource(vertexSource, fragmentSource);
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
     * <p><strong>UBO contract</strong>: {@code cg_ShadowViewProjMatrix},
     * {@code cg_LightDirection}, and {@code cg_ShadowParams} are declared in the
     * {@code CgFrameBlock} UBO (injected via {@code cg_env.glsl}). The generated GLSL
     * references them as plain identifiers — never as standalone {@code uniform}
     * declarations, which would cause GLSL redeclaration conflicts.</p>
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
        if (isSimpleVertex(forwardPass)) {
            // Simple path: minimal position-only transform using the frame UBO shadow matrix
            shadowVertexBody =
                    "    // Auto-generated shadow caster — minimal position transform\n"
                    + "    gl_Position = cg_ShadowViewProjMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);\n"
                    + "    // Depth bias to prevent shadow acne (cg_ShadowParams.z)\n"
                    + "    gl_Position.z += cg_ShadowParams.z * gl_Position.w;\n"
                    + "    // Pancaking: clamp to avoid near-plane clipping on thin objects\n"
                    + "    gl_Position.z = max(gl_Position.z, -gl_Position.w);\n";
        } else {
            // Complex path: run forward vertex body, then override gl_Position with shadow transform
            shadowVertexBody =
                    "    // Auto-generated shadow caster — complex vertex (has animation or custom attributes)\n"
                    + forwardPass.vertexBody() + "\n"
                    + "    // Override gl_Position with light-space transform\n"
                    + "    gl_Position = cg_ShadowViewProjMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);\n"
                    + "    // Depth bias and pancaking (cg_ShadowParams.z)\n"
                    + "    gl_Position.z += cg_ShadowParams.z * gl_Position.w;\n"
                    + "    gl_Position.z = max(gl_Position.z, -gl_Position.w);\n";
        }

        // Fragment: depth-only — empty body, rasterizer writes depth automatically
        final String shadowFragmentBody =
                "    // Depth-only shadow pass — rasterizer writes depth automatically.\n";

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

        return compile(shader, shadowPass, attachedBuffers, matPropsUbo, CompileConfig.DEFAULT);
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
        if (isSimpleVertex(forwardPass)) {
            depthVertexBody =
                    "    // Auto-generated depth prepass — minimal position transform\n"
                    + "    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n";
        } else {
            depthVertexBody =
                    "    // Auto-generated depth prepass — preserves vertex animation\n"
                    + forwardPass.vertexBody() + "\n";
        }

        final String depthFragmentBody;
        if (forwardPass.fragmentBody().contains("discard")) {
            depthFragmentBody = forwardPass.fragmentBody();
        } else {
            depthFragmentBody = "    // Depth-only prepass — rasterizer writes depth automatically.\n";
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

        return compile(shader, depthPass, attachedBuffers, matPropsUbo, CompileConfig.DEFAULT);
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
                                             CompileConfig config) {
        StringBuilder sb = new StringBuilder(1024);

        // Step 1: #version
        sb.append(useSsbo ? "#version 430 core\n" : "#version 330 core\n");

        // Keyword #define injection — in featureNames declaration order, before user #-lines
        appendKeywordDefines(sb, shader.featureNames(), config.activeKeywords());

        String[] gd = partitionGlobalDecls(pass.globalDecls());
        String directiveLines = gd[0];
        String codeLines = gd[1];
        if (!directiveLines.isEmpty()) sb.append(directiveLines).append('\n');
        appendAutoRequiredExtensions(sb, requiredExtensions, directiveLines);

        // Step 2: CG_VERTEX_STAGE define
        sb.append("#define CG_VERTEX_STAGE 1\n");

        if (useSsbo) {
            sb.append("#define CG_USE_SSBO 1\n");
        }

        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");

        // Property uniform declarations (sampler types only; non-sampler go into CgMaterialBlock UBO)
        appendPropertyUniforms(sb, properties);

        // Material properties UBO (CgMaterialBlock) — emitted before user-attached buffers
        if (matPropsEntry != null) {
            sb.append(CgBufferGlslEmitter.emitUbo(matPropsEntry)).append('\n');
        }

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        // v2f struct
        appendV2fStruct(sb, pass.v2fStructBody());

        // Compiler-wired flat int varying for instance ID
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
        sb.append(useSsbo ? "#version 430 core\n" : "#version 330 core\n");

        // Keyword #define injection — in featureNames declaration order, before user #-lines
        appendKeywordDefines(sb, shader.featureNames(), config.activeKeywords());

        String[] gd = partitionGlobalDecls(pass.globalDecls());
        String directiveLines = gd[0];
        String codeLines = gd[1];
        if (!directiveLines.isEmpty()) sb.append(directiveLines).append('\n');
        appendAutoRequiredExtensions(sb, requiredExtensions, directiveLines);

        // CG_USE_SSBO (SSBO path only; fragment has no CG_VERTEX_STAGE)
        if (useSsbo) {
            sb.append("#define CG_USE_SSBO 1\n");
        }

        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");

        // Property uniform declarations (sampler types only; non-sampler go into CgMaterialBlock UBO)
        appendPropertyUniforms(sb, properties);

        // Material properties UBO (CgMaterialBlock) — emitted before user-attached buffers
        if (matPropsEntry != null) {
            sb.append(CgBufferGlslEmitter.emitUbo(matPropsEntry)).append('\n');
        }

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        // v2f struct
        appendV2fStruct(sb, pass.v2fStructBody());

        // v2f varying inputs
        appendV2fVaryingInputs(sb, v2fFields);

        // Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // Fragment output declarations (single-output or MRT)
        appendFragmentOutputDeclarations(sb, pass);

        // User fragment function
        sb.append("// User fragment function\n");
        appendFragmentUserFunction(sb, pass);

        // Generated main()
        appendFragmentMain(sb, v2fFields, pass);

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

    private static void appendKeywordDefines(StringBuilder sb, List<String> featureNames,
                                              Set<String> activeKeywords) {
        if (activeKeywords.isEmpty()) return;
        for (String name : featureNames) {
            if (activeKeywords.contains(name)) {
                sb.append("#define ").append(name).append(" 1\n");
            }
        }
    }

    private static void appendAttachedBuffers(StringBuilder sb,
                                               List<CgAttachedBuffer> attachedBuffers,
                                               CgCapabilities.ShaderBufferPath path) {
        boolean useSsbo = (path == CgCapabilities.ShaderBufferPath.SSBO_GL43
                || path == CgCapabilities.ShaderBufferPath.SSBO_ARB);
        for (CgAttachedBuffer ab : attachedBuffers) {
            if (ab.isUbo()) {
                sb.append(CgBufferGlslEmitter.emitUbo(ab.getBuffer().getFormat(), ab.getBuffer().getName())).append('\n');
            } else {
                String block = useSsbo
                        ? CgBufferGlslEmitter.emitSsbo(ab)
                        : CgBufferGlslEmitter.emitTbo(ab);
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
        sb.append("  cg_InstanceId = gl_InstanceID;\n");
        sb.append("  v2f _v2f_local;\n");
        sb.append("  vertex(_v2f_local);\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("  _cg_v2f.").append(f.name())
              .append(" = _v2f_local.").append(f.name()).append(";\n");
        }
        sb.append("}\n");
    }

    private static void appendFragmentOutputDeclarations(StringBuilder sb, CgParsedPass pass) {
        if (!pass.fragOutput().isMrt()) {
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

    private static void appendFragmentMain(StringBuilder sb, List<CgShaderParser.V2fField> fields,
                                            CgParsedPass pass) {
        sb.append("void main() {\n");
        sb.append("  v2f _v2f_local;\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("  _v2f_local.").append(f.name())
              .append(" = _cg_v2f.").append(f.name()).append(";\n");
        }
        if (!pass.fragOutput().isMrt()) {
            sb.append("  fragment(_v2f_local, _cg_fragColor);\n");
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
