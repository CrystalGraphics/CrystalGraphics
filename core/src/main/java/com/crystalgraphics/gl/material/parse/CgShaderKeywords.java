package com.crystalgraphics.gl.material.parse;


import com.crystalgraphics.platform.gl.CgGL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Package-private registry of all CrystalShader {@code .shader} keyword token sets and
 * keyword descriptors. Every value comparison in the parser delegates to a {@link TokenSet}
 * from this class — no ad-hoc {@code toLowerCase()} + string comparison elsewhere.
 */
public final class CgShaderKeywords {
    
    /** Depth / stencil compare functions. Also reused for alpha-test func. */
    static final TokenSet<Integer> COMPARE_FUNCS = TokenSet.<Integer>builder()
            .put("LESS",      CgGL.GL_LESS)
            .put("LEQUAL",    CgGL.GL_LEQUAL)
            .put("EQUAL",     CgGL.GL_EQUAL)
            .put("GEQUAL",    CgGL.GL_GEQUAL)
            .put("GREATER",   CgGL.GL_GREATER)
            .put("NOT_EQUAL", CgGL.GL_NOTEQUAL)
            .put("ALWAYS",    CgGL.GL_ALWAYS)
            .put("NEVER",     CgGL.GL_NEVER)
            .build();

    /** Blend source/destination factors. All use UPPER_SNAKE_CASE. */
    static final TokenSet<Integer> BLEND_FACTORS = TokenSet.<Integer>builder()
            .put("ONE",                   CgGL.GL_ONE)
            .put("ZERO",                  CgGL.GL_ZERO)
            .put("SRC_COLOR",             CgGL.GL_SRC_COLOR)
            .put("SRC_ALPHA",             CgGL.GL_SRC_ALPHA)
            .put("DST_COLOR",             CgGL.GL_DST_COLOR)
            .put("DST_ALPHA",             CgGL.GL_DST_ALPHA)
            .put("ONE_MINUS_SRC_COLOR",   CgGL.GL_ONE_MINUS_SRC_COLOR)
            .put("ONE_MINUS_SRC_ALPHA",   CgGL.GL_ONE_MINUS_SRC_ALPHA)
            .put("ONE_MINUS_DST_COLOR",   CgGL.GL_ONE_MINUS_DST_COLOR)
            .put("ONE_MINUS_DST_ALPHA",   CgGL.GL_ONE_MINUS_DST_ALPHA)
            .put("SRC_ALPHA_SATURATE",    CgGL.GL_SRC_ALPHA_SATURATE)
            .build();

    /** Blend equations. Renamed from BlendOp. */    static final TokenSet<Integer> BLEND_EQUATIONS = TokenSet.<Integer>builder()
            .put("ADD",     CgGL.GL_FUNC_ADD)
            .put("SUB",     CgGL.GL_FUNC_SUBTRACT)
            .put("REV_SUB", CgGL.GL_FUNC_REVERSE_SUBTRACT)
            .put("MIN",     CgGL.GL_MIN)
            .put("MAX",     CgGL.GL_MAX)
            .build();

    /**
     * Cull face modes. {@code OFF} maps to {@code 0} (sentinel for disabled).
     * A value of {@code 0} means culling disabled; any other value is a GL face constant.
     */
    static final TokenSet<Integer> CULL_MODES = TokenSet.<Integer>builder()
            .put("BACK",  CgGL.GL_BACK)
            .put("FRONT", CgGL.GL_FRONT)
            .put("OFF",   0)
            .build();

    /** ON/OFF boolean toggle used for DepthWrite and similar binary switches. */
    static final TokenSet<Boolean> ON_OFF = TokenSet.<Boolean>builder()
            .put("ON",  true)
            .put("OFF", false)
            .build();

    /** Stencil per-fragment operations. */
    static final TokenSet<Integer> STENCIL_OPS = TokenSet.<Integer>builder()
            .put("KEEP",      CgGL.GL_KEEP)
            .put("ZERO",      CgGL.GL_ZERO)
            .put("REPLACE",   CgGL.GL_REPLACE)
            .put("INCR_SAT",  CgGL.GL_INCR)
            .put("DECR_SAT",  CgGL.GL_DECR)
            .put("INVERT",    CgGL.GL_INVERT)
            .put("INCR_WRAP", CgGL.GL_INCR_WRAP)
            .put("DECR_WRAP", CgGL.GL_DECR_WRAP)
            .build();
    
    static final Keyword DEPTH_TEST      = new Keyword("DepthTest",      "LESS | LEQUAL | EQUAL | GEQUAL | GREATER | NOT_EQUAL | ALWAYS | NEVER");
    static final Keyword DEPTH_WRITE     = new Keyword("DepthWrite",     "ON | OFF");
    static final Keyword BLEND           = new Keyword("Blend",          "OFF | <srcFactor> <dstFactor> [, <srcAlphaFactor> <dstAlphaFactor>]");
    static final Keyword BLEND_EQUATION  = new Keyword("BlendEquation",  "[N] ADD | SUB | REV_SUB | MIN | MAX");
    static final Keyword CULL            = new Keyword("Cull",           "BACK | FRONT | OFF");
    static final Keyword ALPHA_TEST      = new Keyword("AlphaTest",      "<compareFunc> <cutoff>");
    static final Keyword COLOR_MASK      = new Keyword("ColorMask",      "<channels> [targetIndex]");
    static final Keyword STENCIL         = new Keyword("Stencil",        "{ Ref <n> Comp <func> Pass <op> ... }");

    static final Keyword STENCIL_REF    = new Keyword("Ref",      "<int>");
    static final Keyword STENCIL_COMP   = new Keyword("Comp",     COMPARE_FUNCS.validValues());
    static final Keyword STENCIL_PASS   = new Keyword("Pass",     STENCIL_OPS.validValues());
    static final Keyword STENCIL_FAIL   = new Keyword("Fail",     STENCIL_OPS.validValues());
    static final Keyword STENCIL_ZFAIL  = new Keyword("ZFail",    STENCIL_OPS.validValues());
    static final Keyword STENCIL_RMASK  = new Keyword("ReadMask", "<int 0-255>");
    static final Keyword STENCIL_WMASK  = new Keyword("WriteMask","<int 0-255>");

    private static final Map<String, Keyword> RENDER_STATE_INDEX;
    private static final Map<String, Keyword> STENCIL_INDEX;

    static {
        RENDER_STATE_INDEX = new LinkedHashMap<>();
        for (Keyword kw : Arrays.asList(
                DEPTH_TEST, DEPTH_WRITE, BLEND, BLEND_EQUATION, CULL, ALPHA_TEST, COLOR_MASK, STENCIL)) {
            RENDER_STATE_INDEX.put(kw.name().toLowerCase(Locale.ROOT), kw);
        }

        STENCIL_INDEX = new LinkedHashMap<>();
        for (Keyword kw : Arrays.asList(
                STENCIL_REF, STENCIL_COMP, STENCIL_PASS, STENCIL_FAIL,
                STENCIL_ZFAIL, STENCIL_RMASK, STENCIL_WMASK)) {
            STENCIL_INDEX.put(kw.name().toLowerCase(Locale.ROOT), kw);
        }
    }

    /** Returns the matching {@link Keyword}, or {@code null} if the name is unknown. */
    static Keyword renderStateKey(String name) {
        if (name == null) return null;
        return RENDER_STATE_INDEX.get(name.toLowerCase(Locale.ROOT));
    }

    /** Returns the matching stencil sub-block {@link Keyword}, or {@code null} if unknown. */
    static Keyword stencilKey(String name) {
        if (name == null) return null;
        return STENCIL_INDEX.get(name.toLowerCase(Locale.ROOT));
    }

    static String validRenderStateKeys() {
        StringBuilder sb = new StringBuilder();
        for (String key : RENDER_STATE_INDEX.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            // Emit PascalCase name for readability
            sb.append(RENDER_STATE_INDEX.get(key).name());
        }
        return sb.toString();
    }
        
    /** Describes a known render-state or stencil sub-key. */
    static final class Keyword {
        private final String name;
        private final String grammar;

        private Keyword(String name, String grammar) {
            this.name    = name;
            this.grammar = grammar;
        }

        String name()    { return name; }
        String grammar() { return grammar; }
    }
}
