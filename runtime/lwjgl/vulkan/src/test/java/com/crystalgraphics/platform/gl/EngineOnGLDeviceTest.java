package com.crystalgraphics.platform.gl;

import org.lwjgl.system.MemoryUtil;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.binding.GLBindingLayout;
import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.ShippedPrograms;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.Assert.assertTrue;
import static org.lwjgl.opengl.GL46.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * Every program the shipped materials make, both forms (bound and bindless), built as a pipeline on the GL device; and
 * the two ways GL can take each measured: SPIRV-Cross's GLSL compiled by the driver, and that GLSL as GL SPIR-V
 * ({@code ARB_gl_spirv}). The measurement goes to {@code -Dcrystalgraphics.test.glShaderReport}, when given.
 */
public class EngineOnGLDeviceTest {

    private record Recorded(String label, String vertex, String fragment) {}

    /** The device's compiler, recording each material program's source, in its bound and bindless forms. */
    private record Recording(CgGlslCompiler linker, Consumer<Recorded> out) implements CgGlslCompiler {
        @Override
        public Program compile(String vertex, String fragment, Map<String, Integer> attribs, String label) {
            return linker.compile(vertex, fragment, attribs, label);
        }

        @Override
        public Explicit compileExplicit(String vertex, String fragment, Map<String, Integer> attribs, String label) {
            if (vertex.contains("CG_BIND(")) {
                out.accept(new Recorded(label, vertex, fragment));
                out.accept(new Recorded(label + " bindless", bindless(vertex), bindless(fragment)));
            }
            return linker.compileExplicit(vertex, fragment, attribs, label);
        }

        private static String bindless(String glsl) {
            int line = glsl.indexOf('\n', glsl.indexOf("#version"));
            return glsl.substring(0, line + 1) + "#define CG_BINDLESS 1\n" + glsl.substring(line + 1);
        }

        @Override
        public ComputeProgram compileCompute(String glsl, String label) {
            return linker.compileCompute(glsl, label);
        }

        @Override
        public ExplicitCompute compileExplicitCompute(String glsl, String label) {
            return linker.compileExplicitCompute(glsl, label);
        }

        @Override
        public boolean compilesOnAnyThread() {
            return linker.compilesOnAnyThread();
        }
    }

    private CgGLDevice device;
    private final SpvcGlslTranslator translator = new SpvcGlslTranslator();
    private long shaderc;
    private final List<String> failures = new ArrayList<>(), spirvFailures = new ArrayList<>();
    private long glslDriver, spirvCompile, spirvDriver, translate;
    private int recorded, built, spirvBuilt;

    @Test
    public void everyShippedProgramBuildsOnTheGLDevice() throws Exception {
        try (GLTestWindow window = new GLTestWindow()) {
            device = new CgGLDevice(new Lwjgl3GLDriver(), translator, 64, 64, true);
            shaderc = shaderc_compiler_initialize();
            // Built after the walk: a compile's caller holds LWJGL's thread stack, which a second shaderc inside it overflows
            List<Recorded> programs = Collections.synchronizedList(new ArrayList<>());
            try (ShadercGlslCompiler linker = new ShadercGlslCompiler()) {
                new CgNullRHI(64, 64).install(new Recording(linker, programs::add));
                CgCapabilities.clearCache();
                CgBindingPoints.init(CgCapabilities.detect());
                CgFallbackTextures.init();
                Class.forName("com.crystalgraphics.text.render.CgTextMaterials");
                List<String> walkFailures = new ArrayList<>();
                ShippedPrograms.linkEveryVariant(walkFailures);
                assertTrue(String.join("\n", walkFailures), walkFailures.isEmpty());
            } finally {
            }
            for (Recorded r : programs) build(r);
            assertTrue("no program was recorded: the test proves nothing", recorded > 10);
            shaderc_compiler_release(shaderc);
            device.close();
            String report = "programs " + recorded + ", built on the"
                    + " device " + built + "\n"
                    + "GLSL path: SPIRV-Cross " + translate / 1_000_000 + " ms, driver compile+link " + glslDriver / 1_000_000
                    + " ms\n"
                    + "SPIR-V path (bound forms): " + spirvBuilt + " linked, shaderc " + spirvCompile / 1_000_000
                    + " ms, driver specialize+link " + spirvDriver / 1_000_000 + " ms, refused " + spirvFailures.size() + "\n"
                    + String.join("\n", spirvFailures.subList(0, Math.min(10, spirvFailures.size()))) + "\n"
                    + "renderer " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION) + "\n";
            String to = System.getProperty("crystalgraphics.test.glShaderReport");
            if (to != null) Files.writeString(Paths.get(to), report);
            assertTrue(String.join("\n", failures.subList(0, Math.min(20, failures.size()))), failures.isEmpty());
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    /** Builds one program on the device, then measures both paths on the same GLSL. */
    private void build(Recorded r) {
        recorded++;
        ByteBuffer vertex = vulkanSpirv(r.vertex(), shaderc_vertex_shader), fragment = vulkanSpirv(r.fragment(),
                shaderc_fragment_shader);
        if (vertex == null || fragment == null) {
            failures.add(r.label() + ": shaderc (Vulkan, strict) refused it");
            return;
        }
        CgPipelineLayout layout;
        int outputs;
        try {
            layout = layout(device, vertex, fragment);
            outputs = outputs(fragment);
        } catch (RuntimeException e) {
            failures.add(r.label() + ": reflection: " + e.getMessage());
            return;
        }
        CgShaderModule vs = device.createShaderModule(CgShaderModule.Stage.VERTEX, vertex, r.label());
        CgShaderModule fs = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, fragment, r.label());
        List<CgGraphicsPipelineDesc.ColorTarget> targets = new ArrayList<>();
        for (int i = 0; i < outputs; i++) targets.add(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA16_FLOAT, null, 0xF));
        try {
            CgGraphicsPipeline pipeline = device.createPipeline(new CgGraphicsPipelineDesc(r.label(), layout, vs, fs,
                    List.of(), CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                    CgGraphicsPipelineDesc.DepthStencil.OFF, targets, CgFormat.DEPTH32_FLOAT, 1));
            device.release(pipeline);
            built++;
        } catch (RuntimeException e) {
            failures.add(r.label() + ": " + firstLines(e.getMessage()));
            String dump = System.getProperty("crystalgraphics.test.glShaderReport");
            if (dump != null && failures.size() == 1) {
                GLBindingPoints at = new GLBindingPoints(layout);
                try {
                    String nl = System.lineSeparator();
                    Files.writeString(Paths.get(dump + ".failed.glsl"), at + nl + layout + nl + "=== vertex" + nl
                            + translator.glsl(vertex, CgShaderModule.Stage.VERTEX, at, r.label())
                            + nl + "=== fragment" + nl
                            + translator.glsl(fragment, CgShaderModule.Stage.FRAGMENT, at, r.label()));
                } catch (IOException ignored) {
                }
            }
            return;
        } finally {
            device.release(vs);
            device.release(fs);
            device.endFrame();
        }

        // the two paths, measured on what the driver does: the same GLSL text, compiled or ingested as SPIR-V
        GLBindingPoints points = new GLBindingPoints(layout);
        long t0 = System.nanoTime();
        String v = translator.glsl(vertex, CgShaderModule.Stage.VERTEX, points, r.label());
        String f = translator.glsl(fragment, CgShaderModule.Stage.FRAGMENT, points, r.label());
        translate += System.nanoTime() - t0;
        long t1 = System.nanoTime();
        linkText(v, f);
        glslDriver += System.nanoTime() - t1;
        if (v.contains("GL_ARB_bindless_texture") || f.contains("GL_ARB_bindless_texture")) return;
        long t2 = System.nanoTime();
        ByteBuffer vs2 = glSpirv(shaderc, v, shaderc_vertex_shader), fs2 = glSpirv(shaderc, f, shaderc_fragment_shader);
        spirvCompile += System.nanoTime() - t2;
        if (vs2 == null || fs2 == null) {
            spirvFailures.add(r.label() + ": shaderc (OpenGL) refused the GLSL");
            return;
        }
        long t3 = System.nanoTime();
        String log = linkSpirv(vs2, fs2);
        spirvDriver += System.nanoTime() - t3;
        if (log == null) spirvBuilt++;
        else spirvFailures.add(r.label() + ": " + firstLines(log));
    }

    private static String firstLines(String s) {
        String[] lines = s == null ? new String[0] : s.split("\n");
        return String.join(" | ", Arrays.copyOf(lines, Math.min(4, lines.length)));
    }

    /** Compiles and links {@code v} and {@code f} as GLSL, waiting for the link, then deletes it. */
    private static void linkText(String v, String f) {
        int program = glCreateProgram();
        int a = glCreateShader(GL_VERTEX_SHADER), b = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(a, v);
        glShaderSource(b, f);
        glCompileShader(a);
        glCompileShader(b);
        glAttachShader(program, a);
        glAttachShader(program, b);
        glLinkProgram(program);
        glGetProgrami(program, GL_LINK_STATUS);
        glDeleteShader(a);
        glDeleteShader(b);
        glDeleteProgram(program);
    }

    /**
     * Vulkan SPIR-V of a stage's source as written, sets and bindings its own: Vulkan's rules, nothing auto-bound, and
     * the two defines {@code ShadercGlslCompiler} puts after {@code #version}. Null where shaderc refuses it.
     */
    private ByteBuffer vulkanSpirv(String glsl, int kind) {
        int line = glsl.indexOf('\n', glsl.indexOf("#version")) + 1;
        String source = glsl.substring(0, line) + "#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n"
                + glsl.substring(line);
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_vulkan_rules_relaxed(options, false);
        shaderc_compile_options_set_auto_map_locations(options, true);
        long result = compileHeap(shaderc, source, kind, "material", options);
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                if (failures.size() < 3) failures.add(shaderc_result_get_error_message(result));
                return null;
            }
            ByteBuffer b = shaderc_result_get_bytes(result);
            return ByteBuffer.allocateDirect(b.remaining()).order(ByteOrder.LITTLE_ENDIAN).put(b).flip();
        } finally {
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
        }
    }

    /** shaderc on {@code source} from the native heap: a material's largest variants outgrow LWJGL's thread stack. */
    private static long compileHeap(long compiler, String source, int kind, String name, long options) {
        ByteBuffer text = MemoryUtil.memUTF8(source, false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return shaderc_compile_into_spv(compiler, text, kind, stack.UTF8(name), stack.UTF8("main"), options);
        } finally {
            MemoryUtil.memFree(text);
        }
    }

    /** GL SPIR-V of {@code glsl}, or null where shaderc refuses it. */
    private static ByteBuffer glSpirv(long compiler, String glsl, int kind) {
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
        long result = compileHeap(compiler, glsl, kind, "gl", options);
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) return null;
            ByteBuffer b = shaderc_result_get_bytes(result);
            return ByteBuffer.allocateDirect(b.remaining()).put(b).flip();
        } finally {
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
        }
    }

    /** Ingests both stages' SPIR-V and links them, waiting for the link; null when linked, else the log. */
    private static String linkSpirv(ByteBuffer v, ByteBuffer f) {
        int program = glCreateProgram();
        int[] shaders = {glCreateShader(GL_VERTEX_SHADER), glCreateShader(GL_FRAGMENT_SHADER)};
        ByteBuffer[] code = {v, f};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < 2; i++) {
                glShaderBinary(stack.ints(shaders[i]), GL_SHADER_BINARY_FORMAT_SPIR_V, code[i]);
                glSpecializeShader(shaders[i], "main", stack.mallocInt(0), stack.mallocInt(0));
                if (glGetShaderi(shaders[i], GL_COMPILE_STATUS) == 0) return glGetShaderInfoLog(shaders[i]);
                glAttachShader(program, shaders[i]);
            }
        }
        glLinkProgram(program);
        String log = glGetProgrami(program, GL_LINK_STATUS) == 0 ? glGetProgramInfoLog(program) : null;
        for (int s : shaders) glDeleteShader(s);
        glDeleteProgram(program);
        return log;
    }

    /** The pipeline layout both stages declare: each set's slots by binding, the heap's at set 3, the push range. */
    private static CgPipelineLayout layout(CgRHI device, ByteBuffer... stages) {
        Map<Integer, Map<Integer, CgBindingLayout.Type>> sets = new TreeMap<>();
        int push = 0;
        boolean heap = false;
        for (ByteBuffer spirv : stages) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer p = stack.callocPointer(1);
                spvc_context_create(p);
                long context = p.get(0);
                try {
                    spvc_context_parse_spirv(context, spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer(), spirv.remaining() / 4, p);
                    spvc_context_create_compiler(context, SPVC_BACKEND_NONE, p.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p);
                    long compiler = p.get(0);
                    spvc_compiler_create_shader_resources(compiler, p);
                    long resources = p.get(0);
                    int[][] kinds = {{SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, 0}, {SPVC_RESOURCE_TYPE_STORAGE_BUFFER, 1},
                            {SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, 2}, {SPVC_RESOURCE_TYPE_STORAGE_IMAGE, 3}};
                    for (int[] k : kinds) {
                        for (SpvcReflectedResource r : list(stack, resources, k[0])) {
                            int set = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationDescriptorSet);
                            int binding = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationBinding);
                            long type = spvc_compiler_get_type_handle(compiler, r.type_id());
                            boolean runtime = spvc_type_get_num_array_dimensions(type) > 0
                                    && spvc_type_get_array_dimension(type, 0) == 0;
                            if (runtime && k[0] == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE) {
                                heap = true;
                                continue;
                            }
                            boolean buffer = (k[0] == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE || k[0] == SPVC_RESOURCE_TYPE_STORAGE_IMAGE)
                                    && spvc_type_get_image_dimension(type) == SpvDimBuffer;
                            CgBindingLayout.Type t = switch (k[1]) {
                                case 0 -> CgBindingLayout.Type.UNIFORM_BUFFER;
                                case 1 -> CgBindingLayout.Type.STORAGE_BUFFER;
                                case 2 -> buffer ? CgBindingLayout.Type.TEXEL_BUFFER : CgBindingLayout.Type.SAMPLED_TEXTURE;
                                default -> CgBindingLayout.Type.STORAGE_IMAGE;
                            };
                            sets.computeIfAbsent(set, s -> new TreeMap<>()).put(binding, t);
                        }
                    }
                    for (SpvcReflectedResource r : list(stack, resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT)) {
                        PointerBuffer size = stack.callocPointer(1);
                        spvc_compiler_get_declared_struct_size(compiler, spvc_compiler_get_type_handle(compiler, r.base_type_id()), size);
                        push = Math.max(push, (int) size.get(0));
                    }
                } finally {
                    spvc_context_destroy(context);
                }
            }
        }
        int count = heap ? CgRHI.BINDLESS_SET + 1 : sets.isEmpty() ? 0 : ((TreeMap<Integer, ?>) sets).lastKey() + 1;
        List<CgBindingLayout> layouts = new ArrayList<>();
        for (int s = 0; s < count; s++) {
            if (heap && s == CgRHI.BINDLESS_SET) {
                layouts.add(device.bindlessLayout());
                continue;
            }
            List<CgBindingLayout.Slot> slots = new ArrayList<>();
            for (Map.Entry<Integer, CgBindingLayout.Type> e : sets.getOrDefault(s, Map.of()).entrySet()) {
                slots.add(new CgBindingLayout.Slot(e.getKey(), e.getValue()));
            }
            layouts.add(new GLBindingLayout("set " + s, slots, false, false));
        }
        return new CgPipelineLayout(layouts, (push + 3) / 4 * 4);
    }

    private static int outputs(ByteBuffer fragment) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.callocPointer(1);
            spvc_context_create(p);
            long context = p.get(0);
            try {
                spvc_context_parse_spirv(context, fragment.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer(), fragment.remaining() / 4, p);
                spvc_context_create_compiler(context, SPVC_BACKEND_NONE, p.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p);
                long compiler = p.get(0);
                spvc_compiler_create_shader_resources(compiler, p);
                int count = 0;
                for (SpvcReflectedResource r : list(stack, p.get(0), SPVC_RESOURCE_TYPE_STAGE_OUTPUT)) {
                    count = Math.max(count, spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationLocation) + 1);
                }
                return count;
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static SpvcReflectedResource.Buffer list(MemoryStack stack, long resources, int type) {
        PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
        spvc_resources_get_resource_list_for_type(resources, type, list, count);
        return SpvcReflectedResource.create(list.get(0), (int) count.get(0));
    }
}
