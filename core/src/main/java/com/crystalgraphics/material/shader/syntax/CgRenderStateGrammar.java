package com.crystalgraphics.material.shader.syntax;

import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.platform.gl.driver.GLC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code RenderState { }}: how a {@code #domain global} pass or a ui primitive draws. A domain building its passes'
 * states writes the same entries through {@link State}, so an authored state and a generated one mean the same.
 *
 * <pre>{@code
 * RenderState {
 *     Blend ONE ONE_MINUS_SRC_ALPHA             // src dst [, srcAlpha dstAlpha]; OFF
 *     BlendEquation REV_SUB                     // ADD SUB REV_SUB MIN MAX
 *     DepthTest LEQUAL                          // LESS LEQUAL EQUAL GEQUAL GREATER NOT_EQUAL ALWAYS NEVER
 *     DepthWrite OFF
 *     Cull OFF                                  // BACK FRONT OFF
 *     ColorMask RGB                             // any of RGBA, or 0; a target index after
 *     Stencil { Ref 1 Comp ALWAYS Pass REPLACE }
 * }
 * }</pre>
 *
 * <pre>{@code
 * CgRenderState s = CgRenderStateGrammar.state().blend(GLC.GL_ONE, GLC.GL_ONE).depthTest(GLC.GL_LEQUAL)
 *         .depthWrite(false).cull(CgCullState.BACK).build();
 * }</pre>
 *
 * <ul>
 *   <li>A slot no entry names stays null ("not declared"): the pipeline's own decides it.</li>
 *   <li>{@code DepthTest} and {@code DepthWrite} make one depth state, testing; {@code Blend} and
 *       {@code BlendEquation} one blend state. {@code Blend a b} applies {@code a b} to alpha too.</li>
 *   <li>Names and values are case-insensitive. Unity's {@code ZTest}, {@code ZWrite}, {@code BlendOp} and
 *       {@code AlphaTest} fail naming their replacement.</li>
 * </ul>
 */
public final class CgRenderStateGrammar {

    private static final Map<String, Integer> COMPARE = table("LESS", GLC.GL_LESS, "LEQUAL", GLC.GL_LEQUAL, "EQUAL",
            GLC.GL_EQUAL, "GEQUAL", GLC.GL_GEQUAL, "GREATER", GLC.GL_GREATER, "NOT_EQUAL", GLC.GL_NOTEQUAL, "ALWAYS",
            GLC.GL_ALWAYS, "NEVER", GLC.GL_NEVER);
    private static final Map<String, Integer> FACTORS = table("ONE", GLC.GL_ONE, "ZERO", GLC.GL_ZERO, "SRC_COLOR",
            GLC.GL_SRC_COLOR, "SRC_ALPHA", GLC.GL_SRC_ALPHA, "DST_COLOR", GLC.GL_DST_COLOR, "DST_ALPHA", GLC.GL_DST_ALPHA,
            "ONE_MINUS_SRC_COLOR", GLC.GL_ONE_MINUS_SRC_COLOR, "ONE_MINUS_SRC_ALPHA", GLC.GL_ONE_MINUS_SRC_ALPHA,
            "ONE_MINUS_DST_COLOR", GLC.GL_ONE_MINUS_DST_COLOR, "ONE_MINUS_DST_ALPHA", GLC.GL_ONE_MINUS_DST_ALPHA,
            "SRC_ALPHA_SATURATE", GLC.GL_SRC_ALPHA_SATURATE);
    private static final Map<String, Integer> EQUATIONS = table("ADD", GLC.GL_FUNC_ADD, "SUB", GLC.GL_FUNC_SUBTRACT,
            "REV_SUB", GLC.GL_FUNC_REVERSE_SUBTRACT, "MIN", GLC.GL_MIN, "MAX", GLC.GL_MAX);
    private static final Map<String, CgCullState> CULL = new LinkedHashMap<>();
    private static final Map<String, Integer> STENCIL_OPS = table("KEEP", GLC.GL_KEEP, "ZERO", GLC.GL_ZERO, "REPLACE",
            GLC.GL_REPLACE, "INCR_SAT", GLC.GL_INCR, "DECR_SAT", GLC.GL_DECR, "INVERT", GLC.GL_INVERT, "INCR_WRAP",
            GLC.GL_INCR_WRAP, "DECR_WRAP", GLC.GL_DECR_WRAP);
    private static final String KEYS = "DepthTest, DepthWrite, Blend, BlendEquation, Cull, AlphaTest, ColorMask, Stencil";

    static {
        CULL.put("BACK", CgCullState.BACK);
        CULL.put("FRONT", CgCullState.FRONT);
        CULL.put("OFF", CgCullState.NONE);
    }

    private CgRenderStateGrammar() {}

    /** An empty state to write entries into. */
    public static State state() {
        return new State();
    }

    /** Tokens {@code from} to {@code to} (exclusive), a block's body, as a state. */
    public static CgRenderState read(CgShaderTokens t, int from, int to) {
        List<Words.Word> w = Words.of(t, from, to);
        State s = new State();
        int i = 0;
        while (i < w.size()) {
            Words.Word key = w.get(i++);
            String k = key.text().toLowerCase(Locale.ROOT);
            switch (k) {
                case "ztest" -> throw t.error(key.first(), "Unknown RenderState key 'ZTest'. Use 'DepthTest' instead. Valid: " + KEYS);
                case "zwrite" -> throw t.error(key.first(), "Unknown RenderState key 'ZWrite'. Use 'DepthWrite' instead. Valid: " + KEYS);
                case "blendop" -> throw t.error(key.first(), "Unknown RenderState key 'BlendOp'. Use 'BlendEquation' instead. Valid: " + KEYS);
                case "alphatest" -> throw t.error(key.first(), "AlphaTest is not a render state: discard in the fragment stage"
                        + " (if (color.a <= cutoff) discard;)");
                case "depthtest" -> s.depthTest(value(t, w, i++, COMPARE, "DepthTest"));
                case "depthwrite" -> s.depthWrite(onOff(t, w, i++, "DepthWrite"));
                case "blend" -> {
                    Words.Word first = need(t, w, i++, "Blend");
                    if (first.text().equalsIgnoreCase("off")) {
                        s.blendOff();
                    } else {
                        int src = value(t, w, i - 1, FACTORS, "Blend src factor");
                        int dst = value(t, w, i++, FACTORS, "Blend dst factor");
                        if (i < w.size() && w.get(i).is(",")) {
                            int sa = value(t, w, i + 1, FACTORS, "Blend srcAlpha factor");
                            int da = value(t, w, i + 2, FACTORS, "Blend dstAlpha factor");
                            i += 3;
                            s.blend(src, dst, sa, da);
                        } else {
                            s.blend(src, dst);
                        }
                    }
                }
                case "blendequation" -> {
                    // An optional render target index before it: one equation serves every target
                    if (integer(need(t, w, i, "BlendEquation").text()) != null) i++;
                    s.equation(value(t, w, i++, EQUATIONS, "BlendEquation"));
                }
                case "cull" -> {
                    Words.Word v = need(t, w, i++, "Cull");
                    CgCullState cull = CULL.get(v.text().toUpperCase(Locale.ROOT));
                    if (cull == null) throw t.error(v.first(), "'" + v.text() + "' is no Cull: " + CULL.keySet());
                    s.cull(cull);
                }
                case "colormask" -> {
                    Words.Word v = need(t, w, i++, "ColorMask");
                    boolean[] m = mask(t, v);
                    Integer target = i < w.size() ? integer(w.get(i).text()) : null;
                    if (target != null) i++;
                    s.colorMask(new CgColorMask(m[0], m[1], m[2], m[3], target == null ? -1 : target));
                }
                case "stencil" -> i = stencil(t, w, i, s);
                default -> throw t.error(key.first(), "Unknown RenderState key '" + key.text() + "'. Valid: " + KEYS);
            }
        }
        return s.build();
    }

    /** The {@code Stencil { }} sub-block from word {@code i} (its {@code {}); the word after its {@code }}. */
    private static int stencil(CgShaderTokens t, List<Words.Word> w, int i, State s) {
        Words.Word open = need(t, w, i++, "Stencil '{'");
        if (!open.is("{")) throw t.error(open.first(), "Expected '{' after 'Stencil', got: '" + open.text() + "'");
        int ref = 0, read = 0xFF, write = 0xFF, comp = GLC.GL_ALWAYS, pass = GLC.GL_KEEP, fail = GLC.GL_KEEP, zfail = GLC.GL_KEEP;
        while (i < w.size()) {
            Words.Word key = w.get(i++);
            if (key.is("}")) {
                s.stencil(new CgStencilState(true, ref, read, write, comp, pass, fail, zfail));
                return i;
            }
            if (i >= w.size() || w.get(i).is("}")) throw t.error(key.first(), "Stencil key '" + key.text() + "' has no value");
            switch (key.text().toLowerCase(Locale.ROOT)) {
                case "ref" -> ref = number(t, w.get(i++), "Stencil Ref");
                case "comp" -> comp = value(t, w, i++, COMPARE, "Stencil Comp");
                case "pass" -> pass = value(t, w, i++, STENCIL_OPS, "Stencil Pass");
                case "fail" -> fail = value(t, w, i++, STENCIL_OPS, "Stencil Fail");
                case "zfail" -> zfail = value(t, w, i++, STENCIL_OPS, "Stencil ZFail");
                case "readmask" -> read = number(t, w.get(i++), "Stencil ReadMask");
                case "writemask" -> write = number(t, w.get(i++), "Stencil WriteMask");
                default -> throw t.error(key.first(), "Unknown Stencil key '" + key.text()
                        + "'. Valid: Ref, Comp, Pass, Fail, ZFail, ReadMask, WriteMask");
            }
        }
        throw t.error(open.first(), "Stencil block missing closing '}'");
    }

    private static Words.Word need(CgShaderTokens t, List<Words.Word> w, int i, String what) {
        if (i >= w.size()) {
            throw t.error(w.get(w.size() - 1).first(), "Expected value for '" + what + "' but reached end of RenderState block");
        }
        return w.get(i);
    }

    private static int value(CgShaderTokens t, List<Words.Word> w, int i, Map<String, Integer> table, String what) {
        Words.Word v = need(t, w, i, what);
        Integer out = table.get(v.text().toUpperCase(Locale.ROOT));
        if (out == null) throw t.error(v.first(), "Invalid " + what + " value '" + v.text() + "'. Valid: " + table.keySet());
        return out;
    }

    private static boolean onOff(CgShaderTokens t, List<Words.Word> w, int i, String what) {
        Words.Word v = need(t, w, i, what);
        if (v.text().equalsIgnoreCase("on")) return true;
        if (v.text().equalsIgnoreCase("off")) return false;
        throw t.error(v.first(), "Invalid " + what + " value '" + v.text() + "'. Valid: ON, OFF");
    }

    private static int number(CgShaderTokens t, Words.Word v, String what) {
        Integer n = integer(v.text());
        if (n == null) throw t.error(v.first(), "Expected integer for '" + what + "', got: '" + v.text() + "'");
        return n;
    }

    private static Integer integer(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean[] mask(CgShaderTokens t, Words.Word v) {
        if (v.is("0")) return new boolean[4];
        boolean[] m = new boolean[4];
        for (char c : v.text().toUpperCase(Locale.ROOT).toCharArray()) {
            int at = "RGBA".indexOf(c);
            if (at < 0) {
                throw t.error(v.first(), "Invalid ColorMask channel '" + c + "'. Use any combination of R, G, B, A or '0'");
            }
            m[at] = true;
        }
        return m;
    }

    private static Map<String, Integer> table(Object... pairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    /**
     * Render-state entries accumulated as a {@code RenderState { }} block writes them, a slot no entry names left
     * null.
     */
    public static final class State {
        private boolean depthSeen, depthWrite = true, blendSeen, blendOn;
        private int depthFunc = GLC.GL_LEQUAL;
        private int srcRgb = GLC.GL_ONE, dstRgb = GLC.GL_ZERO, srcAlpha = GLC.GL_ONE, dstAlpha = GLC.GL_ZERO;
        private int equationRgb = GLC.GL_FUNC_ADD, equationAlpha = GLC.GL_FUNC_ADD;
        private final CgRenderState.Builder b = CgRenderState.builder();

        private State() {}

        /** {@code DepthTest func}: testing, by {@code func}. */
        public State depthTest(int func) {
            depthSeen = true;
            depthFunc = func;
            return this;
        }

        /** {@code DepthWrite ON|OFF}. */
        public State depthWrite(boolean on) {
            depthSeen = true;
            depthWrite = on;
            return this;
        }

        /** {@code Blend OFF}. */
        public State blendOff() {
            blendSeen = true;
            blendOn = false;
            srcRgb = srcAlpha = GLC.GL_ONE;
            dstRgb = dstAlpha = GLC.GL_ZERO;
            return this;
        }

        /** {@code Blend src dst}, alpha alike. */
        public State blend(int src, int dst) {
            return blend(src, dst, src, dst);
        }

        /** {@code Blend src dst, srcAlpha dstAlpha}. */
        public State blend(int src, int dst, int srcA, int dstA) {
            blendSeen = blendOn = true;
            srcRgb = src;
            dstRgb = dst;
            srcAlpha = srcA;
            dstAlpha = dstA;
            return this;
        }

        /** {@code BlendEquation eq}, colour and alpha. */
        public State equation(int eq) {
            blendSeen = true;
            equationRgb = equationAlpha = eq;
            return this;
        }

        public State cull(CgCullState cull) {
            b.cull(cull);
            return this;
        }

        public State colorMask(CgColorMask mask) {
            b.colorMask(mask);
            return this;
        }

        public State stencil(CgStencilState stencil) {
            b.stencil(stencil);
            return this;
        }

        public CgRenderState build() {
            if (depthSeen) b.depth(new CgDepthState(true, depthWrite, depthFunc));
            if (blendSeen) b.blend(new CgBlendState(blendOn, srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha));
            return b.build();
        }
    }
}
