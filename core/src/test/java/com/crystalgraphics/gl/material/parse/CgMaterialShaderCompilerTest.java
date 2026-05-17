package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CgMaterialShaderCompilerTest {

    private static final CgCapabilities.ShaderBufferPath TBO =
            CgCapabilities.ShaderBufferPath.TBO;

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

    @BeforeClass
    public static void injectTboCapabilities() throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, CgCapabilities.ShaderBufferPath.TBO);
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, stub);
    }

    @AfterClass
    public static void clearCapabilitiesCache() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }

    /** Minimal valid shader body. */
    private static final String MINIMAL =
            "#type spatial\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n};\n" +
            "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
            "}\n";

    private static CgParsedShader parse(String src) {
        return CgShaderParser.parse(src, "test");
    }

    // ── Version directive ─────────────────────────────────────────────────────

    @Test
    public void tboPath_emits_versionDirective() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue("Vertex must start with #version",
                cs.vertexSource().startsWith("#version"));
        assertTrue("Fragment must start with #version",
                cs.fragmentSource().startsWith("#version"));
    }

    // ── Define injection ──────────────────────────────────────────────────────

    @Test
    public void vertex_definesVertexStage() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("#define CG_VERTEX_STAGE 1"));
        assertFalse("Fragment must NOT have CG_VERTEX_STAGE",
                cs.fragmentSource().contains("CG_VERTEX_STAGE"));
    }

    @Test
    public void tboPath_doesNotEmitUseSsbo() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertFalse(cs.vertexSource().contains("CG_USE_SSBO"));
        assertFalse(cs.fragmentSource().contains("CG_USE_SSBO"));
    }

    // ── env include ───────────────────────────────────────────────────────────

    @Test
    public void both_includeEnvGlsl() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("cg_env.glsl"));
        assertTrue(cs.fragmentSource().contains("cg_env.glsl"));
    }

    // ── v2f struct ────────────────────────────────────────────────────────────

    @Test
    public void both_containV2fStruct() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("struct v2f"));
        assertTrue(cs.fragmentSource().contains("struct v2f"));
    }

    @Test
    public void both_containCgV2fInterfaceBlock() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("_CgV2fBlock"));
        assertTrue(cs.fragmentSource().contains("_CgV2fBlock"));
    }

    // ── User bodies ───────────────────────────────────────────────────────────

    @Test
    public void vertex_containsUserVertexFunction() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.vertexSource().contains("void vertex(out v2f o)"));
    }

    @Test
    public void fragment_containsUserFragmentFunction() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.fragmentSource().contains("void fragment(in v2f i"));
    }

    @Test
    public void both_containGeneratedMain() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
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
                "Pass {\n" +
                "    Tags { \"LightMode\" = \"Forward\" }\n" +
                "    struct v2f {\n    vec2 uv;\n};\n" +
                "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
                "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
                "}\n";
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(src), NO_BUFFERS);
        assertTrue("Sampler must be emitted as a uniform declaration",
                cs.fragmentSource().contains("uniform sampler2D _MainTex"));
    }

    @Test
    public void nonSamplerProperty_notEmittedAsIndividualUniform_whenNoMatPropsUbo() {
        String src =
                "#type spatial\n" +
                "Properties {\n" +
                "    _Alpha : float = 1.0\n" +
                "}\n" +
                "Pass {\n" +
                "    Tags { \"LightMode\" = \"Forward\" }\n" +
                "    struct v2f {\n    vec2 uv;\n};\n" +
                "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
                "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
                "}\n";
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(src), TBO, NO_BUFFERS, null);
        assertFalse("Non-sampler prop must not be emitted as a standalone uniform when matPropsUbo=null",
                cs.fragmentSource().contains("uniform float _Alpha"));
    }

    // ── MRT output-struct tests (T5) ──────────────────────────────────────────

    private static final String MRT_3_SHADER =
            "#type spatial\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n};\n" +
            "    struct GBuffer {\n" +
            "        vec4 albedo : RT0;\n" +
            "        vec4 normal : RT1;\n" +
            "        vec4 material : RT2;\n" +
            "    };\n" +
            "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
            "    void fragment(in v2f i, out GBuffer o) { o.albedo = vec4(1.0); }\n" +
            "}\n";

    private static final String MRT_SKIPPED_SHADER =
            "#type spatial\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n};\n" +
            "    struct GBuffer {\n" +
            "        vec4 albedo : RT0;\n" +
            "        vec4 emission : RT2;\n" +
            "    };\n" +
            "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
            "    void fragment(in v2f i, out GBuffer o) { o.albedo = vec4(1.0); }\n" +
            "}\n";

    @Test
    public void compiler_singleOutput_emitsFragColorOut() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.fragmentSource().contains("out vec4 _cg_fragColor;"));
    }

    @Test
    public void compiler_singleOutput_mainCallsFragment() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertTrue(cs.fragmentSource().contains("fragment(_v2f_local, _cg_fragColor);"));
    }

    @Test
    public void compiler_singleOutput_noRtNAnnotationsInOutput() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MINIMAL), NO_BUFFERS);
        assertFalse(cs.fragmentSource().contains(": RT"));
        assertFalse(cs.vertexSource().contains(": RT"));
    }

    @Test
    public void compiler_mrt3Fields_emitsThreeLayoutQualifiedOuts() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MRT_3_SHADER), NO_BUFFERS);
        assertTrue(cs.fragmentSource().contains("layout(location = 0) out vec4 _cg_RT0;"));
        assertTrue(cs.fragmentSource().contains("layout(location = 1) out vec4 _cg_RT1;"));
        assertTrue(cs.fragmentSource().contains("layout(location = 2) out vec4 _cg_RT2;"));
        assertFalse("MRT must not emit _cg_fragColor",
                cs.fragmentSource().contains("_cg_fragColor"));
    }

    @Test
    public void compiler_mrt3Fields_emitsFragmentFunctionWithStructSignature() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MRT_3_SHADER), NO_BUFFERS);
        assertTrue(cs.fragmentSource().contains("void fragment(in v2f i, out GBuffer o)"));
    }

    @Test
    public void compiler_mrt3Fields_mainDeclaresStructLocal_andCopiesFields() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MRT_3_SHADER), NO_BUFFERS);
        String frag = cs.fragmentSource();
        assertTrue(frag.contains("GBuffer _cg_mrtOut;"));
        assertTrue(frag.contains("fragment(_v2f_local, _cg_mrtOut);"));
        assertTrue(frag.contains("_cg_RT0 = _cg_mrtOut.albedo;"));
        assertTrue(frag.contains("_cg_RT1 = _cg_mrtOut.normal;"));
        assertTrue(frag.contains("_cg_RT2 = _cg_mrtOut.material;"));
    }

    @Test
    public void compiler_mrtSkippedLocation_emitsCorrectLocationNumbers() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MRT_SKIPPED_SHADER), NO_BUFFERS);
        String frag = cs.fragmentSource();
        assertTrue(frag.contains("layout(location = 0) out vec4 _cg_RT0;"));
        assertTrue(frag.contains("layout(location = 2) out vec4 _cg_RT2;"));
        assertFalse("Must not emit RT1 for skipped location", frag.contains("_cg_RT1"));
    }

    @Test
    public void compiler_mrt_structNotEmittedTwice() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MRT_3_SHADER), NO_BUFFERS);
        String frag = cs.fragmentSource();
        int firstIdx = frag.indexOf("struct GBuffer {");
        assertTrue("struct GBuffer must appear at least once", firstIdx >= 0);
        int secondIdx = frag.indexOf("struct GBuffer {", firstIdx + 1);
        assertEquals("struct GBuffer must NOT appear twice", -1, secondIdx);
    }

    @Test
    public void compiler_mrt_noAnnotationTokensInGlsl() {
        CgMaterialShaderCompiler.CompiledSource cs =
                CgMaterialShaderCompiler.compile(parse(MRT_3_SHADER), NO_BUFFERS);
        assertFalse("No ': RT' tokens must appear in fragment GLSL",
                cs.fragmentSource().contains(": RT"));
        assertFalse("No ': RT' tokens must appear in vertex GLSL",
                cs.vertexSource().contains(": RT"));
    }

    @Test
    public void compiler_vertexSource_unchangedForMrt() {
        CgMaterialShaderCompiler.CompiledSource csMrt =
                CgMaterialShaderCompiler.compile(parse(MRT_3_SHADER), NO_BUFFERS);
        assertTrue(csMrt.vertexSource().contains("void vertex(out v2f o)"));
        assertTrue(csMrt.vertexSource().contains("void main()"));
        // MRT layout-qualified outputs must NOT appear in vertex shader
        assertFalse("Vertex must not emit layout-qualified RT outputs",
                csMrt.vertexSource().contains("layout(location") && csMrt.vertexSource().contains("_cg_RT"));
        assertFalse("Vertex must not reference _cg_mrtOut", csMrt.vertexSource().contains("_cg_mrtOut"));
    }
}
