package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * What every draw of one pass reads in {@code CgFrameBlock}: camera, projection, time, resolution, depth
 * convention and world origin. One per pass, where the frame block used to be one global that every caller saved and restored around
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
 *   <li>{@link #camera} is what {@code CG_CAMERA_WORLD_POS} reads; it is not derived from {@link #view}, so a pass
 *       sets both. A camera-relative pass puts the camera at the origin and its absolute position in
 *       {@link #origin}.</li>
 *   <li>A value: {@link #capture} copies it, so changing it afterwards changes no captured pass.</li>
 * </ul>
 */
public final class CgPassConstants {

    /** The GLSL block, declared by {@code cg_env.glsl}. */
    public static final String BLOCK_NAME = "CgFrameBlock";

    /** {@code CgFrameBlock} in std140: view, projection, time, resolution (+2 pad), camera, depth parameters, origin. */
    public static final CgBufferFormat FORMAT = CgBufferFormat
            .builder(BLOCK_NAME, CgBufferFormat.MemoryLayout.STD140)
            .mat4("cg_ViewMatrix")
            .mat4("cg_ProjMatrix")
            .vec4("cg_Time")
            .vec2("cg_Resolution")
            .vec4("cg_CameraPos")
            .vec4("cg_DepthParams")
            .vec4("cg_WorldOrigin")
            .build();

    public static final int FLOATS = 52;

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
    private float originX;
    private float originY;
    private float originZ;

    private final float[] packed = new float[FLOATS];
    private final Vector3f eye = new Vector3f();

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

    /** Puts the camera where {@link #view} sees from, for an affine view: a pass with no other camera to give. */
    public CgPassConstants cameraFromView() {
        view.originAffine(eye);
        return camera(eye.x, eye.y, eye.z);
    }

    /** The depth convention of the target this pass draws into: what {@code cg_LinearEyeDepth} reads. */
    public CgPassConstants depth(boolean reversed, boolean zeroToOne) {
        this.depthReversed = reversed;
        this.depthZeroToOne = zeroToOne;
        return this;
    }

    /** Where world space's origin is in absolute coordinates: what {@code CG_ABSOLUTE_WORLD_POS} adds. */
    public CgPassConstants origin(double x, double y, double z) {
        this.originX = (float) x;
        this.originY = (float) y;
        this.originZ = (float) z;
        return this;
    }

    /**
     * Takes every value from a block laid out as {@link #write} lays it out: what an immediate draw does with the
     * frame block a caller already prepared. {@link #write} reproduces the same floats.
     */
    public CgPassConstants read(float[] block, int at) {
        view.set(block, at);
        projection.set(block, at + 16);
        time = block[at + 33];
        width = block[at + 36];
        height = block[at + 37];
        cameraX = block[at + 40];
        cameraY = block[at + 41];
        cameraZ = block[at + 42];
        depthReversed = block[at + 44] != 0f;
        depthZeroToOne = block[at + 45] != 0f;
        originX = block[at + 48];
        originY = block[at + 49];
        originZ = block[at + 50];
        return this;
    }

    /** The target height a packed block was written with: what a pass's palette flips {@code gl_FragCoord} by. */
    public static float height(float[] block) {
        return block[37];
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
        out[at + 48] = originX;
        out[at + 49] = originY;
        out[at + 50] = originZ;
        out[at + 51] = 0f;
    }

    /** Snapshots the block into {@code table} at the frame block's binding, and answers its id. */
    public int capture(CgBindingTable table) {
        write(packed, 0);
        return table.begin().block(CgBindingPoints.FRAME_DATA_UBO, packed, 0, FLOATS).end();
    }
}
