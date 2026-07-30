package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlStateManager;

/**
 * Flat, mutable record of the GL state CrystalGraphics tracks.
 *
 * <p>Public fields, no accessors, no invariants. It is a struct — {@link CgGlStateManager} owns the rules,
 * this owns the bytes.</p>
 *
 * <h3>Why flat fields rather than the immutable records this replaces</h3>
 * <p>Deduplication now happens at the individual {@link CgGL} call, and a single call sets a single field:
 * {@code glDepthMask} touches one of the depth domain's three values. Whole-value equality cannot answer
 * "is only the depth <em>mask</em> unchanged?", so the shadow has to be addressable per field.</p>
 *
 * <p>The immutable-record design that preceded this was the right shape while deduplication compared whole
 * domains, and it is what kept the old manager free of per-domain code. That trade no longer applies: per
 * field is strictly finer, and the per-domain knowledge it costs is confined to one {@code reissue} switch
 * rather than spread across twelve classes.</p>
 *
 * <h3>Copying</h3>
 * <p>{@link #copyFrom} duplicates the whole struct rather than a named subset. A scope then restores only
 * the domains it named. Copying everything is simpler and, at roughly a dozen scopes per frame, not worth
 * optimising — the array copy for texture units is the only part that is not a plain field assignment.</p>
 */
public final class CgGlStateShadow {

    /** Matches the largest unit count worth tracking; higher units are CrystalGraphics-owned. */
    public static final int MAX_TEXTURE_UNITS = 32;

    // ── Blend ─────────────────────────────────────────────────────────────────
    public boolean blendEnabled;
    public int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
    public int blendEqRgb, blendEqAlpha;

    // ── Depth ─────────────────────────────────────────────────────────────────
    public boolean depthTest;
    /** The write mask. Distinct from {@link #depthTest} — conflating the two is a classic source of bugs. */
    public boolean depthMask;
    public int depthFunc;

    // ── Cull ──────────────────────────────────────────────────────────────────
    public boolean cullEnabled;
    public int cullFace;
    /** Winding. Independent of {@link #cullEnabled} — two-sided stencil depends on it too. */
    public int frontFace;

    // ── Stencil ───────────────────────────────────────────────────────────────
    public boolean stencilTest;
    public int stencilFunc, stencilRef, stencilValueMask, stencilWriteMask;
    public int stencilFail, stencilZFail, stencilZPass;

    // ── Alpha test (compatibility profile only) ───────────────────────────────
    public boolean alphaTest;
    public int alphaFunc;
    public float alphaRef;

    /**
     * Colour write mask for eight render targets, four bits each — R,G,B,A from the low bit.
     *
     * <p>Eight targets times four channels is exactly thirty-two bits, so the whole domain is one
     * comparison. An array would have needed element-wise compares in the hottest path in the class.</p>
     */
    public int colorMaskPacked;

    // ── Viewport / scissor ────────────────────────────────────────────────────
    public int viewportX, viewportY, viewportW, viewportH;
    public boolean scissorTest;
    public int scissorX, scissorY, scissorW, scissorH;

    // ── Polygon ───────────────────────────────────────────────────────────────
    public boolean polygonOffsetFill, polygonOffsetLine, polygonOffsetPoint;
    public float polygonOffsetFactor, polygonOffsetUnits;
    public int polygonModeFront, polygonModeBack;

    // ── Line / point ──────────────────────────────────────────────────────────
    public float lineWidth, pointSize;

    // ── Bindings ──────────────────────────────────────────────────────────────
    public int programId;
    public int drawFbo, readFbo;

    /**
     * Which API bound the current framebuffer.
     *
     * <p>Part of the value, not incidental: an {@code EXT_framebuffer_object} name is not valid in a Core
     * call, so releasing across families must go through the family that owns the name. Derived from the
     * bind target rather than passed in — see {@code CgGlStateManager.fboBound}.</p>
     */
    public FboFamily fboFamily = FboFamily.UNKNOWN;

    public int activeTextureUnit;
    public final int[] boundTexture2D = new int[MAX_TEXTURE_UNITS];

    public int vertexArray, arrayBuffer;

    /**
     * Sentinel for {@link #elementArrayBuffer}: the binding is real but we do not know its name.
     *
     * <p>Distinct from {@code 0}, which means <em>known to be unbound</em>. Negative because GL object names
     * are never negative, so it can never collide with a value a caller might legitimately bind.</p>
     */
    public static final int UNKNOWN_BINDING = -1;

    /**
     * The element array buffer binding — <strong>per-VAO state, not global.</strong>
     *
     * <p>This is the one field here that is not a plain context value. {@code glBindVertexArray} implicitly
     * swaps the element array binding to whatever that VAO recorded, without any {@code glBindBuffer} we
     * could observe. Tracking it as an ordinary global therefore goes stale the instant the VAO changes, and
     * the next bind of the same IBO looks redundant and gets elided — which surfaces as
     * {@code "Cannot use offsets when Element Array Buffer Object is disabled"} at the draw call, far from
     * the cause.</p>
     *
     * <p>So a VAO change sets this to {@link #UNKNOWN_BINDING}. Deduplication still works within a VAO,
     * which is where the repeat binds actually are (bind once, draw many).</p>
     *
     * <p>{@code GL_ARRAY_BUFFER} above needs none of this — it is genuine global context state and is
     * <em>not</em> captured by a VAO.</p>
     */
    public int elementArrayBuffer;

    /**
     * Which framebuffer API owns the current binding.
     *
     * <p>Only three cases matter. {@code ARB_framebuffer_object} was specified to match GL 3.0 core
     * semantics and <strong>shares its object namespace</strong>, so Core and ARB need no distinction —
     * only the older, incompatible {@code EXT_framebuffer_object} does.</p>
     */
    public enum FboFamily {
        /** Core GL 3.0 or {@code ARB_framebuffer_object} — one namespace, interchangeable. */
        CORE_OR_ARB,
        /** {@code EXT_framebuffer_object} — a separate namespace with no draw/read split. */
        EXT,
        /** Minecraft's {@code OpenGlHelper} wrapper, which picks the API itself. */
        MC_WRAPPER,
        /** Nothing has been bound through this manager yet. */
        UNKNOWN
    }

    /** Copies every field. Deliberately whole-struct; scopes restore only the domains they named. */
    public void copyFrom(CgGlStateShadow o) {
        blendEnabled = o.blendEnabled;
        blendSrcRgb = o.blendSrcRgb; blendDstRgb = o.blendDstRgb;
        blendSrcAlpha = o.blendSrcAlpha; blendDstAlpha = o.blendDstAlpha;
        blendEqRgb = o.blendEqRgb; blendEqAlpha = o.blendEqAlpha;

        depthTest = o.depthTest; depthMask = o.depthMask; depthFunc = o.depthFunc;

        cullEnabled = o.cullEnabled; cullFace = o.cullFace; frontFace = o.frontFace;

        stencilTest = o.stencilTest;
        stencilFunc = o.stencilFunc; stencilRef = o.stencilRef;
        stencilValueMask = o.stencilValueMask; stencilWriteMask = o.stencilWriteMask;
        stencilFail = o.stencilFail; stencilZFail = o.stencilZFail; stencilZPass = o.stencilZPass;

        alphaTest = o.alphaTest; alphaFunc = o.alphaFunc; alphaRef = o.alphaRef;

        colorMaskPacked = o.colorMaskPacked;

        viewportX = o.viewportX; viewportY = o.viewportY;
        viewportW = o.viewportW; viewportH = o.viewportH;

        scissorTest = o.scissorTest;
        scissorX = o.scissorX; scissorY = o.scissorY;
        scissorW = o.scissorW; scissorH = o.scissorH;

        polygonOffsetFill = o.polygonOffsetFill;
        polygonOffsetLine = o.polygonOffsetLine;
        polygonOffsetPoint = o.polygonOffsetPoint;
        polygonOffsetFactor = o.polygonOffsetFactor; polygonOffsetUnits = o.polygonOffsetUnits;
        polygonModeFront = o.polygonModeFront; polygonModeBack = o.polygonModeBack;

        lineWidth = o.lineWidth; pointSize = o.pointSize;

        programId = o.programId;
        drawFbo = o.drawFbo; readFbo = o.readFbo; fboFamily = o.fboFamily;

        activeTextureUnit = o.activeTextureUnit;
        System.arraycopy(o.boundTexture2D, 0, boundTexture2D, 0, MAX_TEXTURE_UNITS);

        vertexArray = o.vertexArray; arrayBuffer = o.arrayBuffer;
        elementArrayBuffer = o.elementArrayBuffer;
    }
}
