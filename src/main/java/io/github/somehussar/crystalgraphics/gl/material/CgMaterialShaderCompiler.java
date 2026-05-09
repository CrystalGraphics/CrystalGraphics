package io.github.somehussar.crystalgraphics.gl.material;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.material.CgAttachedBuffer;
import io.github.somehussar.crystalgraphics.api.shader.CgPreprocessorException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates complete GLSL vertex and fragment source strings from a {@link CgParsedShader}.
 *
 * <p>This is the bridge between the structural parser ({@link CgShaderParser}) and the
 * GLSL compilation backend ({@link io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory}).
 * It injects the {@code #version} directive, the {@code CG_VERTEX_STAGE} / {@code CG_USE_SSBO}
 * defines, the {@code cg_env.glsl} include, property uniform declarations, the v2f struct
 * and its flat-packed varying declarations, global declarations, the user-authored functions,
 * and the generated {@code void main()} wrappers.</p>
 *
 * <h3>Include resolution</h3>
 * <p>The emitted {@code #include "crystalgraphics:shaders/env/cg_env.glsl"} is an absolute
 * resource-location path. {@link io.github.somehussar.crystalgraphics.api.shader.CgShaderPreprocessor}
 * resolves it via {@code CgIO.loadSource()} without requiring a base-path context. {@code CgMaterial.load()}
 * runs the preprocessor exactly once after this compiler runs and before
 * {@link io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory#fromSource}.</p>
 *
 * <h3>Attribute binding</h3>
 * <p>No explicit {@code layout(location=N)} attributes are emitted — attribute locations
 * are bound before link by passing {@link io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat#SPATIAL}
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

    private static final String ENV_INCLUDE = "crystalgraphics:shaders/env/cg_env.glsl";

    private CgMaterialShaderCompiler() {
        throw new AssertionError("CgMaterialShaderCompiler is not instantiable");
    }

    /**
     * Compiles a {@link CgParsedShader} into GLSL vertex + fragment source strings.
     *
     * <p>The returned strings contain the full shader source including {@code #version},
     * defines, includes, struct declarations, varying declarations, global declarations,
     * user-authored functions, and the generated {@code void main()} wrappers.</p>
     *
     * <p>The returned sources must be run through
     * {@link io.github.somehussar.crystalgraphics.api.shader.CgShaderPreprocessor} to resolve
     * the {@code cg_env.glsl} include before passing to
     * {@link io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory#fromSource}.</p>
     *
     * @param parsed          structural parse result from {@link CgShaderParser#parse(String)}
     * @param shaderBufferPath which GPU path is active; drives {@code #version} selection and
     *                         the {@code CG_USE_SSBO} define
     * @param attachedBuffers  user-defined SSBO/TBO and UBO buffers to inject (may be empty);
     *                         UBO entries are identified via {@link CgAttachedBuffer#isUbo()}
     * @return a {@link CompiledSource} holding the complete vertex and fragment sources
     * @throws IllegalArgumentException if {@code shaderBufferPath} is {@code NONE}
     * @throws CgPreprocessorException  if a TBO-path buffer contains incompatible field types
     */
    public static CompiledSource compile(CgParsedShader parsed,
                                         CgCapabilities.ShaderBufferPath shaderBufferPath,
                                         List<CgAttachedBuffer> attachedBuffers) {
        if (shaderBufferPath == CgCapabilities.ShaderBufferPath.NONE) {
            throw new IllegalArgumentException(
                    "Cannot compile material shader: ShaderBufferPath is NONE (GL 3.3+ required)");
        }

        boolean useSsbo = (shaderBufferPath == CgCapabilities.ShaderBufferPath.SSBO_GL43
                || shaderBufferPath == CgCapabilities.ShaderBufferPath.SSBO_ARB);

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

        List<CgMaterialProperty> properties = parsed.properties();
        List<CgShaderParser.V2fField> v2fFields = CgShaderParser.parseV2fFields(parsed);

        String vertexSource = buildVertexSource(parsed, properties, v2fFields, useSsbo,
                shaderBufferPath, attachedBuffers, requiredExtensions);
        String fragmentSource = buildFragmentSource(parsed, properties, v2fFields, useSsbo,
                shaderBufferPath, attachedBuffers, requiredExtensions);

        return new CompiledSource(vertexSource, fragmentSource);
    }

    // ── Vertex shader builder ─────────────────────────────────────────────────

    /**
     * Generates the complete GLSL vertex shader source.
     *
     * <p>Generation sequence (matches plan T8, steps 1–11):</p>
     * <ol>
     *   <li>{@code #version} directive</li>
     *   <li>{@code #define CG_VERTEX_STAGE 1}</li>
     *   <li>{@code #define CG_USE_SSBO 1} (SSBO path only)</li>
     *   <li>{@code #include "crystalgraphics:shaders/env/cg_env.glsl"}</li>
     *   <li>Property uniform declarations</li>
     *   <li>v2f struct</li>
     *   <li>{@code flat out int cg_InstanceId;}</li>
     *   <li>v2f varying outputs ({@code out <type> _v2f_<name>;})</li>
     *   <li>Global declarations</li>
     *   <li>User vertex function</li>
     *   <li>Generated {@code void main()}</li>
     * </ol>
     */
    private static String buildVertexSource(CgParsedShader parsed,
                                             List<CgMaterialProperty> properties,
                                             List<CgShaderParser.V2fField> v2fFields,
                                             boolean useSsbo,
                                             CgCapabilities.ShaderBufferPath shaderBufferPath,
                                             List<CgAttachedBuffer> attachedBuffers,
                                             Set<String> requiredExtensions) {
        StringBuilder sb = new StringBuilder(1024);

        // Step 1: #version
        sb.append(useSsbo ? "#version 430 core\n" : "#version 330 core\n");

        String[] gd = partitionGlobalDecls(parsed.globalDecls());
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

        // Step 5: Property uniform declarations
        appendPropertyUniforms(sb, properties);

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        // Step 6: v2f struct
        appendV2fStruct(sb, parsed.v2fStructBody());

        // Step 7: compiler-wired flat int varying for instance ID
        sb.append("flat out int cg_InstanceId;\n");

        // Step 8: v2f varying outputs
        appendV2fVaryingOutputs(sb, v2fFields);

        // Step 9: Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // Step 10: User vertex function
        sb.append("// User vertex function\n");
        sb.append("void vertex(out v2f o) {\n")
          .append(parsed.vertexBody()).append("\n")
          .append("}\n");

        // Step 11: Generated main()
        appendVertexMain(sb, v2fFields);

        return sb.toString();
    }

    // ── Fragment shader builder ───────────────────────────────────────────────

    /**
     * Generates the complete GLSL fragment shader source.
     *
     * <p>Generation sequence (matches plan T8, steps 1–10):</p>
     * <ol>
     *   <li>{@code #version} directive</li>
     *   <li>{@code #define CG_USE_SSBO 1} (SSBO path only; no CG_VERTEX_STAGE)</li>
     *   <li>{@code #include "crystalgraphics:shaders/env/cg_env.glsl"}</li>
     *   <li>Property uniform declarations</li>
     *   <li>v2f struct</li>
     *   <li>v2f varying inputs ({@code in <type> _v2f_<name>;})</li>
     *   <li>Global declarations</li>
     *   <li>{@code out vec4 _cg_fragColor;}</li>
     *   <li>User fragment function</li>
     *   <li>Generated {@code void main()}</li>
     * </ol>
     */
    private static String buildFragmentSource(CgParsedShader parsed,
                                               List<CgMaterialProperty> properties,
                                               List<CgShaderParser.V2fField> v2fFields,
                                               boolean useSsbo,
                                               CgCapabilities.ShaderBufferPath shaderBufferPath,
                                               List<CgAttachedBuffer> attachedBuffers,
                                               Set<String> requiredExtensions) {
        StringBuilder sb = new StringBuilder(1024);

        // Step 1: #version
        sb.append(useSsbo ? "#version 430 core\n" : "#version 330 core\n");

        String[] gd = partitionGlobalDecls(parsed.globalDecls());
        String directiveLines = gd[0];
        String codeLines = gd[1];
        if (!directiveLines.isEmpty()) sb.append(directiveLines).append('\n');
        appendAutoRequiredExtensions(sb, requiredExtensions, directiveLines);

        // Step 2: CG_USE_SSBO (SSBO path only; fragment has no CG_VERTEX_STAGE)
        if (useSsbo) {
            sb.append("#define CG_USE_SSBO 1\n");
        }

        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");

        // Step 4: Property uniform declarations (same as vertex; unused uniforms compiled out by driver)
        appendPropertyUniforms(sb, properties);

        appendAttachedBuffers(sb, attachedBuffers, shaderBufferPath);

        // Step 5: v2f struct
        appendV2fStruct(sb, parsed.v2fStructBody());

        // Step 6: v2f varying inputs
        appendV2fVaryingInputs(sb, v2fFields);

        // Step 7: Global declarations (code lines only — directives emitted above)
        appendGlobalDecls(sb, codeLines);

        // Step 8: fragment color output
        sb.append("out vec4 _cg_fragColor;\n");

        // Step 9: User fragment function
        sb.append("// User fragment function\n");
        sb.append("void fragment(in v2f i, out vec4 fragColor) {\n")
          .append(parsed.fragmentBody()).append("\n")
          .append("}\n");

        // Step 10: Generated main()
        appendFragmentMain(sb, v2fFields);

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
     * Injects SSBO/TBO and UBO declarations for all user-attached buffers.
     * No-op when the list is empty. Dispatches on {@link CgAttachedBuffer#isUbo()}.
     * UBO declarations are path-independent (identical on SSBO and TBO paths).
     */
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

    /**
     * Emits one {@code uniform <type> <name>;} line per property.
     * Properties are emitted in both stages; unused uniforms are optimised out by the driver.
     * {@code sampler2D} properties are emitted as {@code uniform sampler2D <name>;}.
     */
    private static void appendPropertyUniforms(StringBuilder sb, List<CgMaterialProperty> properties) {
        if (properties.isEmpty()) return;
        sb.append("// Properties\n");
        for (CgMaterialProperty p : properties) {
            sb.append("uniform ").append(p.getGlslType()).append(' ').append(p.getName()).append(";\n");
        }
    }

    /**
     * Emits the {@code struct v2f} declaration block.
     */
    private static void appendV2fStruct(StringBuilder sb, String v2fStructBody) {
        sb.append("// v2f struct\n");
        sb.append("struct v2f {\n");
        sb.append(v2fStructBody).append("\n");
        sb.append("};\n");
    }

    private static void appendV2fVaryingOutputs(StringBuilder sb, List<CgShaderParser.V2fField> fields) {
        if (fields.isEmpty()) return;
        // Block name must differ from the struct type name 'v2f' — GLSL does not allow an interface
        // block and a struct to share an identifier in the same scope.
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

    /**
     * Emits the optional global declarations section (helper functions, additional uniforms).
     * Empty or blank globalDecls produces no output.
     */
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

    private static void appendFragmentMain(StringBuilder sb, List<CgShaderParser.V2fField> fields) {
        sb.append("void main() {\n");
        sb.append("  v2f _v2f_local;\n");
        for (CgShaderParser.V2fField f : fields) {
            sb.append("  _v2f_local.").append(f.name())
              .append(" = _cg_v2f.").append(f.name()).append(";\n");
        }
        sb.append("  fragment(_v2f_local, _cg_fragColor);\n");
        sb.append("}\n");
    }
}
