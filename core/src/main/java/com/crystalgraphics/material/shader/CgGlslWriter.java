package com.crystalgraphics.material.shader;

import com.crystalgraphics.animation.CgBonePalettes;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.gpu.resource.CgUniformBuffer;
import com.crystalgraphics.material.CgAttachedBuffer;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.shader.glsl.CgGlslBlocks;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.render.draw.CgMaterialArena;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A pass's program as GLSL, for a set of keywords: the version and stage defines, the engine's environment, the
 * material's values, buffers and code, the v2f interface, and the generated {@code main} that runs the pass's bodies
 * and applies what the engine applies to every material (light, fog, the scene's encoding, the media, its targets).
 *
 * <pre>{@code
 * CgShaderPass forward = spec.pass(CgRenderPassVariant.FORWARD);
 * CgGlslProgram base = CgGlslWriter.write(spec, forward, buffers, block, Set.of());
 * CgGlslProgram normals = CgGlslWriter.write(spec, forward, buffers, block, Set.of(CgEngineKeywords.NORMAL_TARGET));
 * CgGlslProgram depth = CgGlslWriter.writeDepth(spec, forward, buffers, block, Set.of());   // an opaque material's
 * }</pre>
 *
 * <ul>
 *   <li>One GLSL for every device: GL 4.5 drivers take it, shaderc compiles it for Vulkan ({@link #VERSION}).</li>
 *   <li>{@code buffers} are the engine buffers its spec reads, attached; {@code block} its material values' uniform
 *       block, null for a material with no number among its properties.</li>
 *   <li>{@code #include}s are left for the preprocessor; nothing here reads a file.</li>
 *   <li>A wrapped {@code main} (the volume and layer targets) is the generated one renamed {@code _cg_main}: every way
 *       out of it, a {@code return} at {@code cg_Clip} too, still writes the extra target.</li>
 * </ul>
 */
public final class CgGlslWriter {

    /**
     * The one GLSL every material compiles to: GL 4.5 drivers take it, and shaderc compiles it to SPIR-V for Vulkan.
     * The vertex stage adds {@code GL_ARB_shader_draw_parameters} (core only from 460).
     */
    public static final String VERSION = "#version 450 core\n";

    /**
     * What a device reading textures by index from its bindless heap defines after {@code #version}, before any code:
     * the runtime arrays {@code cg_env.glsl} declares then need the extension, which no code may precede.
     */
    static final String BINDLESS_EXTENSION = "#if defined(CG_BINDLESS) && defined(VULKAN)\n"
            + "#extension GL_EXT_nonuniform_qualifier : require\n#endif\n";

    /** Elements a table reads through one local array: a longer table branches to its chunk of this many first. */
    static final int TABLE_CHUNK = 16;

    private static final String ENV_INCLUDE = "crystalgraphics:shaders/env/cg_env.glsl";
    private static final String HANDED_LIGHTS_INCLUDE = "crystalgraphics:shaders/env/handed/lights.glsl";
    private static final String HANDED_SUN_INCLUDE = "crystalgraphics:shaders/env/handed/sun.glsl";
    private static final String HANDED_SCENE_INCLUDE = "crystalgraphics:shaders/env/handed/scene.glsl";
    private static final String BONES_ENV = "crystalgraphics:shaders/factories/bones.glsl";

    /** First in each generated main: an arena material's values, read from the draw's slot. */
    private static final String MATERIAL_LOAD = "\n#ifdef CG_MATERIAL_ARENA\n  _cg_material();\n#endif";

    private static final char NL = '\n';

    private CgGlslWriter() {}

    /**
     * {@code pass} of {@code spec} under {@code keywords}.
     *
     * @throws CgPreprocessorException where an attached buffer needs an extension the device lacks
     * @throws IllegalArgumentException where a keyword asks for a variant the spec cannot have ({@link CgEngineKeywords#SKINNED}
     *                                  on a program reading no mesh)
     */
    public static CgGlslProgram write(CgShaderSpec spec, CgShaderPass pass, List<CgAttachedBuffer> buffers,
                                      @Nullable CgUniformBuffer block, Set<String> keywords) {
        Set<String> extensions = new LinkedHashSet<>();
        for (CgAttachedBuffer ab : buffers) {
            for (int i = 0; i < ab.getBuffer().getFormat().getFieldCount(); i++) {
                String ext = ab.getBuffer().getFormat().getField(i).getType().requiredExtension();
                if (ext != null) extensions.add(ext);
            }
        }
        if (extensions.contains("GL_ARB_gpu_shader_int64") && !CgCapabilities.detect().isGpuShaderInt64()) {
            throw new CgPreprocessorException("Buffer format requires extension 'GL_ARB_gpu_shader_int64' (type INT64 or"
                    + " UINT64), but the current GPU/driver does not support it.", "<material>", 0);
        }
        CgVertexFormat format = CgVertexFormat.forShaderType(spec.vertexType());
        if (format == null) {
            throw new IllegalArgumentException("Cannot write: unresolvable vertex type '" + spec.vertexType()
                    + "'. Known types: " + CgVertexFormat.registeredShaderTypes());
        }
        // A sprite or strip variant places quads from CG_VERTEX_ID: a vertex input it declared would be one no mesh provides
        if (sprites(spec, keywords) || strips(spec, keywords)) format = CgVertexFormat.NONE;
        if (keywords.contains(CgEngineKeywords.SKINNED)) {
            if (!format.equals(CgVertexFormat.SPATIAL)) {
                throw new IllegalArgumentException(CgEngineKeywords.SKINNED + " skins a spatial program, not "
                        + spec.vertexType());
            }
            format = CgVertexFormat.SKINNED;
        }
        return new CgGlslProgram(vertex(spec, pass, buffers, block, keywords, extensions, format),
                fragment(spec, pass, buffers, block, keywords, extensions), format);
    }

    /**
     * The depth pass an opaque material leaves to the engine, from its Forward pass: the minimal transform where its
     * vertex is simple, its vertex body where it moves vertices, its fragment where it cuts pixels away.
     */
    public static CgGlslProgram writeDepth(CgShaderSpec spec, CgShaderPass forward, List<CgAttachedBuffer> buffers,
                                           @Nullable CgUniformBuffer block, Set<String> keywords) {
        CgVertexFormat format = CgVertexFormat.forShaderType(spec.vertexType());
        boolean positioned = format != null && format.indexOf("cg_Position") >= 0;
        boolean readsBuffers = false;
        for (CgBufferDecl b : spec.buffers()) readsBuffers |= forward.vertexNames(b.name());
        String vertexBody = positioned && forward.simpleVertex() && !readsBuffers
                && spec.depthMode() != CgShaderSpec.DepthMode.PREPASS
                ? "    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n"
                : forward.vertexBody() + "\n";
        CgShaderPass depth = new CgShaderPass(CgRenderPassVariant.DEPTH, CgRenderPassVariant.DEPTH.lightModeName(),
                CgRenderState.DEFAULT, forward.varyings(), forward.directives(), forward.code(), vertexBody,
                forward.clips() ? forward.fragmentBody() : "", "fragColor", false);
        return write(spec, depth, buffers, block, keywords);
    }

    /** Whether this variant places its vertices with the strip factory, reading no vertex data. */
    private static boolean strips(CgShaderSpec spec, Set<String> keywords) {
        return keywords.contains(CgEngineKeywords.STRIP) && spec.engineBuffers().contains("particle");
    }

    /** Whether this variant places its vertices with the particle sprite factory, reading no vertex data. */
    private static boolean sprites(CgShaderSpec spec, Set<String> keywords) {
        return keywords.contains(CgEngineKeywords.SPRITES) && spec.features().contains(CgEngineKeywords.SPRITES)
                && spec.engineBuffers().contains("particle");
    }

    /** Whether a single-output Forward pass under {@code keywords} draws into {@code target} beside its colour. */
    private static boolean forward(CgShaderPass pass, Set<String> keywords, String target) {
        return keywords.contains(target) && pass.variant() == CgRenderPassVariant.FORWARD;
    }

    // ── The vertex stage ──────────────────────────────────────────────────────────

    private static String vertex(CgShaderSpec spec, CgShaderPass pass, List<CgAttachedBuffer> buffers,
                                 @Nullable CgUniformBuffer block, Set<String> keywords, Set<String> extensions,
                                 CgVertexFormat format) {
        StringBuilder sb = new StringBuilder(8192).append(VERSION).append(BINDLESS_EXTENSION);
        keywordDefines(sb, spec, keywords);
        // Before any code, which a hoisted #include may already be: cg_env.glsl reads a draw's bases from it
        sb.append("#extension GL_ARB_shader_draw_parameters : require\n");
        // Before the material's directives: a guard in an included library sees the stage only if it is defined first
        sb.append("#define CG_VERTEX_STAGE 1\n");
        passDefines(sb, spec, pass);
        directives(sb, pass, extensions);
        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");
        boolean skinned = keywords.contains(CgEngineKeywords.SKINNED);
        sb.append(CgGlslBlocks.vertexDeclarations(format));
        if (!skinned) sb.append(CgGlslBlocks.vertexFetch(format, null, null));
        // A strip variant draws no mesh: its type's attributes stand at their defaults for a port naming one directly
        if (strips(spec, keywords)) {
            CgVertexFormat declared = CgVertexFormat.forShaderType(spec.vertexType());
            for (int i = 0; declared != null && i < declared.getAttributeCount(); i++) {
                CgVertexAttribute a = declared.getAttribute(i);
                sb.append("#define ").append(a.getName()).append(' ').append(a.getGlslType())
                  .append(a.getName().equals("cg_Color") ? "(1.0)\n" : "(0.0)\n");
            }
        }
        properties(sb, spec, buffers, block);
        engineEnvironments(sb, spec);
        if (skinned) skinnedFactory(sb);
        propertyAccessors(sb, spec.properties(), spec.engineBuffers().contains(CgShaderSpec.ROWS));
        materialBuffers(sb, spec);
        handed(sb, spec);
        v2fStruct(sb, pass);
        sb.append("flat out int cg_InstanceId;\n");
        boolean normals = forward(pass, keywords, CgEngineKeywords.NORMAL_TARGET);
        v2fBlock(sb, "out", pass, normals);
        code(sb, pass, CgShaderPass.VERTEX);
        // Its prepass and its colour are two programs over one vertex body: invariance lands them on the same depth.
        // A custom-data writer's redraw is tested against its colour draw's depth, as is a rows effect's layer redraw.
        if (spec.depthMode() == CgShaderSpec.DepthMode.PREPASS || spec.writesCustomData()
                || spec.engineBuffers().contains(CgShaderSpec.ROWS)) {
            sb.append("invariant gl_Position;\n");
        }
        sb.append("// User vertex function\nvoid vertex(out v2f o) {\n").append(pass.vertexBody()).append("\n}\n");
        // The material's values loaded, the vertex fetched, then skinned, before anything reads them
        sb.append("void main() {").append(MATERIAL_LOAD);
        if (format.getAttributeCount() > 0) sb.append("\n  cg_FetchVertex();");
        if (skinned) sb.append("\n  _cg_skin();");
        sb.append("\n  cg_InstanceId = CG_INSTANCE_ID;\n  v2f _v2f_local;\n  vertex(_v2f_local);\n");
        for (CgVarying v : pass.varyings()) {
            sb.append("  _cg_v2f.").append(v.name()).append(" = _v2f_local.").append(v.name()).append(";\n");
        }
        if (normals) {
            String normal = format.indexOf("cg_Normal") >= 0 ? "CG_NORMAL_MATRIX * cg_Normal" : "vec3(0.0)";
            sb.append("  _cg_v2f._cg_normalWs = ").append(normal).append(";\n");
        }
        return sb.append("}\n").toString();
    }

    /**
     * The skinned factory, after the engine buffers: the bone palettes, then {@code cg_Position}, {@code cg_Normal} and
     * {@code cg_Tangent} redefined as the vertex skinned by its bones (Unreal's {@code SkinPosition} and {@code SkinTangents}). The
     * function reading the attributes comes before the macros, so it reads the mesh's; {@code main} calls it first.
     */
    private static void skinnedFactory(StringBuilder sb) {
        attached(sb, List.of(CgAttachedBuffer.of(CgBonePalettes.buffer(), CgBonePalettes.MACRO_NAME)));
        sb.append("#include \"").append(BONES_ENV).append("\"\n");
        // Either width's vertex, as the draw's record says: Unreal's fetched skin weights read their index size likewise
        sb.append(CgGlslBlocks.vertexFetch(CgVertexFormat.SKINNED, CgVertexFormat.SKINNED_WIDE, "CG_BONES_WIDE"));
        sb.append("vec3 _cg_skinPosition;\nvec3 _cg_skinNormal;\nvec4 _cg_skinTangent;\n")
          .append("void _cg_skin() {\n")
          .append("  vec4 r0, r1, r2;\n")
          .append("  cg_BoneBlend(cg_BoneIndices, cg_BoneWeights, r0, r1, r2);\n")
          .append("  vec4 p = vec4(cg_Position, 1.0), n = vec4(cg_Normal, 0.0), t = vec4(cg_Tangent.xyz, 0.0);\n")
          .append("  _cg_skinPosition = vec3(dot(r0, p), dot(r1, p), dot(r2, p));\n")
          .append("  vec3 skinned = vec3(dot(r0, n), dot(r1, n), dot(r2, n));\n")
          .append("  _cg_skinNormal = dot(skinned, skinned) > 0.0 ? normalize(skinned) : cg_Normal;\n")
          .append("  vec3 tangent = vec3(dot(r0, t), dot(r1, t), dot(r2, t));\n")
          .append("  _cg_skinTangent = vec4(dot(tangent, tangent) > 0.0 ? normalize(tangent) : cg_Tangent.xyz, cg_Tangent.w);\n")
          .append("}\n")
          .append("#define cg_Position _cg_skinPosition\n#define cg_Normal _cg_skinNormal\n#define cg_Tangent _cg_skinTangent\n");
    }

    // ── The fragment stage ────────────────────────────────────────────────────────

    private static String fragment(CgShaderSpec spec, CgShaderPass pass, List<CgAttachedBuffer> buffers,
                                   @Nullable CgUniformBuffer block, Set<String> keywords, Set<String> extensions) {
        StringBuilder sb = new StringBuilder(8192).append(VERSION).append(BINDLESS_EXTENSION);
        keywordDefines(sb, spec, keywords);
        sb.append("#define CG_FRAGMENT_STAGE 1\n");
        passDefines(sb, spec, pass);
        clip(sb, spec, pass, keywords);
        sb.append("#define CG_FOG_MODE ").append(fogMode(pass)).append('\n');
        directives(sb, pass, extensions);
        sb.append("#include \"").append(ENV_INCLUDE).append("\"\n");
        properties(sb, spec, buffers, block);
        engineEnvironments(sb, spec);
        propertyAccessors(sb, spec.properties(), spec.engineBuffers().contains(CgShaderSpec.ROWS));
        materialBuffers(sb, spec);
        handed(sb, spec);
        v2fStruct(sb, pass);
        boolean volume = forward(pass, keywords, CgEngineKeywords.VOLUME_TARGET);
        boolean normals = forward(pass, keywords, CgEngineKeywords.NORMAL_TARGET) && !volume;
        v2fBlock(sb, "in", pass, forward(pass, keywords, CgEngineKeywords.NORMAL_TARGET));
        code(sb, pass, CgShaderPass.FRAGMENT);
        boolean layer = forward(pass, keywords, CgEngineKeywords.LAYER_TARGET) && !normals && !volume;
        sb.append("out vec4 _cg_fragColor;\n");
        if (volume) sb.append("layout(location = 1) out vec4 _cg_volumeDepth;\n");
        if (normals) sb.append("layout(location = 1) out vec4 _cg_normalOut;\n");
        if (layer) sb.append("layout(location = 1) out vec4 _cg_layerOut;\n");

        sb.append("// User fragment function\n");
        if (volume) sb.append("float _cg_volumeEye;\n#define CG_VOLUME_DEPTH(eye) _cg_volumeEye = (eye)\n");
        else if (pass.namesVolumeDepth()) sb.append("#define CG_VOLUME_DEPTH(eye)\n");
        if (pass.namesGlow()) sb.append("vec3 _cg_glowRgb = vec3(0.0);\n#define CG_GLOW(rgb) _cg_glowRgb = (rgb)\n");
        if (pass.namesSurfaceNormal()) {
            sb.append("vec3 _cg_surfaceNormal = vec3(0.0, 1.0, 0.0);\n#define CG_SURFACE_NORMAL(n) _cg_surfaceNormal = (n)\n");
        }
        if (pass.namesSurfaceEmissive()) {
            sb.append("vec3 _cg_surfaceEmissive = vec3(0.0);\n#define CG_SURFACE_EMISSIVE(rgb) _cg_surfaceEmissive = (rgb)\n");
        }
        sb.append("void fragment(in v2f i, out vec4 ").append(pass.output()).append(") {\n")
          .append(pass.fragmentBody()).append("\n}\n");

        boolean cover = forward(pass, keywords, CgEngineKeywords.EMISSION_COVER);
        boolean fold = keywords.contains(CgEngineKeywords.SCENE_FOLD) && !cover
                && pass.variant() == CgRenderPassVariant.FORWARD && spec.sceneFolds();
        boolean solid = forward(pass, keywords, CgEngineKeywords.SOLID_COVER) && !normals && !volume && !cover;
        boolean customData = forward(pass, keywords, CgEngineKeywords.CUSTOM_DATA_TARGET) && !normals && !volume;
        // A wrapped main runs the generated one as _cg_main, so every way out of it writes the extra target
        sb.append(volume || layer ? "void _cg_main() {" : "void main() {");
        if (normals) {
            // First, before any return in it: a pixel discarded later writes nothing anyway
            sb.append("\n  vec3 _cg_n = _cg_v2f._cg_normalWs;\n")
              .append("  float _cg_eye = cg_LinearEyeDepth(gl_FragCoord.z);\n")
              .append("  _cg_normalOut = dot(_cg_n, _cg_n) > 1.0e-12 ? vec4(normalize(gl_FrontFacing ? _cg_n : -_cg_n)")
              .append(" * 0.5 + 0.5, _cg_eye) : vec4(0.0, 0.0, 0.0, -_cg_eye);\n");
        }
        sb.append(MATERIAL_LOAD).append('\n');
        mainBody(sb, pass, spec, keywords.contains(CgEngineKeywords.DEBUG_OVERDRAW), cover, fold);
        if (solid) sb.append("  _cg_fragColor.a = 1.0;\n");
        if (customData && !layer) customData(sb);
        sb.append("}\n");
        if (volume) {
            sb.append("void main() {\n  _cg_volumeEye = 0.0;\n  _cg_main();\n")
              .append("  _cg_volumeDepth = vec4(_cg_volumeEye, 0.0, 0.0, _cg_volumeEye > 0.0 ? 1.0 : 0.0);\n}\n");
        }
        if (layer) {
            sb.append("void main() {\n  _cg_main();\n  _cg_layerOut = _cg_fragColor;\n");
            if (customData) customData(sb);
            sb.append("}\n");
        }
        return sb.toString();
    }

    /** Over the colour: x the ID byte and the model and type above it, y the base colour's three bytes, each exact. */
    private static void customData(StringBuilder sb) {
        sb.append("  _cg_fragColor = vec4(round(clamp(_cg_customData.a, 0.0, 1.0) * 255.0)")
          .append(" + 256.0 * float(min(_cg_shadingModel.x, 31u) + 32u * min(_cg_shadingModel.y, 3u)),")
          .append(" dot(round(clamp(_cg_customData.rgb, 0.0, 1.0) * 255.0), vec3(65536.0, 256.0, 1.0)), 0.0, 1.0);\n");
    }

    /** The generated main's body: the fragment run, then what the engine applies to its colour. */
    private static void mainBody(StringBuilder sb, CgShaderPass pass, CgShaderSpec spec, boolean overdraw, boolean cover,
                                 boolean fold) {
        sb.append("  v2f _v2f_local;\n");
        for (CgVarying v : pass.varyings()) {
            sb.append("  _v2f_local.").append(v.name()).append(" = _cg_v2f.").append(v.name()).append(";\n");
        }
        if (spec.readsObjectRecord()) sb.append("  cg_Light = CG_OBJECT_LIGHT;\n");
        boolean emissive = pass.variant() == CgRenderPassVariant.EMISSIVE;
        boolean distortion = pass.variant() == CgRenderPassVariant.DISTORTION;
        boolean forward = pass.variant() == CgRenderPassVariant.FORWARD;
        CgDepthState depth = pass.state().getDepth();
        boolean always = depth != null && depth.test() && depth.compareFunc() == GLC.GL_ALWAYS;
        // The overdraw view's target has no depth either: its test is the pass's own, as a discard
        boolean overdrawTested = overdraw && (depth == null || depth.test() && depth.compareFunc() != GLC.GL_ALWAYS);
        if ((emissive || distortion || cover) && !always || overdrawTested) {
            // The bloom and distortion targets have no depth: this is the pass's test, against the scene's depth.
            // "DepthTest ALWAYS" leaves occlusion to the shader, as a volume drawn on its back faces needs.
            sb.append("  if (cg_LinearEyeDepth(gl_FragCoord.z) > CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION)"
                    + " * CG_EMISSIVE_DEPTH_SLACK + CG_EMISSIVE_DEPTH_BIAS) discard;\n");
        }
        sb.append("  fragment(_v2f_local, _cg_fragColor);\n");
        if (overdraw) {
            // The fragment runs for its discard; what it wrote is replaced by one count
            sb.append("  _cg_fragColor = vec4(1.0, 0.0, 0.0, 0.0);\n");
            return;
        }
        String glow = pass.namesGlow() ? "vec4(_cg_glowRgb, _cg_fragColor.a)" : "_cg_fragColor";
        if (emissive && pass.namesGlow()) sb.append("  _cg_fragColor.rgb = _cg_glowRgb;\n");
        if (fold) {
            // What the Emissive pass would add into the scene: unlit, times the scene's gain, faded by fog
            sb.append("  vec4 _cg_glow = ").append(glow).append(";\n");
            if (!pass.readsEmission()) sb.append("  _cg_glow.rgb *= CG_EMISSION;\n");
            sb.append("  _cg_glow.rgb *= CG_SCENE_GLOW;\n");
            if (spec.fogged()) sb.append("  _cg_glow.rgb *= 1.0 - cg_FogAmount(cg_FragmentDistance());\n");
        }
        // Code that reads CG_EMISSION has applied it already
        if (emissive && !pass.readsEmission()) sb.append("  _cg_fragColor.rgb *= CG_EMISSION;\n");
        // A world material is lit and fogged as the host's own things are; emitted light is not lit, only fogged
        if (forward && spec.lit()) {
            sb.append(spec.surfaceLit() ? "  _cg_fragColor = cg_LitSurface(_cg_fragColor, _cg_surfaceNormal);\n"
                    : "  _cg_fragColor = cg_Lit(_cg_fragColor);\n");
        }
        // Unreal's base pass adds the emissive after the lit colour, unlit (BasePassPixelShader.usf: Color += Emissive)
        if (forward && pass.namesSurfaceEmissive()) sb.append("  _cg_fragColor.rgb += _cg_surfaceEmissive;\n");
        if ((forward || emissive) && spec.fogged()) sb.append("  _cg_fragColor = cg_Fog(_cg_fragColor);\n");
        if (cover) sb.append("  _cg_fragColor = vec4(0.0, 0.0, 0.0, _cg_fragColor.a);\n");
        if (forward && !spec.linearColor() && !cover) {
            sceneDecode(sb, pass);
            // Held at white, as the 8-bit target holds it: its glow carries the light past, or it counts twice
            if (spec.pass(CgRenderPassVariant.EMISSIVE) != null) {
                sb.append("  if (CG_LINEAR_SCENE) _cg_fragColor.rgb = min(_cg_fragColor.rgb, vec3(1.0));\n");
            }
        }
        if (fold && pass.state().getBlend().srcRgb() == GLC.GL_SRC_ALPHA) {
            // Weighed by the alpha the Emissive pass would blend with, not the coverage the decode remapped it to
            sb.append("  if (_cg_fragColor.a > 0.0) _cg_fragColor.rgb += _cg_glow.rgb * (_cg_glow.a / _cg_fragColor.a);\n");
        } else if (fold) {
            sb.append("  _cg_fragColor.rgb += _cg_glow.rgb;\n");
        }
        if ((forward || emissive) && spec.mediated() && !pass.namesVolumeDepth()) mediaDim(sb, pass, emissive);
        // The draw's exposure scales linear light: a Linear colour, an Emissive pass, a merged glow. Never a modulate
        // factor, which is no light (BasePassPixelShader.usf skips PreExposure under MATERIALBLENDING_MODULATE)
        if (!cover && spec.engineBuffers().contains(CgShaderSpec.ROWS)
                && (emissive || forward && spec.linearLight() && fogMode(pass) != 3)) {
            sb.append("  _cg_fragColor.rgb *= _cg_Exposure;\n");
        }
        if (forward && spec.linearLight() && !cover) targetEncode(sb, pass);
    }

    // ── Shared sections ───────────────────────────────────────────────────────────

    /** A material keyword's {@code #define} where it is on, in the order declared; then the engine's own. */
    private static void keywordDefines(StringBuilder sb, CgShaderSpec spec, Set<String> keywords) {
        if (keywords.isEmpty()) return;
        for (String name : spec.features()) if (keywords.contains(name)) sb.append("#define ").append(name).append(" 1\n");
        for (String engine : new String[]{CgEngineKeywords.DEBUG_OVERDRAW, CgEngineKeywords.DEPTH_PREPASS,
                CgEngineKeywords.VOLUME_TARGET, CgEngineKeywords.CUSTOM_DATA_TARGET}) {
            if (keywords.contains(engine)) sb.append("#define ").append(engine).append(" 1\n");
        }
    }

    /**
     * The vertex type an engine buffer's environment places vertices for; {@code CG_EMISSIVE_PASS} and
     * {@code CG_DISTORTION_PASS} so shared code tells the passes apart; and {@code CG_EMISSION}, the glow's multiplier:
     * the draw's {@code CG_OBJECT_EMISSION}, and in an Emissive pass {@code CG_SCENE_GLOW}, the world's gain on glows.
     */
    private static void passDefines(StringBuilder sb, CgShaderSpec spec, CgShaderPass pass) {
        sb.append("#define CG_TYPE_").append(spec.vertexType().toUpperCase(Locale.ROOT)).append(" 1\n");
        if (pass.variant() == CgRenderPassVariant.EMISSIVE) sb.append("#define CG_EMISSIVE_PASS 1\n");
        if (pass.variant() == CgRenderPassVariant.DISTORTION) sb.append("#define CG_DISTORTION_PASS 1\n");
        // cg_env.glsl's cg_LitSurface exists only where it is called: it reads a volume no other material binds
        if (spec.surfaceLit()) sb.append("#define CG_SURFACE_LIT 1\n");
        if (spec.handedLights()) sb.append("#define CG_HANDED_LIGHTS 1\n");
        if (spec.handedSun()) sb.append("#define CG_HANDED_SUN 1\n");
        sb.append("#define CG_EMISSION (vec3(1.0)");
        if (spec.readsObjectRecord()) sb.append(" * CG_OBJECT_EMISSION");
        if (pass.variant() == CgRenderPassVariant.EMISSIVE) sb.append(" * CG_SCENE_GLOW");
        sb.append(")\n");
    }

    /**
     * {@code CG_CLIP_THRESHOLD} and {@code cg_Clip(v)} for this pass, where it can be called: in a depth pass a discard
     * below the threshold and a return at it; in a DepthPrepass material's other passes nothing; else the discard. A
     * material that does not clip gets nothing in every pass: Unreal applies the opacity mask to a Masked material only.
     */
    private static void clip(StringBuilder sb, CgShaderSpec spec, CgShaderPass pass, Set<String> keywords) {
        if (spec.depthMode() == CgShaderSpec.DepthMode.NONE && !pass.namesClip()) return;
        sb.append("#define CG_CLIP_THRESHOLD ").append(spec.maskClip() != null ? spec.maskClip() : "0.5").append('\n');
        if (spec.depthMode() == CgShaderSpec.DepthMode.NONE) {
            sb.append("#define cg_Clip(v)\n");
        } else if (pass.variant() == CgRenderPassVariant.DEPTH || keywords.contains(CgEngineKeywords.DEPTH_PREPASS)) {
            sb.append("#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; return; }\n");
        } else if (spec.depthMode() == CgShaderSpec.DepthMode.PREPASS) {
            sb.append("#define cg_Clip(v)\n");
        } else {
            sb.append("#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; }\n");
        }
    }

    /** How {@code cg_Fog} treats the pass's output: mixed toward the fog (0), premultiplied (1), added (2), modulated (3). */
    private static int fogMode(CgShaderPass pass) {
        if (pass.variant() == CgRenderPassVariant.EMISSIVE) return 2;   // added onto the scene by bloom
        CgBlendState blend = pass.state().getBlend();
        if (blend == null || !blend.enabled()) return 0;
        if (blend.dstRgb() == GLC.GL_ONE) return 2;
        if (blend.srcRgb() == GLC.GL_DST_COLOR && blend.dstRgb() == GLC.GL_ZERO) return 3;
        return blend.srcRgb() == GLC.GL_ONE ? 1 : 0;
    }

    /** The pass's hoisted directives, and an extension an attached buffer needs that they do not enable. */
    private static void directives(StringBuilder sb, CgShaderPass pass, Set<String> extensions) {
        StringBuilder lines = new StringBuilder();
        for (String d : pass.directives()) lines.append(d).append('\n');
        sb.append(lines);
        for (String ext : extensions) {
            if (lines.indexOf(ext) < 0) sb.append("#extension ").append(ext).append(" : enable\n");
        }
    }

    /** The samplers, the material block and the attached buffers, in that order. */
    private static void properties(StringBuilder sb, CgShaderSpec spec, List<CgAttachedBuffer> buffers,
                                   @Nullable CgUniformBuffer block) {
        samplers(sb, spec.properties());
        materialBlock(sb, spec.properties(), block, spec.valuesInArena());
        attached(sb, buffers);
    }

    /**
     * Each sampler: bound where the device binds, or its handle in the material block read through cg_env's
     * {@code CG_TEXTURE_<type>} under the name the shader samples where it reads textures by index (Unreal's
     * {@code UB_RESOURCE_MEMBER_SRV}).
     */
    private static void samplers(StringBuilder sb, List<CgMaterialProperty> properties) {
        StringBuilder reads = new StringBuilder();
        for (CgMaterialProperty p : properties) {
            if (!p.getType().isSampler()) continue;
            reads.append("#define ").append(p.getName()).append(" CG_TEXTURE_").append(p.getGlslType()).append("(_cgt")
                 .append(p.getName()).append(")\n");
        }
        if (reads.length() == 0) return;
        sb.append("// Sampler properties\n#ifdef CG_BINDLESS\n").append(reads).append("#else\n");
        int textures = 0;
        for (CgMaterialProperty p : properties) {
            if (!p.getType().isSampler()) continue;
            sb.append(CgBindingModel.texture(textures++).glsl()).append("uniform ").append(p.getGlslType()).append(' ')
              .append(p.getName()).append(";\n");
        }
        sb.append("#endif\n");
    }

    /**
     * {@code CgMaterialBlock}: the material's values, and where the device reads textures by index each texture's
     * handle after them, so a material is one block (Unreal's material uniform buffer). Where its values live in its
     * shader's arena, a slot of {@link CgMaterialArena} instead, but in the bindless form.
     */
    private static void materialBlock(StringBuilder sb, List<CgMaterialProperty> properties, @Nullable CgUniformBuffer values,
                                      boolean arena) {
        StringBuilder handles = new StringBuilder();
        for (CgMaterialProperty p : properties) {
            if (p.getType().isSampler()) handles.append("    uvec2 _cgt").append(p.getName()).append(";\n");
        }
        if (values != null) {
            String block = CgGlslBlocks.uniformBlock(values.getFormat(), values.getName(), CgBindingModel.MATERIAL_BLOCK);
            if (handles.length() > 0) {
                int close = block.lastIndexOf("};");
                block = block.substring(0, close) + "#ifdef CG_BINDLESS\n" + handles + "#endif\n" + block.substring(close);
            }
            if (arena) {
                int floats = values.getFormat().getFloatCount();
                block = "#ifdef CG_BINDLESS\n" + block + "#else\n"
                        + CgGlslBlocks.arena(values.getFormat(), CgBindingModel.MATERIAL_BLOCK, CgMaterialArena.stride(floats))
                        + "#endif\n";
            }
            sb.append(block).append('\n');
        } else if (handles.length() > 0) {
            sb.append("#ifdef CG_BINDLESS\n").append(CgBindingModel.MATERIAL_BLOCK.glsl())
              .append("layout(std140) uniform CgMaterialBlock {\n").append(handles).append("};\n#endif\n");
        }
    }

    private static void attached(StringBuilder sb, List<CgAttachedBuffer> buffers) {
        int user = 0;
        for (CgAttachedBuffer ab : buffers) {
            CgBindingModel.Slot slot = CgBindingModel.attached(ab, user);
            if (slot.set() == CgBindingModel.MATERIAL) user++;
            if (ab.isUbo()) {
                sb.append(CgGlslBlocks.uniformBlock(ab.getBuffer().getFormat(), ab.getBuffer().getName(), slot)).append('\n');
            } else {
                sb.append(CgGlslBlocks.storageBlock(ab.getBuffer().getFormat(), ab.getBuffer().getName(), ab.getMacroName(),
                        true, slot)).append('\n');
            }
        }
    }

    /**
     * Each engine buffer's own GLSL environment, after its declaration (its macros read the struct), as an include so
     * {@code #pragma once} collapses one both stages name.
     */
    private static void engineEnvironments(StringBuilder sb, CgShaderSpec spec) {
        for (String token : spec.engineBuffers()) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            if (provider == null) continue;
            if (provider.envPath() != null) sb.append("#include \"").append(provider.envPath()).append("\"\n");
            if (provider.generated() != null) sb.append(provider.generated().get());
        }
    }

    /**
     * What a property read through its own name needs: an array's {@code _Name(i)}, and where its numbers come from a
     * draw's row each number's macro choosing the row ({@code _cg_at<name>} at or above 0) over the material's value.
     *
     * <pre>{@code
     * #define _Radius (_cg_at_Radius < 0 ? _cgm_Radius : CG_ROW(CG_PROPERTY_ROW + _cg_at_Radius).x)
     * vec4 _cg_arr_Bands(int i) { ... }
     * #define _Bands(i) _cg_arr_Bands(i)
     * }</pre>
     */
    static void propertyAccessors(StringBuilder sb, List<CgMaterialProperty> properties, boolean rows) {
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
                tableRead(sb, p, n);
                sb.append('}').append(NL).append("#define ").append(name).append("(i) _cg_arr").append(name).append("(i)").append(NL);
            } else if (rows && p.isRows()) {
                String swizzle = p.getType().getComponents() == 1 ? ".x" : p.getType().getComponents() == 2 ? ".xy" : "";
                sb.append("#define ").append(name).append(" (").append(slot).append(" < 0 ? ").append(p.blockName())
                  .append(" : CG_ROW(CG_PROPERTY_ROW + ").append(slot).append(")").append(swizzle).append(')').append(NL);
            }
        }
    }

    /**
     * Element {@code k} of array {@code p}'s {@code n} block fields. A table past {@link #TABLE_CHUNK} reads the chunk
     * holding {@code k}: a local array of every field would be n vec4s of registers a read.
     */
    static void tableRead(StringBuilder sb, CgMaterialProperty p, int n) {
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

    /** The material's {@code Buffers { }}: the structs they hold, then each read through its storage block. */
    private static void materialBuffers(StringBuilder sb, CgShaderSpec spec) {
        if (spec.buffers().isEmpty()) return;
        sb.append("// Buffers { }\n").append(spec.bufferStructs());
        for (int i = 0; i < spec.buffers().size(); i++) sb.append(CgGlslBlocks.reader(spec.buffers().get(i), i));
    }

    /** What the world hands a program carrying its own lighting, after the buffers and properties it reads through. */
    private static void handed(StringBuilder sb, CgShaderSpec spec) {
        if (spec.handedLights()) sb.append("#include \"").append(HANDED_LIGHTS_INCLUDE).append("\"\n");
        if (spec.handedSun()) sb.append("#include \"").append(HANDED_SUN_INCLUDE).append("\"\n");
        if (spec.handedScene()) sb.append("#include \"").append(HANDED_SCENE_INCLUDE).append("\"\n");
    }

    /** {@code struct v2f}: GLSL has no empty struct, so a pass passing nothing declares one member, never a varying. */
    private static void v2fStruct(StringBuilder sb, CgShaderPass pass) {
        sb.append("// v2f struct\nstruct v2f {\n");
        if (pass.varyings().isEmpty()) sb.append("    int cg_Unused;\n");
        for (CgVarying v : pass.varyings()) sb.append("    ").append(v.type()).append(' ').append(v.name()).append(";\n");
        sb.append("};\n");
    }

    /** The v2f interface block, each field with its interpolation; with {@code normals}, the normal target's last. */
    private static void v2fBlock(StringBuilder sb, String direction, CgShaderPass pass, boolean normals) {
        if (pass.varyings().isEmpty() && !normals) return;
        sb.append(direction).append(" _CgV2fBlock {\n");
        for (CgVarying v : pass.varyings()) {
            sb.append("    ");
            if (!v.interpolation().isEmpty()) sb.append(v.interpolation()).append(' ');
            sb.append(v.type()).append(' ').append(v.name()).append(";\n");
        }
        if (normals) sb.append("    vec3 _cg_normalWs;\n");
        sb.append("} _cg_v2f;\n");
    }

    /** The pass's code that compiles into {@code stage}. */
    private static void code(StringBuilder sb, CgShaderPass pass, int stage) {
        boolean any = false;
        for (CgShaderPass.Code c : pass.code()) {
            if ((c.stages() & stage) == 0) continue;
            if (!any) sb.append("// Global declarations\n");
            any = true;
            sb.append(c.text()).append('\n');
        }
    }

    /**
     * Decodes a Forward colour, authored as sRGB, where the pass draws into the linear scene. A blend over what is
     * behind also has its coverage remapped ({@code cg_SceneCoverage}); a premultiplied colour is decoded unpremultiplied.
     */
    private static void sceneDecode(StringBuilder sb, CgShaderPass pass) {
        CgBlendState blend = pass.state().getBlend();
        boolean over = blend != null && blend.enabled() && blend.dstRgb() == GLC.GL_ONE_MINUS_SRC_ALPHA;
        if (over && blend.srcRgb() == GLC.GL_ONE) {
            sb.append("  if (CG_LINEAR_SCENE) {\n")
              .append("    float _cg_cover = cg_SceneCoverage(_cg_fragColor.a);\n")
              .append("    _cg_fragColor.rgb = _cg_fragColor.a > 0.0")
              .append(" ? cg_SceneDecode(_cg_fragColor.rgb / _cg_fragColor.a) * _cg_cover")
              .append(" : cg_SceneDecode(_cg_fragColor.rgb);\n")
              .append("    _cg_fragColor.a = _cg_cover;\n  }\n");
        } else if (over && blend.srcRgb() == GLC.GL_SRC_ALPHA) {
            sb.append("  if (CG_LINEAR_SCENE) _cg_fragColor = vec4(cg_SceneDecode(_cg_fragColor.rgb),")
              .append(" cg_SceneCoverage(_cg_fragColor.a));\n");
        } else {
            sb.append("  if (CG_LINEAR_SCENE) _cg_fragColor.rgb = cg_SceneDecode(_cg_fragColor.rgb);\n");
        }
    }

    /**
     * Encodes a linear-light Forward colour where the pass draws onto the host's own sRGB-encoded target
     * ({@code CG_ENCODED_TARGET}), alpha untouched; a premultiplied colour is encoded unpremultiplied.
     */
    private static void targetEncode(StringBuilder sb, CgShaderPass pass) {
        CgBlendState blend = pass.state().getBlend();
        boolean premultiplied = blend != null && blend.enabled() && blend.srcRgb() == GLC.GL_ONE
                && blend.dstRgb() == GLC.GL_ONE_MINUS_SRC_ALPHA;
        if (premultiplied) {
            sb.append("  if (CG_ENCODED_TARGET) _cg_fragColor.rgb = _cg_fragColor.a > 0.0")
              .append(" ? cg_SceneEncode(_cg_fragColor.rgb / _cg_fragColor.a) * _cg_fragColor.a")
              .append(" : cg_SceneEncode(_cg_fragColor.rgb);\n");
        } else {
            sb.append("  if (CG_ENCODED_TARGET) _cg_fragColor.rgb = cg_SceneEncode(_cg_fragColor.rgb);\n");
        }
    }

    /**
     * Dims the pass's output by what gets through the world's media between the eye and the fragment, as its blend
     * takes it: light added or premultiplied whole, a colour blended by its alpha through that alpha.
     */
    private static void mediaDim(StringBuilder sb, CgShaderPass pass, boolean emissive) {
        CgBlendState blend = pass.state().getBlend();
        boolean whole = emissive || blend != null && blend.enabled() && blend.srcRgb() == GLC.GL_ONE;
        boolean alpha = !emissive && blend != null && blend.enabled() && blend.srcRgb() == GLC.GL_SRC_ALPHA;
        if (!whole && !alpha) return;
        sb.append("  float _cg_media = cg_MediaTransmit(gl_FragCoord.xy / CG_RESOLUTION, cg_LinearEyeDepth(gl_FragCoord.z));\n");
        sb.append(whole ? "  _cg_fragColor *= _cg_media;\n" : "  _cg_fragColor.a *= _cg_media;\n");
    }
}
