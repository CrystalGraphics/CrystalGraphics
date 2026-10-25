package com.crystalgraphics.gl.material.parse;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A multi-pass post material's passes: each but the last into a target a sampler names, the last over the scene. */
public class CgPostTargetsParseTest {

    private static String post(String tags, String properties, String... passTags) {
        StringBuilder s = new StringBuilder("#type none\nTags { \"RenderType\" = \"Transparent\" \"Lighting\" = \"Unlit\"")
                .append(tags).append(" }\nQueue = \"Overlay\"\nProperties {\n").append(properties)
                .append("}\nstruct v2f { vec2 uv; };\n");
        for (String t : passTags) {
            s.append("Pass {\n    Tags { \"LightMode\" = \"Forward\" ").append(t).append(" }\n")
                    .append("    void vertex(out v2f o) { o.uv = vec2(0.0); gl_Position = vec4(0.0); }\n")
                    .append("    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n}\n");
        }
        return s.toString();
    }

    private static final String SAMPLERS = "    _Half (\"Half\", sampler2D) = \"black\"\n    _Blur (\"Blur\", sampler2D) = \"black\"\n"
            + "    _Mask (\"Mask\", sampler2D) = \"black\"\n    _Amount (\"Amount\", float) = 1.0\n";

    @Test
    public void eachPassButTheLastDrawsIntoItsTargetInOrder() {
        CgParsedShader parsed = CgShaderParser.parse(post(" \"Emission\" = \"_Mask\"", SAMPLERS,
                "\"Name\" = \"Down\" \"Target\" = \"_Half\" \"Scale\" = \"0.5\"",
                "\"Name\" = \"Blur\" \"Target\" = \"_Blur\" \"Scale\" = \"0.25\"",
                "\"Name\" = \"Over\""), "test:post");
        List<CgParsedShader.PostTarget> targets = parsed.postTargets();
        assertEquals(2, targets.size());
        assertEquals(new CgParsedShader.PostTarget("Down", "_Half", 0.5f), targets.get(0));
        assertEquals(new CgParsedShader.PostTarget("Blur", "_Blur", 0.25f), targets.get(1));
        assertEquals("_Mask", parsed.emissionInput());
    }

    @Test
    public void aOnePassMaterialHasNoTargets() {
        CgParsedShader parsed = CgShaderParser.parse(post("", SAMPLERS, "\"Name\" = \"Over\""), "test:post");
        assertTrue(parsed.postTargets().isEmpty());
        assertNull(parsed.emissionInput());
    }

    @Test
    public void aTargetWithoutItsSamplerFailsNamingIt() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(post("", SAMPLERS,
                "\"Name\" = \"Down\" \"Target\" = \"_Missing\"", "\"Name\" = \"Over\""), "test:post"));
        assertTrue(e.getMessage(), e.getMessage().contains("_Missing") && e.getMessage().contains("sampler2D"));
        e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(post("", SAMPLERS,
                "\"Name\" = \"Down\" \"Target\" = \"_Amount\"", "\"Name\" = \"Over\""), "test:post"));
        assertTrue(e.getMessage(), e.getMessage().contains("_Amount"));
    }

    @Test
    public void theLastPassDrawsOverTheSceneAndEveryOtherIntoATarget() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(post("", SAMPLERS,
                "\"Name\" = \"Down\" \"Target\" = \"_Half\"", "\"Name\" = \"Over\" \"Target\" = \"_Blur\""), "test:post"));
        assertTrue(e.getMessage(), e.getMessage().contains("Over"));
        e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(post("", SAMPLERS,
                "\"Name\" = \"Down\"", "\"Name\" = \"Blur\" \"Target\" = \"_Blur\"", "\"Name\" = \"Over\""), "test:post"));
        assertTrue(e.getMessage(), e.getMessage().contains("Down"));
    }

    @Test
    public void aScaleIsAShareOfTheScreen() {
        for (String scale : new String[]{"0", "1.5", "half"}) {
            CgShaderParseException e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(post("", SAMPLERS,
                    "\"Name\" = \"Down\" \"Target\" = \"_Half\" \"Scale\" = \"" + scale + "\"", "\"Name\" = \"Over\""), "test:post"));
            assertTrue(e.getMessage(), e.getMessage().contains("Scale"));
        }
    }

    @Test
    public void twoPassesIntoOneTargetFail() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(post("", SAMPLERS,
                "\"Name\" = \"A\" \"Target\" = \"_Half\"", "\"Name\" = \"B\" \"Target\" = \"_Half\"", "\"Name\" = \"Over\""),
                "test:post"));
        assertTrue(e.getMessage(), e.getMessage().contains("_Half"));
    }

    @Test
    public void theEmissionTagNamesASampler() {
        CgShaderParseException e = assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(
                post(" \"Emission\" = \"_Glare\"", SAMPLERS, "\"Name\" = \"Over\""), "test:post"));
        assertTrue(e.getMessage(), e.getMessage().contains("_Glare"));
    }
}
