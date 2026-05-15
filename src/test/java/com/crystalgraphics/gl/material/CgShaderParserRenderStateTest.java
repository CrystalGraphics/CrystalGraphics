package com.crystalgraphics.gl.material;

import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgRenderState;

import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParseException;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Intensive unit tests for the {@link CgShaderParser} — RenderState block, Queue keyword,
 * and new property type parsing.
 *
 * <p>All tests are pure string processing — no GL context required. All GL constants
 * referenced here are static final int fields in LWJGL classes, not actual GL calls.</p>
 *
 * <p>Every test logs its result to stdout:
 * <ul>
 *   <li>Success: {@code ✓ [TestName]: <field values>}</li>
 *   <li>Expected throw: {@code ✓ [TestName]: threw as expected — <message>}</li>
 * </ul>
 */
public class CgShaderParserRenderStateTest {

    // ── Shader builder helpers ────────────────────────────────────────────────

    private static CgRenderState rs(CgParsedShader p) {
        return p.passes().get(0).renderState();
    }

    /** Wraps a RenderState block string into a complete minimal shader. */
    private static CgParsedShader parseWithRS(String renderStateBlock) {
        String src = "#type spatial\n" +
                "Pass {\n" +
                "    Tags { \"LightMode\" = \"Forward\" }\n" +
                renderStateBlock +
                "    struct v2f { vec2 uv; };\n" +
                "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
                "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
                "}\n";
        return CgShaderParser.parse(src, "test");
    }

    /** Parses a full shader source string. */
    private static CgParsedShader parseFull(String source) {
        return CgShaderParser.parse(source, "test");
    }

    /** Asserts that parsing throws {@link CgShaderParseException} and logs the message. */
    private static void assertThrows(String testName, Runnable action) {
        try {
            action.run();
            fail(testName + " — expected CgShaderParseException but none was thrown");
        } catch (CgShaderParseException e) {
            System.out.println("✓ " + testName + ": threw as expected — " + e.getMessage());
        }
    }

    // ── Group 1 — DepthTest ───────────────────────────────────────────────────

    @Test
    public void depthTest_LESS() {
        CgParsedShader p = parseWithRS("RenderState { DepthTest LESS }\n");
        int func = rs(p).getDepth().compareFunc();
        System.out.println("✓ depthTest_LESS: compareFunc=" + func);
        assertEquals(GL11.GL_LESS, func);
        assertTrue(rs(p).getDepth().test());
    }

    @Test
    public void depthTest_lowercase_lequal() {
        CgParsedShader p = parseWithRS("RenderState { DepthTest lequal }\n");
        int func = rs(p).getDepth().compareFunc();
        System.out.println("✓ depthTest_lowercase_lequal: compareFunc=" + func);
        assertEquals(GL11.GL_LEQUAL, func);
    }

    @Test
    public void depthTest_mixedCase_Less() {
        CgParsedShader p = parseWithRS("RenderState { DepthTest Less }\n");
        int func = rs(p).getDepth().compareFunc();
        System.out.println("✓ depthTest_mixedCase_Less: compareFunc=" + func);
        assertEquals(GL11.GL_LESS, func);
    }

    @Test
    public void depthTest_ALWAYS() {
        CgParsedShader p = parseWithRS("RenderState { DepthTest ALWAYS }\n");
        System.out.println("✓ depthTest_ALWAYS: compareFunc=" + rs(p).getDepth().compareFunc());
        assertEquals(GL11.GL_ALWAYS, rs(p).getDepth().compareFunc());
    }

    @Test
    public void depthTest_NEVER() {
        CgParsedShader p = parseWithRS("RenderState { DepthTest NEVER }\n");
        System.out.println("✓ depthTest_NEVER: compareFunc=" + rs(p).getDepth().compareFunc());
        assertEquals(GL11.GL_NEVER, rs(p).getDepth().compareFunc());
    }

    @Test
    public void depthTest_oldKeyword_ZTest_throws() {
        assertThrows("depthTest_oldKeyword_ZTest_throws",
                () -> parseWithRS("RenderState { ZTest Less }\n"));
    }

    @Test
    public void depthTest_invalidValue_MEDIUM_throws() {
        assertThrows("depthTest_invalidValue_MEDIUM_throws",
                () -> parseWithRS("RenderState { DepthTest MEDIUM }\n"));
    }

    @Test
    public void depthTest_invalidValue_null_throws() {
        assertThrows("depthTest_invalidValue_null_throws",
                () -> parseWithRS("RenderState { DepthTest null }\n"));
    }

    @Test
    public void depthTest_wrongDomain_SRC_ALPHA_throws() {
        assertThrows("depthTest_wrongDomain_SRC_ALPHA_throws",
                () -> parseWithRS("RenderState { DepthTest SRC_ALPHA }\n"));
    }

    @Test
    public void depthTest_missingValue_throws() {
        assertThrows("depthTest_missingValue_throws",
                () -> parseWithRS("RenderState { DepthTest }\n"));
    }

    // ── Group 2 — DepthWrite ──────────────────────────────────────────────────

    @Test
    public void depthWrite_ON() {
        CgParsedShader p = parseWithRS("RenderState { DepthWrite ON }\n");
        System.out.println("✓ depthWrite_ON: write=" + rs(p).getDepth().write());
        assertTrue(rs(p).getDepth().write());
    }

    @Test
    public void depthWrite_OFF() {
        CgParsedShader p = parseWithRS("RenderState { DepthWrite OFF }\n");
        System.out.println("✓ depthWrite_OFF: write=" + rs(p).getDepth().write());
        assertFalse(rs(p).getDepth().write());
    }

    @Test
    public void depthWrite_lowercase_on() {
        CgParsedShader p = parseWithRS("RenderState { DepthWrite on }\n");
        System.out.println("✓ depthWrite_lowercase_on: write=" + rs(p).getDepth().write());
        assertTrue(rs(p).getDepth().write());
    }

    @Test
    public void depthWrite_oldKeyword_ZWrite_throws() {
        assertThrows("depthWrite_oldKeyword_ZWrite_throws",
                () -> parseWithRS("RenderState { ZWrite Off }\n"));
    }

    @Test
    public void depthWrite_invalidValue_YES_throws() {
        assertThrows("depthWrite_invalidValue_YES_throws",
                () -> parseWithRS("RenderState { DepthWrite YES }\n"));
    }

    // ── Group 3 — Blend ───────────────────────────────────────────────────────

    @Test
    public void blend_SRC_ALPHA_ONE_MINUS_SRC_ALPHA() {
        CgParsedShader p = parseWithRS("RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA }\n");
        CgBlendState b = rs(p).getBlend();
        System.out.println("✓ blend_SRC_ALPHA_ONE_MINUS_SRC_ALPHA: enabled=" + b.enabled()
                + " srcRgb=" + b.srcRgb() + " dstRgb=" + b.dstRgb());
        assertTrue(b.enabled());
        assertEquals(GL11.GL_SRC_ALPHA, b.srcRgb());
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, b.dstRgb());
    }

    @Test
    public void blend_ONE_ZERO() {
        CgParsedShader p = parseWithRS("RenderState { Blend ONE ZERO }\n");
        CgBlendState b = rs(p).getBlend();
        System.out.println("✓ blend_ONE_ZERO: enabled=" + b.enabled() + " srcRgb=" + b.srcRgb() + " dstRgb=" + b.dstRgb());
        assertTrue(b.enabled());
        assertEquals(GL11.GL_ONE, b.srcRgb());
        assertEquals(GL11.GL_ZERO, b.dstRgb());
    }

    @Test
    public void blend_OFF() {
        CgParsedShader p = parseWithRS("RenderState { Blend OFF }\n");
        System.out.println("✓ blend_OFF: enabled=" + rs(p).getBlend().enabled());
        assertFalse(rs(p).getBlend().enabled());
    }

    @Test
    public void blend_off_lowercase() {
        CgParsedShader p = parseWithRS("RenderState { Blend off }\n");
        System.out.println("✓ blend_off_lowercase: enabled=" + rs(p).getBlend().enabled());
        assertFalse(rs(p).getBlend().enabled());
    }

    @Test
    public void blend_typo_SRC_ALPHAAA_throws() {
        assertThrows("blend_typo_SRC_ALPHAAA_throws",
                () -> parseWithRS("RenderState { Blend SRC_ALPHAAA ONE_MINUS_SRC_ALPHA }\n"));
    }

    @Test
    public void blend_null_src_throws() {
        assertThrows("blend_null_src_throws",
                () -> parseWithRS("RenderState { Blend null ONE }\n"));
    }

    @Test
    public void blend_only_one_factor_throws() {
        assertThrows("blend_only_one_factor_throws",
                () -> parseWithRS("RenderState { Blend SRC_ALPHA }\n"));
    }

    @Test
    public void blend_PascalCase_factors_throws() {
        assertThrows("blend_PascalCase_factors_throws",
                () -> parseWithRS("RenderState { Blend SrcAlpha OneMinusSrcAlpha }\n"));
    }

    @Test
    public void blend_SRC_ALPHA_SATURATE() {
        CgParsedShader p = parseWithRS("RenderState { Blend SRC_ALPHA_SATURATE ONE }\n");
        CgBlendState b = rs(p).getBlend();
        System.out.println("✓ blend_SRC_ALPHA_SATURATE: srcRgb=" + b.srcRgb());
        assertTrue(b.enabled());
        assertEquals(GL11.GL_SRC_ALPHA_SATURATE, b.srcRgb());
        assertEquals(GL11.GL_ONE, b.dstRgb());
    }

    // ── Group 4 — BlendEquation ───────────────────────────────────────────────

    @Test
    public void blendEquation_ADD() {
        CgParsedShader p = parseWithRS("RenderState { BlendEquation ADD }\n");
        int eq = rs(p).getBlend().blendEquationRgb();
        System.out.println("✓ blendEquation_ADD: blendEquationRgb=" + eq);
        assertEquals(GL14.GL_FUNC_ADD, eq);
    }

    @Test
    public void blendEquation_lowercase_add() {
        CgParsedShader p = parseWithRS("RenderState { BlendEquation add }\n");
        System.out.println("✓ blendEquation_lowercase_add: blendEquationRgb=" + rs(p).getBlend().blendEquationRgb());
        assertEquals(GL14.GL_FUNC_ADD, rs(p).getBlend().blendEquationRgb());
    }

    @Test
    public void blendEquation_REV_SUB() {
        CgParsedShader p = parseWithRS("RenderState { BlendEquation REV_SUB }\n");
        System.out.println("✓ blendEquation_REV_SUB: blendEquationRgb=" + rs(p).getBlend().blendEquationRgb());
        assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, rs(p).getBlend().blendEquationRgb());
    }

    @Test
    public void blendEquation_mrtIndex_0_ADD() {
        CgParsedShader p = parseWithRS("RenderState { BlendEquation 0 ADD }\n");
        System.out.println("✓ blendEquation_mrtIndex_0_ADD: blendEquationRgb=" + rs(p).getBlend().blendEquationRgb());
        assertEquals(GL14.GL_FUNC_ADD, rs(p).getBlend().blendEquationRgb());
    }

    @Test
    public void blendEquation_mrtIndex_1_MAX() {
        CgParsedShader p = parseWithRS("RenderState { BlendEquation 1 MAX }\n");
        System.out.println("✓ blendEquation_mrtIndex_1_MAX: blendEquationRgb=" + rs(p).getBlend().blendEquationRgb());
        assertEquals(GL14.GL_MAX, rs(p).getBlend().blendEquationRgb());
    }

    @Test
    public void blendEquation_oldKeyword_BlendOp_throws() {
        assertThrows("blendEquation_oldKeyword_BlendOp_throws",
                () -> parseWithRS("RenderState { BlendOp Add }\n"));
    }

    @Test
    public void blendEquation_invalidValue_MULTIPLY_throws() {
        assertThrows("blendEquation_invalidValue_MULTIPLY_throws",
                () -> parseWithRS("RenderState { BlendEquation MULTIPLY }\n"));
    }

    // ── Group 5 — Cull ────────────────────────────────────────────────────────

    @Test
    public void cull_BACK() {
        CgParsedShader p = parseWithRS("RenderState { Cull BACK }\n");
        System.out.println("✓ cull_BACK: face=" + rs(p).getCull().face() + " enabled=" + rs(p).getCull().enabled());
        assertTrue(rs(p).getCull().enabled());
        assertEquals(GL11.GL_BACK, rs(p).getCull().face());
    }

    @Test
    public void cull_FRONT() {
        CgParsedShader p = parseWithRS("RenderState { Cull FRONT }\n");
        System.out.println("✓ cull_FRONT: face=" + rs(p).getCull().face());
        assertEquals(GL11.GL_FRONT, rs(p).getCull().face());
    }

    @Test
    public void cull_OFF() {
        CgParsedShader p = parseWithRS("RenderState { Cull OFF }\n");
        System.out.println("✓ cull_OFF: enabled=" + rs(p).getCull().enabled());
        assertFalse(rs(p).getCull().enabled());
    }

    @Test
    public void cull_off_lowercase() {
        CgParsedShader p = parseWithRS("RenderState { Cull off }\n");
        System.out.println("✓ cull_off_lowercase: enabled=" + rs(p).getCull().enabled());
        assertFalse(rs(p).getCull().enabled());
    }

    @Test
    public void cull_Back_mixedCase() {
        CgParsedShader p = parseWithRS("RenderState { Cull Back }\n");
        System.out.println("✓ cull_Back_mixedCase: face=" + rs(p).getCull().face());
        assertEquals(GL11.GL_BACK, rs(p).getCull().face());
    }

    @Test
    public void cull_NONE_throws() {
        assertThrows("cull_NONE_throws",
                () -> parseWithRS("RenderState { Cull NONE }\n"));
    }

    @Test
    public void cull_null_throws() {
        assertThrows("cull_null_throws",
                () -> parseWithRS("RenderState { Cull null }\n"));
    }

    // ── Group 6 — AlphaTest ───────────────────────────────────────────────────

    @Test
    public void alphaTest_GREATER_0_5() {
        CgParsedShader p = parseWithRS("RenderState { AlphaTest GREATER 0.5 }\n");
        System.out.println("✓ alphaTest_GREATER_0_5: enabled=" + rs(p).getAlpha().enabled()
                + " func=" + rs(p).getAlpha().func()
                + " cutoff=" + rs(p).getAlpha().cutoff());
        assertTrue(rs(p).getAlpha().enabled());
        assertEquals(GL11.GL_GREATER, rs(p).getAlpha().func());
        assertEquals(0.5f, rs(p).getAlpha().cutoff(), 0.0001f);
    }

    @Test
    public void alphaTest_LEQUAL_0_0() {
        CgParsedShader p = parseWithRS("RenderState { AlphaTest LEQUAL 0.0 }\n");
        System.out.println("✓ alphaTest_LEQUAL_0_0: func=" + rs(p).getAlpha().func());
        assertEquals(GL11.GL_LEQUAL, rs(p).getAlpha().func());
        assertEquals(0.0f, rs(p).getAlpha().cutoff(), 0.0001f);
    }

    @Test
    public void alphaTest_lowercase_greater() {
        CgParsedShader p = parseWithRS("RenderState { AlphaTest greater 0.5 }\n");
        System.out.println("✓ alphaTest_lowercase_greater: func=" + rs(p).getAlpha().func());
        assertEquals(GL11.GL_GREATER, rs(p).getAlpha().func());
    }

    @Test
    public void alphaTest_null_cutoff_throws() {
        assertThrows("alphaTest_null_cutoff_throws",
                () -> parseWithRS("RenderState { AlphaTest GEQUAL null }\n"));
    }

    @Test
    public void alphaTest_wrongDomain_SRC_ALPHA_throws() {
        assertThrows("alphaTest_wrongDomain_SRC_ALPHA_throws",
                () -> parseWithRS("RenderState { AlphaTest SRC_ALPHA 0.5 }\n"));
    }

    @Test
    public void alphaTest_missingCutoff_throws() {
        assertThrows("alphaTest_missingCutoff_throws",
                () -> parseWithRS("RenderState { AlphaTest GREATER }\n"));
    }

    // ── Group 7 — ColorMask ───────────────────────────────────────────────────

    @Test
    public void colorMask_RGB() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask RGB }\n");
        List<?> masks = rs(p).getColorMasks();
        System.out.println("✓ colorMask_RGB: masks.size=" + masks.size());
        assertEquals(1, masks.size());
        CgColorMask m =
                (CgColorMask) masks.get(0);
        assertTrue(m.r()); assertTrue(m.g()); assertTrue(m.b()); assertFalse(m.a());
        assertEquals(-1, m.targetIndex());
    }

    @Test
    public void colorMask_RGBA() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask RGBA }\n");
        CgColorMask m =
                (CgColorMask) rs(p).getColorMasks().get(0);
        System.out.println("✓ colorMask_RGBA: r=" + m.r() + " g=" + m.g() + " b=" + m.b() + " a=" + m.a());
        assertTrue(m.r()); assertTrue(m.g()); assertTrue(m.b()); assertTrue(m.a());
        assertEquals(-1, m.targetIndex());
    }

    @Test
    public void colorMask_R_only() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask R }\n");
        CgColorMask m =
                (CgColorMask) rs(p).getColorMasks().get(0);
        System.out.println("✓ colorMask_R_only: r=" + m.r() + " g=" + m.g() + " b=" + m.b() + " a=" + m.a());
        assertTrue(m.r()); assertFalse(m.g()); assertFalse(m.b()); assertFalse(m.a());
    }

    @Test
    public void colorMask_zero_all_false() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask 0 }\n");
        CgColorMask m =
                (CgColorMask) rs(p).getColorMasks().get(0);
        System.out.println("✓ colorMask_zero_all_false: r=" + m.r() + " g=" + m.g());
        assertFalse(m.r()); assertFalse(m.g()); assertFalse(m.b()); assertFalse(m.a());
    }

    @Test
    public void colorMask_rgb_lowercase() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask rgb }\n");
        CgColorMask m =
                (CgColorMask) rs(p).getColorMasks().get(0);
        System.out.println("✓ colorMask_rgb_lowercase: r=" + m.r() + " g=" + m.g() + " b=" + m.b());
        assertTrue(m.r()); assertTrue(m.g()); assertTrue(m.b()); assertFalse(m.a());
    }

    @Test
    public void colorMask_RGB_mrtIndex_1() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask RGB 1 }\n");
        CgColorMask m =
                (CgColorMask) rs(p).getColorMasks().get(0);
        System.out.println("✓ colorMask_RGB_mrtIndex_1: targetIndex=" + m.targetIndex());
        assertEquals(1, m.targetIndex());
        assertTrue(m.r()); assertTrue(m.g()); assertTrue(m.b()); assertFalse(m.a());
    }

    @Test
    public void colorMask_RGBA_mrtIndex_0() {
        CgParsedShader p = parseWithRS("RenderState { ColorMask RGBA 0 }\n");
        CgColorMask m =
                (CgColorMask) rs(p).getColorMasks().get(0);
        System.out.println("✓ colorMask_RGBA_mrtIndex_0: targetIndex=" + m.targetIndex());
        assertEquals(0, m.targetIndex());
    }

    @Test
    public void colorMask_XYZ_throws() {
        assertThrows("colorMask_XYZ_throws",
                () -> parseWithRS("RenderState { ColorMask XYZ }\n"));
    }

    @Test
    public void colorMask_null_throws() {
        assertThrows("colorMask_null_throws",
                () -> parseWithRS("RenderState { ColorMask null }\n"));
    }

    // ── Group 8 — Stencil block ───────────────────────────────────────────────

    private static final String FULL_STENCIL_BLOCK =
            "RenderState {\n" +
            "  Stencil {\n" +
            "    Ref 1\n" +
            "    Comp ALWAYS\n" +
            "    Pass REPLACE\n" +
            "    Fail KEEP\n" +
            "    ZFail KEEP\n" +
            "    ReadMask 255\n" +
            "    WriteMask 255\n" +
            "  }\n" +
            "}\n";

    @Test
    public void stencil_fullBlock() {
        CgParsedShader p = parseWithRS(FULL_STENCIL_BLOCK);
        CgStencilState ss = rs(p).getStencil();
        System.out.println("✓ stencil_fullBlock: enabled=" + ss.enabled() + " ref=" + ss.ref()
                + " comp=" + ss.compFunc() + " pass=" + ss.passOp());
        assertTrue(ss.enabled());
        assertEquals(1, ss.ref());
        assertEquals(GL11.GL_ALWAYS, ss.compFunc());
        assertEquals(GL11.GL_REPLACE, ss.passOp());
        assertEquals(GL11.GL_KEEP, ss.failOp());
        assertEquals(GL11.GL_KEEP, ss.zfailOp());
        assertEquals(255, ss.readMask());
        assertEquals(255, ss.writeMask());
    }

    @Test
    public void stencil_partial_defaults() {
        CgParsedShader p = parseWithRS("RenderState {\n  Stencil {\n    Ref 0\n    Comp EQUAL\n    Pass KEEP\n  }\n}\n");
        CgStencilState ss = rs(p).getStencil();
        System.out.println("✓ stencil_partial_defaults: enabled=" + ss.enabled() + " ref=" + ss.ref() + " comp=" + ss.compFunc());
        assertTrue(ss.enabled());
        assertEquals(0, ss.ref());
        assertEquals(GL11.GL_EQUAL, ss.compFunc());
        assertEquals(GL11.GL_KEEP, ss.passOp());
        // defaults for unspecified ops
        assertEquals(GL11.GL_KEEP, ss.failOp());
        assertEquals(GL11.GL_KEEP, ss.zfailOp());
    }

    @Test
    public void stencil_caseInsensitive_always() {
        CgParsedShader p = parseWithRS("RenderState { Stencil { Comp always } }\n");
        System.out.println("✓ stencil_caseInsensitive_always: comp=" + rs(p).getStencil().compFunc());
        assertEquals(GL11.GL_ALWAYS, rs(p).getStencil().compFunc());
    }

    @Test
    public void stencil_invalidComp_throws() {
        assertThrows("stencil_invalidComp_throws",
                () -> parseWithRS("RenderState { Stencil { Comp MEDIUM } }\n"));
    }

    @Test
    public void stencil_INCR_SAT() {
        CgParsedShader p = parseWithRS("RenderState { Stencil { Pass INCR_SAT } }\n");
        System.out.println("✓ stencil_INCR_SAT: passOp=" + rs(p).getStencil().passOp());
        assertEquals(GL11.GL_INCR, rs(p).getStencil().passOp());
    }

    @Test
    public void stencil_INCR_WRAP() {
        CgParsedShader p = parseWithRS("RenderState { Stencil { Pass INCR_WRAP } }\n");
        System.out.println("✓ stencil_INCR_WRAP: passOp=" + rs(p).getStencil().passOp());
        assertEquals(GL14.GL_INCR_WRAP, rs(p).getStencil().passOp());
    }

    @Test
    public void stencil_unknownKey_throws() {
        assertThrows("stencil_unknownKey_throws",
                () -> parseWithRS("RenderState { Stencil { UnknownKey 1 } }\n"));
    }

     @Test
     public void stencil_absent_disabledByDefault() {
         CgParsedShader p = parseWithRS("RenderState { DepthTest LESS }\n");
         CgStencilState ss = rs(p).getStencil();
         System.out.println("✓ stencil_absent_disabledByDefault: stencil=" + ss);
         assertNull("Stencil slot should be null when not declared in RenderState block", ss);
     }

    // ── Group 9 — Queue keyword ───────────────────────────────────────────────

    private static String shaderWithQueue(String queueLine) {
        return "#type spatial\n" + queueLine + "\n" +
                "Pass {\n" +
                "    Tags { \"LightMode\" = \"Forward\" }\n" +
                "    struct v2f { vec2 uv; };\n" +
                "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
                "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
                "}\n";
    }

    @Test
    public void queue_Geometry() {
        CgParsedShader p = parseFull(shaderWithQueue("Queue = \"Geometry\""));
        System.out.println("✓ queue_Geometry: renderQueue=" + p.renderQueue());
        assertEquals(CgRenderQueue.GEOMETRY, p.renderQueue());
    }

    @Test
    public void queue_Transparent() {
        CgParsedShader p = parseFull(shaderWithQueue("Queue = \"Transparent\""));
        System.out.println("✓ queue_Transparent: renderQueue=" + p.renderQueue());
        assertEquals(CgRenderQueue.TRANSPARENT, p.renderQueue());
    }

    @Test
    public void queue_AlphaTest() {
        CgParsedShader p = parseFull(shaderWithQueue("Queue = \"AlphaTest\""));
        System.out.println("✓ queue_AlphaTest: renderQueue=" + p.renderQueue());
        assertEquals(CgRenderQueue.ALPHA_TEST, p.renderQueue());
    }

    @Test
    public void queue_Background() {
        CgParsedShader p = parseFull(shaderWithQueue("Queue = \"Background\""));
        System.out.println("✓ queue_Background: renderQueue=" + p.renderQueue());
        assertEquals(CgRenderQueue.BACKGROUND, p.renderQueue());
    }

    @Test
    public void queue_numeric_2500() {
        CgParsedShader p = parseFull(shaderWithQueue("Queue = \"2500\""));
        System.out.println("✓ queue_numeric_2500: renderQueue=" + p.renderQueue());
        assertEquals(2500, p.renderQueue());
    }

    @Test
    public void queue_caseInsensitive_geometry() {
        CgParsedShader p = parseFull(shaderWithQueue("Queue = \"geometry\""));
        System.out.println("✓ queue_caseInsensitive_geometry: renderQueue=" + p.renderQueue());
        assertEquals(CgRenderQueue.GEOMETRY, p.renderQueue());
    }

    @Test
    public void queue_oldSyntax_RenderQueue_throws() {
        assertThrows("queue_oldSyntax_RenderQueue_throws",
                () -> parseFull(shaderWithQueue("RenderQueue Transparent")));
    }

    @Test
    public void queue_unquoted_throws() {
        assertThrows("queue_unquoted_throws",
                () -> parseFull(shaderWithQueue("Queue = Transparent")));
    }

    @Test
    public void queue_invalidName_throws() {
        assertThrows("queue_invalidName_throws",
                () -> parseFull(shaderWithQueue("Queue = \"InvalidQueueName\"")));
    }

    @Test
    public void queue_absent_defaultsToGeometry() {
        CgParsedShader p = parseWithRS("");
        System.out.println("✓ queue_absent_defaultsToGeometry: renderQueue=" + p.renderQueue());
        assertEquals(CgRenderQueue.GEOMETRY, p.renderQueue());
    }

    // ── Group 10 — Property types (new types only) ────────────────────────────

    private static String shaderWithProp(String propLine) {
        return "#type spatial\n" +
               "Properties {\n" + propLine + "\n}\n" +
               "Pass {\n" +
               "    Tags { \"LightMode\" = \"Forward\" }\n" +
               "    struct v2f { vec2 uv; };\n" +
               "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n" +
               "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n" +
               "}\n";
    }

    @Test
    public void property_int_type() {
        CgParsedShader p = parseFull(shaderWithProp("_N (\"Count\", int) = 0"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_int_type: name=" + prop.getName() + " type=" + prop.getType());
        assertEquals("_N", prop.getName());
        assertEquals(CgMaterialProperty.Type.INT, prop.getType());
    }

    @Test
    public void property_color_type() {
        CgParsedShader p = parseFull(shaderWithProp("_C (\"Color\", color) = (1,0,0,1)"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_color_type: type=" + prop.getType() + " glslName=" + prop.getGlslType());
        assertEquals(CgMaterialProperty.Type.COLOR, prop.getType());
        assertEquals("vec4", prop.getGlslType());
    }

    @Test
    public void property_Range_type() {
        CgParsedShader p = parseFull(shaderWithProp("_Speed (\"Speed\", Range(0, 10)) = 5.0"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_Range_type: type=" + prop.getType()
                + " rangeMin=" + prop.getRangeMin() + " rangeMax=" + prop.getRangeMax());
        assertEquals(CgMaterialProperty.Type.RANGE, prop.getType());
        assertEquals(0.0f, prop.getRangeMin(), 0.001f);
        assertEquals(10.0f, prop.getRangeMax(), 0.001f);
    }

    @Test
    public void property_sampler2DArray() {
        CgParsedShader p = parseFull(shaderWithProp("_TexArray (\"Tex Array\", sampler2DArray) = \"white\""));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_sampler2DArray: type=" + prop.getType());
        assertEquals(CgMaterialProperty.Type.SAMPLER2D_ARRAY, prop.getType());
    }

    @Test
    public void property_sampler3D() {
        CgParsedShader p = parseFull(shaderWithProp("_Vol (\"Volume\", sampler3D) = \"black\""));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_sampler3D: type=" + prop.getType());
        assertEquals(CgMaterialProperty.Type.SAMPLER3D, prop.getType());
    }

    @Test
    public void property_samplerCube() {
        CgParsedShader p = parseFull(shaderWithProp("_Sky (\"Sky\", samplerCube) = \"white\""));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_samplerCube: type=" + prop.getType());
        assertEquals(CgMaterialProperty.Type.SAMPLER_CUBE, prop.getType());
    }

    @Test
    public void property_sampler2D_quotedDefault() {
        CgParsedShader p = parseFull(shaderWithProp("_MainTex (\"Main Texture\", sampler2D) = \"white\""));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_sampler2D_quotedDefault: name=" + prop.getName()
                + " displayName=" + prop.getDisplayName());
        assertEquals("_MainTex", prop.getName());
        assertEquals("Main Texture", prop.getDisplayName());
    }

    @Test
    public void property_displayName_newStyle() {
        CgParsedShader p = parseFull(shaderWithProp("_Alpha (\"Opacity\", float) = 1.0"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_displayName_newStyle: name=" + prop.getName()
                + " displayName=" + prop.getDisplayName());
        assertEquals("_Alpha", prop.getName());
        assertEquals("Opacity", prop.getDisplayName());
    }

    @Test
    public void property_displayName_oldStyle_fallsBackToName() {
        CgParsedShader p = parseFull(shaderWithProp("_Speed : float = 1.0"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_displayName_oldStyle_fallsBackToName: displayName=" + prop.getDisplayName());
        assertEquals("_Speed", prop.getDisplayName());
    }

    @Test
    public void property_boolean_true_default() {
        CgParsedShader p = parseFull(shaderWithProp("_Flag (\"Enable Effect\", boolean) = true"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_boolean_true: displayName=" + prop.getDisplayName()
                + " type=" + prop.getType() + " intValue=" + prop.getIntValue());
        assertEquals("Enable Effect", prop.getDisplayName());
        assertEquals(CgMaterialProperty.Type.BOOLEAN, prop.getType());
        assertEquals("bool", prop.getType().getGlslName());
        assertEquals(1, prop.getIntValue());
    }

    @Test
    public void property_boolean_false_default() {
        CgParsedShader p = parseFull(shaderWithProp("_Flag (\"Enable Effect\", boolean) = false"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_boolean_false: intValue=" + prop.getIntValue());
        assertEquals(CgMaterialProperty.Type.BOOLEAN, prop.getType());
        assertEquals(0, prop.getIntValue());
    }

    @Test
    public void property_boolean_numeric_1() {
        CgParsedShader p = parseFull(shaderWithProp("_Flag (\"Enable Effect\", boolean) = 1"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_boolean_numeric_1: intValue=" + prop.getIntValue());
        assertEquals(1, prop.getIntValue());
    }

    @Test
    public void property_boolean_numeric_0() {
        CgParsedShader p = parseFull(shaderWithProp("_Flag (\"Enable Effect\", boolean) = 0"));
        CgMaterialProperty prop = p.properties().get(0);
        System.out.println("✓ property_boolean_numeric_0: intValue=" + prop.getIntValue());
        assertEquals(0, prop.getIntValue());
    }

    @Test
    public void property_sampler2D_unquotedDefault_throws() {
        assertThrows("property_sampler2D_unquotedDefault_throws",
                () -> parseFull(shaderWithProp("_MainTex (\"Main Texture\", sampler2D) = white")));
    }

    @Test
    public void property_invalidType_mat4_throws() {
        assertThrows("property_invalidType_mat4_throws",
                () -> parseFull(shaderWithProp("_Bad (\"Bad\", mat4) = (1,0,0,0)")));
    }

    @Test
    public void property_missingDisplayName_throws() {
        assertThrows("property_missingDisplayName_throws",
                () -> parseFull(shaderWithProp("_Name (mat4) = x")));
    }

    // ── Group 11 — Absent blocks / defaults ───────────────────────────────────

    @Test
    public void absent_blocks_allDefaults() {
        CgParsedShader p = parseWithRS("");
        System.out.println("✓ absent_blocks_allDefaults: renderState=DEFAULT renderQueue="
                + p.renderQueue());
        assertEquals(CgRenderState.DEFAULT.getBlend().enabled(), rs(p).getBlend().enabled());
        assertEquals(CgRenderQueue.GEOMETRY, p.renderQueue());
        assertFalse(rs(p).getAlpha().enabled());
        assertTrue(rs(p).getColorMasks().isEmpty());
    }

    @Test
    public void renderState_emptyBlock_allDefaults() {
        CgParsedShader p = parseWithRS("RenderState { }\n");
        System.out.println("✓ renderState_emptyBlock_allDefaults: blend=" + rs(p).getBlend() + " alpha=" + rs(p).getAlpha());
        assertNull("Empty RenderState block should leave blend slot null", rs(p).getBlend());
        assertNull("Empty RenderState block should leave alpha slot null", rs(p).getAlpha());
    }

    @Test
    public void emptySource_throws() {
        assertThrows("emptySource_throws",
                () -> CgShaderParser.parse("", "test"));
    }

    @Test
    public void nullSource_throws() {
        assertThrows("nullSource_throws",
                () -> CgShaderParser.parse(null, "test"));
    }

    // ── Group 12 — Combined / edge cases ─────────────────────────────────────

    @Test
    public void combined_allKeywords() {
        String rs =
                "RenderState {\n" +
                "  DepthTest LESS\n" +
                "  DepthWrite OFF\n" +
                "  Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA\n" +
                "  BlendEquation ADD\n" +
                "  Cull FRONT\n" +
                "  AlphaTest GREATER 0.5\n" +
                "  ColorMask RGB\n" +
                "  Stencil { Ref 1 Comp ALWAYS Pass REPLACE }\n" +
                "}\n";
        CgParsedShader p = parseWithRS(rs);
        CgRenderState rs2 = rs(p);
        System.out.println("✓ combined_allKeywords: depth.func=" + rs2.getDepth().compareFunc()
                + " blend.enabled=" + rs2.getBlend().enabled()
                + " cull.face=" + rs2.getCull().face()
                + " alpha.enabled=" + rs2.getAlpha().enabled()
                + " stencil.enabled=" + rs2.getStencil().enabled()
                + " masks=" + rs2.getColorMasks().size());
        assertEquals(GL11.GL_LESS, rs2.getDepth().compareFunc());
        assertFalse(rs2.getDepth().write());
        assertTrue(rs2.getBlend().enabled());
        assertEquals(GL11.GL_FRONT, rs2.getCull().face());
        assertTrue(rs2.getAlpha().enabled());
        assertEquals(GL11.GL_GREATER, rs2.getAlpha().func());
        assertEquals(0.5f, rs2.getAlpha().cutoff(), 0.001f);
        assertFalse(rs2.getColorMasks().isEmpty());
        assertTrue(rs2.getStencil().enabled());
    }

    @Test
    public void combined_sameLine_multipleKeywords() {
        CgParsedShader p = parseWithRS("RenderState { DepthTest LESS DepthWrite OFF Cull FRONT }\n");
        System.out.println("✓ combined_sameLine: depth.func=" + rs(p).getDepth().compareFunc()
                + " write=" + rs(p).getDepth().write()
                + " cull.face=" + rs(p).getCull().face());
        assertEquals(GL11.GL_LESS, rs(p).getDepth().compareFunc());
        assertFalse(rs(p).getDepth().write());
        assertEquals(GL11.GL_FRONT, rs(p).getCull().face());
    }

    @Test
    public void alphaTest_largeCutoff_parses() {
        CgParsedShader p = parseWithRS("RenderState { AlphaTest GREATER 999.9 }\n");
        System.out.println("✓ alphaTest_largeCutoff_parses: cutoff=" + rs(p).getAlpha().cutoff());
        assertEquals(999.9f, rs(p).getAlpha().cutoff(), 0.1f);
    }

    @Test
    public void renderState_withComments_commentsIgnored() {
        String rs = "RenderState {\n" +
                    "  // This is a comment\n" +
                    "  DepthTest LESS\n" +
                    "  // Another comment\n" +
                    "}\n";
        CgParsedShader p = parseWithRS(rs);
        System.out.println("✓ renderState_withComments_commentsIgnored: depth.func=" + rs(p).getDepth().compareFunc());
        assertEquals(GL11.GL_LESS, rs(p).getDepth().compareFunc());
    }
}
