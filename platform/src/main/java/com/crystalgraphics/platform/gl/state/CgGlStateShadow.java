package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlStateManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;

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
    /** Storage-buffer binding points a scope restores. */
    public static final int MAX_STORAGE_BINDINGS = 32;
    /** Image units a scope restores: GL's guaranteed eight. */
    public static final int MAX_IMAGE_UNITS = 8;
    /** The indirect-argument targets, in {@link #indirectBuffer}'s order, and the queries that read them. */
    public static final int[] INDIRECT_TARGETS = {CgGL.GL_DRAW_INDIRECT_BUFFER, CgGL.GL_DISPATCH_INDIRECT_BUFFER,
            CgGL.GL_PARAMETER_BUFFER};
    public static final int[] INDIRECT_BINDINGS = {CgGL.GL_DRAW_INDIRECT_BUFFER_BINDING,
            CgGL.GL_DISPATCH_INDIRECT_BUFFER_BINDING, CgGL.GL_PARAMETER_BUFFER_BINDING};
    /** Transform-feedback buffer points a scope restores: GL 3.0's four separate captures. */
    public static final int MAX_FEEDBACK_BINDINGS = 4;
    /** {@link CgGlSlot#TRANSFORM_FEEDBACK}'s point for {@code GL_RASTERIZER_DISCARD}, after the buffer points. */
    public static final int RASTERIZER_DISCARD_POINT = MAX_FEEDBACK_BINDINGS;

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

    // ── Captured at first write ───────────────────────────────────────────────
    /** Per point; a size of 0 is the whole buffer, as {@code glBindBufferBase} binds it and GL reports it. */
    public final int[] storageBuffer = new int[MAX_STORAGE_BINDINGS];
    public final long[] storageOffset = new long[MAX_STORAGE_BINDINGS], storageSize = new long[MAX_STORAGE_BINDINGS];

    /** Per unit, as {@code glBindImageTexture} takes it; a layer of -1 is every layer. */
    public final int[] imageTexture = new int[MAX_IMAGE_UNITS], imageLevel = new int[MAX_IMAGE_UNITS],
            imageLayer = new int[MAX_IMAGE_UNITS], imageAccess = new int[MAX_IMAGE_UNITS],
            imageFormat = new int[MAX_IMAGE_UNITS];

    /** Per {@link #INDIRECT_TARGETS} entry. */
    public final int[] indirectBuffer = new int[INDIRECT_TARGETS.length];

    /** Per point, as {@link #storageBuffer} is. */
    public final int[] feedbackBuffer = new int[MAX_FEEDBACK_BINDINGS];
    public final long[] feedbackOffset = new long[MAX_FEEDBACK_BINDINGS], feedbackSize = new long[MAX_FEEDBACK_BINDINGS];
    public boolean rasterizerDiscard;

    /** The {@link #INDIRECT_TARGETS} index of {@code target}, or -1. */
    public static int indirectIndex(int target) {
        for (int i = 0; i < INDIRECT_TARGETS.length; i++) if (INDIRECT_TARGETS[i] == target) return i;
        return -1;
    }

    /** Copies one binding point of a domain captured at first write. */
    public void copyBinding(CgGlSlot slot, int index, CgGlStateShadow o) {
        switch (slot) {
            case STORAGE_BUFFERS:
                storageBuffer[index] = o.storageBuffer[index];
                storageOffset[index] = o.storageOffset[index];
                storageSize[index] = o.storageSize[index];
                break;
            case IMAGES:
                imageTexture[index] = o.imageTexture[index];
                imageLevel[index] = o.imageLevel[index];
                imageLayer[index] = o.imageLayer[index];
                imageAccess[index] = o.imageAccess[index];
                imageFormat[index] = o.imageFormat[index];
                break;
            case INDIRECT_BUFFERS:
                indirectBuffer[index] = o.indirectBuffer[index];
                break;
            case TRANSFORM_FEEDBACK:
                if (index == RASTERIZER_DISCARD_POINT) {
                    rasterizerDiscard = o.rasterizerDiscard;
                } else {
                    feedbackBuffer[index] = o.feedbackBuffer[index];
                    feedbackOffset[index] = o.feedbackOffset[index];
                    feedbackSize[index] = o.feedbackSize[index];
                }
                break;
            default: throw new IllegalArgumentException(slot + " has no binding points");
        }
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
        drawFbo = o.drawFbo; readFbo = o.readFbo;

        activeTextureUnit = o.activeTextureUnit;
        System.arraycopy(o.boundTexture2D, 0, boundTexture2D, 0, MAX_TEXTURE_UNITS);

        vertexArray = o.vertexArray; arrayBuffer = o.arrayBuffer;
        elementArrayBuffer = o.elementArrayBuffer;

        System.arraycopy(o.storageBuffer, 0, storageBuffer, 0, MAX_STORAGE_BINDINGS);
        System.arraycopy(o.storageOffset, 0, storageOffset, 0, MAX_STORAGE_BINDINGS);
        System.arraycopy(o.storageSize, 0, storageSize, 0, MAX_STORAGE_BINDINGS);
        System.arraycopy(o.imageTexture, 0, imageTexture, 0, MAX_IMAGE_UNITS);
        System.arraycopy(o.imageLevel, 0, imageLevel, 0, MAX_IMAGE_UNITS);
        System.arraycopy(o.imageLayer, 0, imageLayer, 0, MAX_IMAGE_UNITS);
        System.arraycopy(o.imageAccess, 0, imageAccess, 0, MAX_IMAGE_UNITS);
        System.arraycopy(o.imageFormat, 0, imageFormat, 0, MAX_IMAGE_UNITS);
        System.arraycopy(o.indirectBuffer, 0, indirectBuffer, 0, indirectBuffer.length);
        System.arraycopy(o.feedbackBuffer, 0, feedbackBuffer, 0, MAX_FEEDBACK_BINDINGS);
        System.arraycopy(o.feedbackOffset, 0, feedbackOffset, 0, MAX_FEEDBACK_BINDINGS);
        System.arraycopy(o.feedbackSize, 0, feedbackSize, 0, MAX_FEEDBACK_BINDINGS);
        rasterizerDiscard = o.rasterizerDiscard;
    }

    /**
     * The fields where {@code actual} differs from this one, as {@code name tracked=… actual=…}, or
     * {@code null} when they agree. For {@code -Dcrystalgraphics.state.verify}, so it may be slow.
     *
     * <pre>{@code
     * String diff = tracked.differences(readFromDriver);   // "depthMask tracked=true actual=false"
     * }</pre>
     *
     * <p>An {@link #UNKNOWN_BINDING} element buffer is not a disagreement: the shadow never claimed a name.</p>
     */
    public String differences(CgGlStateShadow actual) {
        return differences(actual, -1);
    }

    /**
     * {@link #differences(CgGlStateShadow)} restricted to the fields of the domains in {@code slotMask},
     * one bit per {@link CgGlSlot} ordinal.
     *
     * <pre>{@code
     * before.differences(after, 1 << CgGlSlot.BLEND.ordinal());   // "blendDstRgb tracked=771 actual=1"
     * }</pre>
     */
    public String differences(CgGlStateShadow actual, int slotMask) {
        StringBuilder out = null;
        for (Field f : FIELDS) {
            if ((slotMask & (1 << slotOf(f).ordinal())) == 0) continue;
            try {
                Object mine = f.get(this), theirs = f.get(actual);
                boolean same = Objects.deepEquals(mine, theirs);
                if (same) continue;
                if (f.getName().equals("elementArrayBuffer") && elementArrayBuffer == UNKNOWN_BINDING) continue;
                out = out == null ? new StringBuilder() : out.append(", ");
                out.append(f.getName()).append(" tracked=").append(show(mine)).append(" actual=").append(show(theirs));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return out == null ? null : out.toString();
    }

    private static String show(Object v) {
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        return v instanceof long[] ? Arrays.toString((long[]) v) : String.valueOf(v);
    }

    private static final Field[] FIELDS = Arrays.stream(CgGlStateShadow.class.getFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers())).toArray(Field[]::new);

    static {
        for (Field f : FIELDS) slotOf(f);   // a field added without a domain fails here, not in a diff
    }

    /** The domain a field belongs to, by name. */
    private static CgGlSlot slotOf(Field f) {
        String n = f.getName();
        if (n.startsWith("blend")) return CgGlSlot.BLEND;
        if (n.startsWith("depth")) return CgGlSlot.DEPTH;
        if (n.equals("cullEnabled") || n.equals("cullFace") || n.equals("frontFace")) return CgGlSlot.CULL;
        if (n.startsWith("stencil")) return CgGlSlot.STENCIL;
        if (n.startsWith("alpha")) return CgGlSlot.ALPHA_TEST;
        if (n.equals("colorMaskPacked")) return CgGlSlot.COLOR_MASK;
        if (n.startsWith("viewport")) return CgGlSlot.VIEWPORT;
        if (n.startsWith("scissor")) return CgGlSlot.SCISSOR;
        if (n.startsWith("polygonOffset")) return CgGlSlot.POLYGON_OFFSET;
        if (n.startsWith("polygonMode")) return CgGlSlot.POLYGON_MODE;
        if (n.equals("lineWidth")) return CgGlSlot.LINE_WIDTH;
        if (n.equals("pointSize")) return CgGlSlot.POINT_SIZE;
        if (n.equals("programId")) return CgGlSlot.PROGRAM;
        if (n.endsWith("Fbo")) return CgGlSlot.FBO;
        if (n.equals("activeTextureUnit") || n.equals("boundTexture2D")) return CgGlSlot.TEXTURES;
        if (n.equals("vertexArray") || n.equals("arrayBuffer") || n.equals("elementArrayBuffer")) return CgGlSlot.VERTEX_INPUT;
        if (n.startsWith("storage")) return CgGlSlot.STORAGE_BUFFERS;
        if (n.startsWith("image")) return CgGlSlot.IMAGES;
        if (n.equals("indirectBuffer")) return CgGlSlot.INDIRECT_BUFFERS;
        if (n.startsWith("feedback") || n.equals("rasterizerDiscard")) return CgGlSlot.TRANSFORM_FEEDBACK;
        throw new IllegalStateException("CgGlStateShadow." + n + " belongs to no CgGlSlot; add it to slotOf");
    }
}
