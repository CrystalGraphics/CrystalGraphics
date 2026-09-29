package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;

/**
 * GL's fixed-pipeline state on the tracked backend: what the setters wrote, answered back to {@code glGet}, and
 * turned into the tracker's pipeline records only when it changed — so an unchanged draw reuses the records and
 * with them the pipeline.
 */
public final class TrackedRenderState {

    private static final int GL_LINE_SMOOTH = CgGL.GL_LINE_SMOOTH, GL_MULTISAMPLE = CgGL.GL_MULTISAMPLE;
    private static final int GL_FRAMEBUFFER_SRGB = 0x8DB9, GL_PROGRAM_POINT_SIZE = CgGL.GL_VERTEX_PROGRAM_POINT_SIZE;
    private static final int GL_COLOR_CLEAR_VALUE = 0x0C22, GL_DEPTH_CLEAR_VALUE = 0x0B73, GL_STENCIL_CLEAR_VALUE = 0x0B91;

    private final CgTracker tracker;
    private final TrackedGlErrors errors;

    public boolean blend, depthTest, cullFace, scissorTest, stencilTest, polygonOffsetFill, polygonOffsetLine, polygonOffsetPoint;
    public boolean alphaTest;
    public int srcRgb = CgGL.GL_ONE, dstRgb = CgGL.GL_ZERO, srcAlpha = CgGL.GL_ONE, dstAlpha = CgGL.GL_ZERO;
    public int equationRgb = CgGL.GL_FUNC_ADD, equationAlpha = CgGL.GL_FUNC_ADD;
    public boolean depthMask = true;
    public int depthFunc = CgGL.GL_LESS;
    public int cullMode = CgGL.GL_BACK, frontFace = CgGL.GL_CCW, polygonMode = CgGL.GL_FILL;
    public int colorMasks = 0xFFFFFFFF;
    public int stencilFunc = CgGL.GL_ALWAYS, stencilRef, stencilValueMask = -1, stencilWriteMask = -1;
    public int stencilFail = CgGL.GL_KEEP, stencilDepthFail = CgGL.GL_KEEP, stencilPass = CgGL.GL_KEEP;
    public float offsetFactor, offsetUnits, lineWidth = 1, pointSize = 1, alphaRef;
    public int alphaFunc = CgGL.GL_ALWAYS;
    public int viewportX, viewportY, viewportWidth, viewportHeight;
    public int scissorX, scissorY, scissorWidth, scissorHeight;
    public float clearR, clearG, clearB, clearA;
    public double clearDepth = 1;
    public int clearStencil;

    private boolean blendDirty = true, depthStencilDirty = true, rasterDirty = true;
    private boolean warnedFixedFunction, warnedLineWidth;

    public TrackedRenderState(CgTracker tracker, TrackedGlErrors errors, int width, int height) {
        this.tracker = tracker;
        this.errors = errors;
        viewportWidth = scissorWidth = width;
        viewportHeight = scissorHeight = height;
    }

    // ── setters ────────────────────────────────────────────────────────────────

    public void enable(int cap, boolean on) {
        switch (cap) {
            case CgGL.GL_BLEND:                blend = on; blendDirty = true; break;
            case CgGL.GL_DEPTH_TEST:           depthTest = on; depthStencilDirty = true; break;
            case CgGL.GL_STENCIL_TEST:         stencilTest = on; depthStencilDirty = true; break;
            case CgGL.GL_CULL_FACE:            cullFace = on; rasterDirty = true; break;
            case CgGL.GL_POLYGON_OFFSET_FILL:  polygonOffsetFill = on; rasterDirty = true; break;
            case CgGL.GL_POLYGON_OFFSET_LINE:  polygonOffsetLine = on; rasterDirty = true; break;
            case CgGL.GL_POLYGON_OFFSET_POINT: polygonOffsetPoint = on; rasterDirty = true; break;
            case CgGL.GL_SCISSOR_TEST:         scissorTest = on; break;
            case CgGL.GL_ALPHA_TEST:           alphaTest = on; if (on) fixedFunction("GL_ALPHA_TEST"); break;
            case GL_LINE_SMOOTH: case GL_MULTISAMPLE: case GL_FRAMEBUFFER_SRGB: case GL_PROGRAM_POINT_SIZE: break;
            default: errors.invalidEnum("glEnable", cap);
        }
    }

    public void blendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        this.srcRgb = srcRgb; this.dstRgb = dstRgb; this.srcAlpha = srcAlpha; this.dstAlpha = dstAlpha;
        blendDirty = true;
    }

    public void blendEquation(int rgb, int alpha) {
        equationRgb = rgb; equationAlpha = alpha;
        blendDirty = true;
    }

    public void depthMask(boolean flag) { depthMask = flag; depthStencilDirty = true; }

    public void depthFunc(int func) { depthFunc = func; depthStencilDirty = true; }

    public void cullFace(int mode) { cullMode = mode; rasterDirty = true; }

    public void frontFace(int mode) { frontFace = mode; rasterDirty = true; }

    public void polygonMode(int face, int mode) {
        if (face != CgGL.GL_FRONT_AND_BACK) errors.invalidEnum("glPolygonMode face", face);
        polygonMode = mode;
        rasterDirty = true;
    }

    public void polygonOffset(float factor, float units) { offsetFactor = factor; offsetUnits = units; }

    public void colorMask(int buffer, boolean r, boolean g, boolean b, boolean a) {
        int bits = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        if (buffer < 0) {
            colorMasks = bits * 0x11111111;
        } else {
            colorMasks = (colorMasks & ~(0xF << (4 * buffer))) | (bits << (4 * buffer));
        }
    }

    public void stencilFunc(int func, int ref, int mask) {
        stencilFunc = func; stencilRef = ref; stencilValueMask = mask;
        depthStencilDirty = true;
    }

    public void stencilOp(int fail, int depthFail, int pass) {
        stencilFail = fail; stencilDepthFail = depthFail; stencilPass = pass;
        depthStencilDirty = true;
    }

    public void stencilMask(int mask) { stencilWriteMask = mask; depthStencilDirty = true; }

    public void viewport(int x, int y, int w, int h) { viewportX = x; viewportY = y; viewportWidth = w; viewportHeight = h; }

    public void scissor(int x, int y, int w, int h) { scissorX = x; scissorY = y; scissorWidth = w; scissorHeight = h; }

    public void lineWidth(float width) {
        lineWidth = width;
        if (width != 1f && !warnedLineWidth) {
            warnedLineWidth = true;
            System.err.println("[crystalgraphics] tracked backend: line width " + width + " draws as 1 (warned once)");
        }
    }

    public void pointSize(float size) { pointSize = size; }

    public void alphaFunc(int func, float ref) {
        alphaFunc = func;
        alphaRef = ref;
    }

    private void fixedFunction(String what) {
        if (warnedFixedFunction) return;
        warnedFixedFunction = true;
        System.err.println("[crystalgraphics] tracked backend: " + what + " is fixed function, which a core profile "
                + "has none of; ignored (warned once)");
    }

    // ── into the tracker ───────────────────────────────────────────────────────

    /** Brings {@code tracker.state}'s pipeline records and dynamic state up to date, before a draw or a clear. */
    public void sync() {
        CgDrawState s = tracker.state;
        if (blendDirty) {
            s.blend = !blend ? null : new CgPipelineDesc.Blend(GlEnums.blendFactor(srcRgb), GlEnums.blendFactor(dstRgb),
                    GlEnums.blendOp(equationRgb), GlEnums.blendFactor(srcAlpha), GlEnums.blendFactor(dstAlpha),
                    GlEnums.blendOp(equationAlpha));
            blendDirty = false;
        }
        if (depthStencilDirty) {
            CgPipelineDesc.StencilFace face = new CgPipelineDesc.StencilFace(GlEnums.compare(stencilFunc),
                    GlEnums.stencilOp(stencilFail), GlEnums.stencilOp(stencilDepthFail), GlEnums.stencilOp(stencilPass));
            s.depthStencil = new CgPipelineDesc.DepthStencil(depthTest, depthMask, GlEnums.compare(depthFunc),
                    stencilTest, face, face, stencilValueMask & 0xFF, stencilWriteMask & 0xFF);
            depthStencilDirty = false;
        }
        if (rasterDirty) {
            CgPipelineDesc.PolygonMode mode = GlEnums.polygonMode(polygonMode);
            boolean bias = mode == CgPipelineDesc.PolygonMode.FILL ? polygonOffsetFill
                    : mode == CgPipelineDesc.PolygonMode.LINE ? polygonOffsetLine : polygonOffsetPoint;
            s.raster = new CgPipelineDesc.Raster(GlEnums.cull(cullFace, cullMode),
                    frontFace == CgGL.GL_CW ? CgPipelineDesc.FrontFace.CW : CgPipelineDesc.FrontFace.CCW, mode, bias);
            rasterDirty = false;
        }
        s.colorMasks = colorMasks;
        s.viewportX = viewportX; s.viewportY = viewportY; s.viewportWidth = viewportWidth; s.viewportHeight = viewportHeight;
        s.scissorTest = scissorTest;
        s.scissorX = scissorX; s.scissorY = scissorY; s.scissorWidth = scissorWidth; s.scissorHeight = scissorHeight;
        s.depthBiasConstant = offsetUnits;
        s.depthBiasSlope = offsetFactor;
        s.stencilReference = stencilRef;
    }

    public void clear(int mask) {
        sync();
        tracker.clear((mask & CgGL.GL_COLOR_BUFFER_BIT) != 0, clearR, clearG, clearB, clearA,
                (mask & CgGL.GL_DEPTH_BUFFER_BIT) != 0, (float) clearDepth,
                (mask & CgGL.GL_STENCIL_BUFFER_BIT) != 0, clearStencil);
    }

    // ── glGet ──────────────────────────────────────────────────────────────────

    /** Writes {@code pname}'s values into {@code out}; the count, or -1 if it is not state this domain keeps. */
    public int query(int pname, double[] out) {
        switch (pname) {
            case CgGL.GL_BLEND:                return one(out, blend);
            case CgGL.GL_DEPTH_TEST:           return one(out, depthTest);
            case CgGL.GL_STENCIL_TEST:         return one(out, stencilTest);
            case CgGL.GL_CULL_FACE:            return one(out, cullFace);
            case CgGL.GL_SCISSOR_TEST:         return one(out, scissorTest);
            case CgGL.GL_POLYGON_OFFSET_FILL:  return one(out, polygonOffsetFill);
            case CgGL.GL_POLYGON_OFFSET_LINE:  return one(out, polygonOffsetLine);
            case CgGL.GL_POLYGON_OFFSET_POINT: return one(out, polygonOffsetPoint);
            case CgGL.GL_ALPHA_TEST:           return one(out, alphaTest);
            case CgGL.GL_BLEND_SRC_RGB:        return one(out, srcRgb);
            case CgGL.GL_BLEND_DST_RGB:        return one(out, dstRgb);
            case CgGL.GL_BLEND_SRC_ALPHA:      return one(out, srcAlpha);
            case CgGL.GL_BLEND_DST_ALPHA:      return one(out, dstAlpha);
            case CgGL.GL_BLEND_EQUATION_RGB:   return one(out, equationRgb);
            case CgGL.GL_BLEND_EQUATION_ALPHA: return one(out, equationAlpha);
            case CgGL.GL_DEPTH_WRITEMASK:      return one(out, depthMask);
            case CgGL.GL_DEPTH_FUNC:           return one(out, depthFunc);
            case CgGL.GL_CULL_FACE_MODE:       return one(out, cullMode);
            case CgGL.GL_FRONT_FACE:           return one(out, frontFace);
            case CgGL.GL_POLYGON_MODE:         out[0] = out[1] = polygonMode; return 2;
            case CgGL.GL_POLYGON_OFFSET_FACTOR: return one(out, offsetFactor);
            case CgGL.GL_POLYGON_OFFSET_UNITS: return one(out, offsetUnits);
            case CgGL.GL_STENCIL_FUNC:         return one(out, stencilFunc);
            case CgGL.GL_STENCIL_REF:          return one(out, stencilRef);
            case CgGL.GL_STENCIL_VALUE_MASK:   return one(out, stencilValueMask);
            case CgGL.GL_STENCIL_WRITEMASK:    return one(out, stencilWriteMask);
            case CgGL.GL_STENCIL_FAIL:         return one(out, stencilFail);
            case CgGL.GL_STENCIL_PASS_DEPTH_FAIL: return one(out, stencilDepthFail);
            case CgGL.GL_STENCIL_PASS_DEPTH_PASS: return one(out, stencilPass);
            case CgGL.GL_COLOR_WRITEMASK:
                for (int i = 0; i < 4; i++) out[i] = (colorMasks >>> i) & 1;
                return 4;
            case CgGL.GL_VIEWPORT:
                out[0] = viewportX; out[1] = viewportY; out[2] = viewportWidth; out[3] = viewportHeight;
                return 4;
            case CgGL.GL_SCISSOR_BOX:
                out[0] = scissorX; out[1] = scissorY; out[2] = scissorWidth; out[3] = scissorHeight;
                return 4;
            case CgGL.GL_LINE_WIDTH:           return one(out, lineWidth);
            case CgGL.GL_POINT_SIZE:           return one(out, pointSize);
            case CgGL.GL_ALPHA_TEST_FUNC:      return one(out, alphaFunc);
            case CgGL.GL_ALPHA_TEST_REF:       return one(out, alphaRef);
            case GL_COLOR_CLEAR_VALUE:
                out[0] = clearR; out[1] = clearG; out[2] = clearB; out[3] = clearA;
                return 4;
            case GL_DEPTH_CLEAR_VALUE:         return one(out, clearDepth);
            case GL_STENCIL_CLEAR_VALUE:       return one(out, clearStencil);
            default: return -1;
        }
    }

    static int one(double[] out, double v) { out[0] = v; return 1; }

    static int one(double[] out, boolean v) { out[0] = v ? 1 : 0; return 1; }
}
