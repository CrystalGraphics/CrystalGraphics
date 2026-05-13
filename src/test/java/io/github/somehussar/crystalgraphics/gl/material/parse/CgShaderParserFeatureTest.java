package io.github.somehussar.crystalgraphics.gl.material.parse;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.material.CgAttachedBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CgShaderParserFeatureTest {

    private static final CgCapabilities.ShaderBufferPath TBO = CgCapabilities.ShaderBufferPath.TBO;
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

    // ── Shared minimal shader skeleton ────────────────────────────────────────

    private static final String PASS_BODY =
            "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
            + "}\n";

    private static String shader(String... preambleLines) {
        StringBuilder sb = new StringBuilder("#type spatial\n");
        for (String line : preambleLines) sb.append(line).append("\n");
        sb.append(PASS_BODY);
        return sb.toString();
    }

    // ── Happy-path tests ──────────────────────────────────────────────────────

    @Test
    public void noFeatures_returnsEmptyList() {
        CgParsedShader parsed = CgShaderParser.parse(shader(), "test");
        assertTrue("Expected empty feature list", parsed.featureNames().isEmpty());
    }

    @Test
    public void singleFeature_returnsSingleEntry() {
        CgParsedShader parsed = CgShaderParser.parse(shader("#pragma cg_feature SHADOWS_ON"), "test");
        assertEquals(Collections.singletonList("SHADOWS_ON"), parsed.featureNames());
    }

    @Test
    public void twoFeatures_returnsInDeclarationOrder() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma cg_feature SHADOWS_ON", "#pragma cg_feature FOG"), "test");
        List<String> names = parsed.featureNames();
        assertEquals(2, names.size());
        assertEquals("SHADOWS_ON", names.get(0));
        assertEquals("FOG", names.get(1));
    }

    @Test
    public void pragmaOnceIgnored_featuresStillParsed() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma once", "#pragma cg_feature FEATURE_A"), "test");
        assertEquals(Collections.singletonList("FEATURE_A"), parsed.featureNames());
    }

    @Test
    public void underscorePrefix_validIdentifier() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma cg_feature _MY_FLAG"), "test");
        assertEquals("_MY_FLAG", parsed.featureNames().get(0));
    }

    @Test
    public void featureNameInVertexBody_notParsed() {
        // A #pragma cg_feature that appears inside a Pass block must be ignored
        String src = "#type spatial\n"
                + "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    struct v2f {\n    vec2 uv;\n};\n"
                + "    void vertex(out v2f o) {\n"
                + "        #pragma cg_feature INSIDE_VERTEX\n"
                + "        o.uv = vec2(0.0);\n"
                + "    }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
                + "}\n";
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertTrue("Feature inside Pass body must not be parsed", parsed.featureNames().isEmpty());
    }

    @Test
    public void featureNamesImmutable() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma cg_feature FOO"), "test");
        try {
            parsed.featureNames().add("BAR");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // correct — list is unmodifiable
        }
    }

    @Test
    public void eightFeatures_exactlyAtLimit() {
        String src = shader(
                "#pragma cg_feature A",
                "#pragma cg_feature B",
                "#pragma cg_feature C",
                "#pragma cg_feature D",
                "#pragma cg_feature E",
                "#pragma cg_feature F",
                "#pragma cg_feature G",
                "#pragma cg_feature H");
        CgParsedShader parsed = CgShaderParser.parse(src, "test");
        assertEquals(8, parsed.featureNames().size());
    }

    // ── Error tests ───────────────────────────────────────────────────────────

    @Test(expected = CgShaderParseException.class)
    public void invalidIdentifier_startsWithDigit_throws() {
        CgShaderParser.parse(shader("#pragma cg_feature 123BAD"), "test");
    }

    @Test(expected = CgShaderParseException.class)
    public void invalidIdentifier_hasSpace_throws() {
        CgShaderParser.parse(shader("#pragma cg_feature MY FLAG"), "test");
    }

    @Test(expected = CgShaderParseException.class)
    public void missingName_throws() {
        CgShaderParser.parse(shader("#pragma cg_feature"), "test");
    }

    @Test(expected = CgShaderParseException.class)
    public void duplicateName_throws() {
        CgShaderParser.parse(
                shader("#pragma cg_feature SHADOWS_ON", "#pragma cg_feature SHADOWS_ON"), "test");
    }

    @Test(expected = CgShaderParseException.class)
    public void nineFeaturesExceedsMax_throws() {
        CgShaderParser.parse(shader(
                "#pragma cg_feature A",
                "#pragma cg_feature B",
                "#pragma cg_feature C",
                "#pragma cg_feature D",
                "#pragma cg_feature E",
                "#pragma cg_feature F",
                "#pragma cg_feature G",
                "#pragma cg_feature H",
                "#pragma cg_feature I"), "test");
    }

    // ── Compiler integration — pragma never in generated GLSL ────────────────

    @Test
    public void pragmaStrippedFromGeneratedVertexGlsl() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma cg_feature SHADOWS_ON"), "test");
        CgMaterialShaderCompiler.CompiledSource src =
                CgMaterialShaderCompiler.compile(parsed, NO_BUFFERS, null,
                        CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        assertFalse("cg_feature pragma must not appear in vertex GLSL",
                src.vertexSource().contains("#pragma cg_feature"));
    }

    @Test
    public void pragmaStrippedFromGeneratedFragmentGlsl() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma cg_feature SHADOWS_ON"), "test");
        CgMaterialShaderCompiler.CompiledSource src =
                CgMaterialShaderCompiler.compile(parsed, NO_BUFFERS, null,
                        CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        assertFalse("cg_feature pragma must not appear in fragment GLSL",
                src.fragmentSource().contains("#pragma cg_feature"));
    }
}
