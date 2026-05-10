package io.github.somehussar.crystalgraphics.gl.material.parse;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class CgFragOutputParserTest {

    private static final String MINIMAL_PREFIX =
            "#type spatial\n" +
            "struct v2f {\n    vec2 uv;\n};\n";
    private static final String MINIMAL_VERTEX =
            "void vertex(out v2f o) { o.uv = vec2(0.0); }\n";

    private static String minimalShader(String globalDecls, String fragmentSig) {
        return MINIMAL_PREFIX + (globalDecls.isEmpty() ? "" : globalDecls + "\n") +
                MINIMAL_VERTEX +
                fragmentSig;
    }

    private static CgFragOutputParser.FragOutput parse(String source) {
        return CgFragOutputParser.parse(source, "test");
    }

    @Test
    public void parseFragmentOutputs_singleOutput_returnsDefaultDescriptor() {
        String src = minimalShader("",
                "void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n");
        CgFragOutputParser.FragOutput fo = parse(src);
        assertNull(fo.mrtStructName());
        assertEquals("fragColor", fo.outParamName());
        assertEquals(Arrays.asList("fragColor"), fo.fieldNames());
        assertEquals(Arrays.asList(0), fo.locations());
        assertNull(fo.mrtStructBody());
        assertFalse(fo.isMrt());
    }

    @Test
    public void parseFragmentOutputs_singleOutput_customParamName() {
        String src = minimalShader("",
                "void fragment(in v2f i, out vec4 myColor) { myColor = vec4(1.0); }\n");
        CgFragOutputParser.FragOutput fo = parse(src);
        assertNull(fo.mrtStructName());
        assertEquals("myColor", fo.outParamName());
        assertEquals(Arrays.asList("myColor"), fo.fieldNames());
        assertEquals(Arrays.asList(0), fo.locations());
    }

    @Test
    public void parseFragmentOutputs_mrtStruct_withAnnotations_returnsCorrectLocations() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal : RT1;\n" +
                "    vec4 material : RT2;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        CgFragOutputParser.FragOutput fo = parse(src);
        assertEquals("GBuffer", fo.mrtStructName());
        assertEquals("o", fo.outParamName());
        assertEquals(Arrays.asList("albedo", "normal", "material"), fo.fieldNames());
        assertEquals(Arrays.asList(0, 1, 2), fo.locations());
        assertNotNull(fo.mrtStructBody());
        assertFalse("mrtStructBody must not contain : RT", fo.mrtStructBody().contains(": RT"));
        assertTrue(fo.mrtStructBody().contains("vec4 albedo;"));
        assertTrue(fo.isMrt());
        assertEquals(3, fo.rtCount());
    }

    @Test
    public void parseFragmentOutputs_mrtStruct_skippedLocation_returnsCorrectLocations() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 emission : RT2;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        CgFragOutputParser.FragOutput fo = parse(src);
        assertEquals(Arrays.asList(0, 2), fo.locations());
        assertEquals(Arrays.asList("albedo", "emission"), fo.fieldNames());
    }

    @Test
    public void parseFragmentOutputs_mrtStruct_positionalFallback_assignsZeroOneTwoInOrder() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo;\n" +
                "    vec4 normal;\n" +
                "    vec4 emission;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        CgFragOutputParser.FragOutput fo = parse(src);
        assertEquals(Arrays.asList(0, 1, 2), fo.locations());
    }

    @Test
    public void parseFragmentOutputs_mixedAnnotations_throwsCgShaderParseException() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException for mixed annotations");
        } catch (CgShaderParseException e) {
            assertTrue("message should mention all-or-none",
                    e.getMessage().toLowerCase().contains("all-or-none") ||
                    e.getMessage().toLowerCase().contains("mixed"));
        }
    }

    @Test
    public void parseFragmentOutputs_duplicateLocation_throwsCgShaderParseException() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal : RT0;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException for duplicate location");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("duplicate") ||
                    e.getMessage().contains("RT0"));
        }
    }

    @Test
    public void parseFragmentOutputs_locationOutOfRange_throwsCgShaderParseException() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT8;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException for RT8 (out of range)");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().contains("8") ||
                    e.getMessage().toLowerCase().contains("range"));
        }
    }

    @Test
    public void parseFragmentOutputs_nineFields_throwsCgShaderParseException() {
        StringBuilder sb = new StringBuilder("struct GBuffer {\n");
        for (int i = 0; i < 9; i++) {
            sb.append("    vec4 field").append(i).append(";\n");
        }
        sb.append("};");
        String src = minimalShader(sb.toString(),
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException for 9 fields");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().contains("9") ||
                    e.getMessage().toLowerCase().contains("maximum") ||
                    e.getMessage().toLowerCase().contains("fields"));
        }
    }

    @Test
    public void parseFragmentOutputs_nonVec4Field_throwsCgShaderParseException() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec3 normal : RT0;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException for non-vec4 field");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().toLowerCase().contains("vec4") ||
                    e.getMessage().toLowerCase().contains("type"));
        }
    }

    @Test
    public void parseFragmentOutputs_unknownStructType_throwsCgShaderParseException() {
        String src = minimalShader("",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException for missing struct definition");
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage().contains("GBuffer") ||
                    e.getMessage().toLowerCase().contains("not found"));
        }
    }

    @Test
    public void parseFragmentOutputs_errorType_isCgShaderParseException_notCgPreprocessorException() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT8;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        try {
            parse(src);
            fail("Expected CgShaderParseException");
        } catch (CgShaderParseException e) {
            // Correct — it's a CgShaderParseException, not CgPreprocessorException
        } catch (io.github.somehussar.crystalgraphics.api.shader.CgPreprocessorException e) {
            fail("Must NOT throw CgPreprocessorException — parse errors are CgShaderParseException");
        }
    }
}
