package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.state.CgAlphaState;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code RenderState { }} block from a CrystalShader {@code .shader} source
 * string into a {@link CgRenderState} descriptor.
 * Zero GL calls — all GL constants come from LWJGL static final int fields.
 *
 * <p>Returns {@link CgRenderState#DEFAULT} when the block is absent.</p>
 */
final class CgRenderStateParser {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private CgRenderStateParser() {}

    /**
     * Parses the optional {@code RenderState { }} block.
     * Returns {@link CgRenderState#DEFAULT} when absent.
     */
    static CgRenderState parse(String source, String resourcePath) {
        int start = source.indexOf("RenderState {");
        if (start == -1) start = source.indexOf("RenderState{");
        if (start == -1) return CgRenderState.DEFAULT;

        int braceOpen  = source.indexOf('{', start);
        int braceClose = CgStructureParser.matchBrace(source, braceOpen);
        String body    = source.substring(braceOpen + 1, braceClose);

        // Tokenize: strip // comments, pad { } so they split from adjacent text
        String cleaned = CgStructureParser.stripLineComments(body).replace("{", " { ").replace("}", " } ");
        List<String> toks = new ArrayList<>();
        for (String t : cleaned.split("\\s+")) {
            if (!t.isEmpty()) toks.add(t);
        }

        CgRenderState.Builder builder = CgRenderState.builder();

        // Depth — DepthTest and DepthWrite are independent keywords contributing to one CgDepthState
        boolean depthTestKwSeen   = false;
        boolean depthWriteKwSeen  = false;
        boolean depthTestEnabled  = true;            // default: depth test on
        int     depthFunc         = GL11.GL_LEQUAL;  // default compare function
        boolean depthWriteEnabled = true;            // default: writes enabled

        // Blend — Blend sets src/dst; BlendEquation can override the equation independently
        boolean blendKwSeen  = false;
        boolean blendEnabled = false;
        int srcRgb   = GL11.GL_ONE,  dstRgb   = GL11.GL_ZERO;
        int srcAlpha = GL11.GL_ONE,  dstAlpha = GL11.GL_ZERO;
        int blendEqRgb   = GL14.GL_FUNC_ADD;
        int blendEqAlpha = GL14.GL_FUNC_ADD;
        boolean equationKwSeen = false;

        int i = 0;
        while (i < toks.size()) {
            String key = toks.get(i++);

            // ── Reject deprecated keyword names with targeted hints ────────
            String keyLower = key.toLowerCase(Locale.ROOT);
            if ("ztest".equals(keyLower)) {
                throw new CgShaderParseException("[" + resourcePath + "] Unknown RenderState key 'ZTest'. "
                        + "Use 'DepthTest' instead. Valid: " + CgShaderKeywords.validRenderStateKeys());
            }
            if ("zwrite".equals(keyLower)) {
                throw new CgShaderParseException("[" + resourcePath + "] Unknown RenderState key 'ZWrite'. "
                        + "Use 'DepthWrite' instead. Valid: " + CgShaderKeywords.validRenderStateKeys());
            }
            if ("blendop".equals(keyLower)) {
                throw new CgShaderParseException("[" + resourcePath + "] Unknown RenderState key 'BlendOp'. "
                        + "Use 'BlendEquation' instead. Valid: " + CgShaderKeywords.validRenderStateKeys());
            }

            CgShaderKeywords.Keyword kw = CgShaderKeywords.renderStateKey(key);
            if (kw == null) {
                throw new CgShaderParseException("[" + resourcePath + "] Unknown RenderState key '" + key + "'. "
                        + "Valid: " + CgShaderKeywords.validRenderStateKeys());
            }

            switch (kw.name()) {

                case "DepthTest": {
                    String val   = requireToken(toks, i++, "DepthTest", resourcePath);
                    depthFunc        = CgShaderKeywords.COMPARE_FUNCS.parse(val, "DepthTest");
                    depthTestEnabled = true;
                    depthTestKwSeen  = true;
                    break;
                }

                case "DepthWrite": {
                    String val        = requireToken(toks, i++, "DepthWrite", resourcePath);
                    depthWriteEnabled = CgShaderKeywords.ON_OFF.parse(val, "DepthWrite");
                    depthWriteKwSeen  = true;
                    break;
                }

                case "Blend": {
                    String firstTok = requireToken(toks, i++, "Blend", resourcePath);
                    if ("off".equals(firstTok.toLowerCase(Locale.ROOT))) {
                        // Blend OFF — disabled, reset to one/zero
                        blendEnabled = false;
                        srcRgb   = GL11.GL_ONE;  dstRgb   = GL11.GL_ZERO;
                        srcAlpha = GL11.GL_ONE;  dstAlpha = GL11.GL_ZERO;
                    } else {
                        // Blend srcFactor dstFactor
                        int src = CgShaderKeywords.BLEND_FACTORS.parse(firstTok, "Blend src factor");
                        String dstTok = requireToken(toks, i++, "Blend dst factor", resourcePath);
                        int dst = CgShaderKeywords.BLEND_FACTORS.parse(dstTok, "Blend dst factor");
                        blendEnabled = true;
                        srcRgb   = src;  dstRgb   = dst;
                        srcAlpha = src;  dstAlpha = dst;
                    }
                    blendKwSeen = true;
                    break;
                }

                case "BlendEquation": {
                    // Optional MRT index prefix: if next token is a plain integer, consume it
                    String nextTok = requireToken(toks, i++, "BlendEquation", resourcePath);
                    String eqTok;
                    try {
                        // If it parses as int, it's an MRT index; consume equation separately
                        int mrtIndex = Integer.parseInt(nextTok);
                        eqTok = requireToken(toks, i++, "BlendEquation equation", resourcePath);
                        LOGGER.debug("[{}] BlendEquation MRT index {} parsed (global equation applied)",
                                resourcePath, mrtIndex);
                    } catch (NumberFormatException e) {
                        // Not an int — this token IS the equation
                        eqTok = nextTok;
                    }
                    int eq   = CgShaderKeywords.BLEND_EQUATIONS.parse(eqTok, "BlendEquation");
                    blendEqRgb   = eq;
                    blendEqAlpha = eq;
                    equationKwSeen = true;
                    break;
                }

                case "Cull": {
                    String val = requireToken(toks, i++, "Cull", resourcePath);
                    int face   = CgShaderKeywords.CULL_MODES.parse(val, "Cull");
                    builder.cull(face == 0        ? CgCullState.NONE
                               : face == GL11.GL_BACK ? CgCullState.BACK
                               : CgCullState.FRONT);
                    break;
                }

                case "AlphaTest": {
                    String funcTok = requireToken(toks, i++, "AlphaTest func", resourcePath);
                    int func       = CgShaderKeywords.COMPARE_FUNCS.parse(funcTok, "AlphaTest");
                    String cutoffTok = requireToken(toks, i++, "AlphaTest cutoff", resourcePath);
                    float cutoff;
                    try {
                        cutoff = Float.parseFloat(cutoffTok);
                    } catch (NumberFormatException e) {
                        throw new CgShaderParseException(
                                "[" + resourcePath + "] AlphaTest cutoff must be a float literal, got: '"
                                + cutoffTok + "'");
                    }
                    builder.alpha(new CgAlphaState(true, func, cutoff));
                    break;
                }

                case "ColorMask": {
                    String maskTok = requireToken(toks, i++, "ColorMask", resourcePath);
                    boolean[] ch   = parseColorMask(maskTok, resourcePath);
                    // Peek for optional MRT index
                    int targetIndex = -1;
                    if (i < toks.size()) {
                        try {
                            targetIndex = Integer.parseInt(toks.get(i));
                            i++; // consumed the index
                        } catch (NumberFormatException e) {
                            // No MRT index — applies to all targets
                        }
                    }
                    builder.colorMask(new CgColorMask(ch[0], ch[1], ch[2], ch[3], targetIndex));
                    break;
                }

                case "Stencil": {
                    // Expect opening '{'
                    String brace = requireToken(toks, i++, "Stencil '{'", resourcePath);
                    if (!"{".equals(brace)) {
                        throw new CgShaderParseException(
                                "[" + resourcePath + "] Expected '{' after 'Stencil', got: '" + brace + "'");
                    }
                    // parseStencilSubBlock reads tokens until matching '}' and returns updated cursor
                    int[] ss = parseStencilSubBlock(toks, i, resourcePath);
                    i = ss[0]; // updated cursor (past the closing '}')
                    builder.stencil(new CgStencilState(true,
                            ss[1],  // ref
                            ss[2],  // readMask
                            ss[3],  // writeMask
                            ss[4],  // compFunc
                            ss[5],  // passOp
                            ss[6],  // failOp
                            ss[7]   // zfailOp
                    ));
                    break;
                }

                default:
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] Unknown RenderState key '" + kw.name() + "'");
            }
        }

        // ── Assemble depth state from accumulated pieces ───────────────────
        if (depthTestKwSeen || depthWriteKwSeen) {
            builder.depth(new CgDepthState(depthTestEnabled, depthWriteEnabled, depthFunc));
        }

        // ── Assemble blend state with accumulated factors + equation ───────
        if (blendKwSeen || equationKwSeen) {
            builder.blend(new CgBlendState(blendEnabled,
                    srcRgb, dstRgb, srcAlpha, dstAlpha, blendEqRgb, blendEqAlpha));
        }

        return builder.build();
    }

    /**
     * Parses stencil sub-block tokens starting after the '{' token.
     * Reads until the matching '}' token.
     *
     * @return int[8] — [newCursor, ref, readMask, writeMask, compFunc, passOp, failOp, zfailOp]
     */
    private static int[] parseStencilSubBlock(List<String> toks, int start, String resourcePath) {
        int ref      = 0;
        int readMask = 0xFF;
        int writeMask= 0xFF;
        int compFunc = GL11.GL_ALWAYS;
        int passOp   = GL11.GL_KEEP;
        int failOp   = GL11.GL_KEEP;
        int zfailOp  = GL11.GL_KEEP;

        int i = start;
        while (i < toks.size()) {
            String tok = toks.get(i++);
            if ("}".equals(tok)) {
                // End of stencil sub-block; return cursor AFTER the '}'
                return new int[]{i, ref, readMask, writeMask, compFunc, passOp, failOp, zfailOp};
            }
            CgShaderKeywords.Keyword sk = CgShaderKeywords.stencilKey(tok);
            if (sk == null) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Unknown Stencil key '" + tok
                        + "'. Valid: Ref, Comp, Pass, Fail, ZFail, ReadMask, WriteMask");
            }
            // Consume value — peek to ensure we don't accidentally consume the closing '}'
            if (i >= toks.size() || "}".equals(toks.get(i))) {
                throw new CgShaderParseException(
                        "[" + resourcePath + "] Stencil key '" + sk.name() + "' has no value");
            }
            String val = toks.get(i++);

            switch (sk.name()) {
                case "Ref":      ref      = requireInt(val, "Stencil Ref",      resourcePath); break;
                case "Comp":     compFunc = CgShaderKeywords.COMPARE_FUNCS.parse(val, "Stencil Comp"); break;
                case "Pass":     passOp   = CgShaderKeywords.STENCIL_OPS.parse(val, "Stencil Pass"); break;
                case "Fail":     failOp   = CgShaderKeywords.STENCIL_OPS.parse(val, "Stencil Fail"); break;
                case "ZFail":    zfailOp  = CgShaderKeywords.STENCIL_OPS.parse(val, "Stencil ZFail"); break;
                case "ReadMask": readMask = requireInt(val, "Stencil ReadMask", resourcePath); break;
                case "WriteMask":writeMask= requireInt(val, "Stencil WriteMask",resourcePath); break;
                default:
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] Unknown Stencil key '" + sk.name() + "'");
            }
        }
        throw new CgShaderParseException("[" + resourcePath + "] Stencil block missing closing '}'");
    }

    /**
     * Parses a ColorMask channel string ({@code "RGB"}, {@code "RGBA"}, {@code "R"}, {@code "0"}).
     * Case-insensitive. Returns {@code boolean[4]} for [R, G, B, A].
     */
    private static boolean[] parseColorMask(String token, String resourcePath) {
        if (token == null) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Expected ColorMask channel string but got nothing");
        }
        if ("0".equals(token)) return new boolean[]{false, false, false, false};
        boolean[] m = new boolean[4];
        for (char c : token.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case 'R': m[0] = true; break;
                case 'G': m[1] = true; break;
                case 'B': m[2] = true; break;
                case 'A': m[3] = true; break;
                default:
                    throw new CgShaderParseException(
                            "[" + resourcePath + "] Invalid ColorMask channel '" + c
                            + "'. Use any combination of R, G, B, A or '0'");
            }
        }
        return m;
    }

    // ── Token utilities ───────────────────────────────────────────────────────

    private static String requireToken(List<String> toks, int idx, String context, String resourcePath) {
        if (idx >= toks.size()) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Expected value for '" + context
                    + "' but reached end of RenderState block");
        }
        return toks.get(idx);
    }

    private static int requireInt(String token, String context, String resourcePath) {
        if (token == null) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Expected integer for '" + context + "' but got nothing");
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new CgShaderParseException(
                    "[" + resourcePath + "] Expected integer for '" + context + "', got: '" + token + "'");
        }
    }
}
