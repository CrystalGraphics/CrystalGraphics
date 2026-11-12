package com.crystalgraphics.material.parse;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.material.CgAttachedBuffer;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** What the material format refuses, and the passes its outputs derive. Its programs: the Vulkan module's MaterialFormatTest. */
public class CgMaterialFormatTest {

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

    @BeforeClass
    public static void injectCapabilities() throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, CgCapabilities.ShaderBufferPath.SSBO_GL43);
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, stub);
    }

    @AfterClass
    public static void clearCapabilities() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }

    private static final String SURFACE = "#domain surface\nstruct v2f { vec2 uv0; };\n";

    @Test
    public void relevanceFollowsTheOutputsNamed() {
        CgParsedShader plain = CgShaderParser.parse(SURFACE + "void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(i.uv0, 0.0); }");
        assertEquals(1, plain.passes().size());
        assertEquals(CgMaterialDomain.SURFACE, plain.domain());

        CgParsedShader both = CgShaderParser.parse("#domain surface\nMaterial { BlendMode Translucent }\nstruct v2f { vec2 uv0; };\n"
                + "void surface(in v2f i, inout CgSurface s) { s.opacity = 0.5; s.emissive = vec3(1.0); s.refraction = i.uv0; }");
        assertEquals(3, both.passes().size());
        assertEquals("Emissive", both.passes().get(1).lightMode());
        assertEquals("Distortion", both.passes().get(2).lightMode());
    }

    @Test
    public void maskClipIsTheThresholdAndNoParameterIsMagic() {
        CgParsedShader masked = CgShaderParser.parse("#domain surface\nMaterial { BlendMode Masked  MaskClip 0.4 }\n"
                + "Properties { _Clip (\"Not the threshold\", float) = 0.9 }\nstruct v2f { vec2 uv0; };\n"
                + "void surface(in v2f i, inout CgSurface s) { s.opacityMask = i.uv0.x * _Clip; }");
        assertEquals("0.4", masked.maskClip());
        String fragment = CgMaterialShaderCompiler.compile(masked, NO_BUFFERS).fragmentSource();
        assertTrue(fragment, fragment.contains("#define CG_CLIP_THRESHOLD 0.4"));

        CgParsedShader glow = CgShaderParser.parse("#domain surface\nMaterial { BlendMode Additive }\n"
                + "Properties { _EmissionColor (\"Glow\", color) = (1, 0, 0, 1) }\nstruct v2f { vec2 uv0; };\n"
                + "void surface(in v2f i, inout CgSurface s) { s.emissive = _EmissionColor.rgb; }");
        String emissive = CgMaterialShaderCompiler.compile(glow, glow.passes().get(1),
                NO_BUFFERS, null, CgMaterialShaderCompiler.CompileConfig.DEFAULT).fragmentSource();
        assertFalse(emissive, emissive.contains("* _EmissionColor.rgb"));
    }

    @Test
    public void separateTranslucencyIsTheShaders() {
        CgParsedShader separate = CgShaderParser.parse("#domain surface\nMaterial { BlendMode Additive  TranslucencyPass Separate }\n"
                + SURFACE.substring(SURFACE.indexOf('\n') + 1) + "void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(1.0); }");
        assertTrue(separate.separateTranslucency());
        assertNull(CgShaderParser.parse(SURFACE.replace("#domain surface\n", "#type spatial\n")
                + "Pass { void vertex(out v2f o) { gl_Position = vec4(0.0); o.uv0 = vec2(0.0); }"
                + " void fragment(in v2f i, out vec4 c) { c = vec4(1.0); } }").domain());
    }

    @Test
    public void refusals() {
        refused("#domain surface\n#pragma cg_feature X\nstruct v2f { vec2 uv0; };\nvoid surface(in v2f i, inout CgSurface s) { }", "#pragma");
        refused(SURFACE + "Pass { }", "old format");
        refused(SURFACE + "void surface(in v2f i, inout CgSurface s) { s.opacity = 0.5; }", "translucent");
        refused("#domain surface\nMaterial { BlendMode Opaque  DepthPrepass On }\n" + SURFACE.substring(16)
                + "void surface(in v2f i, inout CgSurface s) { }", "translucent");
        refused("#domain surface\nMaterial { MaskClip 0.3 }\n" + SURFACE.substring(16)
                + "void surface(in v2f i, inout CgSurface s) { }", "MaskClip");
        refused("#domain surface\nMaterial { BlendMode Masked  MaskClip _Cut }\n" + SURFACE.substring(16)
                + "void surface(in v2f i, inout CgSurface s) { s.opacityMask = 1.0; }", "_Cut");
        refused("#domain surface\nMaterial { BlendMode Glass }\n" + SURFACE.substring(16)
                + "void surface(in v2f i, inout CgSurface s) { }", "BlendMode");
        refused("#domain surface\nMaterial { VertexFactory Procedural }\n" + SURFACE.substring(16)
                + "void surface(in v2f i, inout CgSurface s) { }", "vertex()");
        refused("#domain surface\nstruct v2f { vec2 bend; };\nvoid surface(in v2f i, inout CgSurface s) { }", "bend");
        refused(SURFACE + "void vertex(inout CgVertex v, out v2f o) { gl_Position = vec4(0.0); o.uv0 = v.uv0; }\n"
                + "void surface(in v2f i, inout CgSurface s) { }", "gl_Position");
        refused("#domain decal\nvoid surface(in v2f i, inout CgSurface s) { }", "R11");
        refused("#domain surface\nKeywords { SHAPE (\"Shape\") = BOX }\n" + SURFACE.substring(16)
                + "void surface(in v2f i, inout CgSurface s) { }", "one value");
        refused(SURFACE + "void surface(in v2f i, inout CgSurface s) { s.baseColor = CG_QUAD_COLOR.rgb; }", "never by naming it");
        refused("#domain global\nstruct v2f { vec2 uv; };\nvoid fragment(in v2f i, out vec4 c) { c = vec4(1.0); }", "vertex");
    }

    private static void refused(String source, String naming) {
        try {
            CgShaderParser.parse(source, "test");
            fail("parsed: " + source);
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(naming));
        }
    }
}
