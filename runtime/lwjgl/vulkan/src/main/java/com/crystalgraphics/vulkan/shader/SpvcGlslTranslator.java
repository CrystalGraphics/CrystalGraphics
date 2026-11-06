package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.pipeline.GLShaderTranslator;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * {@link GLShaderTranslator} by SPIRV-Cross's GLSL backend, as Unreal's OpenGL RHI takes cross-compiled GLSL: every
 * resource's set and binding rewritten to its GL binding point, push constants emitted as a uniform block, and the
 * bindless heap's runtime sampler array declared as a storage block of {@code ARB_bindless_texture} handles.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(driver, new SpvcGlslTranslator(), width, height, true);
 * }</pre>
 *
 * <p>Needs LWJGL's spvc natives, which ship with LWJGL 3.3 and newer.</p>
 */
public final class SpvcGlslTranslator implements GLShaderTranslator {

    private static final int[][] CLASSES = {
            {SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, GLBindingPoints.UNIFORM},
            {SPVC_RESOURCE_TYPE_STORAGE_BUFFER, GLBindingPoints.STORAGE},
            {SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, GLBindingPoints.TEXTURE},
            {SPVC_RESOURCE_TYPE_STORAGE_IMAGE, GLBindingPoints.IMAGE}};

    @Override
    public String glsl(ByteBuffer spirv, CgShaderModule.Stage stage, GLBindingPoints points, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.callocPointer(1);
            check(spvc_context_create(p), "context", label);
            long context = p.get(0);
            IntBuffer words = withoutDescriptorIndexing(spirv);
            try {
                check(spvc_context_parse_spirv(context, words, words.remaining(), p), "parse", label);
                long ir = p.get(0);
                check(spvc_context_create_compiler(context, SPVC_BACKEND_GLSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p),
                        "compiler", label);
                long compiler = p.get(0);
                check(spvc_compiler_create_compiler_options(compiler, p), "options", label);
                long options = p.get(0);
                spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, 460);
                spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ES, false);
                spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, false);
                spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER, true);
                check(spvc_compiler_install_compiler_options(compiler, options), "install options", label);
                // the active resources alone, as the layout reflects them: a declared, unread one has no point
                check(spvc_compiler_get_active_interface_variables(compiler, p), "active variables", label);
                long active = p.get(0);
                check(spvc_compiler_set_enabled_interface_variables(compiler, active), "enable active", label);
                check(spvc_compiler_create_shader_resources_for_active_variables(compiler, p, active), "resources", label);
                long resources = p.get(0);

                List<String> heaps = new ArrayList<>(1);
                for (int[] c : CLASSES) {
                    for (SpvcReflectedResource r : list(stack, resources, c[0], label)) {
                        int set = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationDescriptorSet);
                        int binding = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationBinding);
                        int kind = c[1];
                        if (c[0] == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE && runtimeArray(compiler, r.type_id())) {
                            kind = GLBindingPoints.STORAGE;
                            heaps.add(r.nameString());
                        }
                        spvc_compiler_unset_decoration(compiler, r.id(), SpvDecorationDescriptorSet);
                        spvc_compiler_set_decoration(compiler, r.id(), SpvDecorationBinding, points.point(set, binding, kind));
                    }
                }
                for (SpvcReflectedResource r : list(stack, resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT, label)) {
                    spvc_compiler_set_decoration(compiler, r.id(), SpvDecorationBinding, points.pushConstants());
                }
                if (spvc_compiler_compile(compiler, p) != SPVC_SUCCESS)
                    throw new CgShaderModule.CompileException(label + " " + stage + ": SPIRV-Cross: "
                            + spvc_context_get_last_error_string(context));
                String glsl = memUTF8(p.get(0));
                return heaps.isEmpty() ? glsl : bindless(glsl, heaps, label);
            } finally {
                spvc_context_destroy(context);
                memFree(words);
            }
        }
    }

    /** Raised whenever its output changes for the same SPIR-V: 2, a fragment stage's origin lower-left. */
    @Override
    public String version() {
        return "spvc-glsl 2";
    }

    private static final Pattern HEAP = Pattern.compile("layout\\(binding = (\\d+)\\) uniform (\\w+) (\\w+)\\[\\];");

    /** The heap's sampler arrays as storage blocks of bindless handles; GL has no runtime-sized sampler array. */
    private static String bindless(String glsl, List<String> heaps, String label) {
        StringBuilder out = new StringBuilder(glsl.length() + 256);
        Matcher m = HEAP.matcher(glsl);
        int at = 0, found = 0;
        while (m.find()) {
            if (!heaps.contains(m.group(3))) continue;
            out.append(glsl, at, m.start());
            out.append("layout(binding = ").append(m.group(1)).append(", std430) readonly buffer CgHeap_").append(m.group(3))
                    .append(" { ").append(m.group(2)).append(' ').append(m.group(3)).append("[]; };");
            at = m.end();
            found++;
        }
        if (found != heaps.size())
            throw new CgShaderModule.CompileException(label + ": the bindless heap's declaration not found in\n" + glsl);
        out.append(glsl, at, glsl.length());
        String s = out.toString()
                .replace("#extension GL_EXT_nonuniform_qualifier : require\n", "")
                .replace("nonuniformEXT(", "(");
        int line = s.indexOf('\n') + 1;   // after #version
        return s.substring(0, line) + "#extension GL_ARB_bindless_texture : require\n" + s.substring(line);
    }

    /**
     * The module's words made GL's: descriptor indexing's capabilities turned into {@code Shader} (SPIRV-Cross refuses
     * them outside Vulkan GLSL, and GL reads the heap through bindless handles, which need neither), and a fragment
     * stage's {@code OriginUpperLeft} made {@code OriginLowerLeft}: under {@code GL_LOWER_LEFT} clip control a lower-left
     * {@code gl_FragCoord} has row 0 where Vulkan's has it, where an upper-left one would count rows from the other end.
     */
    private static IntBuffer withoutDescriptorIndexing(ByteBuffer spirv) {
        IntBuffer in = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        IntBuffer words = memAllocInt(in.remaining());
        words.put(in).flip();
        for (int at = 5; at < words.limit(); ) {
            int word = words.get(at), count = word >>> 16;
            if ((word & 0xFFFF) == OP_CAPABILITY && count == 2 && words.get(at + 1) >= CAPABILITY_NON_UNIFORM_FIRST
                    && words.get(at + 1) <= CAPABILITY_NON_UNIFORM_LAST) words.put(at + 1, SpvCapabilityShader);
            if ((word & 0xFFFF) == OP_EXECUTION_MODE && count >= 3 && words.get(at + 2) == MODE_ORIGIN_UPPER_LEFT) {
                words.put(at + 2, MODE_ORIGIN_LOWER_LEFT);
            }
            if (count == 0) break;
            at += count;
        }
        return words;
    }

    private static final int OP_CAPABILITY = 17;

    private static final int OP_EXECUTION_MODE = 16, MODE_ORIGIN_UPPER_LEFT = 7, MODE_ORIGIN_LOWER_LEFT = 8;
    /** {@code ShaderNonUniform} (5301) to {@code StorageTexelBufferArrayNonUniformIndexing} (5312). */
    private static final int CAPABILITY_NON_UNIFORM_FIRST = 5301, CAPABILITY_NON_UNIFORM_LAST = 5312;

    private static boolean runtimeArray(long compiler, int typeId) {
        long type = spvc_compiler_get_type_handle(compiler, typeId);
        int dims = spvc_type_get_num_array_dimensions(type);
        return dims > 0 && spvc_type_get_array_dimension(type, 0) == 0;
    }

    private static SpvcReflectedResource.Buffer list(MemoryStack stack, long resources, int type, String label) {
        PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
        check(spvc_resources_get_resource_list_for_type(resources, type, list, count), "resource list", label);
        return SpvcReflectedResource.create(list.get(0), (int) count.get(0));
    }

    private static void check(int result, String what, String label) {
        if (result != SPVC_SUCCESS)
            throw new CgShaderModule.CompileException(label + ": SPIRV-Cross " + what + " failed (" + result + ")");
    }
}
