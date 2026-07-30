package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Reads GL state from the driver. The universal fallback, and the base every platform provider extends.
 *
 * <p><strong>This is the slow path, deliberately kept.</strong> Every {@code glGet*} here is a driver
 * synchronisation point — the GPU must drain queued work to answer. It exists because
 * <strong>adoption must be total</strong>: a domain left unpopulated has no valid restore baseline, so a
 * scope could neither restore it nor safely leave it alone. There is no platform that genuinely cannot
 * answer a {@code glGet}, so making this the base class means totality is structural rather than something
 * each provider has to remember.</p>
 *
 * <p>Platform providers override only the domains their host state manager can answer for free — Angelica's
 * mirror answers every one, Blaze3D's about half — and inherit the rest from here. Cost therefore degrades
 * smoothly instead of being all-or-nothing.</p>
 *
 * <p>The critical difference from the capture code this replaces is <em>frequency</em>, not mechanism:
 * these reads happen a few times per frame at scope boundaries, rather than roughly twenty-five times per
 * material bind.</p>
 */
public class CgGlGetProvider implements CgGlStateProvider {

    /** Sized 16 because LWJGL 2 validates capacity, not how many values the query actually writes. */
    private static final IntBuffer INTS =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private static final ByteBuffer BYTES =
            ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

    @Override
    public void read(CgGlSlot slot, CgGlStateShadow t) {
        switch (slot) {
            case BLEND:          readBlend(t);         break;
            case DEPTH:          readDepth(t);         break;
            case CULL:           readCull(t);          break;
            case STENCIL:        readStencil(t);       break;
            case ALPHA_TEST:     readAlpha(t);         break;
            case COLOR_MASK:     readColorMask(t);     break;
            case VIEWPORT:       readViewport(t);      break;
            case SCISSOR:        readScissor(t);       break;
            case POLYGON_OFFSET: readPolygonOffset(t); break;
            case POLYGON_MODE:   readPolygonMode(t);   break;
            case LINE_WIDTH:     t.lineWidth = CgGL.glGetFloat(CgGL.GL_LINE_WIDTH); break;
            case POINT_SIZE:     t.pointSize = CgGL.glGetFloat(CgGL.GL_POINT_SIZE); break;
            case PROGRAM:        readProgram(t);       break;
            case FBO:            readFbo(t);           break;
            case TEXTURES:       readTextures(t);      break;
            case VERTEX_INPUT:   readVertexInput(t);   break;
            default:
                // Reached only if a slot is added without a reader. Failing loudly is the point: returning
                // silently would strand the domain with no restore baseline.
                throw new IllegalStateException("No glGet reader for slot " + slot);
        }
    }

    protected void readBlend(CgGlStateShadow t) {
        t.blendEnabled  = CgGL.glGetBoolean(CgGL.GL_BLEND);
        t.blendSrcRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_SRC_RGB);
        t.blendDstRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_DST_RGB);
        t.blendSrcAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_SRC_ALPHA);
        t.blendDstAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_DST_ALPHA);
        t.blendEqRgb    = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION_RGB);
        t.blendEqAlpha  = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION_ALPHA);
    }

    protected void readDepth(CgGlStateShadow t) {
        t.depthTest = CgGL.glGetBoolean(CgGL.GL_DEPTH_TEST);
        t.depthMask = CgGL.glGetBoolean(CgGL.GL_DEPTH_WRITEMASK);
        t.depthFunc = CgGL.glGetInteger(CgGL.GL_DEPTH_FUNC);
    }

    protected void readCull(CgGlStateShadow t) {
        t.cullEnabled = CgGL.glGetBoolean(CgGL.GL_CULL_FACE);
        t.cullFace    = CgGL.glGetInteger(CgGL.GL_CULL_FACE_MODE);
        t.frontFace   = CgGL.glGetInteger(CgGL.GL_FRONT_FACE);
    }

    protected void readStencil(CgGlStateShadow t) {
        t.stencilTest      = CgGL.glGetBoolean(CgGL.GL_STENCIL_TEST);
        t.stencilFunc      = CgGL.glGetInteger(CgGL.GL_STENCIL_FUNC);
        t.stencilRef       = CgGL.glGetInteger(CgGL.GL_STENCIL_REF);
        t.stencilValueMask = CgGL.glGetInteger(CgGL.GL_STENCIL_VALUE_MASK);
        t.stencilWriteMask = CgGL.glGetInteger(CgGL.GL_STENCIL_WRITEMASK);
        t.stencilFail      = CgGL.glGetInteger(CgGL.GL_STENCIL_FAIL);
        t.stencilZFail     = CgGL.glGetInteger(CgGL.GL_STENCIL_PASS_DEPTH_FAIL);
        t.stencilZPass     = CgGL.glGetInteger(CgGL.GL_STENCIL_PASS_DEPTH_PASS);
    }

    /**
     * Alpha test, or a disabled value on a core profile.
     *
     * <p>{@code glGetBoolean(GL_ALPHA_TEST)} raises {@code GL_INVALID_ENUM} on a core profile, where
     * fixed-function alpha testing does not exist. MC 1.7.10 is compatibility and genuinely uses it.</p>
     */
    protected void readAlpha(CgGlStateShadow t) {
        if (CgCapabilities.detect().isCoreProfile()) {
            t.alphaTest = false;
            t.alphaFunc = CgGL.GL_ALWAYS;
            t.alphaRef  = 0f;
            return;
        }
        t.alphaTest = CgGL.glGetBoolean(CgGL.GL_ALPHA_TEST);
        t.alphaFunc = CgGL.glGetInteger(CgGL.GL_ALPHA_TEST_FUNC);
        t.alphaRef  = CgGL.glGetFloat(CgGL.GL_ALPHA_TEST_REF);
    }

    /**
     * Reads the global colour write mask and mirrors it across all eight targets.
     *
     * <p>Per-target masks would need {@code glGetBooleani_v} per attachment. Anything that set a per-target
     * mask did so through {@link CgGL}, which tracks them exactly, so the global read is sufficient as a
     * restore baseline.</p>
     */
    protected void readColorMask(CgGlStateShadow t) {
        BYTES.clear();
        CgGL.glGetBoolean(CgGL.GL_COLOR_WRITEMASK, BYTES);
        int nibble = (BYTES.get(0) != 0 ? 1 : 0) | (BYTES.get(1) != 0 ? 2 : 0)
                   | (BYTES.get(2) != 0 ? 4 : 0) | (BYTES.get(3) != 0 ? 8 : 0);
        int packed = 0;
        for (int i = 0; i < 8; i++) packed |= nibble << (i * 4);
        t.colorMaskPacked = packed;
    }

    protected void readViewport(CgGlStateShadow t) {
        INTS.clear();
        CgGL.glGetInteger(CgGL.GL_VIEWPORT, INTS);
        t.viewportX = INTS.get(0); t.viewportY = INTS.get(1);
        t.viewportW = INTS.get(2); t.viewportH = INTS.get(3);
    }

    protected void readScissor(CgGlStateShadow t) {
        t.scissorTest = CgGL.glGetBoolean(CgGL.GL_SCISSOR_TEST);
        INTS.clear();
        CgGL.glGetInteger(CgGL.GL_SCISSOR_BOX, INTS);
        t.scissorX = INTS.get(0); t.scissorY = INTS.get(1);
        t.scissorW = INTS.get(2); t.scissorH = INTS.get(3);
    }

    protected void readPolygonOffset(CgGlStateShadow t) {
        t.polygonOffsetFill   = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_FILL);
        t.polygonOffsetLine   = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_LINE);
        t.polygonOffsetPoint  = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_POINT);
        t.polygonOffsetFactor = CgGL.glGetFloat(CgGL.GL_POLYGON_OFFSET_FACTOR);
        t.polygonOffsetUnits  = CgGL.glGetFloat(CgGL.GL_POLYGON_OFFSET_UNITS);
    }

    protected void readPolygonMode(CgGlStateShadow t) {
        INTS.clear();
        CgGL.glGetInteger(CgGL.GL_POLYGON_MODE, INTS);   // returns front then back
        t.polygonModeFront = INTS.get(0);
        t.polygonModeBack  = INTS.get(1);
    }

    protected void readProgram(CgGlStateShadow t) {
        t.programId = CgGL.glGetInteger(CgGL.GL_CURRENT_PROGRAM);
    }

    protected void readFbo(CgGlStateShadow t) {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps.isCoreFbo() || caps.isArbFbo()) {
            t.drawFbo   = CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
            t.readFbo   = CgGL.glGetInteger(CgGL.GL_READ_FRAMEBUFFER_BINDING);
            t.fboFamily = CgGlStateShadow.FboFamily.CORE_OR_ARB;
        } else if (caps.isExtFbo()) {
            // EXT has a single binding covering both targets.
            t.drawFbo = t.readFbo = CgGL.glGetInteger(CgGL.GL_FRAMEBUFFER_BINDING_EXT);
            t.fboFamily = CgGlStateShadow.FboFamily.EXT;
        } else {
            t.drawFbo = t.readFbo = 0;
            t.fboFamily = CgGlStateShadow.FboFamily.UNKNOWN;
        }
    }

    /**
     * Reads the active unit and every unit's {@code GL_TEXTURE_2D} binding.
     *
     * <p>By far the most expensive read here — two GL calls per unit. It is a faithful port of what the
     * previous capture did, so it is no worse than the status quo, but it is the obvious thing to narrow if
     * a profile ever shows texture adoption mattering.</p>
     */
    protected void readTextures(CgGlStateShadow t) {
        int active = CgGL.glGetInteger(CgGL.GL_ACTIVE_TEXTURE) - CgGL.GL_TEXTURE0;
        if (active < 0) active = 0;
        for (int unit = 0; unit < CgGlStateShadow.MAX_TEXTURE_UNITS; unit++) {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
            t.boundTexture2D[unit] = CgGL.glGetInteger(CgGL.GL_TEXTURE_BINDING_2D);
        }
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + active);   // the loop above moved it
        t.activeTextureUnit = active;
    }

    protected void readVertexInput(CgGlStateShadow t) {
        t.vertexArray = CgCapabilities.detect().isVaoSupported()
                ? CgGL.glGetInteger(CgGL.GL_VERTEX_ARRAY_BINDING) : 0;
        t.arrayBuffer        = CgGL.glGetInteger(CgGL.GL_ARRAY_BUFFER_BINDING);
        t.elementArrayBuffer = CgGL.glGetInteger(CgGL.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    }
}
