package io.github.somehussar.crystalgraphics.gl.material;

import io.github.somehussar.crystalgraphics.gl.material.parse.CgParsedPass;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgParsedShader;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgShaderParseException;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CgShaderParserTest {

    private static CgParsedPass pass0(CgParsedShader p) {
        return p.passes().get(0);
    }

    private static final String MINIMAL_SHADER =
        "#type spatial\n" +
        "\n" +
        "Properties {\n" +
        "}\n" +
        "\n" +
        "Pass {\n" +
        "    Tags { \"LightMode\" = \"Forward\" }\n" +
        "    struct v2f {\n" +
        "        vec2 uv;\n" +
        "    };\n" +
        "    void vertex(out v2f o) {\n" +
        "        o.uv = cg_TexCoord0;\n" +
        "        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n" +
        "    }\n" +
        "    void fragment(in v2f i, out vec4 fragColor) {\n" +
        "        fragColor = vec4(i.uv, 0.0, 1.0);\n" +
        "    }\n" +
        "}\n";

    private static final String FULL_SHADER =
        "#type spatial\n" +
        "\n" +
        "Properties {\n" +
        "    _MainTex : sampler2D\n" +
        "    _Color   : vec4 = (1.0, 1.0, 1.0, 1.0)\n" +
        "    _Alpha   : float = 1.0\n" +
        "}\n" +
        "\n" +
        "Pass {\n" +
        "    Tags { \"LightMode\" = \"Forward\" }\n" +
        "    struct v2f {\n" +
        "        vec3 worldPos;\n" +
        "        vec3 normal;\n" +
        "        vec2 uv;\n" +
        "    };\n" +
        "    float computeAttenuation(float dist) {\n" +
        "        return clamp(1.0 - dist, 0.0, 1.0);\n" +
        "    }\n" +
        "    void vertex(out v2f o) {\n" +
        "        o.worldPos = vec3(1.0);\n" +
        "        o.normal = cg_Normal;\n" +
        "        o.uv = cg_TexCoord0;\n" +
        "        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n" +
        "    }\n" +
        "    void fragment(in v2f i, out vec4 fragColor) {\n" +
        "        fragColor = texture(_MainTex, i.uv) * _Color;\n" +
        "    }\n" +
        "}\n";

    @Test
    public void testParseMinimalShader() {
        CgParsedShader parsed = CgShaderParser.parse(MINIMAL_SHADER);
        assertNotNull(parsed);
        assertEquals("spatial", parsed.shaderType());
        assertTrue(parsed.properties().isEmpty());
        assertNotNull(pass0(parsed).v2fStructBody());
        assertNotNull(pass0(parsed).vertexBody());
        assertNotNull(pass0(parsed).fragmentBody());
    }

    @Test
    public void testShaderTypeSpatial() {
        CgParsedShader parsed = CgShaderParser.parse(MINIMAL_SHADER);
        assertEquals("spatial", parsed.shaderType());
    }

    @Test
    public void testPropertiesParsed() {
        CgParsedShader parsed = CgShaderParser.parse(FULL_SHADER);
        List<CgMaterialProperty> props = parsed.properties();
        assertEquals(3, props.size());
        assertEquals("_MainTex", props.get(0).getName());
        assertEquals("sampler2D", props.get(0).getGlslType());
        assertNull(props.get(0).getRawDefault());

        assertEquals("_Color", props.get(1).getName());
        assertEquals("vec4", props.get(1).getGlslType());
        assertEquals("(1.0, 1.0, 1.0, 1.0)", props.get(1).getRawDefault());

        assertEquals("_Alpha", props.get(2).getName());
        assertEquals("float", props.get(2).getGlslType());
        assertEquals("1.0", props.get(2).getRawDefault());
    }

    @Test
    public void testV2fFieldsParsed() {
        CgParsedShader parsed = CgShaderParser.parse(FULL_SHADER);
        List<CgShaderParser.V2fField> fields = CgShaderParser.parseV2fFields(pass0(parsed));
        assertEquals(3, fields.size());
        assertEquals("vec3", fields.get(0).type());
        assertEquals("worldPos", fields.get(0).name());
        assertEquals("vec3", fields.get(1).type());
        assertEquals("normal", fields.get(1).name());
        assertEquals("vec2", fields.get(2).type());
        assertEquals("uv", fields.get(2).name());
    }

    @Test
    public void testGlobalDeclsExtracted() {
        CgParsedShader parsed = CgShaderParser.parse(FULL_SHADER);
        String globals = pass0(parsed).globalDecls();
        assertTrue("Expected helper function in globalDecls", globals.contains("computeAttenuation"));
    }

    @Test
    public void testVertexBodyExtracted() {
        CgParsedShader parsed = CgShaderParser.parse(MINIMAL_SHADER);
        assertTrue(pass0(parsed).vertexBody().contains("gl_Position"));
    }

    @Test
    public void testFragmentBodyExtracted() {
        CgParsedShader parsed = CgShaderParser.parse(MINIMAL_SHADER);
        assertTrue(pass0(parsed).fragmentBody().contains("fragColor"));
    }

    @Test
    public void testThrowsOnIntegerV2fField() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n" +
            "        int id;\n" +
            "    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for int in v2f");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("integer"));
        }
    }

    @Test
    public void testThrowsOnMissingType() {
        String src =
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for missing #type");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("#type"));
        }
    }

    @Test
    public void testMissingPropertiesBlockParsesAsEmpty() {
        String src =
            "#type spatial\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertTrue("Properties block is optional; absence must yield empty list",
                parsed.properties().isEmpty());
    }

    @Test
    public void testThrowsOnUvecV2fField() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n" +
            "        uvec2 coords;\n" +
            "    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for uvec2 in v2f");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("integer"));
        }
    }

    @Test
    public void testNestedBracesInFunctionBodies() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {\n" +
            "        if (true) { o.uv = vec2(0.0); }\n" +
            "    }\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {\n" +
            "        if (true) { fragColor = vec4(1.0); }\n" +
            "    }\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertTrue(pass0(parsed).vertexBody().contains("if"));
        assertTrue(pass0(parsed).fragmentBody().contains("if"));
    }

    // ── NEW: enforcement tests added for Waves 1-4 compliance ────────────────

    @Test
    public void testThrowsOnUnknownShaderType() {
        String src =
            "#type compute\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for unknown #type");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention unknown type",
                e.getMessage().toLowerCase().contains("unknown") ||
                e.getMessage().toLowerCase().contains("compute"));
        }
    }

    @Test
    public void testThrowsOnCanvasShaderType() {
        String src =
            "#type canvas\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for unknown #type canvas");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("canvas") ||
                       e.getMessage().toLowerCase().contains("unknown"));
        }
    }

    @Test
    public void testThrowsOnReservedPrefixCgInProperty() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    cg_Color : vec4\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for cg_ prefix in property");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention reserved prefix",
                e.getMessage().contains("cg_") || e.getMessage().toLowerCase().contains("reserved"));
        }
    }

    @Test
    public void testThrowsOnReservedPrefixCGInProperty() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    CG_MyUniform : float\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for CG_ prefix in property");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention reserved prefix",
                e.getMessage().contains("CG_") || e.getMessage().toLowerCase().contains("reserved"));
        }
    }

    @Test
    public void testThrowsOnReservedPrefixV2fInProperty() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _v2f_data : vec4\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for _v2f_ prefix in property");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention reserved prefix",
                e.getMessage().contains("_v2f_") || e.getMessage().toLowerCase().contains("reserved"));
        }
    }

    @Test
    public void testThrowsOnReservedPrefixInV2fFieldName() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n" +
            "        vec3 cg_worldPos;\n" +
            "    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for cg_ prefix in v2f field name");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention reserved prefix",
                e.getMessage().contains("cg_") || e.getMessage().toLowerCase().contains("reserved"));
        }
    }

    @Test
    public void testThrowsOnVersionDirective() {
        String src =
            "#version 330 core\n" +
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for #version directive");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention #version",
                e.getMessage().toLowerCase().contains("version"));
        }
    }

    @Test
    public void testThrowsOnVersionDirectiveInBody() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "#version 430 core\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for #version in file body");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("version"));
        }
    }

    @Test
    public void testThrowsOnMainInVertexBody() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {\n" +
            "        void main() { gl_Position = vec4(0); }\n" +
            "    }\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for main() in vertex body");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention main()",
                e.getMessage().toLowerCase().contains("main"));
        }
    }

    @Test
    public void testThrowsOnMainInFragmentBody() {
        String src =
            "#type spatial\n" +
            "Properties {\n}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {\n" +
            "        void main() { fragColor = vec4(1); }\n" +
            "    }\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for main() in fragment body");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention main()",
                e.getMessage().toLowerCase().contains("main"));
        }
    }

    @Test
    public void testValidShaderWithUnderscorePrefixedProperties() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _MainTex : sampler2D\n" +
            "    _Color   : vec4 = (1.0, 1.0, 1.0, 1.0)\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertEquals(2, parsed.properties().size());
        assertEquals("_MainTex", parsed.properties().get(0).getName());
    }

    @Test
    public void testThrowsOnUnknownPropertyType() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _Foo : mat4x4\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for unknown property type");
        } catch (CgShaderParseException e) {
            assertTrue("Error should mention the bad type",
                e.getMessage().contains("mat4x4") || e.getMessage().toLowerCase().contains("unknown") ||
                e.getMessage().toLowerCase().contains("type"));
        }
    }

    @Test
    public void testThrowsOnMat4PropertyType() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _Transform : mat4\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for mat4 property type (not in valid set)");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().contains("mat4") || e.getMessage().toLowerCase().contains("type"));
        }
    }

    @Test
    public void testAllValidPropertyTypesAccepted() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _F : float\n" +
            "    _V2 : vec2\n" +
            "    _V4 : vec4\n" +
            "    _Tex : sampler2D\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertEquals(4, parsed.properties().size());
    }

    @Test
    public void testEmptyGlobalDecls() {
        CgParsedShader parsed = CgShaderParser.parse(MINIMAL_SHADER);
        String globals = pass0(parsed).globalDecls();
        assertNotNull("globalDecls must never be null", globals);
        assertEquals("Expected empty globalDecls for minimal shader", "", globals.trim());
    }

    @Test
    public void testPropertyWithDefault() {
        CgParsedShader parsed = CgShaderParser.parse(FULL_SHADER);
        CgMaterialProperty color = parsed.properties().get(1);
        assertEquals("_Color", color.getName());
        assertNotNull("Default value must be non-null when specified", color.getRawDefault());
        assertEquals("(1.0, 1.0, 1.0, 1.0)", color.getRawDefault());
    }

    @Test
    public void testPropertyWithoutDefault() {
        CgParsedShader parsed = CgShaderParser.parse(FULL_SHADER);
        CgMaterialProperty mainTex = parsed.properties().get(0);
        assertEquals("_MainTex", mainTex.getName());
        assertNull("Default value must be null when not specified", mainTex.getRawDefault());
    }

    // ── Additional edge-case tests ─────────────────────────────────────────────

    @Test
    public void testOldStyleIntPropertyParsed() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _N : int = 42\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertEquals(1, parsed.properties().size());
        assertEquals("_N", parsed.properties().get(0).getName());
        assertEquals(CgMaterialProperty.Type.INT, parsed.properties().get(0).getType());
    }

    @Test
    public void testOldStyleSamplerCubePropertyParsed() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _Sky : samplerCube\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertEquals(CgMaterialProperty.Type.SAMPLER_CUBE, parsed.properties().get(0).getType());
    }

    @Test
    public void testOldStyleSampler2DArrayPropertyParsed() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    _TexArr : sampler2DArray\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertEquals(CgMaterialProperty.Type.SAMPLER2D_ARRAY, parsed.properties().get(0).getType());
    }

    @Test
    public void testThrowsOnUnknownRenderStateKey() {
        String src =
            "#type spatial\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    RenderState { UnknownGarbage foo }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        try {
            CgShaderParser.parse(src);
            fail("Expected CgShaderParseException for unknown RenderState key");
        } catch (io.github.somehussar.crystalgraphics.gl.material.parse.CgShaderParseException e) {
            assertTrue("Error should mention the unknown key",
                e.getMessage().contains("UnknownGarbage") ||
                e.getMessage().toLowerCase().contains("unknown") ||
                e.getMessage().toLowerCase().contains("unexpected"));
        }
    }

    @Test
    public void testPropertyCommentsSkipped() {
        String src =
            "#type spatial\n" +
            "Properties {\n" +
            "    // This is a comment\n" +
            "    _Alpha : float = 1.0\n" +
            "}\n" +
            "Pass {\n" +
            "    Tags { \"LightMode\" = \"Forward\" }\n" +
            "    struct v2f {\n    vec2 uv;\n    };\n" +
            "    void vertex(out v2f o) {}\n" +
            "    void fragment(in v2f i, out vec4 fragColor) {}\n" +
            "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src);
        assertEquals("Comment line must not produce a property", 1, parsed.properties().size());
        assertEquals("_Alpha", parsed.properties().get(0).getName());
    }
}
