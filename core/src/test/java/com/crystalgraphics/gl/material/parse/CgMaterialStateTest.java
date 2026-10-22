package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * A material's state ({@link CgMaterialState}) applied to a shader with none of its own parses and compiles exactly as
 * the shader authored with it would: every blend mode, the clip, and what a state is refused.
 */
public class CgMaterialStateTest {

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

    /** A transparent glow: Forward with {@code forwardState}, a codeless Emissive pass with {@code emissiveState}. */
    private static String shader(String tags, String forwardState, String emissiveState) {
        return "#type spatial\n"
                + "Tags { \"RenderType\" = \"Transparent\" " + tags + " }\n"
                + "Queue = \"Transparent\"\n"
                + "Properties { _Clip (\"Clip\", float) = 0.33 }\n"
                + "struct v2f { vec2 uv; };\n"
                + "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    RenderState { " + forwardState + " }\n"
                + "    void vertex(out v2f o) { o.uv = cg_TexCoord0; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { cg_Clip(i.uv.x); fragColor = vec4(i.uv, 0.5, i.uv.x); }\n"
                + "}\n"
                + "Pass { Tags { \"LightMode\" = \"Emissive\" }" + (emissiveState.isEmpty() ? "" : " RenderState { " + emissiveState + " }") + " }\n";
    }

    private static final String STATELESS = shader("", "DepthWrite OFF", "");

    private static void assertSameShader(CgParsedShader authored, CgParsedShader stated) {
        assertEquals(authored.depthMode(), stated.depthMode());
        assertEquals(authored.passes().size(), stated.passes().size());
        for (int p = 0; p < authored.passes().size(); p++) {
            CgParsedPass a = authored.passes().get(p), s = stated.passes().get(p);
            assertEquals(a.name(), s.name());
            assertState(a.name(), a.renderState(), s.renderState());
            CgMaterialShaderCompiler.CompiledSource ac = CgMaterialShaderCompiler.compile(authored, a, NO_BUFFERS, null,
                    CgMaterialShaderCompiler.CompileConfig.DEFAULT);
            CgMaterialShaderCompiler.CompiledSource sc = CgMaterialShaderCompiler.compile(stated, s, NO_BUFFERS, null,
                    CgMaterialShaderCompiler.CompileConfig.DEFAULT);
            assertEquals(a.name() + " vertex", ac.vertexSource(), sc.vertexSource());
            assertEquals(a.name() + " fragment", ac.fragmentSource(), sc.fragmentSource());
        }
        assertEquals(CgMaterialShaderCompiler.emissionMerge(authored), CgMaterialShaderCompiler.emissionMerge(stated));
        assertEquals(CgMaterialShaderCompiler.sceneFolds(authored), CgMaterialShaderCompiler.sceneFolds(stated));
    }

    private static void assertState(String pass, CgRenderState authored, CgRenderState stated) {
        assertEquals(pass + " blend", authored.getBlend(), stated.getBlend());
        assertEquals(pass + " depth", authored.getDepth(), stated.getDepth());
        assertEquals(pass + " cull", authored.getCull(), stated.getCull());
    }

    private static CgParsedShader stated(CgMaterialState state) {
        return CgShaderParser.parse(STATELESS, "test:stateless.shader").withState(state, "test:stateless.shader");
    }

    @Test
    public void everyBlendModeIsTheBlendLineItStandsFor() {
        String[][] lines = {
                {"OPAQUE", "Blend OFF", ""},
                {"ALPHA", "Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA", ""},
                {"PREMULTIPLIED", "Blend ONE ONE_MINUS_SRC_ALPHA", ""},
                {"ADDITIVE", "Blend ONE ONE", ""},
                {"MODULATE", "Blend DST_COLOR ZERO", ""},
                // A glow is never subtracted: its codeless Emissive pass adds.
                {"SUBTRACT", "Blend ONE ONE BlendEquation REV_SUB", "Blend ONE ONE BlendEquation ADD DepthWrite OFF"},
        };
        for (String[] line : lines) {
            CgBlendMode mode = CgBlendMode.valueOf(line[0]);
            CgParsedShader authored = CgShaderParser.parse(shader("", line[1] + " DepthWrite OFF", line[2]), "test:" + mode);
            assertSameShader(authored, stated(CgMaterialState.builder().blend(mode).build()));
        }
    }

    @Test
    public void subtractIsFoggedAndDecodedAsAdditive() {
        String fragment = CgMaterialShaderCompiler.compile(stated(CgMaterialState.builder().blend(CgBlendMode.SUBTRACT).build()),
                NO_BUFFERS).fragmentSource();
        assertTrue(fragment.contains("#define CG_FOG_MODE 2"));
        assertTrue(fragment.contains("if (CG_LINEAR_SCENE) _cg_fragColor.rgb = cg_SceneDecode(_cg_fragColor.rgb);"));
    }

    /** Unreal's modulate fogs toward white by the square of what gets through (BasePassPixelShader.usf). */
    @Test
    public void modulateIsFoggedTowardWhite() {
        String fragment = CgMaterialShaderCompiler.compile(stated(CgMaterialState.builder().blend(CgBlendMode.MODULATE).build()),
                NO_BUFFERS).fragmentSource();
        assertTrue(fragment.contains("#define CG_FOG_MODE 3"));
    }

    /** Emissive and opacity are composed as Unreal's base pass does: added as emissive times opacity, else as written. */
    @Test
    public void emissiveOpacityIsComposedForTheBlend() {
        CgParsedShader source = CgShaderParser.parse(shader("\"Output\" = \"EmissiveOpacity\"", "DepthWrite OFF", ""), "test");
        String compose = "_cg_fragColor = vec4(_cg_fragColor.rgb * _cg_fragColor.a, 0.0);";
        for (CgBlendMode mode : CgBlendMode.values()) {
            CgParsedShader stated = source.withState(CgMaterialState.builder().blend(mode).build(), "test");
            String fragment = CgMaterialShaderCompiler.compile(stated, stated.getPassByLightMode("Forward"), NO_BUFFERS,
                    null, CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
            boolean added = mode == CgBlendMode.ADDITIVE || mode == CgBlendMode.SUBTRACT;
            assertEquals(mode.name(), added, fragment.contains(compose));
        }
        assertFalse(CgMaterialShaderCompiler.compile(stated(CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build()),
                NO_BUFFERS).fragmentSource().contains(compose));
        assertThrows(CgShaderParseException.class,
                () -> CgShaderParser.parse(shader("\"Output\" = \"Emissive\"", "DepthWrite OFF", ""), "test"));
    }

    @Test
    public void anEmissivePassWithABlendOfItsOwnKeepsIt() {
        String source = shader("", "DepthWrite OFF", "Blend ONE ONE");
        CgParsedShader stated = CgShaderParser.parse(source, "test").withState(
                CgMaterialState.builder().blend(CgBlendMode.ALPHA).build(), "test");
        assertEquals(CgBlendMode.ADDITIVE.state(), stated.getPassByLightMode("Emissive").renderState().getBlend());
        assertEquals(CgBlendMode.ALPHA.state(), stated.getPassByLightMode("Forward").renderState().getBlend());
    }

    @Test
    public void clipIsTheClipTag() {
        CgParsedShader authored = CgShaderParser.parse(shader("\"Depth\" = \"Clip\"", "Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA DepthWrite OFF", ""),
                "test:clip");
        CgParsedShader stated = stated(CgMaterialState.builder().blend(CgBlendMode.ALPHA).clip().build());
        assertSameShader(authored, stated);
        assertEquals(CgParsedShader.DepthMode.CLIP, stated.depthMode());
        assertTrue(stated.getPassByLightMode("Forward").renderState().getDepth().write());
        String fragment = CgMaterialShaderCompiler.compile(stated, NO_BUFFERS).fragmentSource();
        assertTrue(fragment.contains("#define CG_CLIP_THRESHOLD _Clip"));
    }

    /** Unreal: an instance overriding a masked parent's blend takes it whole, and only a masked material clips. */
    @Test
    public void aBlendWithoutClipUnmasksAMaskedShader() {
        CgParsedShader masked = CgShaderParser.parse(shader("\"Depth\" = \"Clip\"", "DepthWrite OFF", ""), "test:masked");
        CgParsedShader translucent = masked.withState(CgMaterialState.builder().blend(CgBlendMode.ALPHA).depthWrite(false)
                .build(), "test:masked");
        assertEquals(CgParsedShader.DepthMode.NONE, translucent.depthMode());
        assertFalse(translucent.getPassByLightMode("Forward").renderState().getDepth().write());
        String fragment = CgMaterialShaderCompiler.compile(translucent, NO_BUFFERS).fragmentSource();
        assertTrue(fragment.contains("#define cg_Clip(v)\n"));
        assertFalse(fragment.contains("discard"));
        CgParsedShader opaque = masked.withState(CgMaterialState.builder().blend(CgBlendMode.OPAQUE).build(), "test:masked");
        assertTrue("an opaque one writes depth", opaque.getPassByLightMode("Forward").renderState().getDepth().write());
        assertEquals(CgParsedShader.DepthMode.CLIP, masked.withState(CgMaterialState.builder().twoSided().build(), "test")
                .depthMode());
    }

    @Test
    public void cgClipCutsNothingWhereTheMaterialDoesNotClip() {
        String fragment = CgMaterialShaderCompiler.compile(CgShaderParser.parse(STATELESS, "test"), NO_BUFFERS).fragmentSource();
        assertTrue(fragment.contains("#define cg_Clip(v)\n"));
        assertFalse(fragment.contains("discard"));
    }

    @Test
    public void cullAndDepthWriteArePipelineStateAlone() {
        CgMaterialState twoSided = CgMaterialState.builder().twoSided().depthWrite(true).build();
        assertFalse(twoSided.changesProgram());
        CgParsedShader parsed = CgShaderParser.parse(STATELESS, "test");
        assertSame("the parse is untouched", parsed, parsed.withState(twoSided, "test"));
        CgRenderState forward = parsed.getPassByLightMode("Forward").renderState();
        CgRenderState drawn = twoSided.onPass(forward, true);
        assertEquals(CgCullState.NONE, drawn.getCull());
        assertTrue(drawn.getDepth().write());
        assertEquals(forward.getBlend(), drawn.getBlend());
        CgRenderState emissive = twoSided.onPass(parsed.getPassByLightMode("Emissive").renderState(), false);
        assertEquals("two-sided is every pass's", CgCullState.NONE, emissive.getCull());
        assertSame(forward, CgMaterialState.SHADER.onPass(forward, true));
    }

    @Test
    public void aStateTheShaderOwnsIsRefused() {
        CgParsedShader clipped = CgShaderParser.parse(shader("\"Depth\" = \"Clip\"", "DepthWrite OFF", ""), "test");
        assertThrows(CgShaderParseException.class,
                () -> clipped.withState(CgMaterialState.builder().depthWrite(false).build(), "test"));
        CgParsedShader prepass = CgShaderParser.parse(shader("\"Depth\" = \"Prepass\"", "DepthWrite OFF", ""), "test");
        assertThrows(CgShaderParseException.class, () -> prepass.withState(CgMaterialState.builder().clip().build(), "test"));
        assertThrows(IllegalStateException.class, () -> CgMaterialState.builder().clip().depthWrite(true).build());
    }

    @Test
    public void statesAreValuesKeyedByWhatTheyCompile() {
        CgMaterialState a = CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build();
        CgMaterialState b = CgMaterialState.builder().twoSided().blend(CgBlendMode.ADDITIVE).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("blend=ADDITIVE", a.programKey());
        assertSame(CgMaterialState.SHADER, CgMaterialState.builder().build());
    }
}
