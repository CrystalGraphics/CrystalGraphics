package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;

import java.util.Map;

/**
 * A compiler that proves a material's source binds everything it reads: each program carrying {@code CG_BIND}
 * ({@code CgBindingModel}) is compiled first with nothing auto-bound, failing on what it left unbound, then linked as
 * {@code linker} links it. Raw programs and kernels go to {@code linker} alone. For the tests linking every shipped
 * shader on the tracked backend.
 *
 * <pre>{@code
 * try (ShadercGlslCompiler auto = new ShadercGlslCompiler(); ShadercGlslCompiler bare = new ShadercGlslCompiler(null, false)) {
 *     CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ExplicitBindingCheck(auto, bare), true);
 * }
 * }</pre>
 *
 * <p>The draw bases {@code cg_InstanceBase} and {@code cg_VertexBase} are the last loose uniforms a material has; the
 * check reads them as constants until they go (SH4).</p>
 */
public record ExplicitBindingCheck(CgGlslCompiler linker, CgGlslCompiler bare) implements CgGlslCompiler {

    @Override
    public Program compile(String vertex, String fragment, Map<String, Integer> attribs, String label) {
        if (vertex.contains("CG_BIND(")) bare.compile(withoutDrawBases(vertex), withoutDrawBases(fragment), attribs, label);
        return linker.compile(vertex, fragment, attribs, label);
    }

    @Override
    public ComputeProgram compileCompute(String glsl, String label) {
        return linker.compileCompute(glsl, label);
    }

    private static String withoutDrawBases(String glsl) {
        return glsl.replace("uniform int cg_InstanceBase;", "const int cg_InstanceBase = 0;")
                .replace("uniform int cg_VertexBase;", "const int cg_VertexBase = 0;");
    }
}
