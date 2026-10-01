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
 * {@link CgPropertyValues} changes them. The executor prepares it per raster pass ({@link #pass}) and binds it; a
 * shader reads it through {@code #pragma cg_use palette}, which {@code quad}, {@code curve} and {@code clip} bring.
 *
 * <pre>{@code
 * Quad q = quads.quad().at(x, y).size(w, h).node(scrollContent, fade);
 * }</pre>
 *
 * <ul>
 *   <li>Node worlds are in the recording's root space. A pass into a layer names its view ({@code CgRasterPass.view}):
 *       the layer's origin in root space and the node it moves with, so a moved window carries its layer's
 *       composite and leaves what is inside the layer where it was drawn.</li>
 *   <li>A record names both of its nodes in one float, {@link #pack}: spatial plus
 *       {@value CgSpatialTree#MAX_NODES} times effect.</li>
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
    /** What the buffer holds now: a palette, at which stamp, through which pass view, this frame. */
    @Nullable
    private static CgPalette uploaded;
    private static int uploadedStamp = -1;
    private static long uploadedFrame = -1;
    private static final float[] uploadedPass = new float[7];

    private final CgSpatialTree spatial = new CgSpatialTree();
    private final CgEffectTree effects = new CgEffectTree();
    @Nullable
    private CgPropertyValues values;

    /** Each node into the root as recorded, and as the values change it; each effect node's opacity. */
    private float[] recorded = new float[6 * 16];
    private float[] world = new float[6 * 16];
    private float[] opacity = new float[16];
    /** The values' revision the arrays above were resolved at; -1 before the first. */
    private int resolvedRevision = -1;
    /** Bumped by {@link #copyFrom} and by a resolve: what tells the upload its bytes changed. */
    private int stamp;

    /** The current pass: root space into its target ({@code a b c d tx ty}), and the target's height. */
    private final float[] pre = new float[6];
    private float targetHeight;

    private final float[] local = new float[6];
    private final float[] scratch = new float[6];
    private final float[] scratch2 = new float[6];

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
        int nodes = spatial.count();
        if (recorded.length < nodes * 6) recorded = new float[nodes * 6];
        identity(recorded, 0);
        for (int n = 1; n < nodes; n++) {
            for (int i = 0; i < 6; i++) local[i] = spatial.local(n, i);
            compose(recorded, spatial.parent(n) * 6, local, 0, recorded, n * 6);
        }
    }

    public CgSpatialTree spatial() {
        return spatial;
    }

    public CgEffectTree effects() {
        return effects;
    }

    /**
     * Prepares the palette for one raster pass: its target is {@code targetHeight} pixels tall, and the recording's
     * root space reaches it through a translation by {@code (-originX, -originY)} as recorded, moving with
     * {@code owner} — 0 for a pass into the root's own target. Render thread.
     */
    public void pass(int owner, float originX, float originY, float targetHeight) {
        resolve();
        this.targetHeight = targetHeight;
        // T(-origin) x recorded[owner] x world[owner]^-1: what the owner's movement since recording cancels.
        identity(pre, 0);
        pre[4] = -originX;
        pre[5] = -originY;
        if (owner != 0) {
            compose(pre, 0, recorded, owner * 6, scratch, 0);
            invert(world, owner * 6, scratch2, 0);
            compose(scratch, 0, scratch2, 0, pre, 0);
        }
    }

    /** Uploads the entries for the pass {@link #pass} prepared, unless the buffer already holds them, and binds it. */
    public void bindForDraw() {
        CgShaderBuffer target = buffer();
        long frame = CgFrameRing.frame();
        if (uploaded != this || uploadedStamp != stamp || uploadedFrame != frame || !samePass()) {
            upload(target);
            uploaded = this;
            uploadedStamp = stamp;
            uploadedFrame = frame;
            System.arraycopy(pre, 0, uploadedPass, 0, 6);
            uploadedPass[6] = targetHeight;
        }
        target.bind();
    }

    /**
     * A scissor recorded as {@code box} ({@code x0 y0 x1 y1} at {@code at}) in {@code node}'s space, in the prepared
     * pass's target as GL takes it: {@code x, y, w, h} from the bottom-left, written to {@code out}.
     */
    public void scissorOf(int node, float[] box, int at, int[] out) {
        compose(pre, 0, world, node * 6, scratch, 0);
        float a = scratch[0], b = scratch[1], c = scratch[2], d = scratch[3], tx = scratch[4], ty = scratch[5];
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float x = (corner & 1) == 0 ? box[at] : box[at + 2];
            float y = (corner & 2) == 0 ? box[at + 1] : box[at + 3];
            float px = a * x + c * y + tx, py = b * x + d * y + ty;
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        int x0 = (int) Math.floor(minX), x1 = (int) Math.ceil(maxX);
        int top = (int) Math.floor(minY), bottom = (int) Math.ceil(maxY);
        out[0] = x0;
        out[1] = (int) targetHeight - bottom;
        out[2] = Math.max(0, x1 - x0);
        out[3] = Math.max(0, bottom - top);
    }

    /** Each node's affine into the root, and each effect node's opacity, as the values stand now. */
    private void resolve() {
        int revision = values == null ? 0 : values.revision();
        if (revision == resolvedRevision) return;
        resolvedRevision = revision;
        stamp++;
        int nodes = spatial.count();
        if (world.length < nodes * 6) world = new float[nodes * 6];
        if (values == null) {
            System.arraycopy(recorded, 0, world, 0, nodes * 6);
        } else {
            identity(world, 0);
            for (int n = 1; n < nodes; n++) {
                for (int i = 0; i < 6; i++) local[i] = spatial.local(n, i);
                values.apply(n, local, 0, local, 0);
                compose(world, spatial.parent(n) * 6, local, 0, world, n * 6);
            }
        }
        int groups = effects.count();
        if (opacity.length < groups) opacity = new float[Math.max(groups, opacity.length * 2)];
        opacity[0] = 1f;
        for (int n = 1; n < groups; n++) {
            float own = values == null ? effects.opacity(n) : values.opacityOf(n, effects.opacity(n));
            opacity[n] = opacity[effects.parent(n)] * own;
        }
    }

    private void upload(CgShaderBuffer target) {
        int entries = Math.max(spatial.count(), effects.count());
        CgBufferWriter w = target.beginWrite(entries);
        float[] m = scratch;
        for (int n = 0; n < entries; n++) {
            if (n < spatial.count()) {
                compose(pre, 0, world, n * 6, m, 0);
            } else {
                identity(m, 0);
            }
            float a = m[0], b = m[1], c = m[2], d = m[3], tx = m[4], ty = m[5];
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
            w.beginRecord()
                    .vec4("toTarget0", a, c, tx, 0f)
                    .vec4("toTarget1", b, d, ty, 0f)
                    .vec4("fromFragment0", ia, -ic, ic * targetHeight + itx, 0f)
                    .vec4("fromFragment1", ib, -id, id * targetHeight + ity, 0f)
                    .vec4("effect", n < effects.count() ? opacity[n] : 1f, 0f, 0f, 0f);
            target.endRecord();
        }
        target.endWrite();
    }

    private boolean samePass() {
        for (int i = 0; i < 6; i++) if (uploadedPass[i] != pre[i]) return false;
        return uploadedPass[6] == targetHeight;
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

    private static void invert(float[] m, int o, float[] out, int r) {
        float a = m[o], b = m[o + 1], c = m[o + 2], d = m[o + 3], tx = m[o + 4], ty = m[o + 5];
        float det = a * d - b * c;
        if (Math.abs(det) < 1e-12f) {
            identity(out, r);
            return;
        }
        out[r] = d / det;
        out[r + 1] = -b / det;
        out[r + 2] = -c / det;
        out[r + 3] = a / det;
        out[r + 4] = (c * ty - d * tx) / det;
        out[r + 5] = (b * tx - a * ty) / det;
    }

    private static void identity(float[] m, int o) {
        m[o] = 1f;
        m[o + 1] = 0f;
        m[o + 2] = 0f;
        m[o + 3] = 1f;
        m[o + 4] = 0f;
        m[o + 5] = 0f;
    }
}
