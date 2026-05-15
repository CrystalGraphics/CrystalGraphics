package com.crystalgraphics.gl.state;

import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.platform.CgGlDispatch;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

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

        private static final int GL_FRAMEBUFFER      = 0x8D40;
        private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
        private static final int GL_READ_FRAMEBUFFER = 0x8CA8;

        private final int drawFbo;
        private final int readFbo;
        private final CallFamily family;

        private FboState(int drawFbo, int readFbo, CallFamily family) {
            this.drawFbo = drawFbo;
            this.readFbo = readFbo;
            this.family  = family;
        }

        public static FboState capture(ContextCapabilities caps, boolean useGlGet) {
            int drawFbo, readFbo;
            CallFamily family;
            if (useGlGet) {
                if (caps.OpenGL30 || caps.GL_ARB_framebuffer_object) {
                    drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                    readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                } else if (caps.GL_EXT_framebuffer_object) {
                    int bound = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
                    drawFbo = readFbo = bound;
                } else {
                    drawFbo = readFbo = 0;
                }
                family = detectFboFamily(caps);
            } else {
                drawFbo = GLStateMirror.getDrawFboId();
                readFbo = GLStateMirror.getReadFboId();
                family  = GLStateMirror.getCurrentFboFamily();
            }
            return new FboState(drawFbo, readFbo, family);
        }

        @Override
        public void restore() {
            CallFamily currentFamily = GLStateMirror.getCurrentFboFamily();
            if (currentFamily != family && currentFamily != CallFamily.UNKNOWN) {
                bindFboByFamily(GL_FRAMEBUFFER, 0, currentFamily);
            }
            ContextCapabilities caps = GLContext.getCapabilities();
            boolean separateTargets = caps.OpenGL30 || caps.GL_ARB_framebuffer_object;
            if (separateTargets && family != CallFamily.EXT_FBO && drawFbo != readFbo) {
                bindFboByFamily(GL_DRAW_FRAMEBUFFER, drawFbo, family);
                bindFboByFamily(GL_READ_FRAMEBUFFER, readFbo, family);
            } else {
                bindFboByFamily(GL_FRAMEBUFFER, drawFbo, family);
            }
        }

        private static CallFamily detectFboFamily(ContextCapabilities caps) {
            if (caps.OpenGL30)                  return CallFamily.CORE_GL30;
            if (caps.GL_ARB_framebuffer_object) return CallFamily.ARB_FBO;
            if (caps.GL_EXT_framebuffer_object) return CallFamily.EXT_FBO;
            return CallFamily.OPENGLHELPER_WRAPPER;
        }

        private static void bindFboByFamily(int target, int fboId, CallFamily family) {
            switch (family) {
                case ARB_FBO:
                    ARBFramebufferObject.glBindFramebuffer(target, fboId);
                    break;
                case EXT_FBO:
                    EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER, fboId);
                    break;
                case OPENGLHELPER_WRAPPER:
                    CgGlDispatch.get().bindFramebufferCompat(fboId);
                    break;
                case CORE_GL30:
                default:
                    ContextCapabilities caps = GLContext.getCapabilities();
                    if (caps.OpenGL30) {
                        GL30.glBindFramebuffer(target, fboId);
                    } else if (caps.GL_ARB_framebuffer_object) {
                        ARBFramebufferObject.glBindFramebuffer(target, fboId);
                    } else if (caps.GL_EXT_framebuffer_object) {
                        EXTFramebufferObject.glBindFramebufferEXT(target, fboId);
                    } else {
                        CgGlDispatch.get().bindFramebufferCompat(fboId);
                    }
                    break;
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

        public static ProgramState capture(ContextCapabilities caps, boolean useGlGet) {
            int programId;
            CallFamily family;
            if (useGlGet) {
                if (caps.OpenGL20) {
                    programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                    family    = CallFamily.CORE_GL20;
                } else if (caps.GL_ARB_shader_objects) {
                    programId = ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
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
                useProgramByFamily(0, currentFamily);
            }
            useProgramByFamily(programId, family);
        }

        private static void useProgramByFamily(int id, CallFamily family) {
            switch (family) {
                case ARB_SHADER_OBJECTS:
                    ARBShaderObjects.glUseProgramObjectARB(id);
                    break;
                case CORE_GL20:
                default:
                    ContextCapabilities caps = GLContext.getCapabilities();
                    if (caps.OpenGL20) {
                        GL20.glUseProgram(id);
                    } else if (caps.GL_ARB_shader_objects) {
                        ARBShaderObjects.glUseProgramObjectARB(id);
                    }
                    break;
            }
        }
    }

    // ── TEXTURES ──────────────────────────────────────────────────────────────

    public static final class TextureState implements SlotState {

        private static Integer MAX_UNITS;
        private static final int GL_TEXTURE0  = 0x84C0;
        private static final int GL_TEXTURE_2D = 0x0DE1;

        private final int   activeUnit;
        private final int[] bound2D;

        private TextureState(int activeUnit, int[] bound2D) {
            this.activeUnit = activeUnit;
            this.bound2D    = bound2D.clone();
        }

        public static TextureState capture(ContextCapabilities caps) {
            if(MAX_UNITS == null)  MAX_UNITS = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
            
            int activeUnit = 0;
            if (caps.OpenGL13) {
                activeUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL_TEXTURE0;
            } else if (caps.GL_ARB_multitexture) {
                activeUnit = GL11.glGetInteger(ARBMultitexture.GL_ACTIVE_TEXTURE_ARB) - GL_TEXTURE0;
            }
            if (activeUnit < 0) activeUnit = 0;

            int[] bound2D = new int[MAX_UNITS];

            // MANDATORY: wrap the glActiveTexture loop in enterRedirect/exitRedirect.
            // Without it, each glActiveTexture call is intercepted by the redirect layer
            // and updates GLStateMirror mid-capture, corrupting it.
            GLStateMirror.enterRedirect();
            try {
                for (int i = 0; i < MAX_UNITS; i++) {
                    setActiveTextureUnit(i, caps);
                    bound2D[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                }
                // restore active unit within capture so the mirror stays consistent
                setActiveTextureUnit(activeUnit, caps);
            } finally {
                GLStateMirror.exitRedirect();
            }

            return new TextureState(activeUnit, bound2D);
        }

        @Override
        public void restore() {
            // NOTE: do NOT use enterRedirect here — glBindTexture calls during restore are
            // intentional and SHOULD update the mirror via the redirect layer.
            ContextCapabilities caps = GLContext.getCapabilities();
            for (int i = 0; i < bound2D.length; i++) {
                if (bound2D[i] != GLStateMirror.getBoundTexture2D(i)) {
                    setActiveTextureUnit(i, caps);
                    GL11.glBindTexture(GL_TEXTURE_2D, bound2D[i]);
                }
            }
            setActiveTextureUnit(activeUnit, caps);
        }

        private static void setActiveTextureUnit(int unitIndex, ContextCapabilities caps) {
            int unitConst = GL_TEXTURE0 + unitIndex;
            if (caps.OpenGL13) {
                GL13.glActiveTexture(unitConst);
                return;
            }
            if (caps.GL_ARB_multitexture) {
                ARBMultitexture.glActiveTextureARB(unitConst);
                return;
            }
            throw new IllegalStateException(
                    "No active-texture API available (OpenGL13 and ARB_multitexture both absent)");
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

        public static VertexState capture(ContextCapabilities caps) {
            int vaoId = (caps.OpenGL30 || caps.GL_ARB_vertex_array_object)
                    ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
            int arrayBufId = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int elemBufId  = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            return new VertexState(vaoId, arrayBufId, elemBufId);
        }

        @Override
        public void restore() {
            // CRITICAL ORDER: restore VAO first, then VBO, then EBO.
            // Binding an EBO while a VAO is active records it in that VAO's state —
            // restoring EBO before VAO would corrupt the currently-bound VAO.
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps.OpenGL30 || caps.GL_ARB_vertex_array_object) {
                CgVertexArray.bind(vaoId);
                GLStateMirror.onBindVertexArray(vaoId);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBufId);
            GLStateMirror.onBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBufId);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elemBufId);
            GLStateMirror.onBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elemBufId);
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
            boolean enabled = GL11.glGetBoolean(GL11.GL_ALPHA_TEST);
            int func        = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            float ref       = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            return new AlphaTestState(enabled, func, ref);
        }

        @Override
        public void restore() {
            if (enabled) GL11.glEnable(GL11.GL_ALPHA_TEST); else GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(func, ref);
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

        public static BlendState capture(ContextCapabilities caps) {
            boolean enabled  = GL11.glGetBoolean(GL11.GL_BLEND);
            int srcRgb   = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int dstRgb   = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int eqRgb, eqAlpha;
            if (caps.OpenGL20) {
                eqRgb   = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
                eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            } else {
                eqRgb   = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
                eqAlpha = eqRgb;
            }
            return new BlendState(enabled, srcRgb, dstRgb, srcAlpha, dstAlpha, eqRgb, eqAlpha);
        }

        @Override
        public void restore() {
            if (!enabled) {
                GL11.glDisable(GL11.GL_BLEND);
            } else {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
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
            boolean test  = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
            int func      = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            return new DepthState(test, func, write);
        }

        @Override
        public void restore() {
            if (test) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(func);
            GL11.glDepthMask(write);
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
            boolean enabled   = GL11.glGetBoolean(GL11.GL_CULL_FACE);
            int mode          = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            int frontFace     = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            return new CullState(enabled, mode, frontFace);
        }

        @Override
        public void restore() {
            if (enabled) {
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL11.glCullFace(mode);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            GL11.glFrontFace(frontFace);
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
            boolean enabled = GL11.glGetBoolean(GL11.GL_STENCIL_TEST);
            int func        = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
            int ref         = GL11.glGetInteger(GL11.GL_STENCIL_REF);
            int readMask    = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
            int writeMask   = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
            int sfail       = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
            int dpfail      = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
            int dppass      = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
            return new StencilState(enabled, func, ref, readMask, writeMask, sfail, dpfail, dppass);
        }

        @Override
        public void restore() {
            if (enabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glStencilFunc(func, ref, readMask);
            GL11.glStencilMask(writeMask);
            GL11.glStencilOp(sfail, dpfail, dppass);
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
            ByteBuffer buf = BufferUtils.createByteBuffer(16);
            GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, buf);
            return new ColorMaskState(
                    buf.get(0) != 0,
                    buf.get(1) != 0,
                    buf.get(2) != 0,
                    buf.get(3) != 0);
        }

        @Override
        public void restore() {
            GL11.glColorMask(r, g, b, a);
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
            IntBuffer buf = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_VIEWPORT, buf);
            return new ViewportState(buf.get(0), buf.get(1), buf.get(2), buf.get(3));
        }

        @Override
        public void restore() {
            GL11.glViewport(x, y, width, height);
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
            boolean enabled = GL11.glGetBoolean(GL11.GL_SCISSOR_TEST);
            IntBuffer buf = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, buf);
            return new ScissorState(enabled, buf.get(0), buf.get(1), buf.get(2), buf.get(3));
        }

        @Override
        public void restore() {
            if (enabled) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, width, height);
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
            boolean fill  = GL11.glGetBoolean(GL11.GL_POLYGON_OFFSET_FILL);
            boolean line  = GL11.glGetBoolean(GL11.GL_POLYGON_OFFSET_LINE);
            boolean point = GL11.glGetBoolean(GL11.GL_POLYGON_OFFSET_POINT);
            float factor  = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            float units   = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            return new PolygonOffsetState(fill, line, point, factor, units);
        }

        @Override
        public void restore() {
            if (fill)  GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            else       GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            if (line)  GL11.glEnable(GL11.GL_POLYGON_OFFSET_LINE);
            else       GL11.glDisable(GL11.GL_POLYGON_OFFSET_LINE);
            if (point) GL11.glEnable(GL11.GL_POLYGON_OFFSET_POINT);
            else       GL11.glDisable(GL11.GL_POLYGON_OFFSET_POINT);
            GL11.glPolygonOffset(factor, units);
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
            IntBuffer buf = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_POLYGON_MODE, buf);
            return new PolygonModeState(buf.get(0), buf.get(1));
        }

        @Override
        public void restore() {
            GL11.glPolygonMode(GL11.GL_FRONT, front);
            GL11.glPolygonMode(GL11.GL_BACK, back);
        }
    }
    
    // ── LINE WIDTH ────────────────────────────────────────────────────────────

    public static final class LineWidthState implements SlotState {

        private final float width;

        private LineWidthState(float width) {
            this.width = width;
        }

        public static LineWidthState capture() {
            return new LineWidthState(GL11.glGetFloat(GL11.GL_LINE_WIDTH));
        }

        @Override
        public void restore() {
            GL11.glLineWidth(width);
        }
    }

    // ── POINT SIZE ────────────────────────────────────────────────────────────

    public static final class PointSizeState implements SlotState {

        private final float size;

        private PointSizeState(float size) {
            this.size = size;
        }

        public static PointSizeState capture() {
            return new PointSizeState(GL11.glGetFloat(GL11.GL_POINT_SIZE));
        }

        @Override
        public void restore() {
            GL11.glPointSize(size);
        }
    }
}
