package com.crystalgraphics.render.scene.gpu;

import com.crystalgraphics.api.buffer.CgBuffer;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.buffer.CgBufferRecords;
import com.crystalgraphics.api.buffer.CgBuffers;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix3f;
import org.joml.Matrix4dc;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The scene's object records on the GPU, kept across frames: Unreal's GPUScene. A primitive takes a slot once, rewrites
 * it when it changes, and frees it when it leaves; every pass binds the records ({@code cg_SceneObjects}), and a draw
 * names one by {@link #id}, which {@code cg_env.glsl} decodes into the record a frame record would be.
 *
 * <pre>{@code
 * CgGpuScene scene = CgScene.client().gpu();     // a scene's own
 * int slot = scene.add();
 * scene.transform(slot, localToWorld);   // absolute, in doubles: the record keeps a tile and a place in it
 * scene.light(slot, 15f, 15f);
 * int id = CgGpuScene.id(slot);           // what the draw's object id carries
 * ...
 * scene.transform(slot, moved);            // last frame's transform is kept beside it, for velocity
 * scene.remove(slot);
 * }</pre>
 *
 * <ul>
 *   <li>A record is placed by its {@link #TILE tile} and a model relative to the tile's corner, so it never changes
 *       with the camera (Unreal's large-world tile); the shader measures the tile from the view's.</li>
 *   <li>Its previous transform is its transform before the last frame that moved it, and becomes its current one the
 *       frame after it stops: a still primitive has no velocity.</li>
 *   <li>The frame thread's. Changes reach the GPU at the frame's first pass ({@link #records}), the dirty range at
 *       once.</li>
 * </ul>
 */
public final class CgGpuScene {

    /** A tile's size in blocks: a record's model stays within one of its corner, where a float keeps ~0.1 mm. */
    public static final double TILE = 1024.0;

    /** Set in an object id naming a scene record, as {@code cg_env.glsl}'s {@code CG_SCENE_RECORD}. */
    public static final int SCENE_RECORD = 0x80000000;

    /** {@code CgSceneObject}, as {@code cg_env.glsl} declares it. */
    public static final CgBufferLayout RECORD = CgBufferLayout
            .builder("CgSceneObject", CgBufferLayout.MemoryLayout.STD430)
            .vec4("origin")
            .mat4("model")
            .mat4("previousModel")
            .mat4("normalMatrix")
            .vec4("custom0")
            .vec4("custom1")
            .vec4("custom2")
            .vec4("custom3")
            .build();

    static final int FLOATS = 68;
    private static final int ORIGIN = 0, MODEL = 4, PREVIOUS = 20, NORMAL = 36, CUSTOM = 52;

    private static final int UPLOADED = CgTrace.name("scene.records-uploaded");

    /** The object id naming {@code slot}'s record. */
    public static int id(int slot) {
        return slot | SCENE_RECORD;
    }

    /** The tile {@code x} is in, along one axis. */
    public static double tile(double x) {
        return Math.floor(x / TILE);
    }

    private final String name;
    private float[] records = new float[FLOATS * 64];
    /** Per slot: its translation and its previous one, absolute (x, y, z, px, py, pz). */
    private double[] translations = new double[6 * 64];
    /** Per slot: the frame its transform was last set for, -1 never; whether it is live. */
    private long[] movedFor = new long[64];
    private boolean[] live = new boolean[64];
    private int[] free = new int[16];
    private int freeCount, high, count;

    /** The frames' movers: last frame's, which settle unless moved again, and this frame's. */
    private int[] settling = new int[16], moving = new int[16];
    private int settlingCount, movingCount;

    private int dirtyFrom = Integer.MAX_VALUE, dirtyTo = -1;
    /** The frame {@link #records} last flushed for; the frame transforms set now belong to. */
    private long flushed = -1, frame;

    @Nullable
    private CgBuffer buffer;
    private int capacity;

    private final Matrix3f normal = new Matrix3f();
    private int writeFrom;
    private final Consumer<CgBufferRecords> writer = this::writeRecords;

    public CgGpuScene(String name) {
        this.name = name;
    }

    /** Takes a slot, its record identity and at the origin; answers it. */
    public int add() {
        int slot;
        if (freeCount > 0) slot = free[--freeCount];
        else {
            slot = high++;
            if (slot == live.length) grow(slot + 1);
        }
        live[slot] = true;
        movedFor[slot] = -1;
        count++;
        int r = slot * FLOATS;
        Arrays.fill(records, r, r + FLOATS, 0f);
        identity(r + MODEL);
        identity(r + PREVIOUS);
        identity(r + NORMAL);   // light 0, emission 1 and the spare lanes' (0, 0, 0, 1), as a frame record's writer leaves them
        Arrays.fill(translations, slot * 6, slot * 6 + 6, 0.0);
        dirty(slot);
        return slot;
    }

    /** Frees {@code slot}; its id names nothing until {@link #add} hands it out again. */
    public void remove(int slot) {
        requireLive(slot);
        live[slot] = false;
        count--;
        if (freeCount == free.length) free = Arrays.copyOf(free, free.length * 2);
        free[freeCount++] = slot;
    }

    /**
     * {@code slot}'s object-to-world, absolute: its tile, its model within it and its normal matrix. The transform it
     * replaces becomes its previous, unless it was already set this frame; the first one set is its own previous.
     */
    public void transform(int slot, Matrix4dc localToWorld) {
        requireLive(slot);
        int r = slot * FLOATS, t = slot * 6;
        boolean first = beginMove(slot);
        double x = localToWorld.m30(), y = localToWorld.m31(), z = localToWorld.m32();
        translations[t] = x;
        translations[t + 1] = y;
        translations[t + 2] = z;
        if (first) {
            translations[t + 3] = x;
            translations[t + 4] = y;
            translations[t + 5] = z;
        }
        int m = r + MODEL;
        records[m] = (float) localToWorld.m00();
        records[m + 1] = (float) localToWorld.m01();
        records[m + 2] = (float) localToWorld.m02();
        records[m + 3] = (float) localToWorld.m03();
        records[m + 4] = (float) localToWorld.m10();
        records[m + 5] = (float) localToWorld.m11();
        records[m + 6] = (float) localToWorld.m12();
        records[m + 7] = (float) localToWorld.m13();
        records[m + 8] = (float) localToWorld.m20();
        records[m + 9] = (float) localToWorld.m21();
        records[m + 10] = (float) localToWorld.m22();
        records[m + 11] = (float) localToWorld.m23();
        records[m + 15] = (float) localToWorld.m33();
        if (first) System.arraycopy(records, m, records, r + PREVIOUS, 16);
        place(slot);
        normal.set(records[m], records[m + 1], records[m + 2], records[m + 4], records[m + 5], records[m + 6],
                records[m + 8], records[m + 9], records[m + 10]).invert().transpose();
        int n = r + NORMAL;
        records[n] = normal.m00;
        records[n + 1] = normal.m01;
        records[n + 2] = normal.m02;
        records[n + 4] = normal.m10;
        records[n + 5] = normal.m11;
        records[n + 6] = normal.m12;
        records[n + 8] = normal.m20;
        records[n + 9] = normal.m21;
        records[n + 10] = normal.m22;
        dirty(slot);
    }

    /** {@code slot}'s position, absolute, its rotation and scale kept; its previous as {@link #transform}'s. */
    public void translate(int slot, double x, double y, double z) {
        requireLive(slot);
        int t = slot * 6;
        boolean first = beginMove(slot);
        translations[t] = x;
        translations[t + 1] = y;
        translations[t + 2] = z;
        if (first) {
            translations[t + 3] = x;
            translations[t + 4] = y;
            translations[t + 5] = z;
        }
        place(slot);
        dirty(slot);
    }

    /**
     * Opens a move of {@code slot} in this frame: the first in the frame keeps the transform it replaces as the previous.
     * Answers whether the slot was never placed, so its first transform is its own previous.
     */
    private boolean beginMove(int slot) {
        int r = slot * FLOATS, t = slot * 6;
        boolean first = movedFor[slot] < 0;
        if (movedFor[slot] != frame) {
            if (!first) {
                System.arraycopy(records, r + MODEL, records, r + PREVIOUS, 16);
                translations[t + 3] = translations[t];
                translations[t + 4] = translations[t + 1];
                translations[t + 5] = translations[t + 2];
            }
            if (movingCount == moving.length) moving = Arrays.copyOf(moving, moving.length * 2);
            moving[movingCount++] = slot;
            movedFor[slot] = frame;
        }
        return first;
    }

    /** The world's light at {@code slot}: block and sky light, 0 to 15 each ({@code CG_OBJECT_LIGHT}). */
    public void light(int slot, float block, float sky) {
        requireLive(slot);
        int n = slot * FLOATS + NORMAL;
        records[n + 12] = block;
        records[n + 13] = sky;
        dirty(slot);
    }

    /** {@code slot}'s emission scale ({@code CG_OBJECT_EMISSION}), 1 by default. */
    public void emission(int slot, float scale) {
        requireLive(slot);
        records[slot * FLOATS + NORMAL + 14] = scale - 1f;
        dirty(slot);
    }

    /** The four lanes its material reads as {@code CG_OBJECT_SPARE}: (0, 0, 0, 1) by default. */
    public void spare(int slot, float x, float y, float z, float w) {
        requireLive(slot);
        int n = slot * FLOATS + NORMAL;
        records[n + 3] = x;
        records[n + 7] = y;
        records[n + 11] = z;
        records[n + 15] = w;
        dirty(slot);
    }

    /** {@code CG_OBJECT_CUSTOM0} to {@code 3}: custom {@code i}, 0 to 3. */
    public void custom(int slot, int i, float x, float y, float z, float w) {
        requireLive(slot);
        if (i < 0 || i > 3) throw new IndexOutOfBoundsException("custom " + i + "; there are four");
        int c = slot * FLOATS + CUSTOM + i * 4;
        records[c] = x;
        records[c + 1] = y;
        records[c + 2] = z;
        records[c + 3] = w;
        dirty(slot);
    }

    /** Live records. */
    public int size() {
        return count;
    }

    /**
     * Frame thread, at a frame's first pass: last frame's movers that did not move again settle, the dirty range is
     * written, and the buffer every pass binds is answered; null while no record was ever added.
     */
    @Nullable
    public CgBufferHandle records() {
        long now = CgFrameRing.frame();
        if (flushed != now) {
            flushed = now;
            flush();
        }
        return buffer;
    }

    /** {@link #records}'s buffer itself, flushed for this frame: what a compute pass binds by {@link #RECORD}; null with none. */
    @Nullable
    public CgBuffer buffer() {
        records();
        return buffer;
    }

    private void flush() {
        settle();
        upload();
    }

    /**
     * Ends the frame's moves without uploading: last frame's movers that did not move again take their transform as their
     * previous. {@link #records} does it at a frame's first pass; a scene no device draws (a test, a tool) calls it.
     */
    public void settle() {
        for (int i = 0; i < settlingCount; i++) {
            int slot = settling[i];
            if (!live[slot] || movedFor[slot] == frame) continue;
            int r = slot * FLOATS, t = slot * 6;
            translations[t + 3] = translations[t];
            translations[t + 4] = translations[t + 1];
            translations[t + 5] = translations[t + 2];
            System.arraycopy(records, r + MODEL, records, r + PREVIOUS, 16);
            place(slot);
            dirty(slot);
        }
        int[] swap = settling;
        settling = moving;
        moving = swap;
        settlingCount = movingCount;
        movingCount = 0;
        frame++;
    }

    private void upload() {
        if (dirtyTo < 0) return;
        if (buffer == null || capacity < high) {
            if (buffer != null) buffer.release();
            capacity = Math.max(64, Integer.highestOneBit(Math.max(1, high - 1)) << 1);
            buffer = CgBuffers.create(name, RECORD, capacity);
            dirtyFrom = 0;
            dirtyTo = high - 1;
        }
        writeFrom = dirtyFrom;
        int n = dirtyTo - dirtyFrom + 1;
        buffer.write(dirtyFrom, n, writer);
        CgTrace.add(CgChannels.GL, UPLOADED, n);
        dirtyFrom = Integer.MAX_VALUE;
        dirtyTo = -1;
    }

    private void writeRecords(CgBufferRecords out) {
        for (int i = 0; i <= dirtyTo - writeFrom; i++) {
            int r = (writeFrom + i) * FLOATS;
            CgBufferWriter w = out.next();
            for (int f = 0; f < FLOATS; f += 4) w.vec4At(f, records[r + f], records[r + f + 1], records[r + f + 2], records[r + f + 3]);
        }
    }

    /** Releases the GPU's copy; the records are kept, and written whole at the next frame. */
    public void release() {
        if (buffer != null) buffer.release();
        buffer = null;
        capacity = 0;
        if (high > 0) {
            dirtyFrom = 0;
            dirtyTo = high - 1;
        }
    }

    /** The tile from the current translation; the model's and the previous model's translations within it. */
    private void place(int slot) {
        int r = slot * FLOATS, t = slot * 6;
        double tx = tile(translations[t]), ty = tile(translations[t + 1]), tz = tile(translations[t + 2]);
        records[r + ORIGIN] = (float) tx;
        records[r + ORIGIN + 1] = (float) ty;
        records[r + ORIGIN + 2] = (float) tz;
        records[r + MODEL + 12] = (float) (translations[t] - tx * TILE);
        records[r + MODEL + 13] = (float) (translations[t + 1] - ty * TILE);
        records[r + MODEL + 14] = (float) (translations[t + 2] - tz * TILE);
        records[r + PREVIOUS + 12] = (float) (translations[t + 3] - tx * TILE);
        records[r + PREVIOUS + 13] = (float) (translations[t + 4] - ty * TILE);
        records[r + PREVIOUS + 14] = (float) (translations[t + 5] - tz * TILE);
    }

    /** {@code slot}'s position along {@code axis} (0 to 2), absolute, as its last transform set it. */
    public double position(int slot, int axis) {
        requireLive(slot);
        return translations[slot * 6 + axis];
    }

    /** {@code slot}'s previous position along {@code axis}: where its velocity is measured from. */
    public double previousPosition(int slot, int axis) {
        requireLive(slot);
        return translations[slot * 6 + 3 + axis];
    }

    /** Test seam: the CPU's copy of {@code slot}'s record, {@value #FLOATS} floats from {@code slot * FLOATS}. */
    float[] mirror() {
        return records;
    }

    private void dirty(int slot) {
        if (slot < dirtyFrom) dirtyFrom = slot;
        if (slot > dirtyTo) dirtyTo = slot;
    }

    private void identity(int at) {
        records[at] = 1f;
        records[at + 5] = 1f;
        records[at + 10] = 1f;
        records[at + 15] = 1f;
    }

    private void requireLive(int slot) {
        if (slot < 0 || slot >= high || !live[slot]) throw new IllegalArgumentException("slot " + slot + " is not live");
    }

    private void grow(int slots) {
        int n = Math.max(slots, live.length * 2);
        records = Arrays.copyOf(records, n * FLOATS);
        translations = Arrays.copyOf(translations, n * 6);
        movedFor = Arrays.copyOf(movedFor, n);
        live = Arrays.copyOf(live, n);
    }
}
