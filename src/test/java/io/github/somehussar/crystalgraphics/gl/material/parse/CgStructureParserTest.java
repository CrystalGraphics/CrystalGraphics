package io.github.somehussar.crystalgraphics.gl.material.parse;

import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgStructureParserTest {

    private static CgParsedPass pass0(CgParsedShader p) {
        return p.passes().get(0);
    }

    private static String minimalShader(String passGlobalDecls, String fragmentSig) {
        return "#type spatial\n"
                + "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    struct v2f {\n    vec2 uv;\n};\n"
                + (passGlobalDecls.isEmpty() ? "" : passGlobalDecls + "\n")
                + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
                + fragmentSig
                + "}\n";
    }

    @Test
    public void parse_mrtShader_globalDeclsContainsNoAnnotations() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal : RT1;\n" +
                "};",
                "    void fragment(in v2f i, out GBuffer o) {}\n");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertFalse("globalDecls must not contain ': RT' after parse",
                pass0(parsed).globalDecls().contains(": RT"));
    }

    @Test
    public void parse_mrtShader_mrtStructBodyIsClean() {
        String src = minimalShader(
                "struct GBuffer {\n" +
                "    vec4 albedo : RT0;\n" +
                "    vec4 normal : RT1;\n" +
                "};",
                "    void fragment(in v2f i, out GBuffer o) {}\n");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertNotNull(pass0(parsed).fragOutput().mrtStructBody());
        assertFalse("mrtStructBody must not contain ': RT'",
                pass0(parsed).fragOutput().mrtStructBody().contains(": RT"));
        assertTrue(pass0(parsed).fragOutput().mrtStructBody().contains("vec4 albedo;"));
        assertTrue(pass0(parsed).fragOutput().mrtStructBody().contains("vec4 normal;"));
    }

    @Test
    public void parse_wave1Shader_singleOutputDefaults_correctFields() {
        String src = minimalShader("",
                "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertFalse("isMrt must be false for single-output", pass0(parsed).fragOutput().isMrt());
        assertNull("mrtStructName must be null for single-output", pass0(parsed).fragOutput().mrtStructName());
        assertEquals("fragColor", pass0(parsed).fragOutput().outParamName());
        assertEquals(1, pass0(parsed).fragOutput().fieldNames().size());
        assertEquals("fragColor", pass0(parsed).fragOutput().fieldNames().get(0));
        assertEquals(1, pass0(parsed).fragOutput().locations().size());
        assertEquals(Integer.valueOf(0), pass0(parsed).fragOutput().locations().get(0));
        assertNull("mrtStructBody must be null for single-output", pass0(parsed).fragOutput().mrtStructBody());
        assertNotNull("featureNames must not be null", parsed.featureNames());
        assertTrue("featureNames must be empty stub", parsed.featureNames().isEmpty());
    }
}
