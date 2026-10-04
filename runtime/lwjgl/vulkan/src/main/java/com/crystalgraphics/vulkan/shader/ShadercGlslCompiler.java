package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * A GL program's GLSL to SPIR-V, the way Minecraft 26.2 compiles its own shaders: shaderc on the source as written,
 * with bindings and locations assigned automatically, then SPIRV-Cross reflection and the decoration words set in
 * place so the stages agree by name.
 *
 * <pre>{@code
 * try (ShadercGlslCompiler compiler = new ShadercGlslCompiler()) {
 *     CgGlslCompiler.Program p = compiler.compile(vertexGlsl, fragmentGlsl, Map.of("cg_Position", 0), "text");
 * }
 * }</pre>
 *
 * <p>A host keeps what it compiles across launches, so a second launch runs shaderc on nothing it ran it on before:</p>
 * <pre>{@code
 * new ShadercGlslCompiler(CgCacheDirectory.of("spirv"));   // a null folder keeps nothing
 * }</pre>
 *
 * <p>Two edits to the source, both checked by the compiler that follows: 26.2's own defines of
 * {@code gl_VertexID}/{@code gl_InstanceID} to their Vulkan names (every draw starts at instance 0, so they agree),
 * and, for the GL-depth vertex stage, {@code main} wrapped to map clip z from GL's range. glslang's relaxed Vulkan
 * rules take loose uniforms into one block per stage. Compiles are serialized, so a backend compiling in the
 * background shares it with its owner thread.</p>
 */
public final class ShadercGlslCompiler implements CgGlslCompiler, AutoCloseable {

    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b.*$");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final String DEFINES = "#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n";

    /** Every option set below, for the cache's key: a change to them is a new key. */
    private static final String OPTIONS = "vulkan 1.2, relaxed rules, auto-bound uniforms, auto-mapped locations";

    private final long compiler = shaderc_compiler_initialize();
    private final long options = shaderc_compile_options_initialize();
    private final SpirvCache cache;

    /** A compiler that keeps nothing. */
    public ShadercGlslCompiler() {
        this(null);
    }

    /** @param cacheDir where modules are kept across launches, or null for none */
    public ShadercGlslCompiler(Path cacheDir) {
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
        shaderc_compile_options_set_auto_bind_uniforms(options, true);
        shaderc_compile_options_set_auto_map_locations(options, true);
        cache = cacheDir == null ? null : new SpirvCache(cacheDir, OPTIONS);
    }

    @Override
    public synchronized Program compile(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations,
                                        String label) {
        String vertex = withDefines(vertexGlsl, label);
        SpirvModule glDepth = new SpirvModule(spirv(wrapMain(vertex, label), shaderc_glsl_vertex_shader, label), label);
        SpirvModule zeroToOne = new SpirvModule(spirv(vertex, shaderc_glsl_vertex_shader, label), label);
        SpirvModule fragment = new SpirvModule(spirv(withDefines(fragmentGlsl, label), shaderc_glsl_fragment_shader, label),
                label);

        int next = 0;
        int vLoose = has(glDepth.uniformBuffers, SpirvModule.DEFAULT_BLOCK) ? next++ : -1;
        int fLoose = has(fragment.uniformBuffers, SpirvModule.DEFAULT_BLOCK) ? next++ : -1;
        Map<String, Integer> uniformBlocks = new LinkedHashMap<>(), storageBlocks = new LinkedHashMap<>();
        Map<String, SpirvModule.Resource> samplers = new LinkedHashMap<>();
        Map<String, Integer> samplerBindings = new HashMap<>();
        for (SpirvModule m : new SpirvModule[] {glDepth, fragment}) {
            for (SpirvModule.Resource r : m.uniformBuffers) {
                if (!r.name().equals(SpirvModule.DEFAULT_BLOCK) && !uniformBlocks.containsKey(r.name())) uniformBlocks.put(r.name(), next++);
            }
        }
        for (SpirvModule m : new SpirvModule[] {glDepth, fragment}) {
            for (SpirvModule.Resource r : m.storageBuffers) if (!storageBlocks.containsKey(r.name())) storageBlocks.put(r.name(), next++);
        }
        for (SpirvModule m : new SpirvModule[] {glDepth, fragment}) {
            for (SpirvModule.Resource r : m.samplers) {
                if (!samplers.containsKey(r.name())) {
                    samplers.put(r.name(), r);
                    samplerBindings.put(r.name(), next++);
                }
            }
        }

        for (SpirvModule m : new SpirvModule[] {glDepth, zeroToOne, fragment}) {
            int loose = m == fragment ? fLoose : vLoose;
            for (SpirvModule.Resource r : m.uniformBuffers) {
                m.set(r, r.name().equals(SpirvModule.DEFAULT_BLOCK) ? loose : uniformBlocks.get(r.name()));
            }
            for (SpirvModule.Resource r : m.storageBuffers) m.set(r, storageBlocks.get(r.name()));
            for (SpirvModule.Resource r : m.samplers) m.set(r, samplerBindings.get(r.name()));
        }

        List<Attribute> attributes = vertexInputs(glDepth, zeroToOne, attribLocations);
        Map<String, Integer> outputs = new HashMap<>();
        for (SpirvModule.Resource r : glDepth.outputs) outputs.put(r.name(), r.value());
        for (SpirvModule.Resource r : fragment.inputs) {
            Integer loc = outputs.get(r.name());
            if (loc == null) throw new CgShaderModule.CompileException(label + ": the fragment stage reads '" + r.name()
                    + "', which the vertex stage does not write");
            fragment.set(r, loc);
        }

        List<Block> ub = new ArrayList<>(), sb = new ArrayList<>();
        List<Sampler> smp = new ArrayList<>();
        List<CgBindingLayout.Slot> slots = new ArrayList<>();
        if (vLoose >= 0) slots.add(new CgBindingLayout.Slot(vLoose, CgBindingLayout.Type.UNIFORM_BUFFER));
        if (fLoose >= 0) slots.add(new CgBindingLayout.Slot(fLoose, CgBindingLayout.Type.UNIFORM_BUFFER));
        uniformBlocks.forEach((name, b) -> {
            ub.add(new Block(name, b));
            slots.add(new CgBindingLayout.Slot(b, CgBindingLayout.Type.UNIFORM_BUFFER));
        });
        storageBlocks.forEach((name, b) -> {
            sb.add(new Block(name, b));
            slots.add(new CgBindingLayout.Slot(b, CgBindingLayout.Type.STORAGE_BUFFER));
        });
        samplers.forEach((name, r) -> {
            int b = samplerBindings.get(name);
            smp.add(new Sampler(name, b, r.glType(), r.texel()));
            slots.add(new CgBindingLayout.Slot(b, r.texel() ? CgBindingLayout.Type.TEXEL_BUFFER : CgBindingLayout.Type.SAMPLED_TEXTURE));
        });
        slots.sort(Comparator.comparingInt(CgBindingLayout.Slot::binding));

        return new Program(glDepth.spirv, zeroToOne.spirv, fragment.spirv, attributes, ub, sb, smp,
                merge(glDepth.uniforms, fragment.uniforms), vLoose, glDepth.defaultBlockSize, fLoose,
                fragment.defaultBlockSize, slots);
    }

    @Override
    public synchronized ComputeProgram compileCompute(String glsl, String label) {
        SpirvModule m = new SpirvModule(spirv(glsl, shaderc_glsl_compute_shader, label), label);
        int next = 0;
        int loose = has(m.uniformBuffers, SpirvModule.DEFAULT_BLOCK) ? next++ : -1;
        List<Block> ub = new ArrayList<>(), sb = new ArrayList<>();
        List<Sampler> smp = new ArrayList<>();
        List<Image> img = new ArrayList<>();
        List<CgBindingLayout.Slot> slots = new ArrayList<>();
        if (loose >= 0) slots.add(new CgBindingLayout.Slot(loose, CgBindingLayout.Type.UNIFORM_BUFFER));
        for (SpirvModule.Resource r : m.uniformBuffers) {
            if (r.name().equals(SpirvModule.DEFAULT_BLOCK)) {
                m.set(r, loose);
                continue;
            }
            m.set(r, next);
            ub.add(new Block(r.name(), next));
            slots.add(new CgBindingLayout.Slot(next++, CgBindingLayout.Type.UNIFORM_BUFFER));
        }
        for (SpirvModule.Resource r : m.storageBuffers) {
            m.set(r, next);
            sb.add(new Block(r.name(), next));
            slots.add(new CgBindingLayout.Slot(next++, CgBindingLayout.Type.STORAGE_BUFFER));
        }
        for (SpirvModule.Resource r : m.samplers) {
            m.set(r, next);
            smp.add(new Sampler(r.name(), next, r.glType(), r.texel()));
            slots.add(new CgBindingLayout.Slot(next++, r.texel() ? CgBindingLayout.Type.TEXEL_BUFFER
                    : CgBindingLayout.Type.SAMPLED_TEXTURE));
        }
        for (SpirvModule.Resource r : m.images) {
            m.set(r, next);
            img.add(new Image(r.name(), next, r.glType()));
            slots.add(new CgBindingLayout.Slot(next++, CgBindingLayout.Type.STORAGE_IMAGE));
        }
        return new ComputeProgram(m.spirv, m.localSize.clone(), ub, sb, smp, img, List.copyOf(m.uniforms), loose,
                m.defaultBlockSize, slots);
    }

    /**
     * Bound inputs at their {@code glBindAttribLocation} locations; every other input where glslang placed it — its
     * {@code layout(location)}, else declaration order, as GL drivers commonly do — unless a binding holds that
     * location, when it takes the lowest one left free.
     */
    private static List<Attribute> vertexInputs(SpirvModule glDepth, SpirvModule zeroToOne, Map<String, Integer> bound) {
        BitSet used = new BitSet();
        for (SpirvModule.Resource r : glDepth.inputs) {
            Integer loc = bound.get(r.name());
            if (loc != null) used.set(loc, loc + r.locations());
        }
        Integer[] placed = new Integer[glDepth.inputs.size()];
        for (int i = 0; i < placed.length; i++) {
            SpirvModule.Resource r = glDepth.inputs.get(i);
            if (bound.containsKey(r.name()) || !used.get(r.value(), r.value() + r.locations()).isEmpty()) continue;
            placed[i] = r.value();
            used.set(r.value(), r.value() + r.locations());
        }
        List<Attribute> out = new ArrayList<>();
        for (int i = 0; i < glDepth.inputs.size(); i++) {
            SpirvModule.Resource r = glDepth.inputs.get(i);
            Integer loc = bound.containsKey(r.name()) ? bound.get(r.name()) : placed[i];
            if (loc == null) {
                loc = 0;
                while (!used.get(loc, loc + r.locations()).isEmpty()) loc++;
                used.set(loc, loc + r.locations());
            }
            glDepth.set(r, loc);
            zeroToOne.set(zeroToOne.inputs.get(i), loc);
            out.add(new Attribute(r.name(), loc, r.glType()));
        }
        out.sort(Comparator.comparingInt(Attribute::location));
        return out;
    }

    private static List<Uniform> merge(List<Uniform> vertex, List<Uniform> fragment) {
        Map<String, Uniform> out = new LinkedHashMap<>();
        for (Uniform u : vertex) out.put(u.name(), u);
        for (Uniform f : fragment) {
            Uniform v = out.get(f.name());
            out.put(f.name(), v == null
                    ? new Uniform(f.name(), f.glType(), f.count(), f.stride(), f.matrixStride(), -1, f.vertexOffset(),
                            f.columns(), f.rows(), f.integer())
                    : new Uniform(v.name(), v.glType(), v.count(), v.stride(), v.matrixStride(), v.vertexOffset(),
                            f.vertexOffset(), v.columns(), v.rows(), v.integer()));
        }
        return List.copyOf(out.values());
    }

    private static boolean has(List<SpirvModule.Resource> rs, String name) {
        for (SpirvModule.Resource r : rs) if (r.name().equals(name)) return true;
        return false;
    }

    private ByteBuffer spirv(String source, int kind, String label) {
        ByteBuffer kept = cache == null ? null : cache.get(kind, source);
        if (kept != null) return kept;
        ByteBuffer words = compile(source, kind, label);
        if (cache != null) cache.put(kind, source, words);
        return words;
    }

    private ByteBuffer compile(String source, int kind, String label) {
        // The source on the native heap: an expanded material outgrows MemoryStack, which the String overload uses.
        ByteBuffer text = memUTF8(source, false);
        long result;
        try (MemoryStack stack = stackPush()) {
            result = shaderc_compile_into_spv(compiler, text, kind, stack.UTF8(label), stack.UTF8("main"), options);
        } finally {
            memFree(text);
        }
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new CgShaderModule.CompileException(label + " (" + stage(kind) + "): "
                        + shaderc_result_get_error_message(result));
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.nativeOrder());
            copy.put(bytes).flip();
            return copy;
        } finally {
            shaderc_result_release(result);
        }
    }

    private static String stage(int kind) {
        return kind == shaderc_glsl_vertex_shader ? "vertex" : kind == shaderc_glsl_fragment_shader ? "fragment" : "compute";
    }

    /** 26.2's two defines after {@code #version}, and a {@code #line} so errors keep the source's numbering. */
    private static String withDefines(String source, String label) {
        Matcher m = VERSION.matcher(source);
        if (!m.find()) throw new CgShaderModule.CompileException(label + ": no #version");
        int line = 2 + count(source, m.end());
        return source.substring(0, m.end()) + "\n" + DEFINES + "#line " + line + source.substring(m.end());
    }

    /** {@code main} renamed, and a {@code main} after it that calls it and maps clip z from [-w, w] to [0, w]. */
    private static String wrapMain(String source, String label) {
        Matcher m = MAIN.matcher(withoutComments(source));
        if (!m.find()) throw new CgShaderModule.CompileException(label + ": the vertex stage has no main()");
        return source.substring(0, m.start()) + "void cg_main_user()" + source.substring(m.end())
                + "\nvoid main() {\n    cg_main_user();\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
    }

    private static int count(String s, int end) {
        int n = 0;
        for (int i = 0; i < end; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /** Comments as spaces, so a match's offsets are the source's. */
    private static String withoutComments(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') c[i++] = ' ';
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    if (c[i] != '\n') c[i] = ' ';
                    i++;
                }
                if (i < c.length) c[i] = ' ';
                if (i + 1 < c.length) c[++i] = ' ';
            }
        }
        return new String(c);
    }

    @Override
    public void close() {
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
    }
}
