package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A program carrying its own lighting, handed the world's lights by naming them. */
public class CgHandedLightsTest {

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

    private static String shader(String buffers, String fragment) {
        return "#type spatial\nTags { \"RenderType\" = \"Transparent\" \"Lighting\" = \"Unlit\" }\n"
                + "Queue = \"Transparent\"\n" + buffers + "struct v2f { vec3 n; };\n"
                + "Pass {\n    RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA DepthWrite OFF }\n"
                + "    void vertex(out v2f o) { o.n = cg_Normal; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { " + fragment + " }\n}\n";
    }

    @Test
    public void aProgramNamingTheDrawsLightsIsHandedTheGrid() {
        String source = shader("", "fragColor = vec4(CG_DRAW_POINT_LIGHT_COLOR(0), CG_DRAW_POINT_LIGHT_PARAMS(0).x);");
        CgParsedShader parsed = CgShaderParser.parse(source, "test:handed");
        assertTrue(parsed.handedLights());
        assertNotNull(parsed.buffer("WORLD_LIGHTS"));
        assertNotNull(parsed.buffer("WORLD_LIGHT_CELLS"));
        assertNotNull(parsed.buffer("WORLD_LIGHT_INDICES"));
        assertTrue(parsed.properties().stream().anyMatch(p -> p.getName().equals("_cg_LightGrid")));
        String fragment = CgMaterialShaderCompiler.compile(parsed, Collections.emptyList()).fragmentSource();
        assertTrue(fragment, fragment.contains("#define CG_HANDED_LIGHTS 1"));
        assertTrue(fragment, fragment.contains("handed_lights.glsl"));
    }

    @Test
    public void theGridFollowsAProgramsOwnBuffers() {
        String own = "struct Clip { vec4 a; };\nBuffers {\n    CLIPS (\"Clips\", Clip, readonly)\n}\n";
        CgParsedShader parsed = CgShaderParser.parse(shader(own, "int idx[4]; fragColor = vec4(cg_NearestLights(vec3(0.0), idx));"),
                "test:handed");
        assertEquals(0, parsed.buffer("CLIPS").index());
        assertEquals(1, parsed.buffer("WORLD_LIGHTS").index());
        assertEquals(3, parsed.buffer("WORLD_LIGHT_INDICES").index());
    }

    @Test
    public void aProgramNamingNoneIsHandedNothing() {
        CgParsedShader parsed = CgShaderParser.parse(shader("", "fragColor = vec4(1.0);"), "test:plain");
        assertFalse(parsed.handedLights());
        assertTrue(parsed.buffers().isEmpty());
    }

    @Test
    public void aProgramNamingTheSunsShadowIsHandedItsMap() {
        String source = shader("", "fragColor = vec4(vec3(CG_DIRECTIONAL_SHADOW(i.n)), 1.0);");
        CgParsedShader parsed = CgShaderParser.parse(source, "test:sun");
        assertTrue(parsed.handedSun());
        assertFalse(parsed.handedLights());
        assertTrue(parsed.properties().stream().anyMatch(p -> p.getName().equals("_cg_SunMap")));
        String fragment = CgMaterialShaderCompiler.compile(parsed, Collections.emptyList()).fragmentSource();
        assertTrue(fragment, fragment.contains("#define CG_HANDED_SUN 1"));
        assertTrue(fragment, fragment.contains("handed_sun.glsl"));
    }

    @Test
    public void tooManyBuffersFailNamingTheLights() {
        String own = "Buffers {\n    A (\"A\", vec4, readonly)\n    B (\"B\", vec4, readonly)\n}\n";
        CgShaderParseException e = assertThrows(CgShaderParseException.class,
                () -> CgShaderParser.parse(shader(own, "fragColor = vec4(CG_DRAW_POINT_LIGHT_POSITION(0), 1.0);"), "test:full"));
        assertTrue(e.getMessage(), e.getMessage().contains("the world's lights it reads take 3 more"));
    }
}
