package io.github.somehussar.crystalgraphics.gl.material.parse;

import org.junit.Test;

import static org.junit.Assert.*;

public class CgStructureParserTest {

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

    @Test
    public void parse_mrtShader_globalDeclsContainsNoAnnotations() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal : RT1;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertFalse("globalDecls must not contain ': RT' after parse",
                parsed.globalDecls().contains(": RT"));
    }

    @Test
    public void parse_mrtShader_mrtStructBodyIsClean() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal : RT1;\n" +
                "};",
                "void fragment(in v2f i, out GBuffer o) {}\n");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertNotNull(parsed.fragOutput().mrtStructBody());
        assertFalse("mrtStructBody must not contain ': RT'",
                parsed.fragOutput().mrtStructBody().contains(": RT"));
        assertTrue(parsed.fragOutput().mrtStructBody().contains("vec4 albedo;"));
        assertTrue(parsed.fragOutput().mrtStructBody().contains("vec4 normal;"));
    }

    @Test
    public void parse_wave1Shader_singleOutputDefaults_correctFields() {
        String src = minimalShader("",
                "void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertFalse("isMrt must be false for single-output", parsed.fragOutput().isMrt());
        assertNull("mrtStructName must be null for single-output", parsed.fragOutput().mrtStructName());
        assertEquals("fragColor", parsed.fragOutput().outParamName());
        assertEquals(1, parsed.fragOutput().fieldNames().size());
        assertEquals("fragColor", parsed.fragOutput().fieldNames().get(0));
        assertEquals(1, parsed.fragOutput().locations().size());
        assertEquals(Integer.valueOf(0), parsed.fragOutput().locations().get(0));
        assertNull("mrtStructBody must be null for single-output", parsed.fragOutput().mrtStructBody());
        assertNotNull("featureNames must not be null", parsed.featureNames());
        assertTrue("featureNames must be empty stub", parsed.featureNames().isEmpty());
    }
}
