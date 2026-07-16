package com.crystalgraphics.gl.state;


import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Container for all GL state slot implementations. Each static inner class captures one GL state
 * domain via its {@code capture()} factory and restores it via {@link SlotState#restore()}.
 * Used exclusively by {@link CgGlState}.
 *
 * <p>All inner classes are package-private and have no public constructors or accessor methods —
 * they are opaque outside of {@code gl/state/}.</p>
 */
final class CgGlStates {

    private CgGlStates() {}

    // ── FBO ───────────────────────────────────────────────────────────────────

    public static final class FboState implements SlotState {

        private final int drawFbo;
        private final int readFbo;
        private final CallFamily family;

        private FboState(int drawFbo, int readFbo, CallFamily family) {
            this.drawFbo = drawFbo;
            this.readFbo = readFbo;
            this.family  = family;
        }

        public static FboState capture(boolean useGlGet) {
            int drawFbo, readFbo;
            CallFamily family;
            if (useGlGet) {
                CgCapabilities cgCaps = CgCapabilities.detect();
                if (cgCaps.isCoreFbo() || cgCaps.isArbFbo()) {
                    drawFbo = CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
                    readFbo = CgGL.glGetInteger(CgGL.GL_READ_FRAMEBUFFER_BINDING);
                } else if (cgCaps.isExtFbo()) {
                    int bound = CgGL.glGetInteger(CgGL.GL_FRAMEBUFFER_BINDING_EXT);
                    drawFbo = readFbo = bound;
                } else {
                    drawFbo = readFbo = 0;
                }
                family = detectFboFamily(cgCaps);
            } else {
                drawFbo = GLStateMirror.getDrawFboId();
                readFbo = GLStateMirror.getReadFboId();
                family  = GLStateMirror.getCurrentFboFamily();
            }
            return new FboState(drawFbo, readFbo, family);
        }

        @Override
        public void restore() {
            CgCapabilities cgCaps = CgCapabilities.detect();
            CallFamily currentFamily = GLStateMirror.getCurrentFboFamily();
            if (currentFamily != family && currentFamily != CallFamily.UNKNOWN) {
                bindFboByFamily(CgGL.GL_FRAMEBUFFER, 0, currentFamily);
            }
            boolean separateTargets = cgCaps.isCoreFbo() || cgCaps.isArbFbo();
            if (separateTargets && family != CallFamily.EXT_FBO && drawFbo != readFbo) {
                bindFboByFamily(CgGL.GL_DRAW_FRAMEBUFFER, drawFbo, family);
                bindFboByFamily(CgGL.GL_READ_FRAMEBUFFER, readFbo, family);
            } else {
                bindFboByFamily(CgGL.GL_FRAMEBUFFER, drawFbo, family);
            }
        }

        private static CallFamily detectFboFamily(CgCapabilities cgCaps) {
            if (cgCaps.isCoreFbo())  return CallFamily.CORE_GL30;
            if (cgCaps.isArbFbo())   return CallFamily.ARB_FBO;
            if (cgCaps.isExtFbo())   return CallFamily.EXT_FBO;
            return CallFamily.OPENGLHELPER_WRAPPER;
        }

        private static void bindFboByFamily(int target, int fboId, CallFamily family) {
            if (family == CallFamily.OPENGLHELPER_WRAPPER) {
                CgGL.glBindFramebufferCompat(fboId);
            } else if (family == CallFamily.EXT_FBO) {
                CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fboId);
            } else {
                CgGL.glBindFramebuffer(target, fboId);
            }
        }
    }

    // ── PROGRAM ───────────────────────────────────────────────────────────────

    public static final class ProgramState implements SlotState {

        private final int programId;
        private final CallFamily family;

        private ProgramState(int programId, CallFamily family) {
            this.programId = programId;
            this.family    = family;
        }

        public static ProgramState capture(boolean useGlGet) {
            int programId;
            CallFamily family;
            if (useGlGet) {
                CgCapabilities cgCaps = CgCapabilities.detect();
                if (cgCaps.isCoreShaders()) {
                    programId = CgGL.glGetInteger(CgGL.GL_CURRENT_PROGRAM);
                    family    = CallFamily.CORE_GL20;
                } else if (cgCaps.isArbShaders()) {
                    programId = CgGL.glGetHandle(CgGL.GL_PROGRAM_OBJECT_ARB);
                    family    = CallFamily.ARB_SHADER_OBJECTS;
                } else {
                    programId = 0;
                    family    = CallFamily.UNKNOWN;
                }
            } else {
                programId = GLStateMirror.getProgramId();
                family    = GLStateMirror.getCurrentProgramFamily();
            }
            return new ProgramState(programId, family);
        }

        @Override
        public void restore() {
            CallFamily currentFamily = GLStateMirror.getCurrentProgramFamily();
            if (currentFamily != family && currentFamily != CallFamily.UNKNOWN) {
                CgGL.glUseProgram(0);
            }
            CgGL.glUseProgram(programId);
        }
    }

    // ── TEXTURES ──────────────────────────────────────────────────────────────

    public static final class TextureState implements SlotState {

        private static Integer MAX_UNITS;

        private final int   activeUnit;
        private final int[] bound2D;

        private TextureState(int activeUnit, int[] bound2D) {
            this.activeUnit = activeUnit;
            this.bound2D    = bound2D.clone();
        }

        public static TextureState capture() {
            if (MAX_UNITS == null) MAX_UNITS = CgCapabilities.detect().getMaxTextureUnits();

            int activeUnit = CgGL.glGetInteger(CgGL.GL_ACTIVE_TEXTURE) - CgGL.GL_TEXTURE0;
            if (activeUnit < 0) activeUnit = 0;

            int[] bound2D = new int[MAX_UNITS];

            // MANDATORY: wrap the glActiveTexture loop in enterRedirect/exitRedirect.
            // Without it, each glActiveTexture call is intercepted by the redirect layer
            // and updates GLStateMirror mid-capture, corrupting it.
            GLStateMirror.enterRedirect();
            try {
                for (int i = 0; i < MAX_UNITS; i++) {
                    CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + i);
                    bound2D[i] = CgGL.glGetInteger(CgGL.GL_TEXTURE_BINDING_2D);
                }
                CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + activeUnit);
            } finally {
                GLStateMirror.exitRedirect();
            }

            return new TextureState(activeUnit, bound2D);
        }

        @Override
        public void restore() {
            // NOTE: do NOT use enterRedirect here — glBindTexture calls during restore are
            // intentional and SHOULD update the mirror via the redirect layer.
            for (int i = 0; i < bound2D.length; i++) {
                if (bound2D[i] != GLStateMirror.getBoundTexture2D(i)) {
                    CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + i);
                    CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, bound2D[i]);
                }
            }
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + activeUnit);
        }
    }

    // ── VERTEX INPUT ──────────────────────────────────────────────────────────

    public static final class VertexState implements SlotState {

        private final int vaoId;
        private final int arrayBufId;
        private final int elemBufId;

        private VertexState(int vaoId, int arrayBufId, int elemBufId) {
            this.vaoId      = vaoId;
            this.arrayBufId = arrayBufId;
            this.elemBufId  = elemBufId;
        }

        public static VertexState capture() {
            int vaoId = CgCapabilities.detect().isVaoSupported()
                    ? CgGL.glGetInteger(CgGL.GL_VERTEX_ARRAY_BINDING) : 0;
            int arrayBufId = CgGL.glGetInteger(CgGL.GL_ARRAY_BUFFER_BINDING);
            int elemBufId  = CgGL.glGetInteger(CgGL.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            return new VertexState(vaoId, arrayBufId, elemBufId);
        }

        @Override
        public void restore() {
            // CRITICAL ORDER: restore VAO first, then VBO, then EBO.
            // Binding an EBO while a VAO is active records it in that VAO's state —
            // restoring EBO before VAO would corrupt the currently-bound VAO.
            if (CgCapabilities.detect().isVaoSupported()) {
                CgVertexArray.bind(vaoId);
                GLStateMirror.onBindVertexArray(vaoId);
            }
            CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, arrayBufId);
            GLStateMirror.onBindBuffer(CgGL.GL_ARRAY_BUFFER, arrayBufId);
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, elemBufId);
            GLStateMirror.onBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, elemBufId);
        }
    }

    // ── ALPHA TEST ────────────────────────────────────────────────────────────

    /**
     * Fixed-function / compat profile only. Always available in Minecraft 1.7.10.
     * Note: {@code glEnable(GL_ALPHA_TEST)} generates {@code GL_INVALID_ENUM} in a
     * core profile context.
     */
    public static final class AlphaTestState implements SlotState {

        private final boolean enabled;
        private final int func;
        private final float ref;

        private AlphaTestState(boolean enabled, int func, float ref) {
            this.enabled = enabled;
            this.func    = func;
            this.ref     = ref;
        }

        public static AlphaTestState capture() {
            if (isCore()) return new AlphaTestState(false, 0, 0f);
            boolean enabled = CgGL.glGetBoolean(CgGL.GL_ALPHA_TEST);
            int func        = CgGL.glGetInteger(CgGL.GL_ALPHA_TEST_FUNC);
            float ref       = CgGL.glGetFloat(CgGL.GL_ALPHA_TEST_REF);
            return new AlphaTestState(enabled, func, ref);
        }

        @Override
        public void restore() {
            if (isCore()) return;
            if (enabled) CgGL.glEnable(CgGL.GL_ALPHA_TEST); else CgGL.glDisable(CgGL.GL_ALPHA_TEST);
            CgGL.glAlphaFunc(func, ref);
        }

        private static Boolean CORE = Boolean.FALSE;

        private static boolean isCore() {
            if (!CORE) CORE = CgCapabilities.detect().isCoreProfile();
            return CORE;
        }
    }

    // ── BLEND ─────────────────────────────────────────────────────────────────

    public static final class BlendState implements SlotState {

        private final boolean enabled;
        private final int srcRgb;
        private final int dstRgb;
        private final int srcAlpha;
        private final int dstAlpha;
        private final int eqRgb;
        private final int eqAlpha;

        private BlendState(boolean enabled, int srcRgb, int dstRgb,
                           int srcAlpha, int dstAlpha, int eqRgb, int eqAlpha) {
            this.enabled  = enabled;
            this.srcRgb   = srcRgb;   this.dstRgb   = dstRgb;
            this.srcAlpha = srcAlpha; this.dstAlpha = dstAlpha;
            this.eqRgb    = eqRgb;   this.eqAlpha  = eqAlpha;
        }

        public static BlendState capture() {
            boolean enabled  = CgGL.glGetBoolean(CgGL.GL_BLEND);
            int srcRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_SRC_RGB);
            int dstRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_DST_RGB);
            int srcAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_SRC_ALPHA);
            int dstAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_DST_ALPHA);
            int eqRgb, eqAlpha;
            if (CgCapabilities.detect().isCoreShaders()) {
                eqRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION_RGB);
                eqAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION_ALPHA);
            } else {
                eqRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION);
                eqAlpha = eqRgb;
            }
            return new BlendState(enabled, srcRgb, dstRgb, srcAlpha, dstAlpha, eqRgb, eqAlpha);
        }

        @Override
        public void restore() {
            if (!enabled) {
                CgGL.glDisable(CgGL.GL_BLEND);
            } else {
                CgGL.glEnable(CgGL.GL_BLEND);
                CgGL.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                CgGL.glBlendEquationSeparate(eqRgb, eqAlpha);
            }
        }
    }

    // ── DEPTH ─────────────────────────────────────────────────────────────────

    public static final class DepthState implements SlotState {

        private final boolean test;
        private final boolean write;
        private final int func;

        private DepthState(boolean test, int func, boolean write) {
            this.test  = test;
            this.func  = func;
            this.write = write;
        }

        public static DepthState capture() {
            boolean test  = CgGL.glGetBoolean(CgGL.GL_DEPTH_TEST);
            int func      = CgGL.glGetInteger(CgGL.GL_DEPTH_FUNC);
            boolean write = CgGL.glGetBoolean(CgGL.GL_DEPTH_WRITEMASK);
            return new DepthState(test, func, write);
        }

        @Override
        public void restore() {
            if (test) CgGL.glEnable(CgGL.GL_DEPTH_TEST); else CgGL.glDisable(CgGL.GL_DEPTH_TEST);
            CgGL.glDepthFunc(func);
            CgGL.glDepthMask(write);
        }
    }

    // ── CULL ──────────────────────────────────────────────────────────────────

    public static final class CullState implements SlotState {

        private final boolean enabled;
        private final int mode;
        private final int frontFace;

        private CullState(boolean enabled, int mode, int frontFace) {
            this.enabled   = enabled;
            this.mode      = mode;
            this.frontFace = frontFace;
        }

        public static CullState capture() {
            boolean enabled   = CgGL.glGetBoolean(CgGL.GL_CULL_FACE);
            int mode          = CgGL.glGetInteger(CgGL.GL_CULL_FACE_MODE);
            int frontFace     = CgGL.glGetInteger(CgGL.GL_FRONT_FACE);
            return new CullState(enabled, mode, frontFace);
        }

        @Override
        public void restore() {
            if (enabled) {
                CgGL.glEnable(CgGL.GL_CULL_FACE);
                CgGL.glCullFace(mode);
            } else {
                CgGL.glDisable(CgGL.GL_CULL_FACE);
            }
            CgGL.glFrontFace(frontFace);
        }
    }

    // ── STENCIL ───────────────────────────────────────────────────────────────

    /**
     * Captures front-face stencil state only (pre-existing limitation — back-face stencil
     * requires GL 2.0 separate stencil ops which are not exposed in this API).
     */
    public static final class StencilState implements SlotState {

        private final boolean enabled;
        private final int func;
        private final int ref;
        private final int readMask;
        private final int writeMask;
        private final int sfail;
        private final int dpfail;
        private final int dppass;

        private StencilState(boolean enabled, int func, int ref, int readMask,
                             int writeMask, int sfail, int dpfail, int dppass) {
            this.enabled   = enabled;
            this.func      = func;
            this.ref       = ref;
            this.readMask  = readMask;
            this.writeMask = writeMask;
            this.sfail     = sfail;
            this.dpfail    = dpfail;
            this.dppass    = dppass;
        }

        public static StencilState capture() {
            boolean enabled = CgGL.glGetBoolean(CgGL.GL_STENCIL_TEST);
            int func        = CgGL.glGetInteger(CgGL.GL_STENCIL_FUNC);
            int ref         = CgGL.glGetInteger(CgGL.GL_STENCIL_REF);
            int readMask    = CgGL.glGetInteger(CgGL.GL_STENCIL_VALUE_MASK);
            int writeMask   = CgGL.glGetInteger(CgGL.GL_STENCIL_WRITEMASK);
            int sfail       = CgGL.glGetInteger(CgGL.GL_STENCIL_FAIL);
            int dpfail      = CgGL.glGetInteger(CgGL.GL_STENCIL_PASS_DEPTH_FAIL);
            int dppass      = CgGL.glGetInteger(CgGL.GL_STENCIL_PASS_DEPTH_PASS);
            return new StencilState(enabled, func, ref, readMask, writeMask, sfail, dpfail, dppass);
        }

        @Override
        public void restore() {
            if (enabled) CgGL.glEnable(CgGL.GL_STENCIL_TEST); else CgGL.glDisable(CgGL.GL_STENCIL_TEST);
            CgGL.glStencilFunc(func, ref, readMask);
            CgGL.glStencilMask(writeMask);
            CgGL.glStencilOp(sfail, dpfail, dppass);
        }
    }

    // ── COLOR MASK ────────────────────────────────────────────────────────────

    public static final class ColorMaskState implements SlotState {

        private final boolean r;
        private final boolean g;
        private final boolean b;
        private final boolean a;

        private ColorMaskState(boolean r, boolean g, boolean b, boolean a) {
            this.r = r; this.g = g; this.b = b; this.a = a;
        }

        public static ColorMaskState capture() {
            // GL_COLOR_WRITEMASK is a 4-component boolean vector.
            // LWJGL 2's glGetBoolean(int, ByteBuffer) validates capacity >= 16 regardless
            // of how many values the query actually writes, so allocate 16.
            ByteBuffer buf = CgBufferUtils.createByteBuffer(16);
            CgGL.glGetBoolean(CgGL.GL_COLOR_WRITEMASK, buf);
            return new ColorMaskState(
                    buf.get(0) != 0,
                    buf.get(1) != 0,
                    buf.get(2) != 0,
                    buf.get(3) != 0);
        }

        @Override
        public void restore() {
            CgGL.glColorMask(r, g, b, a);
        }
    }

    // ── VIEWPORT ──────────────────────────────────────────────────────────────

    public static final class ViewportState implements SlotState {

        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private ViewportState(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }

        public static ViewportState capture() {
            IntBuffer buf = CgBufferUtils.createIntBuffer(16);
            CgGL.glGetInteger(CgGL.GL_VIEWPORT, buf);
            return new ViewportState(buf.get(0), buf.get(1), buf.get(2), buf.get(3));
        }

        @Override
        public void restore() {
            CgGL.glViewport(x, y, width, height);
        }
    }

    // ── SCISSOR ───────────────────────────────────────────────────────────────

    /**
     * Captures both the GL_SCISSOR_TEST enable flag AND the scissor box.
     * Restoring only one is incorrect.
     */
    public static final class ScissorState implements SlotState {

        private final boolean enabled;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private ScissorState(boolean enabled, int x, int y, int width, int height) {
            this.enabled = enabled;
            this.x = x; this.y = y; this.width = width; this.height = height;
        }

        public static ScissorState capture() {
            boolean enabled = CgGL.glGetBoolean(CgGL.GL_SCISSOR_TEST);
            IntBuffer buf = CgBufferUtils.createIntBuffer(4);
            CgGL.glGetInteger(CgGL.GL_SCISSOR_BOX, buf);
            return new ScissorState(enabled, buf.get(0), buf.get(1), buf.get(2), buf.get(3));
        }

        @Override
        public void restore() {
            if (enabled) CgGL.glEnable(CgGL.GL_SCISSOR_TEST); else CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
            CgGL.glScissor(x, y, width, height);
        }
    }

    // ── POLYGON OFFSET ────────────────────────────────────────────────────────

    public static final class PolygonOffsetState implements SlotState {

        private final boolean fill;
        private final boolean line;
        private final boolean point;
        private final float factor;
        private final float units;

        private PolygonOffsetState(boolean fill, boolean line, boolean point,
                                   float factor, float units) {
            this.fill   = fill;
            this.line   = line;
            this.point  = point;
            this.factor = factor;
            this.units  = units;
        }

        public static PolygonOffsetState capture() {
            boolean fill  = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_FILL);
            boolean line  = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_LINE);
            boolean point = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_POINT);
            float factor  = CgGL.glGetFloat(CgGL.GL_POLYGON_OFFSET_FACTOR);
            float units   = CgGL.glGetFloat(CgGL.GL_POLYGON_OFFSET_UNITS);
            return new PolygonOffsetState(fill, line, point, factor, units);
        }

        @Override
        public void restore() {
            if (fill)  CgGL.glEnable(CgGL.GL_POLYGON_OFFSET_FILL);
            else       CgGL.glDisable(CgGL.GL_POLYGON_OFFSET_FILL);
            if (line)  CgGL.glEnable(CgGL.GL_POLYGON_OFFSET_LINE);
            else       CgGL.glDisable(CgGL.GL_POLYGON_OFFSET_LINE);
            if (point) CgGL.glEnable(CgGL.GL_POLYGON_OFFSET_POINT);
            else       CgGL.glDisable(CgGL.GL_POLYGON_OFFSET_POINT);
            CgGL.glPolygonOffset(factor, units);
        }
    }

    // ── POLYGON MODE ──────────────────────────────────────────────────────────

    /**
     * Protects against debug wireframe passes ({@code GL_LINE}) not restoring {@code GL_FILL}.
     * GL_POLYGON_MODE returns 2 ints (front + back) via the buffer overload in LWJGL 2.
     */
    public static final class PolygonModeState implements SlotState {

        private final int front;
        private final int back;

        private PolygonModeState(int front, int back) {
            this.front = front;
            this.back  = back;
        }

        public static PolygonModeState capture() {
            IntBuffer buf = CgBufferUtils.createIntBuffer(2);
            CgGL.glGetInteger(CgGL.GL_POLYGON_MODE, buf);
            return new PolygonModeState(buf.get(0), buf.get(1));
        }

        @Override
        public void restore() {
            CgGL.glPolygonMode(CgGL.GL_FRONT, front);
            CgGL.glPolygonMode(CgGL.GL_BACK, back);
        }
    }

    // ── LINE WIDTH ────────────────────────────────────────────────────────────

    public static final class LineWidthState implements SlotState {

        private final float width;

        private LineWidthState(float width) {
            this.width = width;
        }

        public static LineWidthState capture() {
            return new LineWidthState(CgGL.glGetFloat(CgGL.GL_LINE_WIDTH));
        }

        @Override
        public void restore() {
            CgGL.glLineWidth(width);
        }
    }

    // ── POINT SIZE ────────────────────────────────────────────────────────────

    public static final class PointSizeState implements SlotState {

        private final float size;

        private PointSizeState(float size) {
            this.size = size;
        }

        public static PointSizeState capture() {
            return new PointSizeState(CgGL.glGetFloat(CgGL.GL_POINT_SIZE));
        }

        @Override
        public void restore() {
            CgGL.glPointSize(size);
        }
    }
}
