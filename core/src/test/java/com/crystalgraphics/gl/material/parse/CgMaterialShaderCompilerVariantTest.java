package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class CgMaterialShaderCompilerVariantTest {

    private static final CgCapabilities.ShaderBufferPath TBO = CgCapabilities.ShaderBufferPath.TBO;
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

    private static final String SHADER_WITH_TWO_FEATURES =
            "#type spatial\n"
            + "#pragma cg_feature SHADOWS_ON\n"
            + "#pragma cg_feature FOG\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
            + "}\n";

    private static final String MINIMAL =
            "#type spatial\n"
            + "Properties { _Alpha (\"Alpha\", float) = 1.0 }\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(i.uv, 0.0, 1.0); }\n"
            + "}\n";

    private static final String SHADER_WITH_EXTENSION_IN_PREAMBLE =
            "#type spatial\n"
            + "#pragma cg_feature MY_FEATURE\n"
            + "#extension GL_OES_x : enable\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
            + "}\n";

    private static CgParsedShader parse(String src) {
        return CgShaderParser.parse(src, "test");
    }

    private static CgMaterialShaderCompiler.CompiledSource compile(CgParsedShader parsed,
                                                                   CgMaterialShaderCompiler.CompileConfig config) {
        return CgMaterialShaderCompiler.compile(parsed, NO_BUFFERS, null, config);
    }

    // ── Keyword injection tests ───────────────────────────────────────────────

    @Test
    public void noActiveKeywords_noDefinesInjected() {
        CgParsedShader parsed = parse(SHADER_WITH_TWO_FEATURES);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [noActiveKeywords_noDefinesInjected] VERTEX SOURCE ===");
        System.out.println(cs.vertexSource());
        System.out.println("=== [noActiveKeywords_noDefinesInjected] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        assertFalse("SHADOWS_ON define must NOT be injected when not active",
                cs.vertexSource().contains("#define SHADOWS_ON"));
        assertFalse(cs.fragmentSource().contains("#define SHADOWS_ON"));
        assertFalse(cs.vertexSource().contains("#define FOG"));
        assertFalse(cs.fragmentSource().contains("#define FOG"));
    }

    @Test
    public void shadowsOn_defineInjected_fogAbsent() {
        CgParsedShader parsed = parse(SHADER_WITH_TWO_FEATURES);
        Set<String> active = Collections.singleton("SHADOWS_ON");
        CgMaterialShaderCompiler.CompileConfig config =
                new CgMaterialShaderCompiler.CompileConfig(active);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, config);

        System.out.println("=== [shadowsOn_defineInjected_fogAbsent] VERTEX SOURCE ===");
        System.out.println(cs.vertexSource());
        System.out.println("=== [shadowsOn_defineInjected_fogAbsent] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        assertTrue("SHADOWS_ON define must be present in vertex", cs.vertexSource().contains("#define SHADOWS_ON 1"));
        assertTrue("SHADOWS_ON define must be present in fragment", cs.fragmentSource().contains("#define SHADOWS_ON 1"));
        assertFalse("FOG define must NOT be present", cs.vertexSource().contains("#define FOG"));
        assertFalse(cs.fragmentSource().contains("#define FOG"));
    }

    @Test
    public void bothKeywords_bothDefinesInjected_inDeclarationOrder() {
        CgParsedShader parsed = parse(SHADER_WITH_TWO_FEATURES);
        // Deliberately put FOG first in the set to verify declaration order is used, not set order
        Set<String> active = new LinkedHashSet<>(Arrays.asList("FOG", "SHADOWS_ON"));
        CgMaterialShaderCompiler.CompileConfig config =
                new CgMaterialShaderCompiler.CompileConfig(active);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, config);

        System.out.println("=== [bothKeywords_bothDefinesInjected_inDeclarationOrder] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        String frag = cs.fragmentSource();
        int shadowsIdx = frag.indexOf("#define SHADOWS_ON 1");
        int fogIdx = frag.indexOf("#define FOG 1");

        assertTrue("SHADOWS_ON must be present", shadowsIdx >= 0);
        assertTrue("FOG must be present", fogIdx >= 0);
        assertTrue("SHADOWS_ON (declared first) must appear before FOG in output", shadowsIdx < fogIdx);
    }

    @Test
    public void keywordDefineAppearsBeforeUserPreambleLines() {
        CgParsedShader parsed = parse(SHADER_WITH_EXTENSION_IN_PREAMBLE);
        Set<String> active = Collections.singleton("MY_FEATURE");
        CgMaterialShaderCompiler.CompileConfig config =
                new CgMaterialShaderCompiler.CompileConfig(active);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, config);

        System.out.println("=== [keywordDefineAppearsBeforeUserPreambleLines] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        String frag = cs.fragmentSource();
        int defineIdx = frag.indexOf("#define MY_FEATURE 1");
        int extensionIdx = frag.indexOf("#extension GL_OES_x");

        assertTrue("MY_FEATURE define must be present", defineIdx >= 0);
        assertTrue("Extension must be present", extensionIdx >= 0);
        assertTrue("Keyword define must appear before user extension line", defineIdx < extensionIdx);
    }

    @Test
    public void pragma_cg_feature_neverInGeneratedGlsl_vertex() {
        CgParsedShader parsed = parse(SHADER_WITH_TWO_FEATURES);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [pragma_cg_feature_neverInGeneratedGlsl_vertex] VERTEX SOURCE ===");
        System.out.println(cs.vertexSource());

        assertFalse("#pragma cg_feature must NOT appear in vertex output",
                cs.vertexSource().contains("#pragma cg_feature"));
    }

    @Test
    public void pragma_cg_feature_neverInGeneratedGlsl_fragment() {
        CgParsedShader parsed = parse(SHADER_WITH_TWO_FEATURES);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [pragma_cg_feature_neverInGeneratedGlsl_fragment] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        assertFalse("#pragma cg_feature must NOT appear in fragment output",
                cs.fragmentSource().contains("#pragma cg_feature"));
    }

    // ── Single-output and MRT regression tests ────────────────────────────────

    @Test
    public void singleOutput_unchanged() {
        CgParsedShader parsed = parse(MINIMAL);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [singleOutput_unchanged] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        assertTrue("Single-output must emit _cg_fragColor", cs.fragmentSource().contains("out vec4 _cg_fragColor;"));
    }

    @Test
    public void mrt_layoutQualifiedOuts() {
        String mrtShader =
                "#type spatial\n"
                + "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    struct v2f {\n    vec2 uv;\n};\n"
                + "    struct GBuffer {\n"
                + "        vec4 albedo : RT0;\n"
                + "        vec4 normal : RT1;\n"
                + "        vec4 emission : RT2;\n"
                + "    };\n"
                + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
                + "    void fragment(in v2f i, out GBuffer o) { o.albedo = vec4(1.0); }\n"
                + "}\n";

        CgParsedShader parsed = parse(mrtShader);
        CgMaterialShaderCompiler.CompiledSource cs = compile(parsed, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [mrt_layoutQualifiedOuts] FRAGMENT SOURCE ===");
        System.out.println(cs.fragmentSource());

        assertTrue(cs.fragmentSource().contains("layout(location = 0) out vec4 _cg_RT0;"));
        assertTrue(cs.fragmentSource().contains("layout(location = 1) out vec4 _cg_RT1;"));
        assertTrue(cs.fragmentSource().contains("layout(location = 2) out vec4 _cg_RT2;"));
    }

    // ── CompileConfig tests ───────────────────────────────────────────────────

    @Test
    public void compileConfig_default_hasEmptyKeywords() {
        CgMaterialShaderCompiler.CompileConfig def = CgMaterialShaderCompiler.CompileConfig.DEFAULT;

        System.out.println("[compileConfig_default_hasEmptyKeywords]");
        System.out.println("  keywords=" + def.activeKeywords());

        assertTrue("DEFAULT keywords must be empty", def.activeKeywords().isEmpty());
    }

    @Test
    public void compile_withDefaultConfig_identicalToLegacyOverload() {
        CgParsedShader parsed = parse(MINIMAL);
        CgMaterialShaderCompiler.CompiledSource legacy =
                CgMaterialShaderCompiler.compile(parsed, TBO, NO_BUFFERS, null);
        CgMaterialShaderCompiler.CompiledSource withDefault =
                CgMaterialShaderCompiler.compile(parsed, NO_BUFFERS, null,
                        CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("[compile_withDefaultConfig_identicalToLegacyOverload]");
        System.out.println("  vertexMatch=" + legacy.vertexSource().equals(withDefault.vertexSource()));
        System.out.println("  fragmentMatch=" + legacy.fragmentSource().equals(withDefault.fragmentSource()));

        assertEquals("Legacy and DEFAULT-config vertex must be identical",
                legacy.vertexSource(), withDefault.vertexSource());
        assertEquals("Legacy and DEFAULT-config fragment must be identical",
                legacy.fragmentSource(), withDefault.fragmentSource());
    }
}
