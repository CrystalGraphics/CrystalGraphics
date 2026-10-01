package com.crystalgraphics.render.property;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;

import javax.annotation.Nullable;

/**
 * A recording's property trees as a frame executes them: every spatial node's affine into a raster pass's target,
 * with the inverse a clip reads from {@code gl_FragCoord}, and every effect node's opacity — the recorded values as a
 * {@link CgPropertyValues} changes them. The executor uploads and binds one per raster pass; a shader reads it
 * through {@code #pragma cg_use palette}, which {@code quad} and {@code curve} bring with them.
 *
 * <pre>{@code
 * Quad q = quads.quad().at(x, y).size(w, h).node(CgPalette.pack(scrollContent, fade));
 * }</pre>
 *
 * <ul>
 *   <li>A record names both of its nodes in one float, {@link #pack}: spatial plus {@value CgSpatialTree#MAX_NODES}
 *       times effect.</li>
 *   <li>Node 0 is the pass's own space at opacity 1, and a shader never reads its entry: the root costs nothing.</li>
 *   <li>A frame keeps its own copy of the trees ({@link #copyFrom}), so the recording may be reset once built.</li>
 * </ul>
 */
public final class CgPalette {

    /** The macro a shader reads an entry through. */
    public static final String MACRO_NAME = "PALETTE_DATA";

    private static final CgBufferFormat FORMAT = CgBufferFormat.builder("PaletteEntry", CgBufferFormat.MemoryLayout.STD430)
            .vec4("toTarget0").vec4("toTarget1")
            .vec4("fromFragment0").vec4("fromFragment1")
            .vec4("effect")
            .build();

    private static CgShaderBuffer buffer;
    /** What the buffer holds now: a palette, through which view, at which values, this frame. */
    @Nullable
    private static CgPalette uploaded;
    private static int uploadedStamp = -1;
    private static long uploadedFrame = -1;
    private static final float[] uploadedView = new float[7];

    private final CgSpatialTree spatial = new CgSpatialTree();
    private final CgEffectTree effects = new CgEffectTree();
    @Nullable
    private CgPropertyValues values;

    /** Each node into the recording's root, as the values change it; each effect node's opacity. */
    private float[] world = new float[6 * 16];
    private float[] opacity = new float[16];
    private final float[] local = new float[6];
    private final float[] toTarget = new float[6];
    /** The values' revision the arrays above were resolved at; -1 before the first. */
    private int resolvedRevision = -1;
    /** Bumped by {@link #copyFrom} and by a resolve: what tells the upload its bytes changed. */
    private int stamp;

    /** The buffer every quad and curve shader reads entries from. */
    public static CgShaderBuffer buffer() {
        if (buffer == null) {
            buffer = CgShaderBufferRegistry.get()
                    .getOrCreateInternal("CgPalette", FORMAT, CgBindingPoints.PALETTE, CgBufferLifetime.FRAME);
        }
        return buffer;
    }

    /** A record's {@code node} field: both of its nodes in one float, exact for every node a tree allows. */
    public static float pack(int spatial, int effect) {
        return spatial + effect * (float) CgSpatialTree.MAX_NODES;
    }

    /** Makes this the palette of a recording with these trees, changed by {@code values} (null for none). */
    public void copyFrom(CgSpatialTree spatial, CgEffectTree effects, @Nullable CgPropertyValues values) {
        this.spatial.copyFrom(spatial);
        this.effects.copyFrom(effects);
        this.values = values;
        resolvedRevision = -1;
        stamp++;
    }

    public CgSpatialTree spatial() {
        return spatial;
    }

    public CgEffectTree effects() {
        return effects;
    }

    /** Whether nothing but the roots: every record reads node 0 and no shader reads an entry. */
    public boolean trivial() {
        return spatial.count() == 1 && effects.count() == 1;
    }

    /**
     * Uploads this palette for a pass whose target is {@code targetHeight} pixels tall, mapping the recording's root
     * into the target through {@code view} ({@code a b c d tx ty}, null for none), and binds it. Render thread.
     */
    public void bindForDraw(@Nullable float[] view, float targetHeight) {
        CgShaderBuffer target = buffer();
        resolve();
        long frame = CgFrameRing.frame();
        if (uploaded != this || uploadedStamp != stamp || uploadedFrame != frame || !sameView(view, targetHeight)) {
            upload(target, view, targetHeight);
            uploaded = this;
            uploadedStamp = stamp;
            uploadedFrame = frame;
            rememberView(view, targetHeight);
        }
        target.bind();
    }

    /** Each node's affine into the root, and each effect node's opacity, as the values stand now. */
    private void resolve() {
        int revision = values == null ? 0 : values.revision();
        if (revision == resolvedRevision) return;
        resolvedRevision = revision;
        stamp++;
        int nodes = spatial.count();
        if (world.length < nodes * 6) world = new float[Math.max(nodes, world.length / 3) * 6];
        world[0] = 1f;
        world[1] = 0f;
        world[2] = 0f;
        world[3] = 1f;
        world[4] = 0f;
        world[5] = 0f;
        for (int n = 1; n < nodes; n++) {
            float[] l = recordedLocal(n);
            compose(world, spatial.parent(n) * 6, l, 0, world, n * 6);
        }
        int groups = effects.count();
        if (opacity.length < groups) opacity = new float[Math.max(groups, opacity.length * 2)];
        opacity[0] = 1f;
        for (int n = 1; n < groups; n++) {
            float own = values == null ? effects.opacity(n) : values.opacityOf(n, effects.opacity(n));
            opacity[n] = opacity[effects.parent(n)] * own;
        }
    }

    private float[] recordedLocal(int node) {
        for (int i = 0; i < 6; i++) local[i] = spatial.local(node, i);
        if (values != null) values.apply(node, local, 0, local, 0);
        return local;
    }

    private void upload(CgShaderBuffer target, @Nullable float[] view, float targetHeight) {
        int entries = Math.max(spatial.count(), effects.count());
        CgBufferWriter w = target.beginWrite(entries);
        float[] m = toTarget;
        for (int n = 0; n < entries; n++) {
            float a = 1f, b = 0f, c = 0f, d = 1f, tx = 0f, ty = 0f;
            if (n < spatial.count()) {
                if (view != null) {
                    compose(view, 0, world, n * 6, m, 0);
                } else {
                    System.arraycopy(world, n * 6, m, 0, 6);
                }
                a = m[0];
                b = m[1];
                c = m[2];
                d = m[3];
                tx = m[4];
                ty = m[5];
            }
            // gl_FragCoord (bottom-up) to the node's space: the target's rows flipped, then the affine inverted.
            float det = a * d - b * c;
            float ia = 0f, ib = 0f, ic = 0f, id = 0f, itx = 0f, ity = 0f;
            if (Math.abs(det) > 1e-12f) {
                ia = d / det;
                ib = -b / det;
                ic = -c / det;
                id = a / det;
                itx = (c * ty - d * tx) / det;
                ity = (b * tx - a * ty) / det;
            }
            float fa = ia, fb = ib, fc = -ic, fd = -id, ftx = ic * targetHeight + itx, fty = id * targetHeight + ity;
            w.beginRecord()
                    .vec4("toTarget0", a, c, tx, 0f)
                    .vec4("toTarget1", b, d, ty, 0f)
                    .vec4("fromFragment0", fa, fc, ftx, 0f)
                    .vec4("fromFragment1", fb, fd, fty, 0f)
                    .vec4("effect", n < effects.count() ? opacity[n] : 1f, 0f, 0f, 0f);
            target.endRecord();
        }
        target.endWrite();
    }

    /** {@code out[o..] = first ∘ second}: a point through {@code second}, then {@code first}. */
    static void compose(float[] first, int f, float[] second, int s, float[] out, int o) {
        float wa = first[f], wb = first[f + 1], wc = first[f + 2], wd = first[f + 3], wtx = first[f + 4], wty = first[f + 5];
        float la = second[s], lb = second[s + 1], lc = second[s + 2], ld = second[s + 3], ltx = second[s + 4], lty = second[s + 5];
        out[o] = wa * la + wc * lb;
        out[o + 1] = wb * la + wd * lb;
        out[o + 2] = wa * lc + wc * ld;
        out[o + 3] = wb * lc + wd * ld;
        out[o + 4] = wa * ltx + wc * lty + wtx;
        out[o + 5] = wb * ltx + wd * lty + wty;
    }

    private static boolean sameView(@Nullable float[] view, float targetHeight) {
        if (uploadedView[6] != targetHeight) return false;
        if (view == null) return uploadedView[0] == 1f && uploadedView[1] == 0f && uploadedView[2] == 0f
                && uploadedView[3] == 1f && uploadedView[4] == 0f && uploadedView[5] == 0f;
        for (int i = 0; i < 6; i++) if (uploadedView[i] != view[i]) return false;
        return true;
    }

    private static void rememberView(@Nullable float[] view, float targetHeight) {
        if (view == null) {
            uploadedView[0] = 1f;
            uploadedView[1] = 0f;
            uploadedView[2] = 0f;
            uploadedView[3] = 1f;
            uploadedView[4] = 0f;
            uploadedView[5] = 0f;
        } else {
            System.arraycopy(view, 0, uploadedView, 0, 6);
        }
        uploadedView[6] = targetHeight;
    }
}
