package io.github.somehussar.crystalgraphics.gl.material.parse;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.material.CgAttachedBuffer;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgMaterialShaderCompiler}.
 *
 * <p>All tests are pure string transformation — no GL context required.
 * The TBO path ({@code ShaderBufferPath.TBO}) is used throughout because it requires only
 * GL 3.3, while SSBO would require GL 4.3 capabilities detection.</p>
 */
public class CgMaterialShaderCompilerTest {

    private static final CgCapabilities.ShaderBufferPath TBO =
            CgCapabilities.ShaderBufferPath.TBO;

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

    /** Minimal valid shader body. */
    private static final String MINIMAL =
            "#type spatial\n" +
            "struct v2f {\n    vec2 uv;\n};\n" +
            "void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
            "void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n";

    private static CgParsedShader parse(String src) {
        return CgShaderParser.parse(src, "test");
    }

    // ── Version directive ─────────────────────────────────────────────────────

    @Test
    public void tboPath_emits330Version() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue("Vertex must start with #version 330 core",
                cs.vertexSource().startsWith("#version 330 core"));
        assertTrue("Fragment must start with #version 330 core",
                cs.fragmentSource().startsWith("#version 330 core"));
    }

    @Test
    public void ssboGl43Path_emits430Version() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL),
                        CgCapabilities.ShaderBufferPath.SSBO_GL43, NO_BUFFERS);
        assertTrue(cs.vertexSource().startsWith("#version 430 core"));
        assertTrue(cs.fragmentSource().startsWith("#version 430 core"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonePath_throws() {
        CgMaterialShaderCompiler.compile(parse(MINIMAL),
                CgCapabilities.ShaderBufferPath.NONE, NO_BUFFERS);
    }

    // ── Define injection ──────────────────────────────────────────────────────

    @Test
    public void vertex_definesVertexStage() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("#define CG_VERTEX_STAGE 1"));
        assertFalse("Fragment must NOT have CG_VERTEX_STAGE",
                cs.fragmentSource().contains("CG_VERTEX_STAGE"));
    }

    @Test
    public void tboPath_doesNotEmitUseSsbo() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertFalse(cs.vertexSource().contains("CG_USE_SSBO"));
        assertFalse(cs.fragmentSource().contains("CG_USE_SSBO"));
    }

    @Test
    public void ssboPath_emitsUseSsbo() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL),
                        CgCapabilities.ShaderBufferPath.SSBO_GL43, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("#define CG_USE_SSBO 1"));
        assertTrue(cs.fragmentSource().contains("#define CG_USE_SSBO 1"));
    }

    // ── env include ───────────────────────────────────────────────────────────

    @Test
    public void both_includeEnvGlsl() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("cg_env.glsl"));
        assertTrue(cs.fragmentSource().contains("cg_env.glsl"));
    }

    // ── v2f struct ────────────────────────────────────────────────────────────

    @Test
    public void both_containV2fStruct() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("struct v2f"));
        assertTrue(cs.fragmentSource().contains("struct v2f"));
    }

    @Test
    public void both_containCgV2fInterfaceBlock() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("_CgV2fBlock"));
        assertTrue(cs.fragmentSource().contains("_CgV2fBlock"));
    }

    // ── User bodies ───────────────────────────────────────────────────────────

    @Test
    public void vertex_containsUserVertexFunction() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("void vertex(out v2f o)"));
    }

    @Test
    public void fragment_containsUserFragmentFunction() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.fragmentSource().contains("void fragment(in v2f i"));
    }

    @Test
    public void both_containGeneratedMain() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), TBO, NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("void main()"));
        assertTrue(cs.fragmentSource().contains("void main()"));
    }

    // ── Property uniform emission ─────────────────────────────────────────────

    @Test
    public void samplerProperty_emittedAsUniform() {
        String src =
                "#type spatial\n" +
                "Properties {\n" +
                "    _MainTex : sampler2D\n" +
                "}\n" +
                "struct v2f {\n    vec2 uv;\n};\n" +
                "void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
                "void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n";
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(src), TBO, NO_BUFFERS);
        assertTrue("Sampler must be emitted as a uniform declaration",
                cs.fragmentSource().contains("uniform sampler2D _MainTex"));
    }

    @Test
    public void nonSamplerProperty_notEmittedAsIndividualUniform_whenNoMatPropsUbo() {
        // Without a matPropsUbo, float properties are NOT individually emitted either
        // (they go into the UBO only when matPropsUbo is non-null; old path = no emission)
        String src =
                "#type spatial\n" +
                "Properties {\n" +
                "    _Alpha : float = 1.0\n" +
                "}\n" +
                "struct v2f {\n    vec2 uv;\n};\n" +
                "void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
                "void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n";
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(src), TBO, NO_BUFFERS, null);
        // Non-sampler props without a UBO entry should NOT appear as "uniform float _Alpha"
        assertFalse("Non-sampler prop must not be emitted as a standalone uniform when matPropsUbo=null",
                cs.fragmentSource().contains("uniform float _Alpha"));
    }
}
