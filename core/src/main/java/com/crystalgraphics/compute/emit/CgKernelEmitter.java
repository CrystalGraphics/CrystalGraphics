package com.crystalgraphics.compute.emit;

import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccess;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgImageFormat;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.compute.source.CgSourcePart;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.material.CgMaterialProperties;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.parse.CgGlslEmitter;
import com.crystalgraphics.material.parse.CgShaderParseException;
import com.crystalgraphics.render.shader.CgBindingModel;

import java.util.Set;

/**
 * One kernel of a parsed {@code .compute} as GLSL for a target: the header the device needs, the engine's environment,
 * declarations and accessors for every buffer and image, the file's code less what this kernel never reaches, and a
 * {@code main} that calls it. {@code #include} lines are left for {@code CgShaderPreprocessor} to expand.
 *
 * <pre>{@code
 * CgComputeSource source = CgComputeParser.parse(text, path);
 * String glsl = CgKernelEmitter.emit(source, source.kernel("Simulate"), Set.of("COLLISION"), CgKernelTarget.current());
 * }</pre>
 *
 * <p>What the kernel declares is bound at emission: buffer {@code i} (and its {@link #bitsBlock}) at storage point
 * {@code i}, an append buffer's count after every buffer ({@link #counterPoint}), checked mode's report after those
 * ({@link #checkPoint}), image {@code i} at unit {@code i}, samplers at units in declaration order. Compiled as written
 * ({@code CG_EXPLICIT}, a device's compile), those points are bindings of {@code CgBindingModel}'s material set, the
 * {@code Properties} block is its block, engine tokens are in the pass set, and the dispatch and append counts' words
 * are push constants; on GL the program wires blocks by name.</p>
 *
 * <p>For a {@link CgKernelTarget#checked} target every indexed accessor is a macro passing {@code __LINE__} to a
 * function that bounds-checks the access, reports one out of range to {@link #CHECK_BLOCK} and skips it; each part of
 * the file is preceded by {@code #line}, so the lines are the file's.</p>
 *
 * @throws CgShaderParseException when the kernel asks for more than the target has: invocations, a size, shared memory
 */
public final class CgKernelEmitter {

    /** The block {@code Properties} values are in. */
    public static final String PROPERTY_BLOCK = "CgKernelBlock";
    /** {@code int[6]}: the dispatch's base and count, set per dispatch. */
    public static final String DISPATCH_UNIFORM = "cg_Dispatch";
    /** A checked kernel's report slot, bound after every buffer and count. */
    public static final String CHECK_BLOCK = "CgCheck";
    /** Where an image accessor's site starts in a checked kernel's reports; a buffer accessor's is below it. */
    public static final int IMAGE_SITES = 1 << 12;
    /** The one GLSL every kernel is emitted at, on every device: the materials' target. */
    public static final String VERSION = "#version 450 core\n";
    /** The append buffers a kernel may declare: each count's word is a push constant compiled as written. */
    public static final int KERNEL_WORDS = 8;
    /**
     * {@code kernel.glsl}'s {@code CgKernelPush}: the dispatch's base at 0 and count at 16 (each an {@code ivec4}), then
     * the counter words from {@link #PUSH_WORDS_AT}, word {@code k} at {@code 4 * k}.
     */
    public static final int PUSH_WORDS_AT = 32, PUSH_BYTES = PUSH_WORDS_AT + 4 * KERNEL_WORDS;

    private static final String ENV = "crystalgraphics:shaders/env/";
    private static final String[] SUBGROUP_EXTENSIONS = {"GL_KHR_shader_subgroup_basic", "GL_KHR_shader_subgroup_vote",
            "GL_KHR_shader_subgroup_arithmetic", "GL_KHR_shader_subgroup_ballot", "GL_KHR_shader_subgroup_shuffle"};

    private CgKernelEmitter() {}

    public static String bufferBlock(CgBufferDecl buffer) { return "CgBuffer_" + buffer.name(); }

    /** A float buffer's elements as uint bits, at the buffer's own binding point: what an emulated float atomic reads. */
    public static String bitsBlock(CgBufferDecl buffer) { return "CgBufferBits_" + buffer.name(); }

    /** An append buffer's count, at its own binding point after every buffer's. */
    public static String counterBlock(CgBufferDecl buffer) { return "CgCounter_" + buffer.name(); }

    /**
     * The word of {@link #counterBlock} an append buffer's count is: the block binds from a storage-aligned offset at
     * or below the count, wherever in its buffer the count sits.
     */
    public static String counterWord(CgBufferDecl buffer) { return "cg_CounterAt_" + buffer.name(); }

    /** The storage binding of append buffer {@code b}'s count, after every buffer's; -1 for any other buffer. */
    public static int counterPoint(CgComputeSource source, CgBufferDecl b) {
        if (b.access() != CgBufferAccess.APPEND) return -1;
        int point = source.buffers().size();
        for (CgBufferDecl other : source.buffers()) {
            if (other.index() < b.index() && other.access() == CgBufferAccess.APPEND) point++;
        }
        return point;
    }

    /** The storage binding of checked mode's report, after every buffer and count. */
    public static int checkPoint(CgComputeSource source) {
        int point = source.buffers().size();
        for (CgBufferDecl b : source.buffers()) if (b.access() == CgBufferAccess.APPEND) point++;
        return point;
    }

    /** Which of the {@link #KERNEL_WORDS} append buffer {@code b}'s {@link #counterWord} is, compiled as written. */
    public static int kernelWord(CgComputeSource source, CgBufferDecl b) {
        return counterPoint(source, b) - source.buffers().size();
    }

    /** What a checked kernel reports an access of buffer {@code b} through {@code accessor} as. */
    public static int site(CgBufferDecl b, CgBufferAccessor accessor) { return b.index() << 4 | accessor.ordinal(); }

    /** What a checked kernel reports an access of image {@code i} through {@code accessor} as. */
    public static int site(CgImageDecl i, CgImageAccessor accessor) { return IMAGE_SITES + (i.index() << 4 | accessor.ordinal()); }

    public static String emit(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgKernelTarget target) {
        check(source, kernel, keywords, target);
        boolean general = kernel.shape().unrestricted();
        boolean checked = target.checked();
        boolean subgroups = !kernel.subgroups().isEmpty();
        boolean nativeSubgroups = subgroups && target.nativeSubgroups();
        boolean nativeFloatAdd = target.floatAtomics() && source.buffers().stream()
                .anyMatch(b -> b.element().equals("float") && kernel.accessors().contains(b.name() + "_ADD"));

        StringBuilder sb = new StringBuilder(4096);
        sb.append(VERSION);
        if (nativeSubgroups) for (String e : SUBGROUP_EXTENSIONS) sb.append("#extension ").append(e).append(" : require\n");
        if (nativeFloatAdd) sb.append("#extension GL_NV_shader_atomic_float : require\n");
        for (String e : source.extensions()) sb.append(e).append('\n');
        for (String feature : source.features()) {
            if (keywords.contains(feature)) sb.append("#define ").append(feature).append(" 1\n");
        }
        int group = kernel.groupSize();
        sb.append("#define CG_COMPUTE_STAGE 1\n");
        if (nativeSubgroups) sb.append("#define CG_SUBGROUPS 1\n");
        if (nativeFloatAdd) sb.append("#define CG_FLOAT_ATOMICS 1\n");
        sb.append("#define CG_LOCAL_SIZE_X ").append(kernel.sizeX()).append('\n');
        sb.append("#define CG_LOCAL_SIZE_Y ").append(kernel.sizeY()).append('\n');
        sb.append("#define CG_LOCAL_SIZE_Z ").append(kernel.sizeZ()).append('\n');
        sb.append("#define CG_GROUP_SIZE ").append(group).append('\n');
        sb.append("#define CG_GROUP_POW2 ").append(Integer.bitCount(group) == 1 ? group : Integer.highestOneBit(group) << 1).append('\n');
        sb.append("#define CG_DIMENSIONS ").append(kernel.dimensions()).append('\n');
        sb.append("#define CG_KERNEL_").append(kernel.name()).append(" 1\n");
        slots(sb);
        sb.append("layout(local_size_x = ").append(kernel.sizeX()).append(", local_size_y = ").append(kernel.sizeY())
          .append(", local_size_z = ").append(kernel.sizeZ()).append(") in;\n");
        include(sb, ENV + "cg_env.glsl");
        include(sb, ENV + "compute/kernel.glsl");
        include(sb, ENV + "compute/atomic.glsl");
        if (subgroups) include(sb, ENV + "compute/subgroup.glsl");
        if (checked) {
            sb.append("#define CG_CHECK_BINDING ").append(checkPoint(source)).append('\n');
            include(sb, ENV + "compute/check.glsl");
        }

        properties(sb, source);
        for (String token : source.engineBuffers()) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            CgShaderBuffer buffer = provider.buffer().get();
            sb.append(CgGlslEmitter.emitSsbo(buffer.getFormat(), buffer.getName(), provider.macroName(), !general,
                    CgBindingModel.token(provider.macroName())));
            if (provider.envPath() != null) include(sb, provider.envPath());
        }

        for (CgSourcePart part : source.parts()) {
            if (part instanceof CgSourcePart.Text t) {
                if (checked) line(sb, t.line());
                sb.append(t.text());
            } else if (part instanceof CgSourcePart.Function f) {
                if (!kernel.functions().contains(f.name())) continue;
                if (checked) line(sb, f.line());
                sb.append(f.text());
            }
            else if (part instanceof CgSourcePart.Shared s) { if (kernel.shared().contains(s.name())) sb.append(s.text()); }
            else if (part instanceof CgSourcePart.Buffers) buffers(sb, source, kernel, nativeFloatAdd, checked);
            else images(sb, source, kernel, checked);
        }

        sb.append("\nvoid main() {\n");
        if (!general) sb.append("    if (!CG_IN_RANGE) return;\n");
        sb.append("    ").append(kernel.name()).append("();\n}\n");
        return sb.toString();
    }

    private static void check(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgKernelTarget target) {
        for (String keyword : keywords) {
            if (!source.features().contains(keyword)) {
                throw new IllegalArgumentException("[" + source.path() + "] keyword '" + keyword + "' is not declared: "
                        + source.features());
            }
        }
        String named = "[" + source.path() + "] kernel " + kernel.name();
        for (int axis = 0; axis < 3; axis++) {
            if (kernel.size(axis) > target.maxSize(axis)) {
                throw new CgShaderParseException(named + " is " + kernel.size(axis) + " wide along " + "xyz".charAt(axis)
                        + "; this device allows " + target.maxSize(axis));
            }
        }
        int appends = checkPoint(source) - source.buffers().size();
        if (appends > KERNEL_WORDS) {
            throw new CgShaderParseException(named + "'s file declares " + appends + " append buffers; a kernel takes "
                    + KERNEL_WORDS);
        }
        int samplers = 0;
        for (CgMaterialProperty p : new CgMaterialProperties(source.properties()).all()) if (p.getType().isSampler()) samplers++;
        if (checkPoint(source) + 1 > CgBindingModel.FIRST_KERNEL_IMAGE - CgBindingModel.FIRST_KERNEL_BUFFER
                || samplers > CgBindingModel.FIRST_KERNEL_BUFFER - CgBindingModel.FIRST_TEXTURE) {
            throw new CgShaderParseException(named + "'s file declares " + source.buffers().size() + " buffers ("
                    + appends + " appending) and " + samplers + " samplers, more than a kernel's set holds");
        }
        if (kernel.groupSize() > target.maxInvocations()) {
            throw new CgShaderParseException(named + " asks for " + kernel.groupSize() + " invocations a group; this "
                    + "device allows " + target.maxInvocations());
        }
        boolean emulated = !kernel.subgroups().isEmpty() && !target.nativeSubgroups();
        if (emulated && kernel.subgroups().contains("CG_SUBGROUP_BALLOT") && kernel.groupSize() > 128) {
            throw new CgShaderParseException(named + " ballots across " + kernel.groupSize() + " invocations; where "
                    + "subgroups are emulated, as on this device, a ballot holds 128");
        }
        if (kernel.sharedBytes() >= 0) {
            int bytes = kernel.sharedBytes() + (emulated ? (kernel.groupSize() + 4) * 4 : 0);
            if (bytes > target.maxSharedMemory()) {
                throw new CgShaderParseException(named + " holds " + bytes + " bytes of shared memory"
                        + (emulated ? " (subgroup emulation included)" : "") + "; this device allows "
                        + target.maxSharedMemory());
            }
        }
    }

    private static void include(StringBuilder sb, String path) {
        sb.append("#include \"").append(path).append("\"\n");
    }

    /** The next line as the file's {@code line}: what {@code __LINE__} reports in checked mode. */
    private static void line(StringBuilder sb, int line) {
        sb.append("\n#line ").append(line).append('\n');
    }

    /**
     * Where a kernel's own resources bind: GL points, or compiled as written ({@code CG_EXPLICIT}) bindings of the
     * material set, from {@link CgBindingModel}'s numbers. Compiled by the GL driver itself ({@code CG_GL_NATIVE}), each
     * index's point is a define the GL device writes; {@code GLNativeGlslCompiler} reads the bindings from the
     * {@code CG_EXPLICIT} lines above it, and the push block's size from {@code CG_PUSH_CONSTANT_BYTES}.
     */
    private static void slots(StringBuilder sb) {
        sb.append("#ifdef CG_EXPLICIT\n")
          .append("#define CG_KBUFFER(i) set = ").append(CgBindingModel.MATERIAL).append(", binding = ")
          .append(CgBindingModel.FIRST_KERNEL_BUFFER).append(" + (i)\n")
          .append("#define CG_KIMAGE(i) set = ").append(CgBindingModel.MATERIAL).append(", binding = ")
          .append(CgBindingModel.FIRST_KERNEL_IMAGE).append(" + (i)\n")
          .append("#define CG_KSAMPLER(i) set = ").append(CgBindingModel.MATERIAL).append(", binding = ")
          .append(CgBindingModel.FIRST_TEXTURE).append(" + (i)\n")
          .append("#define CG_KERNEL_WORDS ").append(KERNEL_WORDS).append('\n')
          .append("#define CG_PUSH_CONSTANT_BYTES ").append(PUSH_BYTES).append('\n')
          .append("#ifdef CG_GL_NATIVE\n#undef CG_KBUFFER\n#undef CG_KIMAGE\n#undef CG_KSAMPLER\n")
          .append("#define CG_KBUFFER(i) binding = CG_GL_KBUFFER_##i\n#define CG_KIMAGE(i) binding = CG_GL_KIMAGE_##i\n")
          .append("#define CG_KSAMPLER(i) binding = CG_GL_KSAMPLER_##i\n#endif\n")
          .append("#else\n#define CG_KBUFFER(i) binding = i\n#define CG_KIMAGE(i) binding = i\n")
          .append("#define CG_KSAMPLER(i) binding = i\n#endif\n");
    }

    /** The {@code Properties} declarations: samplers and the block. */
    private static void properties(StringBuilder sb, CgComputeSource source) {
        CgMaterialProperties properties = new CgMaterialProperties(source.properties());
        int unit = 0;
        for (CgMaterialProperty p : properties.all()) {
            if (!p.getType().isSampler()) continue;
            sb.append("layout(CG_KSAMPLER(").append(unit++).append(")) uniform ").append(p.getGlslType()).append(' ')
              .append(p.getName()).append(";\n");
        }
        if (properties.hasUboProps()) {
            sb.append(CgGlslEmitter.emitUbo(properties.buildUboFormat(), PROPERTY_BLOCK, CgBindingModel.MATERIAL_BLOCK));
        }
    }

    // ── Buffers ───────────────────────────────────────────────────────────────

    private static void buffers(StringBuilder sb, CgComputeSource source, CgKernelDecl kernel, boolean nativeFloatAdd,
                                boolean checked) {
        sb.append("// Buffers { }, generated: those this kernel reaches, since a stage holds few blocks\n");
        for (CgBufferDecl b : source.buffers()) {
            if (!reaches(kernel, b)) continue;
            String e = b.element();
            String a = b.array();
            String qualifier = b.access() == CgBufferAccess.READONLY ? "readonly " : b.access() == CgBufferAccess.WRITEONLY ? "writeonly " : "";
            sb.append("layout(std430, CG_KBUFFER(").append(b.index()).append(")) ").append(qualifier).append("buffer ")
              .append(bufferBlock(b)).append(" { ")
              .append(e).append(' ').append(a).append("[]; };\n");
            boolean floatAtomics = e.equals("float") && b.access() == CgBufferAccess.READWRITE
                    && (uses(kernel, b, CgBufferAccessor.ADD) && !nativeFloatAdd
                        || uses(kernel, b, CgBufferAccessor.MIN) || uses(kernel, b, CgBufferAccessor.MAX));
            if (floatAtomics) sb.append("layout(std430, CG_KBUFFER(").append(b.index()).append(")) buffer ").append(bitsBlock(b))
              .append(" { uint ").append(a).append("_bits[]; };\n");
            if (b.access() == CgBufferAccess.APPEND) {
                sb.append("layout(std430, CG_KBUFFER(").append(counterPoint(source, b)).append(")) buffer ")
                  .append(counterBlock(b)).append(" { uint ").append(a).append("_counter[]; };\n")
                  .append("#ifdef CG_EXPLICIT\n#define ").append(counterWord(b)).append(" cg_KernelWords[")
                  .append(kernelWord(source, b) / 4).append("][").append(kernelWord(source, b) % 4)
                  .append("]\n#else\nuniform int ").append(counterWord(b))
                  .append(";\n#endif\n#define ").append(a)
                  .append("_count ").append(a).append("_counter[").append(counterWord(b)).append("]\n");
            }
            for (CgBufferAccessor accessor : CgBufferAccessor.values()) {
                if (uses(kernel, b, accessor)) accessor(sb, b, accessor, nativeFloatAdd, checked ? site(b, accessor) : -1);
            }
        }
    }

    private static boolean reaches(CgKernelDecl kernel, CgBufferDecl b) {
        for (CgBufferAccessor a : CgBufferAccessor.values()) if (uses(kernel, b, a)) return true;
        return false;
    }

    private static boolean uses(CgKernelDecl kernel, CgBufferDecl b, CgBufferAccessor accessor) {
        return kernel.accessors().contains(b.name() + accessor.suffix);
    }

    /** Buffer {@code b}'s {@code accessor}; with a {@code site}, checked mode's form of it, -1 for the plain one. */
    private static void accessor(StringBuilder sb, CgBufferDecl b, CgBufferAccessor accessor, boolean nativeFloatAdd,
                                 int site) {
        String e = b.element();
        String a = b.array();
        String name = b.name() + accessor.suffix;
        boolean counter = b.access() == CgBufferAccess.COUNTER;
        switch (accessor) {
            case READ -> indexed(sb, e, name, "", "return " + a + "[i];", site, a);
            case LENGTH -> sb.append("int ").append(name).append("() { return ").append(a).append(".length(); }\n");
            case WRITE -> {
                if (site < 0) {
                    sb.append("void ").append(name).append('(').append(e).append(" v) { ").append(a).append("[CG_ELEMENT] = v; }\n");
                } else {
                    sb.append("void cg_c_").append(name).append('(').append(e).append(" v, int line) { ")
                      .append(bufferGuard(site, "uint(CG_ELEMENT)", a, "return;")).append(a).append("[CG_ELEMENT] = v; }\n")
                      .append("#define ").append(name).append("(v) cg_c_").append(name).append("(v, __LINE__)\n");
                }
            }
            case STORE -> indexed(sb, "void", name, ", " + e + " v", a + "[i] = v;", site, a);
            case ADD, MIN, MAX -> {
                String op = accessor == CgBufferAccessor.ADD ? "Add" : accessor == CgBufferAccessor.MIN ? "Min" : "Max";
                if (counter) indexed(sb, e, name, ", " + e + " v", "return atomic" + op + "(" + a + "[i], v);", site, a);
                else if (!e.equals("float")) indexed(sb, "void", name, ", " + e + " v", "atomic" + op + "(" + a + "[i], v);", site, a);
                else if (accessor == CgBufferAccessor.ADD && nativeFloatAdd) indexed(sb, "void", name, ", float v", "atomicAdd(" + a + "[i], v);", site, a);
                else indexed(sb, "void", name, ", float v", "CG_ATOMIC_" + op.toUpperCase() + "_FLOAT(" + a + "_bits[i], v)", site, a);
            }
            // The element it took, or -1 once full: a compute kernel may write beside it (a lowered append answers none).
            case APPEND -> sb.append("int ").append(name).append('(').append(e).append(" v) { uint at = atomicAdd(")
                    .append(a).append("_count, 1u); if (at < uint(").append(a).append(".length())) { ").append(a)
                    .append("[at] = v; return int(at); } return -1; }\n");
            case COUNT -> sb.append("int ").append(name).append("() { return int(min(").append(a).append("_count, uint(")
                    .append(a).append(".length()))); }\n");
            case INC -> indexed(sb, e, name, "", "return atomicAdd(" + a + "[i], " + e + "(1));", site, a);
            case DATA -> sb.append("#define ").append(name).append(' ').append(a).append('\n');
        }
    }

    /**
     * A function taking an element index, once for {@code int} and once for {@code uint}. Checked ({@code site} not
     * -1), it is {@code cg_c_NAME} taking the line too, behind a macro of the accessor's name passing {@code __LINE__}.
     */
    private static void indexed(StringBuilder sb, String type, String name, String moreParameters, String body, int site,
                                String array) {
        String fallback = type.equals("void") ? "return;" : "return " + array + "[0];";
        for (String index : new String[]{"int", "uint"}) {
            sb.append(type).append(' ').append(site < 0 ? name : "cg_c_" + name).append('(').append(index).append(" i")
              .append(moreParameters).append(site < 0 ? "" : ", int line").append(") { ")
              .append(site < 0 ? "" : bufferGuard(site, "uint(i)", array, fallback)).append(body).append(" }\n");
        }
        if (site >= 0) {
            String more = moreParameters.isEmpty() ? "" : ", v";
            sb.append("#define ").append(name).append("(i").append(more).append(") cg_c_").append(name).append("(i")
              .append(more).append(", __LINE__)\n");
        }
    }

    /** Checked mode's test of an element index against a buffer's length: reported and skipped when past it. */
    private static String bufferGuard(int site, String index, String array, String fallback) {
        return "if (" + index + " >= uint(" + array + ".length())) { cg_Violation(" + site + ", line, uvec3(" + index
                + ", 0u, 0u), uvec3(uint(" + array + ".length()), 0u, 0u)); " + fallback + " } ";
    }

    // ── Images ────────────────────────────────────────────────────────────────

    private static void images(StringBuilder sb, CgComputeSource source, CgKernelDecl kernel, boolean checked) {
        sb.append("// Images { }, generated: those this kernel reaches\n");
        for (CgImageDecl image : source.images()) {
            boolean reached = false;
            for (CgImageAccessor a : CgImageAccessor.values()) reached |= kernel.accessors().contains(image.name() + a.suffix);
            if (!reached) continue;
            String qualifier = image.access() == CgImageAccess.READONLY ? "readonly " : image.access() == CgImageAccess.WRITEONLY ? "writeonly " : "";
            sb.append("layout(").append(image.format().qualifier()).append(", CG_KIMAGE(").append(image.index())
              .append(")) uniform ").append(qualifier)
              .append(image.glslType()).append(' ').append(image.uniform()).append(";\n");
            for (CgImageAccessor accessor : CgImageAccessor.values()) {
                if (kernel.accessors().contains(image.name() + accessor.suffix)) {
                    accessor(sb, image, accessor, checked ? site(image, accessor) : -1);
                }
            }
        }
    }

    /** Image {@code image}'s {@code accessor}; with a {@code site}, checked mode's form of it, -1 for the plain one. */
    private static void accessor(StringBuilder sb, CgImageDecl image, CgImageAccessor accessor, int site) {
        if (site >= 0 && accessor != CgImageAccessor.SIZE) {
            checkedAccessor(sb, image, accessor, site);
            return;
        }
        String name = image.name() + accessor.suffix;
        String u = image.uniform();
        String texel = image.format().kind.texel;
        String coordinate = image.dimension().coordinateType();
        String here = image.dimension() == CgImageDimension.D2 ? "CG_TEXEL.xy" : "CG_TEXEL.xyz";
        String size = image.dimension() == CgImageDimension.D2 || image.dimension() == CgImageDimension.CUBE ? "ivec2" : "ivec3";
        String scalar = image.format().kind == CgImageFormat.Kind.INT ? "int" : "uint";
        switch (accessor) {
            case LOAD -> sb.append(texel).append(' ').append(name).append('(').append(coordinate)
                    .append(" p) { return imageLoad(").append(u).append(", p); }\n");
            case SIZE -> sb.append(size).append(' ').append(name).append("() { return imageSize(").append(u).append("); }\n");
            case WRITE -> sb.append("void ").append(name).append('(').append(texel).append(" v) { imageStore(").append(u)
                    .append(", ").append(here).append(", v); }\n");
            case STORE -> sb.append("void ").append(name).append('(').append(coordinate).append(" p, ").append(texel)
                    .append(" v) { imageStore(").append(u).append(", p, v); }\n");
            case ADD, MIN, MAX -> {
                String op = accessor == CgImageAccessor.ADD ? "Add" : accessor == CgImageAccessor.MIN ? "Min" : "Max";
                sb.append(scalar).append(' ').append(name).append('(').append(coordinate).append(" p, ").append(scalar)
                  .append(" v) { return imageAtomic").append(op).append('(').append(u).append(", p, v); }\n");
            }
        }
    }

    /**
     * Checked mode's image accessor: {@code cg_c_NAME} testing the texel against the bound level's size, behind a
     * macro of the accessor's name passing {@code __LINE__}.
     */
    private static void checkedAccessor(StringBuilder sb, CgImageDecl image, CgImageAccessor accessor, int site) {
        String name = image.name() + accessor.suffix;
        String u = image.uniform();
        String texel = image.format().kind.texel;
        String coordinate = image.dimension().coordinateType();
        String scalar = image.format().kind == CgImageFormat.Kind.INT ? "int" : "uint";
        boolean d2 = image.dimension() == CgImageDimension.D2;
        String at = d2 ? "uvec3(uvec2(p), 0u)" : "uvec3(p)";
        String size = d2 ? "uvec3(uvec2(imageSize(" + u + ")), 1u)"
                : image.dimension() == CgImageDimension.CUBE ? "uvec3(uvec2(imageSize(" + u + ")), 6u)"
                : "uvec3(imageSize(" + u + "))";
        String type, parameters, arguments, body, fallback;
        switch (accessor) {
            case LOAD -> {
                type = texel; parameters = coordinate + " p"; arguments = "p";
                body = "return imageLoad(" + u + ", p);"; fallback = "return " + texel + "(0);";
            }
            case WRITE -> {
                type = "void"; parameters = texel + " v"; arguments = "v";
                body = "imageStore(" + u + ", p, v);"; fallback = "return;";
            }
            case STORE -> {
                type = "void"; parameters = coordinate + " p, " + texel + " v"; arguments = "p, v";
                body = "imageStore(" + u + ", p, v);"; fallback = "return;";
            }
            default -> {
                String op = accessor == CgImageAccessor.ADD ? "Add" : accessor == CgImageAccessor.MIN ? "Min" : "Max";
                type = scalar; parameters = coordinate + " p, " + scalar + " v"; arguments = "p, v";
                body = "return imageAtomic" + op + "(" + u + ", p, v);"; fallback = "return " + scalar + "(0);";
            }
        }
        String here = accessor == CgImageAccessor.WRITE ? coordinate + " p = " + (d2 ? "CG_TEXEL.xy" : "CG_TEXEL.xyz") + "; " : "";
        sb.append(type).append(" cg_c_").append(name).append('(').append(parameters).append(", int line) { ").append(here)
          .append("uvec3 at = ").append(at).append("; uvec3 size = ").append(size)
          .append("; if (any(greaterThanEqual(at, size))) { cg_Violation(").append(site).append(", line, at, size); ")
          .append(fallback).append(" } ").append(body).append(" }\n")
          .append("#define ").append(name).append('(').append(arguments).append(") cg_c_").append(name).append('(')
          .append(arguments).append(", __LINE__)\n");
    }
}
