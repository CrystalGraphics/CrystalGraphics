package com.crystalgraphics.gl.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * The frame's rounded clips, as a table a fragment shader reads: a quad, curve or glyph names an entry and is drawn
 * only inside it, antialiased, with no offscreen layer. What a rounded {@code overflow: hidden} costs is one table
 * row, where a mask layer costs two targets, a clear each, a multiply and a composite.
 *
 * <p>An entry is a rounded box in its own space -- a rect with per-corner elliptical radii (top-left, top-right,
 * bottom-right, bottom-left), less an optional border band -- together with the pose that put that space on screen,
 * so a rotated, skewed or scaled box clips exactly. An entry may name a parent; a draw is clipped by the whole
 * chain, up to {@link #MAX_DEPTH} entries deep.</p>
 *
 * <pre>{@code
 * CgClipTable clips = recording.clips();   // one per recording: its chunks name its entries
 *
 * // A 200x120 box under `pose`, 8px corners, a 1px border cut away.
 * int panel = clips.add(0, pose, targetHeight, 0, 0, 200, 120, EIGHTS, EIGHTS, ONES);
 * quadRenderer.quad().at(x, y).size(w, h).clip(panel).color(argb).submit();
 *
 * // A card inside it, clipped by both.
 * int card = clips.add(panel, cardPose, targetHeight, 0, 0, 80, 40, FOURS, FOURS, null);
 *
 * // A viewport under spatial node `scroll`, its pose in the node's space: it moves with the node.
 * int view = clips.add(0, scroll, poseInNode, 0f, 0, 0, 300, 200, FOURS, FOURS, null);
 * }</pre>
 *
 * <p>In a material:</p>
 *
 * <pre>{@code
 * #pragma cg_use quad
 * #pragma cg_use clip
 * ...
 * fragColor.a *= CG_CLIP_QUAD_COVERAGE;     // straight alpha
 * fragColor   *= CG_CLIP_QUAD_COVERAGE;     // premultiplied
 * }</pre>
 *
 * <p>Easy to get wrong:</p>
 * <ul>
 *   <li>An entry belongs to its table, and a table to its recording: an index means nothing in another recording,
 *       and nothing once the recording is reset.</li>
 *   <li>{@code pose} maps the box's space to the bound target's pixels, top-down, and {@code targetHeight} is that
 *       target's height. A draw into another target (an offscreen layer) names no entry; the clip applies when
 *       that target is composited back. An entry under a spatial node maps into the node's space instead, and the
 *       palette places it.</li>
 *   <li>{@link #add} answers -1 when the chain would pass {@link #MAX_DEPTH} or the pose collapses the box: clip
 *       that one with a layer.</li>
 *   <li>A material that does not multiply by the coverage draws past the corners, silently.</li>
 *   <li>Filled on the recording's thread with no GL; uploaded and bound by the executor per raster pass. Entry 0 is
 *       "no clip" and is never read.</li>
 * </ul>
 */
public final class CgClipTable {

    /** The macro a shader's GLSL reads an entry through; {@code #pragma cg_use clip} declares it. */
    public static final String MACRO_NAME = "CLIP_DATA";

    /** How many entries one draw can be clipped by. {@code clip.glsl}'s {@code CG_CLIP_MAX_DEPTH} must agree. */
    public static final int MAX_DEPTH = 4;

    private static final CgBufferFormat FORMAT = CgBufferFormat.builder("ClipEntry", CgBufferFormat.MemoryLayout.STD430)
            .vec4("toLocal0").vec4("toLocal1")
            .vec4("outer").vec4("outerRx").vec4("outerRy")
            .vec4("inner").vec4("innerRx").vec4("innerRy")
            .vec4("space")
            .build();

    private static final int FLOATS = 36;

    /** A UI shape's reconstruction width for an edge off the pixel grid (CrystalGUI's gui_box); 1 on it. */
    private static final float ROTATED_RAMP = 1.5f;

    /** Lazy, like the quad renderer's: the binding points exist only once a context has initialised. */
    private static CgShaderBuffer buffer;
    /** The table the buffer holds now, as of which version and frame: what lets a pass skip the upload. */
    private static CgClipTable uploaded;
    private static int uploadedVersion = -1;
    private static long uploadedFrame = -1;

    private float[] entries = new float[FLOATS * 16];
    private int[] parents = new int[16];
    private int[] depths = new int[16];
    private int count = 1;
    private int version;

    private final float[] innerRx = new float[4], innerRy = new float[4];

    /** The table's buffer, for {@code #pragma cg_use clip}. */
    public static CgShaderBuffer buffer() {
        if (buffer == null) {
            buffer = CgShaderBufferRegistry.get()
                    .getOrCreateInternal("CgClipTable", FORMAT, CgBindingPoints.CLIP_TABLE, CgBufferLifetime.FRAME);
        }
        return buffer;
    }

    /**
     * Adds an entry for this frame. The quad and vector renderers upload the table when they next flush, so every
     * draw submitted after this call can name it.
     *
     * @param parent       the entry this one is inside, 0 for none
     * @param pose         the box's space to the bound target's pixels, top-down; only its 2D affine part is read
     * @param targetHeight the bound target's height in pixels
     * @param rx           horizontal corner radii in the box's space: top-left, top-right, bottom-right, bottom-left
     * @param ry           vertical corner radii, same order
     * @param border       the band cut from the edge, left, top, right, bottom, as a mask with a transparent border
     *                     reveals; null for none
     * @return the entry, or -1 when the chain would pass {@link #MAX_DEPTH} or the pose collapses the box
     */
    public int add(int parent, Matrix4f pose, float targetHeight, float x0, float y0, float x1, float y1,
                   float[] rx, float[] ry, float[] border) {
        return add(parent, 0, pose, targetHeight, x0, y0, x1, y1, rx, ry, border);
    }

    /**
     * As {@link #add(int, Matrix4f, float, float, float, float, float, float[], float[], float[])}, for a box under
     * spatial node {@code node}: {@code pose} maps the box's space into the node's, and the entry moves with the node.
     * Node 0 is the target's own space, top-down, {@code targetHeight} tall; any other ignores the height.
     */
    public int add(int parent, int node, Matrix4f pose, float targetHeight, float x0, float y0, float x1, float y1,
                   float[] rx, float[] ry, float[] border) {
        int depth = (parent > 0 ? depths[parent] : 0) + 1;
        float a = pose.m00(), b = pose.m10(), c = pose.m01(), d = pose.m11();
        float det = a * d - b * c;
        if (depth > MAX_DEPTH || Math.abs(det) < 1e-12f) return -1;
        if (count == parents.length) {
            entries = Arrays.copyOf(entries, entries.length * 2);
            parents = Arrays.copyOf(parents, parents.length * 2);
            depths = Arrays.copyOf(depths, depths.length * 2);
        }
        int o = count * FLOATS;
        float tx = pose.m30(), ty = pose.m31();
        if (node == 0) {
            // gl_FragCoord to the box's space: y flipped to the target's top-down rows, then the pose inverted.
            float e = targetHeight - ty;
            entries[o] = d / det;
            entries[o + 1] = b / det;
            entries[o + 2] = (-d * tx - b * e) / det;
            entries[o + 4] = -c / det;
            entries[o + 5] = -a / det;
            entries[o + 6] = (c * tx + a * e) / det;
        } else {
            // The node's space to the box's: the pose inverted. The palette maps gl_FragCoord into the node.
            entries[o] = d / det;
            entries[o + 1] = -b / det;
            entries[o + 2] = (b * ty - d * tx) / det;
            entries[o + 4] = -c / det;
            entries[o + 5] = a / det;
            entries[o + 6] = (c * tx - a * ty) / det;
        }
        entries[o + 3] = parent;
        entries[o + 7] = b == 0f && c == 0f ? 1f : ROTATED_RAMP;
        entries[o + 32] = node;
        put(o + 8, x0, y0, x1, y1, rx, ry);
        if (border != null && (border[0] > 0f || border[1] > 0f || border[2] > 0f || border[3] > 0f)) {
            // A bordered shape's own inner edge: the rect inset by each side, its radii shrunk by the sides they meet.
            float bl = border[0], bt = border[1], br = border[2], bb = border[3];
            float halfW = Math.max((x1 - x0 - bl - br) * 0.5f, 0f), halfH = Math.max((y1 - y0 - bt - bb) * 0.5f, 0f);
            float cx = (x0 + x1 + bl - br) * 0.5f, cy = (y0 + y1 + bt - bb) * 0.5f;
            innerRx[0] = Math.max(rx[0] - bl, 0f);
            innerRx[1] = Math.max(rx[1] - br, 0f);
            innerRx[2] = Math.max(rx[2] - br, 0f);
            innerRx[3] = Math.max(rx[3] - bl, 0f);
            innerRy[0] = Math.max(ry[0] - bt, 0f);
            innerRy[1] = Math.max(ry[1] - bt, 0f);
            innerRy[2] = Math.max(ry[2] - bb, 0f);
            innerRy[3] = Math.max(ry[3] - bb, 0f);
            put(o + 20, cx - halfW, cy - halfH, cx + halfW, cy + halfH, innerRx, innerRy);
        } else {
            // x1 < x0: no inner edge.
            Arrays.fill(innerRx, 0f);
            put(o + 20, 0f, 0f, -1f, -1f, innerRx, innerRx);
        }
        parents[count] = parent;
        depths[count] = depth;
        version++;
        return count++;
    }

    /** The entry {@code entry} was added inside, 0 for none. */
    public int parent(int entry) {
        return entry > 0 && entry < count ? parents[entry] : 0;
    }

    /** Makes this a copy of {@code other}: what a built frame keeps, so its recording can be reset at once. */
    public void copyFrom(CgClipTable other) {
        if (entries.length < other.entries.length) {
            entries = new float[other.entries.length];
            parents = new int[other.parents.length];
            depths = new int[other.depths.length];
        }
        System.arraycopy(other.entries, 0, entries, 0, other.count * FLOATS);
        System.arraycopy(other.parents, 0, parents, 0, other.count);
        System.arraycopy(other.depths, 0, depths, 0, other.count);
        count = other.count;
        version++;
    }

    /** Empties it for a new recording: entry 0 alone. */
    public void reset() {
        count = 1;
        version++;
    }

    /**
     * Uploads this table unless the buffer already holds it as it is, this frame, and binds it: what an executor does
     * before a pass's draws. Render thread. A device binds every block a program declares whether or not a draw reads
     * it, and a frame-ring binding from another frame is not this frame's storage, so each frame uploads afresh.
     */
    public void bindForDraw() {
        CgShaderBuffer target = buffer();
        long frame = CgFrameRing.frame();
        if (uploaded != this || uploadedVersion != version || uploadedFrame != frame) {
            upload(target);
            uploaded = this;
            uploadedVersion = version;
            uploadedFrame = frame;
        }
        target.bind();
    }

    private void put(int o, float x0, float y0, float x1, float y1, float[] rx, float[] ry) {
        entries[o] = x0;
        entries[o + 1] = y0;
        entries[o + 2] = x1;
        entries[o + 3] = y1;
        System.arraycopy(rx, 0, entries, o + 4, 4);
        System.arraycopy(ry, 0, entries, o + 8, 4);
    }

    /** The whole table: the binding a draw reads holds every entry of its recording. */
    private void upload(CgShaderBuffer target) {
        CgBufferWriter w = target.beginWrite(count);
        for (int i = 0; i < count; i++) {
            int o = i * FLOATS;
            w.beginRecord()
                    .vec4("toLocal0", entries[o], entries[o + 1], entries[o + 2], entries[o + 3])
                    .vec4("toLocal1", entries[o + 4], entries[o + 5], entries[o + 6], entries[o + 7])
                    .vec4("outer", entries[o + 8], entries[o + 9], entries[o + 10], entries[o + 11])
                    .vec4("outerRx", entries[o + 12], entries[o + 13], entries[o + 14], entries[o + 15])
                    .vec4("outerRy", entries[o + 16], entries[o + 17], entries[o + 18], entries[o + 19])
                    .vec4("inner", entries[o + 20], entries[o + 21], entries[o + 22], entries[o + 23])
                    .vec4("innerRx", entries[o + 24], entries[o + 25], entries[o + 26], entries[o + 27])
                    .vec4("innerRy", entries[o + 28], entries[o + 29], entries[o + 30], entries[o + 31])
                    .vec4("space", entries[o + 32], 0f, 0f, 0f);
            target.endRecord();
        }
        target.endWrite();
    }
}
