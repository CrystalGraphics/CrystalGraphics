package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** The {@code "Depth"} tag: what each mode does to the Forward pass's depth write and to {@code cg_Clip}. */
public class CgDepthModeTest {

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();
    private static final String DISCARDS = "#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; }";
    private static final String RETURNS = "#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; return; }";
    private static final String NOTHING = "#define cg_Clip(v)\n";

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

    private static String shader(String depth, String queue, String properties) {
        return "#type spatial\n"
                + "Tags { \"RenderType\" = \"Transparent\" \"Depth\" = \"" + depth + "\" }\n"
                + "Queue = \"" + queue + "\"\n"
                + properties
                + "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA DepthTest LEQUAL DepthWrite OFF }\n"
                + "    struct v2f {\n    vec2 uv;\n};\n"
                + "    void vertex(out v2f o) { o.uv = cg_TexCoord0; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { cg_Clip(i.uv.x); fragColor = vec4(i.uv.x); }\n"
                + "}\n";
    }

    private static CgMaterialShaderCompiler.CompiledSource forward(CgParsedShader parsed, Set<String> keywords) {
        return CgMaterialShaderCompiler.compile(parsed, parsed.passes().get(0), NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(keywords));
    }

    @Test
    public void clipDiscardsAndWritesDepthInOnePass() {
        CgParsedShader parsed = CgShaderParser.parse(shader("Clip", "Transparent", ""), "test");
        assertEquals(CgParsedShader.DepthMode.CLIP, parsed.depthMode());
        assertTrue("its DepthWrite OFF is overridden", parsed.passes().get(0).renderState().getDepth().write());
        CgMaterialShaderCompiler.CompiledSource cs = forward(parsed, Collections.emptySet());
        assertTrue(cs.fragmentSource().contains(DISCARDS));
        assertTrue(cs.fragmentSource().contains("#define CG_CLIP_THRESHOLD 0.5"));
        assertFalse(cs.vertexSource().contains("invariant gl_Position"));
    }

    @Test
    public void prepassClipsOnlyInItsDepthVariant() {
        CgParsedShader parsed = CgShaderParser.parse(shader("Prepass", "Transparent",
                "Properties { _Clip (\"Clip\", float) = 0.9 }\n"), "test");
        assertEquals(CgParsedShader.DepthMode.PREPASS, parsed.depthMode());
        assertFalse("the blend writes no depth", parsed.passes().get(0).renderState().getDepth().write());

        CgMaterialShaderCompiler.CompiledSource blend = forward(parsed, Collections.emptySet());
        assertTrue(blend.fragmentSource().contains(NOTHING));
        assertTrue(blend.fragmentSource().contains("#define CG_CLIP_THRESHOLD _Clip"));
        assertTrue(blend.vertexSource().contains("invariant gl_Position;"));

        CgMaterialShaderCompiler.CompiledSource depth = forward(parsed, Set.of(CgMaterialShaderCompiler.DEPTH_PREPASS));
        assertTrue(depth.fragmentSource().contains(RETURNS));
        assertTrue(depth.vertexSource().contains("invariant gl_Position;"));
    }

    @Test
    public void prepassNeedsATransparentQueue() {
        assertThrows(CgShaderParseException.class,
                () -> CgShaderParser.parse(shader("Prepass", "AlphaTest", ""), "test"));
    }

    @Test
    public void anUnknownModeFailsToParse() {
        assertThrows(CgShaderParseException.class,
                () -> CgShaderParser.parse(shader("Always", "Transparent", ""), "test"));
    }
}
