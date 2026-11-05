package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;

import java.util.Map;

/**
 * A compiler that proves a material's source binds everything it reads: each program carrying {@code CG_BIND}
 * ({@code CgBindingModel}) is compiled first with nothing auto-bound, failing on what it left unbound, and again in its
 * bindless form ({@code CG_BINDLESS}), then linked as {@code linker} links it. Raw programs and kernels go to
 * {@code linker} alone. For the tests linking every shipped shader on the tracked backend.
 *
 * <pre>{@code
 * try (ShadercGlslCompiler auto = new ShadercGlslCompiler(); ShadercGlslCompiler bare = new ShadercGlslCompiler(null, false, false)) {
 *     CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ExplicitBindingCheck(auto, bare), true);
 * }
 * }</pre>
 */
public record ExplicitBindingCheck(CgGlslCompiler linker, CgGlslCompiler bare) implements CgGlslCompiler {

    @Override
    public Program compile(String vertex, String fragment, Map<String, Integer> attribs, String label) {
        if (vertex.contains("CG_BIND(")) {
            bare.compile(vertex, fragment, attribs, label);
            bare.compile(bindless(vertex), bindless(fragment), attribs, label + " bindless");
        }
        return linker.compile(vertex, fragment, attribs, label);
    }

    /** The source as a device with a bindless heap compiles it: {@code CG_BINDLESS} defined after {@code #version}. */
    private static String bindless(String glsl) {
        int line = glsl.indexOf('\n', glsl.indexOf("#version"));
        return glsl.substring(0, line + 1) + "#define CG_BINDLESS 1\n" + glsl.substring(line + 1);
    }

    @Override
    public ComputeProgram compileCompute(String glsl, String label) {
        return linker.compileCompute(glsl, label);
    }
}
