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
 * <p>A platform provider overrides only the domains its host state manager answers for free — Angelica's
 * mirror, the one installed today, answers most — and inherits the rest from here. Cost therefore degrades
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
            case LINE_WIDTH:     t.lineWidth = real(CgGL.GL_LINE_WIDTH); break;
            case POINT_SIZE:     t.pointSize = real(CgGL.GL_POINT_SIZE); break;
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
        t.blendEnabled  = bool(CgGL.GL_BLEND);
        t.blendSrcRgb   = integer(CgGL.GL_BLEND_SRC_RGB);
        t.blendDstRgb   = integer(CgGL.GL_BLEND_DST_RGB);
        t.blendSrcAlpha = integer(CgGL.GL_BLEND_SRC_ALPHA);
        t.blendDstAlpha = integer(CgGL.GL_BLEND_DST_ALPHA);
        t.blendEqRgb    = integer(CgGL.GL_BLEND_EQUATION_RGB);
        t.blendEqAlpha  = integer(CgGL.GL_BLEND_EQUATION_ALPHA);
    }

    protected void readDepth(CgGlStateShadow t) {
        t.depthTest = bool(CgGL.GL_DEPTH_TEST);
        t.depthMask = bool(CgGL.GL_DEPTH_WRITEMASK);
        t.depthFunc = integer(CgGL.GL_DEPTH_FUNC);
    }

    protected void readCull(CgGlStateShadow t) {
        t.cullEnabled = bool(CgGL.GL_CULL_FACE);
        t.cullFace    = integer(CgGL.GL_CULL_FACE_MODE);
        t.frontFace   = integer(CgGL.GL_FRONT_FACE);
    }

    protected void readStencil(CgGlStateShadow t) {
        t.stencilTest      = bool(CgGL.GL_STENCIL_TEST);
        t.stencilFunc      = integer(CgGL.GL_STENCIL_FUNC);
        t.stencilRef       = integer(CgGL.GL_STENCIL_REF);
        t.stencilValueMask = integer(CgGL.GL_STENCIL_VALUE_MASK);
        t.stencilWriteMask = integer(CgGL.GL_STENCIL_WRITEMASK);
        t.stencilFail      = integer(CgGL.GL_STENCIL_FAIL);
        t.stencilZFail     = integer(CgGL.GL_STENCIL_PASS_DEPTH_FAIL);
        t.stencilZPass     = integer(CgGL.GL_STENCIL_PASS_DEPTH_PASS);
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
        t.alphaTest = bool(CgGL.GL_ALPHA_TEST);
        t.alphaFunc = integer(CgGL.GL_ALPHA_TEST_FUNC);
        t.alphaRef  = real(CgGL.GL_ALPHA_TEST_REF);
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
        booleans(CgGL.GL_COLOR_WRITEMASK, BYTES);
        int nibble = (BYTES.get(0) != 0 ? 1 : 0) | (BYTES.get(1) != 0 ? 2 : 0)
                   | (BYTES.get(2) != 0 ? 4 : 0) | (BYTES.get(3) != 0 ? 8 : 0);
        int packed = 0;
        for (int i = 0; i < 8; i++) packed |= nibble << (i * 4);
        t.colorMaskPacked = packed;
    }

    protected void readViewport(CgGlStateShadow t) {
        INTS.clear();
        integers(CgGL.GL_VIEWPORT, INTS);
        t.viewportX = INTS.get(0); t.viewportY = INTS.get(1);
        t.viewportW = INTS.get(2); t.viewportH = INTS.get(3);
    }

    protected void readScissor(CgGlStateShadow t) {
        t.scissorTest = bool(CgGL.GL_SCISSOR_TEST);
        INTS.clear();
        integers(CgGL.GL_SCISSOR_BOX, INTS);
        t.scissorX = INTS.get(0); t.scissorY = INTS.get(1);
        t.scissorW = INTS.get(2); t.scissorH = INTS.get(3);
    }

    protected void readPolygonOffset(CgGlStateShadow t) {
        t.polygonOffsetFill   = bool(CgGL.GL_POLYGON_OFFSET_FILL);
        t.polygonOffsetLine   = bool(CgGL.GL_POLYGON_OFFSET_LINE);
        t.polygonOffsetPoint  = bool(CgGL.GL_POLYGON_OFFSET_POINT);
        t.polygonOffsetFactor = real(CgGL.GL_POLYGON_OFFSET_FACTOR);
        t.polygonOffsetUnits  = real(CgGL.GL_POLYGON_OFFSET_UNITS);
    }

    /**
     * Front then back -- on a compatibility profile. A driver that answers one value, as a core profile
     * does and NVIDIA's compatibility profile was measured to, leaves the second slot as it found it, so
     * it is primed and a back that was never written reads as the front.
     */
    protected void readPolygonMode(CgGlStateShadow t) {
        INTS.clear();
        INTS.put(1, UNWRITTEN);
        integers(CgGL.GL_POLYGON_MODE, INTS);
        t.polygonModeFront = INTS.get(0);
        t.polygonModeBack  = INTS.get(1) == UNWRITTEN ? t.polygonModeFront : INTS.get(1);
    }

    /** No GL enum; primes a slot a query may not write. */
    private static final int UNWRITTEN = 0x7fffffff;

    protected void readProgram(CgGlStateShadow t) {
        t.programId = integer(CgGL.GL_CURRENT_PROGRAM);
    }

    protected void readFbo(CgGlStateShadow t) {
        t.drawFbo = integer(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
        t.readFbo = integer(CgGL.GL_READ_FRAMEBUFFER_BINDING);
    }

    /**
     * Reads the active unit and every unit's {@code GL_TEXTURE_2D} binding.
     *
     * <p>By far the most expensive read here — two GL calls per unit. It is a faithful port of what the
     * previous capture did, so it is no worse than the status quo, but it is the obvious thing to narrow if
     * a profile ever shows texture adoption mattering.</p>
     */
    protected void readTextures(CgGlStateShadow t) {
        readTextures(t, CgGlStateShadow.MAX_TEXTURE_UNITS);
    }

    /**
     * The active unit and the first {@code units} units' bindings; the rest of {@code t} is left as it was.
     *
     * <pre>{@code
     * provider.readTextures(shadow, highestUnitTouched + 1);   // the round trip's cheap read
     * }</pre>
     */
    public void readTextures(CgGlStateShadow t, int units) {
        int active = integer(CgGL.GL_ACTIVE_TEXTURE) - CgGL.GL_TEXTURE0;
        if (active < 0) active = 0;
        for (int unit = 0; unit < Math.min(units, CgGlStateShadow.MAX_TEXTURE_UNITS); unit++) {
            activeTexture(CgGL.GL_TEXTURE0 + unit);
            t.boundTexture2D[unit] = integer(CgGL.GL_TEXTURE_BINDING_2D);
        }
        activeTexture(CgGL.GL_TEXTURE0 + active);   // the loop above moved it
        t.activeTextureUnit = active;
    }

    protected void readVertexInput(CgGlStateShadow t) {
        t.vertexArray        = integer(CgGL.GL_VERTEX_ARRAY_BINDING);
        t.arrayBuffer        = integer(CgGL.GL_ARRAY_BUFFER_BINDING);
        t.elementArrayBuffer = integer(CgGL.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    }

    // -- Where the answers come from ------------------------------------------------------------------
    //
    // Every read above goes through these, so a provider that must reach the driver another way -- past
    // a mod that rewrites GL call sites -- overrides six methods rather than the reader.

    protected int integer(int pname) { return CgGL.glGetInteger(pname); }

    protected boolean bool(int pname) { return CgGL.glGetBoolean(pname); }

    protected float real(int pname) { return CgGL.glGetFloat(pname); }

    protected void integers(int pname, IntBuffer into) { CgGL.glGetInteger(pname, into); }

    protected void booleans(int pname, ByteBuffer into) { CgGL.glGetBoolean(pname, into); }

    /** Moves the active unit, for {@link #readTextures}; the read puts it back. */
    protected void activeTexture(int texture) { CgGL.glActiveTexture(texture); }
}
