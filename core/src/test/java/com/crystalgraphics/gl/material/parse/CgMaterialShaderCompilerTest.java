package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
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

    // ── Emissive pass ─────────────────────────────────────────────────────────

    private static final String EMISSIVE =
            "#type spatial\n" +
            "struct v2f {\n    vec2 uv;\n};\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Emissive\" \"Name\" = \"Glow\" }\n" +
            "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(4.0, 2.0, 1.0, 0.0); }\n" +
            "}\n";

    @Test
    public void emissivePass_isNamedByItsLightMode_andAddsWithoutDepth() {
        CgParsedPass pass = parse(EMISSIVE).getPassByLightMode("Emissive");
        assertNotNull(pass);
        assertEquals("Emissive", pass.name());
        CgBlendState blend = pass.renderState().getBlend();
        assertTrue(blend.enabled());
        assertEquals("ONE ONE: emitted light is written with an alpha of 0", CgGL.GL_ONE, blend.srcRgb());
        assertEquals(CgGL.GL_ONE, blend.dstRgb());
        assertSame(CgDepthState.NONE, pass.renderState().getDepth());
    }

    @Test
    public void emissivePass_keepsAnAuthoredRenderState() {
        String authored = EMISSIVE.replace("\"Name\" = \"Glow\" }\n",
                "\"Name\" = \"Glow\" }\n    RenderState { Blend SRC_ALPHA ONE }\n");
        CgParsedPass pass = parse(authored).getPassByLightMode("Emissive");
        assertEquals(CgGL.GL_SRC_ALPHA, pass.renderState().getBlend().srcRgb());
    }

    @Test
    public void emissivePass_discardsBehindTheScene_andIsFoggedNotLit() {
        CgParsedShader shader = parse(EMISSIVE);
        String frag = CgMaterialShaderCompiler.compile(shader, shader.getPassByLightMode("Emissive"), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        int discard = frag.indexOf("CG_EMISSIVE_DEPTH_SLACK + CG_EMISSIVE_DEPTH_BIAS) discard;");
        assertTrue("the occlusion discard", discard >= 0);
        assertTrue("before fragment()", discard < frag.indexOf("fragment(_v2f_local, _cg_fragColor);"));
        assertTrue(frag.contains("_cg_fragColor = cg_Fog(_cg_fragColor);"));
        assertFalse(frag.contains("cg_Lit(_cg_fragColor)"));
        assertTrue(frag.contains("#define CG_FOG_MODE 2"));
    }

    @Test
    public void emissivePass_withNoCode_drawsTheForwardPass() {
        String src = MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend ONE ONE DepthTest ALWAYS Cull FRONT }\n")
                + "Pass { Tags { \"LightMode\" = \"Emissive\" } }\n";
        CgParsedShader shader = parse(src);
        CgParsedPass forward = shader.passes().get(0), emissive = shader.getPassByLightMode("Emissive");
        assertEquals("Emissive", emissive.name());
        assertEquals(forward.fragmentBody(), emissive.fragmentBody());
        assertSame(forward.renderState(), emissive.renderState());
        String frag = CgMaterialShaderCompiler.compile(shader, emissive, NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertTrue(frag.contains("#define CG_EMISSIVE_PASS 1"));
        assertFalse(CgMaterialShaderCompiler.compile(shader, NO_BUFFERS).fragmentSource().contains("CG_EMISSIVE_PASS"));
    }

    @Test
    public void emissionTarget_withNoGlow_writesBlackAtTheColoursAlpha() {
        CgParsedShader shader = parse(MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA }\n"));
        String frag = CgMaterialShaderCompiler.compile(shader, shader.passes().get(0), NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(Collections.singleton(CgMaterialShaderCompiler.EMISSION_TARGET)))
                .fragmentSource();
        assertTrue(frag.contains("layout(location = 1) out vec4 _cg_emission;"));
        int cover = frag.indexOf("_cg_emission = vec4(0.0, 0.0, 0.0, _cg_fragColor.a);");
        assertTrue("the cover", cover >= 0);
        assertTrue("after the colour is final", cover > frag.indexOf("fragment(_v2f_local, _cg_fragColor);"));
    }

    @Test
    public void aNamedGlow_replacesTheColourInEveryGlow() {
        String src = MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA DepthWrite OFF }\n")
                .replace("fragColor = vec4(1.0); }", "fragColor = vec4(1.0); CG_GLOW(vec3(2.0, 0.0, 0.0)); }")
                + "Pass { Tags { \"LightMode\" = \"Emissive\" } RenderState { Blend ONE ONE DepthWrite OFF } }\n";
        CgParsedShader shader = parse(src);
        CgParsedPass forward = shader.passes().get(0);
        String merged = CgMaterialShaderCompiler.compile(shader, forward, NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(Collections.singleton(CgMaterialShaderCompiler.EMISSION_TARGET)))
                .fragmentSource();
        assertTrue(merged.contains("#define CG_GLOW(rgb) _cg_glowRgb = (rgb)"));
        assertTrue(merged.contains("_cg_emission = vec4(_cg_glowRgb, _cg_fragColor.a);"));
        String folded = CgMaterialShaderCompiler.compile(shader, forward, NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(Collections.singleton(CgMaterialShaderCompiler.SCENE_FOLD)))
                .fragmentSource();
        assertTrue(folded.contains("vec4 _cg_glow = vec4(_cg_glowRgb, _cg_fragColor.a);"));
        String own = CgMaterialShaderCompiler.compile(shader, shader.getPassByLightMode("Emissive"), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        int glow = own.indexOf("_cg_fragColor.rgb = _cg_glowRgb;");
        assertTrue("its own draw glows the named glow", glow > own.indexOf("fragment(_v2f_local, _cg_fragColor);"));
        assertTrue("before CG_EMISSION scales it", glow < own.indexOf("_cg_fragColor.rgb *= CG_EMISSION;"));
        // The Forward pass drawn alone keeps its colour.
        String plain = CgMaterialShaderCompiler.compile(shader, forward, NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertFalse(plain.contains("_cg_fragColor.rgb = _cg_glowRgb;"));
    }

    @Test(expected = CgShaderParseException.class)
    public void emissivePass_withNoCode_needsAForwardPassBeforeIt() {
        parse("#type spatial\nPass { Tags { \"LightMode\" = \"Emissive\" } }\n" + MINIMAL.substring(MINIMAL.indexOf("Pass")));
    }

    // ── Distortion pass ───────────────────────────────────────────────────────

    private static final String DISTORTION =
            "#type spatial\n" +
            "struct v2f {\n    vec2 uv;\n};\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    vec2 shared_bend() { return vec2(0.01); }\n" +
            "    void vertex(out v2f o) { o.uv = vec2(0.5); }\n" +
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(0.0); }\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Distortion\" \"Name\" = \"Bend\" }\n" +
            "    float own_split() { return 0.3; }\n" +
            "    void fragment(in v2f i, out vec4 offset) { offset = vec4(shared_bend(), own_split(), 0.0); }\n" +
            "}\n";

    @Test
    public void distortionPass_withNoVertex_takesTheForwardPasss_andAddsWithoutDepth() {
        CgParsedShader shader = parse(DISTORTION);
        CgParsedPass forward = shader.passes().get(0), distortion = shader.getPassByLightMode("Distortion");
        assertEquals("Distortion", distortion.name());
        assertEquals(forward.vertexBody(), distortion.vertexBody());
        assertTrue(distortion.globalDecls().contains("shared_bend") && distortion.globalDecls().contains("own_split"));
        assertEquals(CgGL.GL_ONE, distortion.renderState().getBlend().dstRgb());
        assertSame(CgDepthState.NONE, distortion.renderState().getDepth());
        String frag = CgMaterialShaderCompiler.compile(shader, distortion, NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertTrue(frag.contains("#define CG_DISTORTION_PASS 1"));
        assertTrue("hidden by the scene", frag.indexOf("CG_EMISSIVE_DEPTH_BIAS) discard;") >= 0);
        assertFalse(frag.contains("cg_Fog(_cg_fragColor)") || frag.contains("cg_Lit(_cg_fragColor)"));
    }

    @Test(expected = CgShaderParseException.class)
    public void distortionPass_withNoVertex_needsAForwardPassBeforeIt() {
        parse("#type spatial\nstruct v2f { vec2 uv; };\nPass { Tags { \"LightMode\" = \"Distortion\" }\n"
                + "    void fragment(in v2f i, out vec4 offset) { offset = vec4(0.0); } }\n");
    }

    // ── The HDR scene ─────────────────────────────────────────────────────────

    @Test
    public void forwardPass_decodesIntoTheLinearScene_afterLightAndFog_unlessLinearOrEmissive() {
        String plain = "if (CG_LINEAR_SCENE) _cg_fragColor.rgb = cg_SceneDecode(_cg_fragColor.rgb);";
        CgParsedShader shader = parse(EMISSIVE);
        String forward = CgMaterialShaderCompiler.compile(shader, NO_BUFFERS).fragmentSource();
        assertTrue("after the fog", forward.indexOf(plain) > forward.indexOf("_cg_fragColor = cg_Fog(_cg_fragColor);"));
        String glow = CgMaterialShaderCompiler.compile(shader, shader.getPassByLightMode("Emissive"), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertFalse("emitted light is linear already", glow.contains("cg_SceneDecode"));

        String premultiplied = CgMaterialShaderCompiler.compile(
                parse(MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA }\n")), NO_BUFFERS)
                .fragmentSource();
        assertTrue("decoded unpremultiplied", premultiplied.contains("cg_SceneDecode(_cg_fragColor.rgb / _cg_fragColor.a) * _cg_cover"));
        assertTrue("its coverage remapped", premultiplied.contains("_cg_fragColor.a = _cg_cover;"));
        String alpha = CgMaterialShaderCompiler.compile(
                parse(MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA }\n")), NO_BUFFERS)
                .fragmentSource();
        assertTrue(alpha.contains("cg_SceneCoverage(_cg_fragColor.a)"));
        String added = CgMaterialShaderCompiler.compile(
                parse(MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend SRC_ALPHA ONE }\n")), NO_BUFFERS)
                .fragmentSource();
        assertFalse("an additive blend's alpha is its strength, kept", added.contains("cg_SceneCoverage"));

        String linear = CgMaterialShaderCompiler.compile(
                parse(MINIMAL.replace("#type spatial\n", "#type spatial\nTags { \"ColorSpace\" = \"Linear\" }\n")), NO_BUFFERS)
                .fragmentSource();
        assertFalse(linear.contains("cg_SceneDecode"));
    }

    @Test
    public void aGlowingMaterialsColour_isHeldAtWhiteInTheLinearScene_beforeItsGlowAdds() {
        String held = "if (CG_LINEAR_SCENE) _cg_fragColor.rgb = min(_cg_fragColor.rgb, vec3(1.0));";
        String src = MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA DepthWrite OFF }\n")
                + "Pass { Tags { \"LightMode\" = \"Emissive\" } RenderState { Blend ONE ONE DepthWrite OFF } }\n";
        CgParsedShader shader = parse(src);
        String folded = CgMaterialShaderCompiler.compile(shader, shader.passes().get(0), NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(Collections.singleton(CgMaterialShaderCompiler.SCENE_FOLD)))
                .fragmentSource();
        int at = folded.indexOf(held);
        assertTrue("after the decode", at > folded.indexOf("_cg_fragColor.a = _cg_cover;"));
        assertTrue("before the glow adds", at < folded.indexOf("_cg_fragColor.rgb += _cg_glow.rgb;"));
        String plain = CgMaterialShaderCompiler.compile(
                parse(MINIMAL.replace("Pass {\n", "Pass {\n    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA }\n")), NO_BUFFERS)
                .fragmentSource();
        assertFalse("with no glow, the colour keeps its excess", plain.contains(held));
    }

    @Test
    public void overdrawVariant_countsOnceAfterTheFragmentRuns() {
        CgParsedShader shader = parse(DISTORTION);
        String frag = CgMaterialShaderCompiler.compile(shader, shader.passes().get(0), NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(Collections.singleton(CgMaterialShaderCompiler.DEBUG_OVERDRAW)))
                .fragmentSource();
        int run = frag.indexOf("fragment(_v2f_local, _cg_fragColor);"), count = frag.indexOf("_cg_fragColor = vec4(1.0, 0.0, 0.0, 0.0);");
        assertTrue("its discard still runs, then one count", run >= 0 && count > run);
        assertTrue("its depth test, as a discard", frag.indexOf("discard;") >= 0 && frag.indexOf("discard;") < run);
    }

    @Test
    public void emissivePass_depthTestAlways_leavesOcclusionToTheShader() {
        CgParsedShader shader = parse(EMISSIVE.replace("\"Name\" = \"Glow\" }\n",
                "\"Name\" = \"Glow\" }\n    RenderState { Blend ONE ONE DepthTest ALWAYS }\n"));
        String frag = CgMaterialShaderCompiler.compile(shader, shader.getPassByLightMode("Emissive"), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertFalse(frag.contains("CG_EMISSIVE_DEPTH_SLACK"));
    }

    @Test
    public void emission_scalesAnEmissivePass_unlessItsCodeAppliesIt() {
        String props = "#type spatial\nProperties {\n    _EmissionColor (\"c\", color) = (1, 1, 1, 1)\n"
                + "    _EmissionStrength (\"s\", float) = 1.0\n}\n";
        CgParsedShader codeless = parse(props + MINIMAL.substring(MINIMAL.indexOf("Pass"))
                + "Pass { Tags { \"LightMode\" = \"Emissive\" } }\n");
        String frag = CgMaterialShaderCompiler.compile(codeless, codeless.getPassByLightMode("Emissive"), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertTrue(frag.contains("#define CG_EMISSION (vec3(1.0) * _EmissionColor.rgb * _EmissionStrength * CG_OBJECT_EMISSION * CG_SCENE_GLOW)"));
        assertTrue(frag.indexOf("_cg_fragColor.rgb *= CG_EMISSION;") > frag.indexOf("fragment(_v2f_local, _cg_fragColor);"));

        CgParsedShader authored = parse(EMISSIVE.replace("vec4(4.0, 2.0, 1.0, 0.0)", "vec4(CG_EMISSION, 0.0)"));
        String own = CgMaterialShaderCompiler.compile(authored, authored.getPassByLightMode("Emissive"), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertTrue("no properties: the draw's scale alone", own.contains("#define CG_EMISSION (vec3(1.0) * CG_OBJECT_EMISSION * CG_SCENE_GLOW)"));
        assertFalse(own.contains("*= CG_EMISSION"));
        assertFalse("Forward is never scaled", CgMaterialShaderCompiler.compile(authored, NO_BUFFERS).fragmentSource()
                .contains("*= CG_EMISSION"));
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
