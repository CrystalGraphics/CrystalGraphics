package com.crystalgraphics.platform.gl.tracked.glsl;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Vulkan GLSL through LWJGL's shaderc, for the tests: the compiler's message, or {@code null} when it compiles. */
final class Shaderc {

    private static final long COMPILER = shaderc_compiler_initialize();
    private static final long OPTIONS = options();

    private Shaderc() {}

    private static long options() {
        long o = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(o, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        return o;
    }

    static String errors(String source, boolean vertex, String name) {
        long result = shaderc_compile_into_spv(COMPILER, source,
                vertex ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader, name, "main", OPTIONS);
        try {
            return shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success
                    ? null : shaderc_result_get_error_message(result);
        } finally {
            shaderc_result_release(result);
        }
    }

    /** Fails with the compiler's message and the numbered source when any stage does not compile. */
    static void assertCompiles(CgGlslRewrite.Program p) {
        check(p.vertexGlDepth(), true, "vertex (GL depth)");
        check(p.vertexZeroToOne(), true, "vertex (zero-to-one)");
        check(p.fragment(), false, "fragment");
    }

    private static void check(String source, boolean vertex, String what) {
        String e = errors(source, vertex, what);
        if (e == null) return;
        StringBuilder numbered = new StringBuilder();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) numbered.append(i + 1).append(": ").append(lines[i]).append('\n');
        throw new AssertionError(what + " does not compile for Vulkan:\n" + e + "\n" + numbered);
    }
}
