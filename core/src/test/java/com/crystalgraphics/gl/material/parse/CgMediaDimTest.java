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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the world's media dims: each blend's share, and the materials it leaves alone. */
public class CgMediaDimTest {

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();
    private static final String WHOLE = "_cg_fragColor *= _cg_media;";
    private static final String ALPHA = "_cg_fragColor.a *= _cg_media;";

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

    private static String fragment(String tags, String queue, String blend, String body) {
        String source = "#type spatial\n"
                + "Tags { \"RenderType\" = \"Transparent\" " + tags + " }\n"
                + "Queue = \"" + queue + "\"\n"
                + "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    RenderState { " + blend + " DepthTest LEQUAL DepthWrite OFF }\n"
                + "    struct v2f {\n    vec2 uv;\n};\n"
                + "    void vertex(out v2f o) { o.uv = cg_TexCoord0; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { " + body + " fragColor = vec4(i.uv.x); }\n"
                + "}\n";
        CgParsedShader parsed = CgShaderParser.parse(source, "test");
        return CgMaterialShaderCompiler.compile(parsed, parsed.passes().get(0), NO_BUFFERS, null,
                new CgMaterialShaderCompiler.CompileConfig(Collections.emptySet())).fragmentSource();
    }

    @Test
    public void lightAddedOrPremultipliedIsDimmedWhole() {
        assertTrue(fragment("", "Transparent", "Blend ONE ONE_MINUS_SRC_ALPHA", "").contains(WHOLE));
        assertTrue(fragment("", "Transparent", "Blend ONE ONE", "").contains(WHOLE));
    }

    @Test
    public void aColourBlendedByItsAlphaIsDimmedThroughIt() {
        String glsl = fragment("", "Transparent", "Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA", "");
        assertTrue(glsl.contains(ALPHA));
        assertFalse(glsl.contains(WHOLE));
    }

    @Test
    public void opaqueOverlayTaggedAndVolumesAreLeftAlone() {
        assertFalse(fragment("", "Geometry", "Blend OFF", "").contains("_cg_media"));
        assertFalse(fragment("", "Overlay", "Blend ONE ONE", "").contains("_cg_media"));
        assertFalse(fragment("\"Media\" = \"Off\"", "Transparent", "Blend ONE ONE", "").contains("_cg_media"));
        assertFalse(fragment("", "Transparent", "Blend ONE ONE_MINUS_SRC_ALPHA", "CG_VOLUME_DEPTH(1.0);")
                .contains("_cg_media"));
    }
}
