package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgGlslSources;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.crystalgraphics.platform.gl.driver.GLC.GL_ACTIVE_RESOURCES;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_BLOCK_INDEX;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_BUFFER_BINDING;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_COMPILE_STATUS;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_LINK_STATUS;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_LOCATION;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_REFERENCED_BY_COMPUTE_SHADER;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_REFERENCED_BY_FRAGMENT_SHADER;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_REFERENCED_BY_VERTEX_SHADER;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_SHADER_STORAGE_BLOCK;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_TYPE;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_UNIFORM;
import static com.crystalgraphics.platform.gl.driver.GLC.GL_UNIFORM_BLOCK;

/**
 * The GL device's compiler before the shader toolchain is on disk: the engine's sources as the GL driver compiles them,
 * no SPIR-V. What lets a GL-era host draw through the device on its first start, the loading screen included. Each
 * stage is its source under {@code CG_GL_NATIVE} (and {@code CG_EXPLICIT}), where every {@code CG_BIND(set, binding)}
 * and kernel binding is a point {@link GLNativeTranslator} defines once the pipeline's layout is known.
 *
 * <pre>{@code
 * GLNativeTranslator translator = new GLNativeTranslator();
 * CgGLDevice device = new CgGLDevice(driver, translator, w, h, false, GLHostState.byQuery(driver));
 * device.install(new GLNativeGlslCompiler(device.driver()));
 * // ... once the toolchain lands:
 * translator.spirv(toolchain.translator());
 * CgGlslCompiler.install(toolchain.compiler(CgCacheDirectory.of("spirv")));
 * }</pre>
 *
 * <ul>
 *   <li>The layout is what the driver says the program reads: every binding the declarations name is given a point
 *       of its own, the program is linked, and the blocks, buffers, samplers and images a stage references are read back.
 *       Narrower than shaderc's SPIR-V reflection where the driver's optimiser drops a read, never wider. A compile is that probe link, on the device's context: call it where the
 *       device draws.</li>
 *   <li>Refused, naming the line: a binding it cannot class, a non-literal kernel binding (checked mode), a
 *       {@code push_constant} other than the kernel's, a vertex input {@code attribLocations} does not place. The
 *       bindless heap (set 3) is never declared: {@code CG_BINDLESS} is not defined.</li>
 * </ul>
 */
public final class GLNativeGlslCompiler implements CgGlslCompiler {

    /** The first line of a native module: what {@link GLNativeTranslator} passes through. */
    static final String MARK = "// cg-native-glsl";
    private static final String DEFINES = "#define CG_EXPLICIT 1\n#define CG_GL_NATIVE 1\n";

    private static final Pattern BIND = Pattern.compile("CG_BIND\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern KERNEL_BASE = Pattern.compile(
            "#define CG_K(BUFFER|IMAGE|SAMPLER)\\(i\\) set = (\\d+), binding = (\\d+) \\+ \\(i\\)");
    private static final Pattern KERNEL_USE = Pattern.compile("CG_K(BUFFER|IMAGE|SAMPLER)\\(([^)]*)\\)");
    private static final Pattern PUSH_BYTES = Pattern.compile("#define CG_PUSH_CONSTANT_BYTES (\\d+)");
    private static final Pattern LOCAL_SIZE = Pattern.compile(
            "layout\\(local_size_x = (\\d+), local_size_y = (\\d+), local_size_z = (\\d+)\\) in;");
    private static final Pattern OUTPUT = Pattern.compile("layout\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\b");
    private static final Pattern PLAIN_OUTPUT = Pattern.compile("(?m)^\\s*out\\s+\\w+\\s+\\w+\\s*;");
    private static final Pattern INPUT = Pattern.compile("(?m)^(\\s*)in\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(u|i)?(sampler|image)(\\w*)\\b");

    @Override
    public Program compile(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations, String label) {
        throw new UnsupportedOperationException("the GL device's native compiler compiles explicit programs only");
    }

    @Override
    public ComputeProgram compileCompute(String glsl, String label) {
        throw new UnsupportedOperationException("the GL device's native compiler compiles explicit kernels only");
    }

    @Override
    public Explicit compileExplicit(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations,
                                    String label) {
        String vertex = placeInputs(CgGlslSources.withDefines(vertexGlsl, label, DEFINES), attribLocations, label);
        String fragment = CgGlslSources.withDefines(fragmentGlsl, label, DEFINES);
        Map<Long, Slot> slots = new TreeMap<>();
        scan(vertex, label, slots);
        scan(fragment, label, slots);
        refusePushConstants(vertex, label);
        refusePushConstants(fragment, label);
        active(slots, label, GL_VERTEX_SHADER, vertex, GL_FRAGMENT_SHADER, fragment);
        return new Explicit(module(vertex, slots), module(CgGlslSources.wrapMain(vertex, label), slots),
                module(fragment, slots), sets(slots), false, 0, outputs(fragment));
    }

    @Override
    public ExplicitCompute compileExplicitCompute(String glsl, String label) {
        String source = CgGlslSources.withDefines(glsl, label, DEFINES);
        Map<Long, Slot> slots = new TreeMap<>();
        scan(source, label, slots);
        String bare = CgGlslSources.withoutComments(source);
        Matcher size = LOCAL_SIZE.matcher(bare);
        if (!size.find()) throw new CgShaderModule.CompileException(label + ": no layout(local_size_x, _y, _z) in;");
        int[] local = {Integer.parseInt(size.group(1)), Integer.parseInt(size.group(2)), Integer.parseInt(size.group(3))};
        int push = 0;
        if (bare.contains("CgKernelPush")) {
            Matcher bytes = PUSH_BYTES.matcher(bare);
            if (!bytes.find()) throw new CgShaderModule.CompileException(label + ": CgKernelPush with no CG_PUSH_CONSTANT_BYTES");
            push = Integer.parseInt(bytes.group(1));
        } else {
            refusePushConstants(source, label);
        }
        active(slots, label, GL_COMPUTE_SHADER, source);
        return new ExplicitCompute(module(source, slots), local, sets(slots), false, push);
    }

    /** One binding: its set, binding and type, the define its point is written as, and whether the program reads it. */
    record Slot(int set, int binding, CgBindingLayout.Type type, String define, boolean active) {
        Slot(int set, int binding, CgBindingLayout.Type type, String define) {
            this(set, binding, type, define, false);
        }
    }

    /**
     * Marks the slots the linked program reads, as the driver says: each declared at a point of its own, the program
     * compiled and linked here as the device will, its active blocks, buffers, samplers and images read back. What
     * SPIR-V's reflection gives shaderc's compile. The driver's errors are the compile's.
     */
    private void active(Map<Long, Slot> slots, String label, Object... stages) {
        // A define names one (set, binding), which declarations under branches the source never takes together may
        // give two classes (a material's block or its arena): one point for it, free in each of its classes
        int[] next = new int[4];
        Slot[][] byPoint = new Slot[4][slots.size() + 1];
        Map<String, List<Slot>> byDefine = new TreeMap<>();
        for (Slot s : slots.values()) byDefine.computeIfAbsent(s.define, d -> new ArrayList<>()).add(s);
        StringBuilder defines = new StringBuilder(64 * byDefine.size());
        for (Map.Entry<String, List<Slot>> e : byDefine.entrySet()) {
            int point = 0;
            for (Slot s : e.getValue()) point = Math.max(point, next[GLBindingPoints.classOf(s.type)]);
            for (Slot s : e.getValue()) {
                int kind = GLBindingPoints.classOf(s.type);
                byPoint[kind][point] = s;
                next[kind] = point + 1;
            }
            defines.append("#define ").append(e.getKey()).append(' ').append(point).append('\n');
        }
        int push = next[GLBindingPoints.UNIFORM];
        defines.append("#define CG_GL_PUSH ").append(push).append('\n');
        int program = gl.glCreateProgram();
        int[] shaders = new int[stages.length / 2];
        try {
            for (int i = 0; i < shaders.length; i++) {
                int shader = shaders[i] = gl.glCreateShader((Integer) stages[2 * i]);
                gl.glShaderSource(shader, CgGlslSources.withDefines((String) stages[2 * i + 1], label, defines.toString()));
                gl.glCompileShader(shader);
                if (gl.glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
                    throw new CgShaderModule.CompileException(label + ": the driver's compile failed:\n" + gl.glGetShaderInfoLog(shader));
                }
                gl.glAttachShader(program, shader);
            }
            gl.glLinkProgram(program);
            if (gl.glGetProgrami(program, GL_LINK_STATUS) == 0) {
                throw new CgShaderModule.CompileException(label + ": the driver's link failed:\n" + gl.glGetProgramInfoLog(program));
            }
            // GL keeps a std140 or std430 block active wherever it is declared; only a stage's reference is a read
            int[] references = new int[shaders.length];
            for (int i = 0; i < references.length; i++) references[i] = referencedBy((Integer) stages[2 * i]);
            for (int b = 0, n = gl.glGetProgramInterfacei(program, GL_UNIFORM_BLOCK, GL_ACTIVE_RESOURCES); b < n; b++) {
                if (!referenced(program, GL_UNIFORM_BLOCK, b, references)) continue;
                int point = gl.glGetProgramResourcei(program, GL_UNIFORM_BLOCK, b, GL_BUFFER_BINDING);
                if (point != push) activate(slots, byPoint, GLBindingPoints.UNIFORM, point, label);
            }
            for (int b = 0, n = gl.glGetProgramInterfacei(program, GL_SHADER_STORAGE_BLOCK, GL_ACTIVE_RESOURCES); b < n; b++) {
                if (!referenced(program, GL_SHADER_STORAGE_BLOCK, b, references)) continue;
                activate(slots, byPoint, GLBindingPoints.STORAGE,
                        gl.glGetProgramResourcei(program, GL_SHADER_STORAGE_BLOCK, b, GL_BUFFER_BINDING), label);
            }
            for (int u = 0, n = gl.glGetProgramInterfacei(program, GL_UNIFORM, GL_ACTIVE_RESOURCES); u < n; u++) {
                if (gl.glGetProgramResourcei(program, GL_UNIFORM, u, GL_BLOCK_INDEX) != -1) continue;
                if (!referenced(program, GL_UNIFORM, u, references)) continue;
                // GLSL's own (gl_SubgroupSize on NVIDIA) are listed as uniforms; nothing binds them
                if (gl.glGetProgramResourceName(program, GL_UNIFORM, u).startsWith("gl_")) continue;
                int type = gl.glGetProgramResourcei(program, GL_UNIFORM, u, GL_TYPE);
                boolean image = type >= GL_IMAGE_FIRST && type <= GL_IMAGE_LAST;
                if (!image && !sampler(type)) {
                    throw new CgShaderModule.CompileException(label + ": uniform " + gl.glGetProgramResourceName(program,
                            GL_UNIFORM, u) + " is outside any block, which Vulkan's rules refuse");
                }
                int kind = image ? GLBindingPoints.IMAGE : GLBindingPoints.TEXTURE;
                int location = gl.glGetProgramResourcei(program, GL_UNIFORM, u, GL_LOCATION);
                if (location < 0) continue;
                activate(slots, byPoint, kind, gl.glGetUniformi(program, location), label);
            }
        } finally {
            for (int shader : shaders) if (shader != 0) gl.glDeleteShader(shader);
            gl.glDeleteProgram(program);
        }
    }

    private boolean referenced(int program, int iface, int index, int[] references) {
        for (int property : references) {
            if (gl.glGetProgramResourcei(program, iface, index, property) != 0) return true;
        }
        return false;
    }

    private static int referencedBy(int stage) {
        return switch (stage) {
            case GL_VERTEX_SHADER -> GL_REFERENCED_BY_VERTEX_SHADER;
            case GL_FRAGMENT_SHADER -> GL_REFERENCED_BY_FRAGMENT_SHADER;
            case GL_COMPUTE_SHADER -> GL_REFERENCED_BY_COMPUTE_SHADER;
            default -> throw new IllegalArgumentException("stage 0x" + Integer.toHexString(stage));
        };
    }

    private static void activate(Map<Long, Slot> slots, Slot[][] byPoint, int kind, int point, String label) {
        Slot s = point >= 0 && point < byPoint[kind].length ? byPoint[kind][point] : null;
        if (s == null) {
            throw new CgShaderModule.CompileException(label + ": the driver reports a class " + kind + " resource at "
                    + point + ", which no declaration placed there");
        }
        for (Slot other : slots.values()) {
            if (other.active && other.set == s.set && other.binding == s.binding && other.type != s.type) {
                throw new CgShaderModule.CompileException(label + ": set " + s.set + " binding " + s.binding
                        + " is read as both " + other.type + " and " + s.type);
            }
        }
        slots.put(key(s), new Slot(s.set, s.binding, s.type, s.define, true));
    }

    /** A slot's key: its set and binding, then its class, so one binding may be declared in two classes. */
    private static long key(Slot s) {
        return (long) s.set << 40 | (long) s.binding << 8 | GLBindingPoints.classOf(s.type);
    }

    /** Whether {@code type} is one of GLSL's sampler types, which GL numbers in four runs. */
    private static boolean sampler(int type) {
        return type >= 0x8B5D && type <= 0x8B64                                 // sampler1D .. sampler2DRectShadow
                || type >= 0x8DC0 && type <= 0x8DD8 && (type < 0x8DC6 || type > 0x8DC8)   // arrays, ints; not uvec2-4
                || type >= 0x900C && type <= 0x900F                             // cube map arrays
                || type >= 0x9108 && type <= 0x910D;                            // multisampled
    }

    /** GLSL's image types, {@code GL_IMAGE_1D} to {@code GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE_ARRAY}. */
    private static final int GL_IMAGE_FIRST = 0x904C, GL_IMAGE_LAST = 0x906C;
    private static final int GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30, GL_COMPUTE_SHADER = 0x91B9;

    private final CgGLDriver gl;

    /**
     * @param gl the device's driver: every compile probe-links its program here, so call it on the thread whose
     *           context the device draws in
     */
    public GLNativeGlslCompiler(CgGLDriver gl) {
        this.gl = gl;
    }

    /** Every binding the source declares, keyed by set and binding. */
    private static void scan(String source, String label, Map<Long, Slot> slots) {
        String bare = CgGlslSources.withoutComments(source);
        Matcher bind = BIND.matcher(bare);
        while (bind.find()) {
            if (lineOf(bare, bind.start()).trim().startsWith("#")) continue;
            int set = Integer.parseInt(bind.group(1)), binding = Integer.parseInt(bind.group(2));
            if (set == CgRHI.BINDLESS_SET) continue;   // only declared under CG_BINDLESS, never defined here
            add(slots, new Slot(set, binding, classify(bare, bind.end(), label), "CG_GL_POINT_" + set + "_" + binding), label);
        }
        Map<String, int[]> bases = new TreeMap<>();
        Matcher base = KERNEL_BASE.matcher(bare);
        while (base.find()) bases.put(base.group(1), new int[] {Integer.parseInt(base.group(2)), Integer.parseInt(base.group(3))});
        Matcher use = KERNEL_USE.matcher(bare);
        while (use.find()) {
            if (lineOf(bare, use.start()).trim().startsWith("#")) continue;
            String kind = use.group(1), index = use.group(2).trim();
            int[] at = bases.get(kind);
            if (at == null) throw refused(label, bare, use.start(), "CG_K" + kind + " with no CG_EXPLICIT definition");
            if (!index.matches("\\d+")) {
                throw refused(label, bare, use.start(), "CG_K" + kind + "(" + index + ") is no literal: checked mode needs the shader toolchain");
            }
            int i = Integer.parseInt(index);
            CgBindingLayout.Type type = switch (kind) {
                case "BUFFER" -> CgBindingLayout.Type.STORAGE_BUFFER;
                case "IMAGE" -> CgBindingLayout.Type.STORAGE_IMAGE;
                default -> classify(bare, use.end(), label);
            };
            add(slots, new Slot(at[0], at[1] + i, type, "CG_GL_K" + kind + "_" + i), label);
        }
    }

    private static void add(Map<Long, Slot> slots, Slot slot, String label) {
        Slot was = slots.putIfAbsent(key(slot), slot);
        if (was != null && was.type != slot.type) {
            throw new CgShaderModule.CompileException(label + ": set " + slot.set + " binding " + slot.binding + " is both "
                    + was.type + " and " + slot.type);
        }
    }

    /** What the declaration after {@code at} binds: a block, a buffer, a sampler or an image. */
    private static CgBindingLayout.Type classify(String bare, int at, String label) {
        int end = at;
        while (end < bare.length() && bare.charAt(end) != ';' && bare.charAt(end) != '{') end++;
        String decl = bare.substring(at, end);
        if (decl.matches("(?s).*\\bbuffer\\b.*")) return CgBindingLayout.Type.STORAGE_BUFFER;
        Matcher type = TYPE.matcher(decl);
        if (type.find()) {
            if (type.group(2).equals("image")) return CgBindingLayout.Type.STORAGE_IMAGE;
            return type.group(3).equals("Buffer") ? CgBindingLayout.Type.TEXEL_BUFFER : CgBindingLayout.Type.SAMPLED_TEXTURE;
        }
        if (decl.matches("(?s).*\\buniform\\b.*") && end < bare.length() && bare.charAt(end) == '{') {
            return CgBindingLayout.Type.UNIFORM_BUFFER;
        }
        throw refused(label, bare, at, "a binding of nothing it can class: " + decl.trim());
    }

    private static void refusePushConstants(String source, String label) {
        String bare = CgGlslSources.withoutComments(source);
        int at = bare.indexOf("push_constant");
        if (at >= 0) throw refused(label, bare, at, "a push_constant block other than the kernel's");
    }

    /** Each named vertex input at its location, as the GL program binds it; any other input is refused. */
    private static String placeInputs(String vertex, Map<String, Integer> locations, String label) {
        Matcher m = INPUT.matcher(CgGlslSources.withoutComments(vertex));
        StringBuilder out = new StringBuilder(vertex.length() + 64);
        int from = 0;
        while (m.find()) {
            Integer location = locations.get(m.group(3));
            if (location == null) throw refused(label, vertex, m.start(), "vertex input " + m.group(3) + " has no location");
            out.append(vertex, from, m.end(1)).append("layout(location = ").append(location).append(") in ");
            from = m.start(2);
        }
        return from == 0 ? vertex : out.append(vertex, from, vertex.length()).toString();
    }

    /** The colour attachments a fragment stage writes: its highest location, plus one. */
    private static int outputs(String fragment) {
        String bare = CgGlslSources.withoutComments(fragment);
        int most = 0;
        Matcher m = OUTPUT.matcher(bare);
        while (m.find()) most = Math.max(most, Integer.parseInt(m.group(1)) + 1);
        return most > 0 ? most : PLAIN_OUTPUT.matcher(bare).find() ? 1 : 0;
    }

    /** Each set's active slots in binding order, sets up to the highest read. */
    private static List<List<CgBindingLayout.Slot>> sets(Map<Long, Slot> slots) {
        int count = 0;
        for (Slot s : slots.values()) if (s.active) count = Math.max(count, s.set + 1);
        List<List<CgBindingLayout.Slot>> sets = new ArrayList<>(count);
        for (int s = 0; s < count; s++) sets.add(new ArrayList<>());
        for (Slot s : slots.values()) if (s.active) sets.get(s.set).add(new CgBindingLayout.Slot(s.binding, s.type));
        List<List<CgBindingLayout.Slot>> out = new ArrayList<>(count);
        for (List<CgBindingLayout.Slot> set : sets) out.add(List.copyOf(set));
        return List.copyOf(out);
    }

    /**
     * {@link #MARK}, then on one line each define once: as {@code define,set,binding,class} where the program reads its
     * slot, alone where it never does (its point is any: nothing binds it), then the source.
     */
    private static ByteBuffer module(String source, Map<Long, Slot> slots) {
        Map<String, Slot> byDefine = new TreeMap<>();
        for (Slot s : slots.values()) {
            Slot was = byDefine.get(s.define);
            if (was == null || !was.active && s.active) byDefine.put(s.define, s);
        }
        StringBuilder sb = new StringBuilder(source.length() + 64 * byDefine.size());
        sb.append(MARK).append("\n//");
        for (Slot s : byDefine.values()) {
            sb.append(' ').append(s.define);
            if (s.active) sb.append(',').append(s.set).append(',').append(s.binding).append(',').append(GLBindingPoints.classOf(s.type));
        }
        sb.append('\n').append(source);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
    }

    private static String lineOf(String s, int at) {
        int start = s.lastIndexOf('\n', at - 1) + 1, end = s.indexOf('\n', at);
        return s.substring(start, end < 0 ? s.length() : end);
    }

    private static CgShaderModule.CompileException refused(String label, String source, int at, String why) {
        return new CgShaderModule.CompileException(label + " line " + CgGlslSources.lineAt(source, at) + ": " + why
                + " (the GL driver's own compile, before the shader toolchain)");
    }
}
