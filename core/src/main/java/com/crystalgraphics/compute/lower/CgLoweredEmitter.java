package com.crystalgraphics.compute.lower;

import com.crystalgraphics.compute.emit.CgGlslBuiltins;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.lower.CgLowering.Kind;
import com.crystalgraphics.compute.lower.CgLowering.Op;
import com.crystalgraphics.compute.lower.CgLowering.Pass;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgElementField;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.compute.source.CgSourcePart;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.material.parse.CgGlslEmitter;
import com.crystalgraphics.platform.gl.CgGL;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * One pass of a kernel lowered below compute (gpu-compute C5) as GLSL 3.30 stages: the kernel's own code, unchanged,
 * run as a fragment stage over the texels of the buffers it writes, a geometry stage emitting points, or a fragment
 * stage over an image, with its accessors rewritten for that stage. {@code #include} lines are left for
 * {@code CgShaderPreprocessor}.
 *
 * <pre>{@code
 * for (CgLowering.Pass pass : CgLowering.passes(source, kernel)) {
 *     CgLoweredEmitter.Stages s = CgLoweredEmitter.emit(source, kernel, keywords, pass, CgLoweredTarget.current());
 *     CgShaderProgram program = CgShaderProgram.compileCapture(s.vertex(), s.geometry(), s.fragment(), s.varyings());
 * }
 * }</pre>
 *
 * <p>A buffer is read as a {@code usamplerBuffer} of its element's 32-bit words: {@link #tbo} holding the buffer whole,
 * {@link #base} the view's first element, {@link #length} its elements. An element of a 4-, 8- or 16-byte scalar or
 * vector is one texel of one, two or four words; a struct of 16-byte fields is a texel per field. A written element is
 * the same words, in {@code uint}, {@code uvec2} or {@code uvec4} outputs: a fragment per texel of an output pass's
 * render target, laid out as the buffer is and read back into it, or a captured vertex per appended element. The buffer
 * holds exactly what compute would have written.</p>
 */
public final class CgLoweredEmitter {

    /** The stages of one pass, and the outputs transform feedback captures (null for none). */
    public record Stages(String vertex, @Nullable String geometry, @Nullable String fragment,
                         @Nullable String[] varyings) {
    }

    public static final String LAYER = "_cg_layer";
    /** A scatter pass's target width and height. */
    public static final String TEXELS_X = "_cg_texels_x", TEXELS_Y = "_cg_texels_y";
    public static final String ARGS = "_cg_DispatchArgs";
    public static final String ARGS_AT = "_cg_DispatchArgsAt";

    private static final String ENV = "crystalgraphics:shaders/env/";

    private CgLoweredEmitter() {}

    public static String tbo(CgBufferDecl b) { return "_cg_tbo_" + b.name(); }

    public static String base(CgBufferDecl b) { return "_cg_base_" + b.name(); }

    public static String length(CgBufferDecl b) { return "_cg_len_" + b.name(); }

    /** An append buffer's count, read as a {@code usamplerBuffer} of words. */
    public static String counterTbo(CgBufferDecl b) { return "_cg_ctbo_" + b.name(); }

    /** The count's word in {@link #counterTbo}. */
    public static String counterAt(CgBufferDecl b) { return "_cg_cat_" + b.name(); }

    public static String sampler(CgImageDecl image) { return "_cg_smp_" + image.name(); }

    public static String level(CgImageDecl image) { return "_cg_level_" + image.name(); }

    // ── Element words ─────────────────────────────────────────────────────────

    /** Words in one buffer-texture texel of {@code b}: 1, 2 or 4. */
    public static int texelWords(CgBufferDecl b) {
        return b.struct() ? 4 : b.stride() / 4;
    }

    /** Texels one element of {@code b} takes. */
    public static int texelsPerElement(CgBufferDecl b) {
        return b.struct() ? b.fields().size() : 1;
    }

    /** {@code GL_R32UI}, {@code GL_RG32UI} or {@code GL_RGBA32UI}: the buffer texture's format. */
    public static int texelFormat(CgBufferDecl b) {
        int words = texelWords(b);
        return words == 1 ? CgGL.GL_R32UI : words == 2 ? CgGL.GL_RG32UI : CgGL.GL_RGBA32UI;
    }

    /** The captured outputs one element appended to {@code b} is written as. */
    public static String[] captures(CgBufferDecl b) {
        String[] names = new String[texelsPerElement(b)];
        for (int i = 0; i < names.length; i++) names[i] = "_cg_c" + i;
        return names;
    }

    static String uintType(int words) {
        return words == 1 ? "uint" : words == 2 ? "uvec2" : "uvec4";
    }

    /** A value of {@code type} from its bits. */
    static String unpack(String type, String bits) {
        if (type.equals("float") || type.startsWith("vec")) return "uintBitsToFloat(" + bits + ")";
        if (type.equals("int") || type.startsWith("ivec")) return type + "(" + bits + ")";
        return bits;
    }

    /** A value of {@code type} as bits. */
    static String pack(String type, String value) {
        if (type.equals("float") || type.startsWith("vec")) return "floatBitsToUint(" + value + ")";
        if (type.equals("int")) return "uint(" + value + ")";
        if (type.startsWith("ivec")) return "u" + type + "(" + value + ")";
        return value;
    }

    // ── Emission ──────────────────────────────────────────────────────────────

    public static Stages emit(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, Pass pass,
                              CgLoweredTarget target) {
        return switch (pass.kind()) {
            case OUTPUT -> new Stages(FULLSCREEN_VERTEX, null, kernelStage(source, kernel, keywords, pass, target), null);
            case APPEND -> new Stages(PASS_VERTEX, kernelStage(source, kernel, keywords, pass, target), COUNT_FRAGMENT,
                    captures(pass.buffer()));
            case SCATTER -> new Stages(PASS_VERTEX, kernelStage(source, kernel, keywords, pass, target),
                    valueFragment(pass.floats()), null);
            case IMAGE -> new Stages(FULLSCREEN_VERTEX, null, kernelStage(source, kernel, keywords, pass, target), null);
        };
    }

    /** One vertex per element, passing its index to the geometry stage. */
    static final String PASS_VERTEX = "#version 330 core\nflat out int _cg_vertex;\n"
            + "void main() { _cg_vertex = gl_VertexID; gl_Position = vec4(0.0); }\n";

    /** Three vertices covering the target. */
    static final String FULLSCREEN_VERTEX = "#version 330 core\nvoid main() {\n"
            + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
            + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";

    /** Each appended element adds one to the count texel. */
    static final String COUNT_FRAGMENT = "#version 330 core\nout vec4 _cg_count;\nvoid main() { _cg_count = vec4(1.0); }\n";

    static String valueFragment(boolean floats) {
        String type = floats ? "vec4" : "uvec4";
        return "#version 330 core\nflat in " + type + " _cg_value;\nout " + type + " _cg_o;\n"
                + "void main() { _cg_o = _cg_value; }\n";
    }

    private static String kernelStage(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, Pass pass,
                                      CgLoweredTarget target) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(target.version());
        for (String e : source.extensions()) sb.append(e).append('\n');
        for (String feature : source.features()) {
            if (keywords.contains(feature)) sb.append("#define ").append(feature).append(" 1\n");
        }
        int group = kernel.groupSize();
        sb.append("#define CG_COMPUTE_STAGE 1\n#define CG_LOWERED 1\n");
        if (target.storageBlocks()) sb.append("#define CG_USE_SSBO 1\n");
        sb.append("#define CG_LOCAL_SIZE_X ").append(kernel.sizeX()).append('\n');
        sb.append("#define CG_LOCAL_SIZE_Y ").append(kernel.sizeY()).append('\n');
        sb.append("#define CG_LOCAL_SIZE_Z ").append(kernel.sizeZ()).append('\n');
        sb.append("#define CG_GROUP_SIZE ").append(group).append('\n');
        sb.append("#define CG_GROUP_POW2 ").append(Integer.bitCount(group) == 1 ? group : Integer.highestOneBit(group) << 1).append('\n');
        sb.append("#define CG_DIMENSIONS ").append(kernel.dimensions()).append('\n');
        sb.append("#define CG_KERNEL_").append(kernel.name()).append(" 1\n");
        include(sb, ENV + "cg_env.glsl");
        include(sb, ENV + "compute/lowered.glsl");
        prologue(sb, pass, target);
        CgKernelEmitter.properties(sb, source);
        for (String token : source.engineBuffers()) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            CgShaderBuffer buffer = provider.buffer().get();
            sb.append(target.storageBlocks()
                    ? CgGlslEmitter.emitSsbo(buffer.getFormat(), buffer.getName(), provider.macroName(), true)
                    : CgGlslEmitter.emitTbo(buffer.getFormat(), buffer.getName(), provider.macroName()));
            if (provider.envPath() != null) include(sb, provider.envPath());
        }
        sb.append(CgGlslBuiltins.polyfills(kernel.builtins(), target.glsl()));
        for (CgSourcePart part : source.parts()) {
            if (part instanceof CgSourcePart.Text t) sb.append(CgKernelEmitter.code(t.text(), kernel, target.glsl()));
            else if (part instanceof CgSourcePart.Function f) {
                if (kernel.functions().contains(f.name())) sb.append(CgKernelEmitter.code(f.text(), kernel, target.glsl()));
            }
            else if (part instanceof CgSourcePart.Buffers) buffers(sb, source, kernel, pass);
            else if (part instanceof CgSourcePart.Images) images(sb, source, kernel, pass);
        }
        main(sb, kernel, pass);
        return sb.toString();
    }

    private static void include(StringBuilder sb, String path) {
        sb.append("#include \"").append(path).append("\"\n");
    }

    /** What a stage declares before the kernel's code: its primitive layout, outputs and emitters. */
    private static void prologue(StringBuilder sb, Pass pass, CgLoweredTarget target) {
        switch (pass.kind()) {
            case OUTPUT -> {
                sb.append("uniform int ").append(TEXELS_X).append(";\n");
                for (int n = 0; n < pass.buffers().size(); n++) {
                    sb.append("layout(location = ").append(n).append(") out ")
                      .append(uintType(texelWords(pass.buffers().get(n)))).append(" _cg_o").append(n).append(";\n");
                }
            }
            case APPEND -> {
                int vertices = target.geometryVertices(pass.buffer().stride() / 4);
                sb.append("layout(points) in;\nlayout(points, max_vertices = ").append(vertices).append(") out;\n");
                sb.append("#define CG_APPENDS_PER_ELEMENT ").append(vertices).append('\n');
                sb.append("flat in int _cg_vertex[];\nint _cg_emitted = 0;\n");
                outputs(sb, pass.buffer());
            }
            case SCATTER -> {
                String type = pass.floats() ? "vec4" : "uvec4";
                int vertices = target.geometryVertices(4);
                sb.append("layout(points) in;\nlayout(points, max_vertices = ").append(vertices).append(") out;\n");
                sb.append("flat in int _cg_vertex[];\nflat out ").append(type).append(" _cg_value;\n");
                sb.append("uniform int ").append(TEXELS_X).append(";\nuniform int ").append(TEXELS_Y)
                  .append(";\nint _cg_emitted = 0;\n");
                sb.append("void _cg_point(int t, ").append(type).append(" v) {\n")
                  .append("    if (_cg_emitted >= ").append(vertices).append(") return;\n")
                  .append("    _cg_emitted++;\n")
                  .append("    vec2 texel = vec2(float(t % ").append(TEXELS_X).append("), float(t / ").append(TEXELS_X).append(")) + 0.5;\n")
                  .append("    gl_Position = vec4(texel / vec2(float(").append(TEXELS_X).append("), float(").append(TEXELS_Y)
                  .append(")) * 2.0 - 1.0, 0.0, 1.0);\n")
                  .append("    _cg_value = v;\n    EmitVertex();\n    EndPrimitive();\n}\n");
            }
            case IMAGE -> sb.append("uniform int ").append(LAYER).append(";\nout ").append(pass.image().format().kind.texel)
                    .append(" _cg_out;\nbool _cg_wrote = false;\n");
        }
    }

    private static void outputs(StringBuilder sb, CgBufferDecl b) {
        String[] names = captures(b);
        for (String name : names) sb.append("flat out ").append(uintType(texelWords(b))).append(' ').append(name).append(";\n");
    }

    private static void main(StringBuilder sb, CgKernelDecl kernel, Pass pass) {
        sb.append("\nvoid main() {\n");
        switch (pass.kind()) {
            case OUTPUT -> {
                int k = texelsPerElement(pass.buffer());
                sb.append("    int _cg_t = int(gl_FragCoord.y) * ").append(TEXELS_X).append(" + int(gl_FragCoord.x);\n")
                  .append("    _cg_setup(").append(k == 1 ? "_cg_t" : "_cg_t / " + k).append(");\n");
                for (CgBufferDecl b : pass.buffers()) {
                    sb.append("    _cg_out_").append(b.name()).append(" = _cg_load_").append(b.name()).append("(CG_ELEMENT);\n");
                }
                sb.append("    if (CG_IN_RANGE) ").append(kernel.name()).append("();\n");
                if (k > 1) sb.append("    int _cg_j = _cg_t % ").append(k).append(";\n");
                for (int n = 0; n < pass.buffers().size(); n++) {
                    CgBufferDecl b = pass.buffers().get(n);
                    sb.append("    _cg_o").append(n).append(" = ").append(texelOf(b, "_cg_out_" + b.name())).append(";\n");
                }
            }
            case APPEND, SCATTER -> sb.append("    _cg_setup(_cg_vertex[0]);\n    if (CG_IN_RANGE) ")
                    .append(kernel.name()).append("();\n");
            case IMAGE -> sb.append("    _cg_setup_texel(ivec3(ivec2(gl_FragCoord.xy), ").append(LAYER).append("));\n")
                    .append("    if (!CG_IN_RANGE) discard;\n    ").append(kernel.name()).append("();\n")
                    .append("    if (!_cg_wrote) discard;\n");
        }
        sb.append("}\n");
    }

    // ── Buffers ───────────────────────────────────────────────────────────────

    private static void buffers(StringBuilder sb, CgComputeSource source, CgKernelDecl kernel, Pass pass) {
        sb.append("// Buffers { }, lowered\n");
        for (CgBufferDecl b : source.buffers()) {
            if (!CgLowering.touches(kernel, b)) continue;
            String e = b.element();
            sb.append("uniform usamplerBuffer ").append(tbo(b)).append(";\nuniform int ").append(base(b))
              .append(";\nuniform int ").append(length(b)).append(";\n");
            if (b.access() == CgBufferAccess.APPEND) {
                sb.append("uniform usamplerBuffer ").append(counterTbo(b)).append(";\nuniform int ").append(counterAt(b)).append(";\n");
            }
            load(sb, b);
            if (pass.kind() == Kind.APPEND && pass.buffer() == b) capture(sb, b);
            if (pass.kind() == Kind.OUTPUT && pass.buffers().contains(b)) sb.append(e).append(" _cg_out_").append(b.name()).append(";\n");
            for (CgBufferAccessor accessor : CgBufferAccessor.values()) {
                if (CgLowering.uses(kernel, b, accessor)) accessor(sb, b, accessor, pass);
            }
        }
    }

    /** {@code _cg_load_NAME(i)}: element {@code i} of the view, from its words. */
    private static void load(StringBuilder sb, CgBufferDecl b) {
        String e = b.element();
        int k = texelsPerElement(b);
        sb.append(e).append(" _cg_load_").append(b.name()).append("(int i) {\n    int t = (").append(base(b))
          .append(" + i) * ").append(k).append(";\n");
        if (!b.struct()) {
            int words = texelWords(b);
            String fetch = "texelFetch(" + tbo(b) + ", t)" + (words == 1 ? ".r" : words == 2 ? ".rg" : "");
            sb.append("    return ").append(unpack(e, fetch)).append(";\n}\n");
            return;
        }
        sb.append("    ").append(e).append(" v;\n");
        for (int j = 0; j < b.fields().size(); j++) {
            CgElementField f = b.fields().get(j);
            sb.append("    v.").append(f.name()).append(" = ")
              .append(unpack(f.type(), "texelFetch(" + tbo(b) + ", t + " + j + ")")).append(";\n");
        }
        sb.append("    return v;\n}\n");
    }

    /** The texel of element {@code v} an output pass's fragment writes: its words, or field {@code _cg_j}'s of a struct. */
    private static String texelOf(CgBufferDecl b, String v) {
        if (!b.struct()) return pack(b.element(), v);
        int k = b.fields().size();
        StringBuilder texel = new StringBuilder();
        for (int j = 0; j < k - 1; j++) {
            CgElementField f = b.fields().get(j);
            texel.append("_cg_j == ").append(j).append(" ? ").append(pack(f.type(), v + "." + f.name())).append(" : ");
        }
        CgElementField last = b.fields().get(k - 1);
        return texel.append(pack(last.type(), v + "." + last.name())).toString();
    }

    /** {@code _cg_capture(v)}: an appended element's words into the captured outputs. */
    private static void capture(StringBuilder sb, CgBufferDecl b) {
        sb.append("void _cg_capture(").append(b.element()).append(" v) {\n");
        if (!b.struct()) {
            sb.append("    _cg_c0 = ").append(pack(b.element(), "v")).append(";\n");
        } else {
            for (int j = 0; j < b.fields().size(); j++) {
                CgElementField f = b.fields().get(j);
                sb.append("    _cg_c").append(j).append(" = ").append(pack(f.type(), "v." + f.name())).append(";\n");
            }
        }
        sb.append("}\n");
    }

    private static void accessor(StringBuilder sb, CgBufferDecl b, CgBufferAccessor accessor, Pass pass) {
        String e = b.element();
        String name = b.name() + accessor.suffix;
        boolean counter = b.access() == CgBufferAccess.COUNTER;
        boolean scatterHere = pass.kind() == Kind.SCATTER && pass.buffer() == b;
        String inRange = "if (int(i) < 0 || int(i) >= " + length(b) + ") return";
        switch (accessor) {
            case READ -> indexed(sb, e + " " + name, "", "return _cg_load_" + b.name() + "(int(i));");
            case LENGTH -> sb.append("int ").append(name).append("() { return ").append(length(b)).append("; }\n");
            case WRITE -> sb.append("void ").append(name).append('(').append(e).append(" v) {")
                    .append(pass.kind() == Kind.OUTPUT && pass.buffers().contains(b) ? " _cg_out_" + b.name() + " = v;" : "")
                    .append(" }\n");
            case STORE -> {
                String body = scatterHere && pass.op() == Op.STORE ? inRange + "; " + storePoints(b, pass.floats()) : "";
                indexed(sb, "void " + name, ", " + e + " v", body);
            }
            case ADD, MIN, MAX -> {
                Op op = accessor == CgBufferAccessor.ADD ? Op.ADD : accessor == CgBufferAccessor.MIN ? Op.MIN : Op.MAX;
                String emit = scatterHere && pass.op() == op ? inRange + (counter ? " " + e + "(0)" : "")
                        + "; _cg_point(int(i), vec4(float(v)));" : "";
                if (counter) indexed(sb, e + " " + name, ", " + e + " v", emit + " return " + e + "(0);");
                else indexed(sb, "void " + name, ", " + e + " v", emit);
            }
            case INC -> {
                String emit = scatterHere && pass.op() == Op.ADD
                        ? inRange + " " + e + "(0); _cg_point(int(i), vec4(1.0));" : "";
                indexed(sb, e + " " + name, "", emit + " return " + e + "(0);");
            }
            case APPEND -> {
                sb.append("void ").append(name).append('(').append(e).append(" v) {");
                if (pass.kind() == Kind.APPEND && pass.buffer() == b) {
                    sb.append(" if (_cg_emitted >= CG_APPENDS_PER_ELEMENT) return; _cg_emitted++; _cg_capture(v);")
                      .append(" gl_Position = vec4(0.0, 0.0, 0.0, 1.0); EmitVertex(); EndPrimitive();");
                }
                sb.append(" }\n");
            }
            case COUNT -> sb.append("int ").append(name).append("() { return int(min(texelFetch(").append(counterTbo(b))
                    .append(", ").append(counterAt(b)).append(").r, uint(").append(length(b)).append("))); }\n");
            case DATA -> throw new IllegalStateException(name + " is a general kernel's, which never lowers");
        }
    }

    /** A store's points: the value's words, a texel each, or the scalar as a float where the target holds floats. */
    private static String storePoints(CgBufferDecl b, boolean floats) {
        if (floats) return "_cg_point(int(i), vec4(float(v)));";
        int k = texelsPerElement(b), words = texelWords(b);
        StringBuilder points = new StringBuilder();
        if (!b.struct()) {
            String bits = pack(b.element(), "v");
            String padded = words == 4 ? bits : words == 2 ? "uvec4(" + bits + ", 0u, 0u)" : "uvec4(" + bits + ", 0u, 0u, 0u)";
            return "_cg_point(int(i), " + padded + ");";
        }
        for (int j = 0; j < k; j++) {
            CgElementField f = b.fields().get(j);
            points.append("_cg_point(int(i) * ").append(k).append(" + ").append(j).append(", ")
                  .append(pack(f.type(), "v." + f.name())).append("); ");
        }
        return points.toString();
    }

    /** A function taking an element index, once for {@code int} and once for {@code uint}. */
    private static void indexed(StringBuilder sb, String signature, String moreParameters, String body) {
        for (String index : new String[]{"int", "uint"}) {
            sb.append(signature).append('(').append(index).append(" i").append(moreParameters).append(") { ")
              .append(body).append(" }\n");
        }
    }

    // ── Images ────────────────────────────────────────────────────────────────

    private static void images(StringBuilder sb, CgComputeSource source, CgKernelDecl kernel, Pass pass) {
        sb.append("// Images { }, lowered\n");
        for (CgImageDecl image : source.images()) {
            if (!CgLowering.touches(kernel, image)) continue;
            String texel = image.format().kind.texel;
            boolean reads = CgLowering.uses(kernel, image, CgImageAccessor.LOAD) || CgLowering.uses(kernel, image, CgImageAccessor.SIZE);
            if (reads) {
                sb.append("uniform ").append(samplerType(image)).append(' ').append(sampler(image)).append(";\nuniform int ")
                  .append(level(image)).append(";\n");
            }
            if (CgLowering.uses(kernel, image, CgImageAccessor.LOAD)) {
                sb.append(texel).append(' ').append(image.name()).append(CgImageAccessor.LOAD.suffix).append('(')
                  .append(image.dimension().coordinateType()).append(" p) { return texelFetch(").append(sampler(image))
                  .append(", p, ").append(level(image)).append("); }\n");
            }
            if (CgLowering.uses(kernel, image, CgImageAccessor.SIZE)) {
                String size = image.dimension() == CgImageDimension.D2 ? "ivec2" : "ivec3";
                sb.append(size).append(' ').append(image.name()).append(CgImageAccessor.SIZE.suffix).append("() { return textureSize(")
                  .append(sampler(image)).append(", ").append(level(image)).append("); }\n");
            }
            if (CgLowering.uses(kernel, image, CgImageAccessor.WRITE)) {
                sb.append("void ").append(image.name()).append(CgImageAccessor.WRITE.suffix).append('(').append(texel)
                  .append(" v) {").append(pass.kind() == Kind.IMAGE && pass.image() == image ? " _cg_out = v; _cg_wrote = true;" : "")
                  .append(" }\n");
            }
        }
    }

    /** {@code usampler2DArray}: the sampler a lowered kernel reads the image through. */
    public static String samplerType(CgImageDecl image) {
        return image.format().kind.prefix + image.dimension().glslType.replace("image", "sampler");
    }
}
