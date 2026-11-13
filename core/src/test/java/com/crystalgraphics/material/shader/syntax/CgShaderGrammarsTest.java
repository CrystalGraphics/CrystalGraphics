package com.crystalgraphics.material.shader.syntax;

import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.shader.CgShaderPermutations;
import com.crystalgraphics.platform.gl.driver.GLC;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** The blocks' grammars: {@code RenderState}, {@code Keywords}, {@code Properties}; and the permutations keywords make. */
public class CgShaderGrammarsTest {

    private static CgRenderState state(String entries) {
        CgShaderTokens t = CgShaderTokens.lex(entries, "test:state");
        return CgRenderStateGrammar.read(t, 0, t.count());
    }

    private static void refused(Runnable read, String naming) {
        try {
            read.run();
        } catch (CgShaderError e) {
            assertTrue(e.getMessage(), e.getMessage().contains(naming));
            return;
        }
        throw new AssertionError("read, expected a refusal naming " + naming);
    }

    // ── RenderState ──────────────────────────────────────────────────────────

    @Test
    public void anAuthoredStateIsTheOneItsBuilderMakes() {
        CgRenderState authored = state("Blend ONE ONE_MINUS_SRC_ALPHA  DepthTest LEQUAL  DepthWrite OFF  Cull OFF");
        CgRenderState built = CgRenderStateGrammar.state().blend(GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA)
                .depthTest(GLC.GL_LEQUAL).depthWrite(false).cull(CgCullState.NONE).build();
        assertEquals(built.getBlend(), authored.getBlend());
        assertEquals(built.getDepth(), authored.getDepth());
        assertEquals(built.getCull(), authored.getCull());
    }

    @Test
    public void whatNoEntryNamesStaysUndeclared() {
        CgRenderState s = state("Blend ONE ONE");
        assertNotNull(s.getBlend());
        assertNull(s.getDepth());
        assertNull(s.getCull());
    }

    @Test
    public void namesAreCaseInsensitiveAndUnitysAreRefusedNamingTheirReplacement() {
        assertEquals(state("Blend ONE ONE").getBlend(), state("blend one one").getBlend());
        refused(() -> state("ZWrite Off"), "DepthWrite");
        refused(() -> state("ZTest LEqual"), "DepthTest");
        refused(() -> state("Blend ONE"), "Blend");
        refused(() -> state("Cull SIDEWAYS"), "SIDEWAYS");
    }

    // ── Keywords ─────────────────────────────────────────────────────────────

    @Test
    public void keywordsAreSwitchesOneOfsAndSwitchedDimensions() {
        List<CgKeywordGrammar.Keyword> k = CgKeywordGrammar.read(
                "MOSS (\"Mossy\")  DEPTH (\"Writes depth\", switched)  SHAPE (\"Shape\") = BOX | CONE  BARE", "test:k");
        assertEquals(4, k.size());
        assertEquals(List.of("MOSS"), k.get(0).features());
        assertFalse(k.get(0).dimension());
        assertTrue(k.get(1).switched());
        assertTrue(k.get(1).dimension());
        assertEquals(List.of("SHAPE_BOX", "SHAPE_CONE"), k.get(2).features());
        assertTrue(k.get(2).dimension());
        assertEquals("BARE", k.get(3).name());
    }

    @Test
    public void keywordRefusals() {
        refused(() -> CgKeywordGrammar.read("SHAPE (\"Shape\") = BOX", "test:k"), "one value");
        refused(() -> CgKeywordGrammar.read("moss", "test:k"), "moss");
    }

    // ── Properties ───────────────────────────────────────────────────────────

    @Test
    public void propertiesKeepTheirDefaultsAndRanges() {
        List<CgMaterialProperty> p = CgPropertyGrammar.read("""
                _Color ("Tint", color) = (1, 0.5, 0, 1)
                _Amount ("Amount", float, range(0, 4)) = 2
                _Steps ("Steps", int) = 3
                _Mask ("Mask", sampler2D) = "white"
                _Bands ("Bands", vec4[3]) = { (0, 0, 0, 1), (0, 0, 0, 0.5), (1, 1, 1, 1) }
                """, "test:p", false);
        assertEquals(5, p.size());
        assertEquals("Tint", p.get(0).getDisplayName());
        assertEquals(4f, p.get(1).getRangeMax(), 0f);
        assertEquals(2f, p.get(1).getFloatValue()[0], 0f);
        assertEquals("white", p.get(3).getRawDefault());
        assertTrue(p.get(4).isArray());
        assertEquals(3, p.get(4).getCount());
    }

    @Test
    public void propertyRefusals() {
        refused(() -> CgPropertyGrammar.read("_A (\"A\", float) = 1\n_A (\"A again\", float) = 2", "test:p", false), "_A");
        refused(() -> CgPropertyGrammar.read("_A (\"A\", quaternion) = 1", "test:p", false), "quaternion");
        refused(() -> CgPropertyGrammar.read("_cg_A (\"A\", float) = 1", "test:p", false), "_cg_");
    }

    // ── Permutations ─────────────────────────────────────────────────────────

    @Test
    public void permutationsSpanEveryCombinationOnce() {
        CgShaderPermutations p = new CgShaderPermutations(List.of(List.of("CHEAP"), List.of("SHAPE_BOX", "SHAPE_CONE")));
        assertEquals(6, p.size());
        List<List<String>> all = new ArrayList<>();
        p.forEach(List.of(), all::add);
        assertEquals(6, all.size());
        assertTrue(all.contains(List.of()));
        assertTrue(all.contains(List.of("CHEAP", "SHAPE_CONE")));

        List<List<String>> fixed = new ArrayList<>();
        p.forEach(List.of("SHAPE_BOX"), fixed::add);
        assertEquals("a fixed one-of yields itself alone", 2, fixed.size());
        for (List<String> k : fixed) assertTrue(k.contains("SHAPE_BOX") && !k.contains("SHAPE_CONE"));
        assertTrue(p.contains("CHEAP"));
        assertEquals(1, CgShaderPermutations.NONE.size());
    }
}
