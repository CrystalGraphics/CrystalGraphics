package com.crystalgraphics.gl.material.parse;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * {@code #pragma cg_use} — declaring an engine-provided shader buffer.
 *
 * <p>The reverse check is the one that matters. Before this existed, a shader could read
 * {@code CG_QUAD_WORLD_POS} without declaring anything and compile fine <em>as long as</em> some
 * Java caller had already run {@code CgQuadRenderer.attachTo(material)}. When nothing had — because
 * the buffer is only attached on a material's first {@code useMaterial()}, and
 * {@code CgMaterial.enableKeyword} recompiles on the spot before that — the shader compiled with
 * {@code QUAD_DATA} undeclared, and the failure surfaced as
 * {@code "Keyword 'WITH_BORDER' is not declared as #pragma cg_feature"}: a message naming a pragma
 * that was present and correct, four layers from the actual cause.</p>
 */
public class CgShaderParserUsePragmaTest {

    private static String shader(String pragmas, String vertexBody) {
        return "#type pos2_uv2_col4ub\n"
                + pragmas
                + "\nstruct v2f { vec2 uv; };\n"
                + "Pass {\n"
                + "    void vertex(out v2f o) {\n"
                + vertexBody
                + "\n    }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
                + "}\n";
    }

    @Test
    public void declaredTokenIsParsed() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("#pragma cg_use quad",
                        "        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);"),
                "test:declared.shader");
        assertEquals(List.of("quad"), parsed.engineBuffers());
    }

    /** A shader that uses none of the macros needs no pragma — the common case must stay free. */
    @Test
    public void shaderNotUsingEngineBuffersNeedsNoPragma() {
        CgParsedShader parsed = CgShaderParser.parse(
                shader("", "        gl_Position = cg_ProjMatrix * vec4(cg_Position, 0.0, 1.0);"),
                "test:plain.shader");
        assertTrue(parsed.engineBuffers().isEmpty());
    }

    /** The regression this whole mechanism exists for. */
    @Test
    public void usingQuadMacrosWithoutPragmaIsARejectedAtParseTime() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class, () ->
                CgShaderParser.parse(
                        shader("", "        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);"),
                        "test:undeclared.shader"));
        assertTrue("message should name the fix, was: " + e.getMessage(),
                e.getMessage().contains("#pragma cg_use quad"));
    }

    /** Indexing the buffer directly must be caught too, not just the convenience macros. */
    @Test
    public void usingRawMacroNameWithoutPragmaIsRejected() {
        assertThrows(CgShaderParseException.class, () ->
                CgShaderParser.parse(
                        shader("", "        vec3 p = QUAD_DATA(CG_INSTANCE_ID).origin;"),
                        "test:raw.shader"));
    }

    @Test
    public void unknownTokenIsRejected() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class, () ->
                CgShaderParser.parse(shader("#pragma cg_use nonsense", "        gl_Position = vec4(0.0);"),
                        "test:unknown.shader"));
        assertTrue(e.getMessage().contains("unknown buffer token"));
    }

    @Test
    public void duplicateTokenIsRejected() {
        assertThrows(CgShaderParseException.class, () ->
                CgShaderParser.parse(
                        shader("#pragma cg_use quad\n#pragma cg_use quad",
                                "        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);"),
                        "test:dupe.shader"));
    }

    @Test
    public void missingTokenIsRejected() {
        assertThrows(CgShaderParseException.class, () ->
                CgShaderParser.parse(shader("#pragma cg_use", "        gl_Position = vec4(0.0);"),
                        "test:blank.shader"));
    }
}
