package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A {@code "Surface"}-lit material: translucent, lit by the normal its fragment names. */
public class CgSurfaceLitTest {

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

    private static String shader(String lighting, String queue, String fragment) {
        return "#type spatial\nTags { \"RenderType\" = \"Transparent\" \"Lighting\" = \"" + lighting + "\" }\n"
                + "Queue = \"" + queue + "\"\nstruct v2f { vec3 n; };\n"
                + "Pass {\n    RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA DepthWrite OFF }\n"
                + "    void vertex(out v2f o) { o.n = cg_Normal; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { " + fragment + " }\n}\n";
    }

    private static String fragmentSource(String source) {
        return CgMaterialShaderCompiler.compile(CgShaderParser.parse(source, "test:surface"), Collections.emptyList())
                .fragmentSource();
    }

    @Test
    public void aSurfaceMaterialIsLitByTheNormalItNames() {
        String source = shader("Surface", "Transparent", "CG_SURFACE_NORMAL(normalize(i.n)); fragColor = vec4(1.0, 0.5, 0.2, 0.5);");
        assertTrue(CgShaderParser.parse(source, "test:surface").surfaceLit());
        String fragment = fragmentSource(source);
        assertTrue(fragment, fragment.contains("#define CG_SURFACE_LIT 1"));
        assertTrue(fragment, fragment.contains("cg_LitSurface(_cg_fragColor, _cg_surfaceNormal)"));
        assertFalse(fragment, fragment.contains("cg_Lit(_cg_fragColor)"));
    }

    @Test
    public void itsEmissiveIsAddedAfterItsLightingAndBeforeItsFog() {
        String fragment = fragmentSource(shader("Surface", "Transparent",
                "CG_SURFACE_NORMAL(i.n); CG_SURFACE_EMISSIVE(vec3(2.0)); fragColor = vec4(1.0, 0.5, 0.2, 0.5);"));
        int lit = fragment.indexOf("cg_LitSurface(_cg_fragColor"), added = fragment.indexOf("_cg_fragColor.rgb += _cg_surfaceEmissive");
        int fog = fragment.indexOf("cg_Fog(_cg_fragColor)");
        assertTrue(fragment, lit >= 0 && added > lit && (fog < 0 || fog > added));
    }

    @Test
    public void aLitMaterialKeepsTheVolumeFromEverySide() {
        String fragment = fragmentSource(shader("Lit", "Transparent", "fragColor = vec4(1.0);"));
        assertTrue(fragment, fragment.contains("cg_Lit(_cg_fragColor)"));
        assertFalse(fragment, fragment.contains("CG_SURFACE_LIT"));
    }

    @Test
    public void aSurfaceMaterialWithoutANormalOrOpaqueFailsNamingWhy() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class,
                () -> CgShaderParser.parse(shader("Surface", "Transparent", "fragColor = vec4(1.0);"), "test:surface"));
        assertTrue(e.getMessage(), e.getMessage().contains("CG_SURFACE_NORMAL"));
        e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(
                shader("Surface", "Geometry", "CG_SURFACE_NORMAL(i.n); fragColor = vec4(1.0);"), "test:surface"));
        assertTrue(e.getMessage(), e.getMessage().contains("transparent"));
    }
}
