package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.CgBindingPoints;
import org.joml.Matrix4f;

/**
 * What every draw of one pass reads in {@code CgFrameBlock}: camera, projection, time, resolution and depth
 * convention. One per pass, where the frame block used to be one global that every caller saved and restored around
 * itself; a UI layer, a preview and the world each carry their own. The GLSL names are unchanged.
 *
 * <pre>{@code
 * CgPassConstants c = new CgPassConstants();
 * c.projection.setOrtho(0, w, h, 0, -1, 1);
 * c.resolution(w, h).time(frameSeconds);
 * int block = c.capture(table);          // bound once, at the start of the pass
 * }</pre>
 *
 * <ul>
 *   <li>{@link #time} is the compositor's frame time, never a clock read while recording: a document's animations
 *       and the compositor's must agree.</li>
 *   <li>{@link #cameraX} and its siblings are what {@code CG_CAMERA_WORLD_POS} reads; they are not derived from
 *       {@link #view}, so a world pass sets both.</li>
 *   <li>A value: {@link #capture} copies it, so changing it afterwards changes no captured pass.</li>
 * </ul>
 */
public final class CgPassConstants {

    /** {@code CgFrameBlock} in std140: view, projection, time, resolution (+2 pad), camera, depth parameters. */
    public static final int FLOATS = 48;

    public final Matrix4f view = new Matrix4f();
    public final Matrix4f projection = new Matrix4f();

    private float time;
    private float width;
    private float height;
    private float cameraX;
    private float cameraY;
    private float cameraZ;
    private boolean depthReversed;
    private boolean depthZeroToOne;

    private final float[] packed = new float[FLOATS];

    /** Seconds; the block carries {@code (t/20, t, 2t, 3t)}. */
    public CgPassConstants time(float seconds) {
        this.time = seconds;
        return this;
    }

    /** The target's size in pixels. */
    public CgPassConstants resolution(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** The camera's world position. */
    public CgPassConstants camera(float x, float y, float z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return this;
    }

    /** The depth convention of the target this pass draws into: what {@code cg_LinearEyeDepth} reads. */
    public CgPassConstants depth(boolean reversed, boolean zeroToOne) {
        this.depthReversed = reversed;
        this.depthZeroToOne = zeroToOne;
        return this;
    }

    /** Writes the block's {@value #FLOATS} floats into {@code out} at {@code at}. */
    public void write(float[] out, int at) {
        view.get(out, at);
        projection.get(out, at + 16);
        out[at + 32] = time / 20f;
        out[at + 33] = time;
        out[at + 34] = time * 2f;
        out[at + 35] = time * 3f;
        out[at + 36] = width;
        out[at + 37] = height;
        out[at + 38] = 0f;
        out[at + 39] = 0f;
        out[at + 40] = cameraX;
        out[at + 41] = cameraY;
        out[at + 42] = cameraZ;
        out[at + 43] = 1f;
        out[at + 44] = depthReversed ? 1f : 0f;
        out[at + 45] = depthZeroToOne ? 1f : 0f;
        out[at + 46] = 0f;
        out[at + 47] = 0f;
    }

    /** Snapshots the block into {@code table} at the frame block's binding, and answers its id. */
    public int capture(CgBindingTable table) {
        write(packed, 0);
        return table.begin().block(CgBindingPoints.FRAME_DATA_UBO, packed, 0, FLOATS).end();
    }
}
