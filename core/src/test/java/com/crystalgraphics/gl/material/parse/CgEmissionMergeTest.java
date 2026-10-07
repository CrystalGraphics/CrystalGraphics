package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler.EmissionMerge;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Which Emissive passes fold into their Forward draw ({@link CgMaterialShaderCompiler#emissionMerge}). */
public class CgEmissionMergeTest {

    private static final String BODY = """
            void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv = cg_TexCoord0; }
            void fragment(in v2f i, out vec4 c) { c = vec4(1.0, 0.5, 0.2, 0.5); }
            """;

    private static EmissionMerge merge(String forwardState, String emissivePass) {
        String source = "#type spatial\nTags { \"RenderType\" = \"Transparent\" }\nQueue = \"Transparent\"\n"
                + "struct v2f { vec2 uv; };\n"
                + "Pass {\n Tags { \"LightMode\" = \"Forward\" }\n RenderState { " + forwardState + " }\n" + BODY + "}\n"
                + emissivePass;
        return CgMaterialShaderCompiler.emissionMerge(CgShaderParser.parse(source, "test:merge.shader"));
    }

    @Test
    public void aCodelessPassOnTheSameBlendMerges() {
        assertEquals(EmissionMerge.SAME_BLEND, merge("Blend ONE ONE DepthWrite OFF", "Pass { Tags { \"LightMode\" = \"Emissive\" } }"));
        assertEquals(EmissionMerge.SAME_BLEND, merge("Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA DepthWrite OFF Cull OFF",
                "Pass { Tags { \"LightMode\" = \"Emissive\" } }"));
    }

    @Test
    public void anAddingPassOverAPremultipliedOneMergesWithAlphaZero() {
        assertEquals(EmissionMerge.ADDED, merge("Blend ONE ONE_MINUS_SRC_ALPHA DepthWrite OFF Cull BACK",
                "Pass { Tags { \"LightMode\" = \"Emissive\" } RenderState { Blend ONE ONE } }"));
    }

    @Test
    public void whatOneBlendCannotServeStaysApart() {
        // An alpha-blended Forward pass under an adding Emissive pass: no single blend writes both.
        assertEquals(EmissionMerge.NONE, merge("Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA DepthWrite OFF",
                "Pass { Tags { \"LightMode\" = \"Emissive\" } RenderState { Blend ONE ONE } }"));
        // An opaque Forward pass would overwrite the glow beneath it.
        assertEquals(EmissionMerge.NONE, merge("DepthWrite ON", "Pass { Tags { \"LightMode\" = \"Emissive\" } }"));
    }

    @Test
    public void codeOfItsOwnOrABranchOnThePassStaysApart() {
        assertEquals(EmissionMerge.NONE, merge("Blend ONE ONE DepthWrite OFF",
                "Pass { Tags { \"LightMode\" = \"Emissive\" }\n" + BODY + "}\n"));
        String branching = BODY.replace("c = vec4", "\n#ifdef CG_EMISSIVE_PASS\nc = vec4(2.0);\n#endif\nc += vec4");
        String source = "#type spatial\nstruct v2f { vec2 uv; };\nPass {\n Tags { \"LightMode\" = \"Forward\" }\n"
                + " RenderState { Blend ONE ONE DepthWrite OFF }\n" + branching + "}\nPass { Tags { \"LightMode\" = \"Emissive\" } }\n";
        assertEquals(EmissionMerge.NONE, CgMaterialShaderCompiler.emissionMerge(CgShaderParser.parse(source, "test:branch.shader")));
    }
}
