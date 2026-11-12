package com.crystalgraphics.render.draw;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLayout;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * What every draw of one pass reads, as two blocks (Unreal's view uniform buffer, and the world's state beside it):
 * {@code CgViewBlock}, the camera, projection, time, resolution, depth convention, origin, history and the target's
 * encoding, made a firing; and {@code CgEnvironmentBlock}, the sun, daylight and fog, which every pass of a frame
 * shares, so the binding table uploads it once. A UI layer, a preview and the world each carry their own.
 *
 * <pre>{@code
 * CgPassConstants c = new CgPassConstants();
 * CgCamera.orthographicMatrix(c.projection, 0, w, h, 0, -1, 1);   // the engine's convention, as every projection
 * c.resolution(w, h).time(frameSeconds);
 * int blocks = c.capture(table);         // both blocks, bound once at the start of the pass
 * }</pre>
 *
 * <ul>
 *   <li>{@link #time} is the compositor's frame time, never a clock read while recording: a document's animations
 *       and the compositor's must agree.</li>
 *   <li>{@link #camera} is what {@code CG_CAMERA_WORLD_POS} reads; it is not derived from {@link #view}, so a pass
 *       sets both. A camera-relative pass puts the camera at the origin and its absolute position in
 *       {@link #origin}.</li>
 *   <li>{@link #sun} starts high in the south-west at full daylight and {@link #fog} off, so a pass with no world
 *       (a preview, a harness scene) lights and fogs nothing oddly.</li>
 *   <li>A value: {@link #capture} copies it, so changing it afterwards changes no captured pass.</li>
 * </ul>
 */
public final class CgPassConstants {

    /** The GLSL blocks, declared by {@code cg_env.glsl}. */
    public static final String VIEW_BLOCK = "CgViewBlock", ENVIRONMENT_BLOCK = "CgEnvironmentBlock";

    /**
     * {@code CgViewBlock} in std140: view, projection, time, resolution (+2 pad), camera, depth parameters, origin, the
     * previous frame's view-projection, and the frame's index, history validity and target encoding.
     */
    public static final CgBufferLayout VIEW_FORMAT = CgBufferLayout
            .builder(VIEW_BLOCK, CgBufferLayout.MemoryLayout.STD140)
            .mat4("cg_ViewMatrix")
            .mat4("cg_ProjMatrix")
            .vec4("cg_Time")
            .vec2("cg_Resolution")
            .vec4("cg_CameraPos")
            .vec4("cg_DepthParams")
            .vec4("cg_WorldOrigin")
            .mat4("cg_PrevViewProj")
            .vec4("cg_FrameParams")
            .build();

    /** {@code CgEnvironmentBlock} in std140: the sun with the daylight, the fog colour, the fog's reach and the sun's share. */
    public static final CgBufferLayout ENVIRONMENT_FORMAT = CgBufferLayout
            .builder(ENVIRONMENT_BLOCK, CgBufferLayout.MemoryLayout.STD140)
            .vec4("cg_SunDirection")
            .vec4("cg_FogColor")
            .vec4("cg_FogParams")
            .build();

    /** Floats a packed pass holds: the view block's, then from {@link #VIEW_FLOATS} the environment block's. */
    public static final int VIEW_FLOATS = 72, ENVIRONMENT_FLOATS = 12, FLOATS = VIEW_FLOATS + ENVIRONMENT_FLOATS;

    /** {@link #sun}'s direction until set: high, toward +x and +z. */
    public static final float SUN_X = 0.3f, SUN_Y = 0.906f, SUN_Z = 0.3f;
    /** {@link #sunShare}'s until set: {@code crystalgraphics.world.sunShare}'s default. */
    private static final float SUN_SHARE = 0.6f;

    public final Matrix4f view = new Matrix4f();
    public final Matrix4f projection = new Matrix4f();
    /** Where this pass's camera-relative points were on screen last frame: {@link #history}'s. */
    private final Matrix4f previous = new Matrix4f();

    private float time;
    private float width;
    private float height;
    private float cameraX;
    private float cameraY;
    private float cameraZ;
    /** Where {@link #encodedTarget} sits in a packed pass: {@code cg_FrameParams.w}. */
    public static final int ENCODED_TARGET = 71;
    /** Where the depth convention sits in a packed pass: {@code cg_DepthParams.x}, 1 where reversed. */
    private static final int DEPTH_REVERSED = 44;

    private boolean linearScene, encodedTarget;
    private float sceneGlow = 1f;
    private float originX;
    private float originY;
    private float originZ;
    private float sunX = SUN_X, sunY = SUN_Y, sunZ = SUN_Z, daylight = 1f, sunShare = SUN_SHARE;
    private float fogRed, fogGreen, fogBlue, fogStart, fogEnd;
    private boolean fog;
    private int frameIndex;
    private boolean historyValid, netherLighting;

    private final float[] packed = new float[FLOATS];
    private final Vector3f eye = new Vector3f();

    /**
     * Seconds; the block carries {@code (t/20, t, 2t, 3t)}. Narrowed to the shader's float here and nowhere else: what
     * wraps, and how, is the material system's.
     */
    public CgPassConstants time(double seconds) {
        this.time = (float) seconds;
        return this;
    }

    /** The target's width in pixels. */
    public float width() {
        return width;
    }

    /** The target's height in pixels. */
    public float height() {
        return height;
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

    /**
     * The view block as {@code camera} sees: its view, projection, time and position, its resolution or else
     * {@code width} x {@code height}, its origin as {@code cg_WorldOrigin}, and what its view state adds (a stage's
     * environment and history). What a pass's or a canvas's {@code camera(...)} writes.
     */
    public CgPassConstants seenThrough(CgCamera camera, float width, float height) {
        view.set(camera.view());
        projection.set(camera.projection());
        time(camera.time()).resolution(camera.width() > 0f ? camera.width() : width,
                camera.height() > 0f ? camera.height() : height);
        camera((float) (camera.x() - camera.originX()), (float) (camera.y() - camera.originY()),
                (float) (camera.z() - camera.originZ()));
        origin(camera.originX(), camera.originY(), camera.originZ());
        if (camera.viewState() != null) camera.viewState().write(this);
        return this;
    }

    /** Puts the camera where {@link #view} sees from, for an affine view: a pass with no other camera to give. */
    public CgPassConstants cameraFromView() {
        view.originAffine(eye);
        return camera(eye.x, eye.y, eye.z);
    }


    /**
     * Whether the target is the linear HDR scene ({@code CG_LINEAR_SCENE}): a Forward pass's colour is then decoded
     * from sRGB as it is written. {@code CgStageFrame.defaults} sets it while the stage draws into the scene.
     */
    public CgPassConstants linearScene(boolean linear) {
        this.linearScene = linear;
        return this;
    }

    /**
     * Whether the target holds its colour sRGB-encoded, as the host's own does ({@code CG_ENCODED_TARGET}): a
     * {@code "ColorSpace" = "Linear"} Forward pass's colour is then encoded as it is written. {@code CgRecording.raster}
     * sets it in a pass's copy from its target: true for the host's, false for every texture of ours.
     */
    public CgPassConstants encodedTarget(boolean encoded) {
        this.encodedTarget = encoded;
        return this;
    }

    /** What an Emissive pass drawn into the HDR scene multiplies its glow by ({@code CG_SCENE_GLOW}): 1 by default. */
    public CgPassConstants sceneGlow(float gain) {
        this.sceneGlow = gain;
        return this;
    }

    /** Where world space's origin is in absolute coordinates: what {@code CG_ABSOLUTE_WORLD_POS} adds. */
    public CgPassConstants origin(double x, double y, double z) {
        this.originX = (float) x;
        this.originY = (float) y;
        this.originZ = (float) z;
        return this;
    }

    /** Toward the sun, or the moon while the sun is down, any length; {@code daylight} 0 to 1. */
    public CgPassConstants sun(float x, float y, float z, float daylight) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length > 0f) {
            sunX = x / length;
            sunY = y / length;
            sunZ = z / length;
        }
        this.daylight = daylight;
        return this;
    }

    /** How bright daylight is, 0 to 1, the sun where it is. */
    public CgPassConstants daylight(float daylight) {
        this.daylight = daylight;
        return this;
    }

    /** The sun this pass started with: high, toward +x and +z, at full daylight. */
    public CgPassConstants defaultSun() {
        sunX = SUN_X;
        sunY = SUN_Y;
        sunZ = SUN_Z;
        daylight = 1f;
        sunShare = SUN_SHARE;
        return this;
    }

    /** The sun's direct share of a full day's sky light, 0 to 1 ({@code CG_SUN_SHARE}): what its shadow takes away. */
    public CgPassConstants sunShare(float share) {
        sunShare = share;
        return this;
    }

    /** The fog: whole from {@code end} blocks from the camera, starting at {@code start}. */
    public CgPassConstants fog(float red, float green, float blue, float start, float end) {
        fog = true;
        fogRed = red;
        fogGreen = green;
        fogBlue = blue;
        fogStart = start;
        fogEnd = Math.max(end, start + 1e-3f);
        return this;
    }

    /**
     * Which two directions light an entity-look material ({@code CG_ENTITY_LIGHT0}, {@code CG_ENTITY_LIGHT1}): the
     * Nether's, or the level's (the default).
     */
    public CgPassConstants entityLights(boolean nether) {
        netherLighting = nether;
        return this;
    }

    /** No fog. */
    public CgPassConstants noFog() {
        fog = false;
        return this;
    }

    /**
     * What a temporal effect reprojects by: the matrix taking this pass's camera-relative points to last frame's clip
     * space ({@code cg_PreviousClip}), the host frame's index ({@code CG_FRAME_INDEX}) and whether the last frame is
     * one of this view ({@code CG_HISTORY_VALID}). {@code CgStageFrame.constants} fills it for the host's camera.
     */
    public CgPassConstants history(Matrix4fc previousViewProjection, int frameIndex, boolean valid) {
        previous.set(previousViewProjection);
        this.frameIndex = frameIndex;
        this.historyValid = valid;
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

        linearScene = block[at + 46] != 0f;
        sceneGlow = block[at + 47];
        originX = block[at + 48];
        originY = block[at + 49];
        originZ = block[at + 50];
        previous.set(block, at + 52);
        frameIndex = (int) block[at + 68];
        historyValid = block[at + 69] != 0f;
        encodedTarget = block[at + ENCODED_TARGET] != 0f;
        int e = at + VIEW_FLOATS;
        sunX = block[e];
        sunY = block[e + 1];
        sunZ = block[e + 2];
        daylight = block[e + 3];
        fogRed = block[e + 4];
        fogGreen = block[e + 5];
        fogBlue = block[e + 6];
        fog = block[e + 7] != 0f;
        fogStart = block[e + 8];
        fogEnd = block[e + 9];
        sunShare = block[e + 10];
        netherLighting = block[e + 11] != 0f;
        return this;
    }

    /** The target width a packed block was written with. */
    public static float width(float[] block) {
        return block[36];
    }

    /** The target height a packed block was written with: what a pass's palette flips {@code gl_FragCoord} by. */
    public static float height(float[] block) {
        return block[37];
    }

    /** Writes both blocks' {@value #FLOATS} floats into {@code out} at {@code at}, the view block's first. */
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
        out[at + DEPTH_REVERSED] = 1f;   // the engine's; blocks(..., reversed, ...) converts for a host's standard depth
        out[at + 45] = 1f;   // clip depth 0..1, on every device
        out[at + 46] = linearScene ? 1f : 0f;
        out[at + 47] = sceneGlow;
        out[at + 48] = originX;
        out[at + 49] = originY;
        out[at + 50] = originZ;
        out[at + 51] = 0f;
        previous.get(out, at + 52);
        out[at + 68] = frameIndex & 0xFFFF;
        out[at + 69] = historyValid ? 1f : 0f;
        out[at + 70] = 0f;
        out[at + ENCODED_TARGET] = encodedTarget ? 1f : 0f;
        int e = at + VIEW_FLOATS;
        out[e] = sunX;
        out[e + 1] = sunY;
        out[e + 2] = sunZ;
        out[e + 3] = daylight;
        out[e + 4] = fogRed;
        out[e + 5] = fogGreen;
        out[e + 6] = fogBlue;
        out[e + 7] = fog ? 1f : 0f;
        out[e + 8] = fogStart;
        out[e + 9] = fogEnd;
        out[e + 10] = sunShare;
        out[e + 11] = netherLighting ? 1f : 0f;
    }

    /** Adds a packed pass's two blocks to the snapshot {@code table} is building, at their bindings. */
    public static CgBindingTable blocks(CgBindingTable table, float[] packed, int at) {
        return table.block(CgBindingPoints.VIEW_UBO, packed, at, VIEW_FLOATS)
                .block(CgBindingPoints.ENVIRONMENT_UBO, packed, at + VIEW_FLOATS, ENVIRONMENT_FLOATS);
    }

    /**
     * {@link #blocks} as the pass's execution draws: a packed pass is in the engine's depth convention, and where
     * {@code reversed} is false (a target drawn in a host's standard depth) its projection, last frame's view-projection
     * and {@code cg_DepthParams.x} are converted, in {@code scratch} ({@link #FLOATS} floats or more). The executor's.
     */
    public static CgBindingTable blocks(CgBindingTable table, float[] packed, int at, boolean reversed, float[] scratch) {
        if (reversed) return blocks(table, packed, at);
        System.arraycopy(packed, at, scratch, 0, FLOATS);
        standardDepth(scratch, 16);
        standardDepth(scratch, 52);
        scratch[DEPTH_REVERSED] = 0f;
        return blocks(table, scratch, 0);
    }

    /** Clip depth {@code w - z}: the depth 1 - d a standard target stores where the engine's would store d. */
    private static void standardDepth(float[] matrix, int at) {
        for (int column = at; column < at + 16; column += 4) matrix[column + 2] = matrix[column + 3] - matrix[column + 2];
    }

    /** Snapshots both blocks into {@code table}, and answers its id. */
    public int capture(CgBindingTable table) {
        write(packed, 0);
        return blocks(table.begin(), packed, 0).end();
    }
}
