package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The entity boxes a Step kernel taking {@code ENTITIES} reads ({@code fx_entities_at.glsl}): the living nearest the
 * camera, at most {@link #MAX} within {@link #REACH} blocks, gathered from {@link CgEntityQuery} once a frame a pool reads
 * them. Items and projectiles are left out. Render thread.
 *
 * <pre>{@code
 * CgVfxEntityBoxes.around(camX, camY, camZ, partialTick);   // what the boxes centre on: the stage's camera
 * CgVfxEntityBoxes.upload(recording);                       // gathers and uploads, when a stepping shape reads them
 * CgVfxEntityBoxes.bind(dispatch);                          // each such shape's Step
 * }</pre>
 */
final class CgVfxEntityBoxes {

    /** The most boxes a step reads: each particle tests every one. */
    static final int MAX = 64;
    /** How far from the camera an entity is gathered, in blocks, along each axis. */
    static final double REACH = 64.0;

    static final String PROPERTIES = """
                _Entities      ("Entity boxes in ENTITIES",              int) = 0
                _EntityAnchorX ("The block the boxes are relative to: x", int) = 0
                _EntityAnchorY ("y",                                     int) = 0
                _EntityAnchorZ ("z",                                     int) = 0
            """;

    private static boolean placed;
    private static double cameraX, cameraY, cameraZ;
    private static float partialTick;
    private static int count, anchorX, anchorY, anchorZ;
    private static CgGraphBuffer buffer;
    private static ByteBuffer staging = ByteBuffer.allocate(MAX * 32).order(ByteOrder.nativeOrder());
    private static final Gather GATHER = new Gather();

    private CgVfxEntityBoxes() {
    }

    /** Centres the next {@link #upload} on {@code (x, y, z)}, poses at {@code partialTick}. */
    static void around(double x, double y, double z, float partialTick) {
        placed = true;
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        CgVfxEntityBoxes.partialTick = partialTick;
    }

    /** Gathers the boxes round where {@link #around} put them and uploads them; none when it was not called. */
    static void upload(CgRecording recording) {
        gather();
        if (buffer == null) {
            buffer = CgGraphBuffer.persistent("vfx.pool.entities", CgBufferDesc.of(MAX * 32L, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        if (count == 0) return;
        staging.clear();
        for (int i = 0; i < count * 8; i++) staging.putInt(i * 4, GATHER.words[i]);
        staging.limit(count * 32);
        recording.update(buffer, 0, staging);
    }

    /** {@link #upload}'s gathering alone: the boxes in {@link #words()}. */
    static void gather() {
        count = 0;
        anchorX = (int) Math.floor(cameraX);
        anchorY = (int) Math.floor(cameraY);
        anchorZ = (int) Math.floor(cameraZ);
        if (placed) GATHER.run();
        placed = false;
    }

    static void bind(CgDispatch d) {
        d.bind("ENTITIES", buffer).set("_Entities", count)
                .set("_EntityAnchorX", anchorX).set("_EntityAnchorY", anchorY).set("_EntityAnchorZ", anchorZ);
    }

    /** A dying context took the buffer. */
    static void forget() {
        buffer = null;
        count = 0;
    }

    /** The nearest {@link #MAX}, kept sorted by distance as {@code within} visits them. */
    private static final class Gather implements IntConsumer {
        final int[] words = new int[MAX * 8];
        private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        private final double[] distance = new double[MAX];
        private CgEntityQuery entities;

        void run() {
            entities = CgPlatform.get(CgEntityQuery.SERVICE);
            entities.within(cameraX - REACH, cameraY - REACH, cameraZ - REACH, cameraX + REACH, cameraY + REACH,
                    cameraZ + REACH, this);
            entities = null;
        }

        @Override
        public void accept(int id) {
            if ((entities.flags(id) & CgEntityQuery.ALIVE) == 0) return;
            int kind = entities.kind(id);
            if (kind == CgEntityQuery.KIND_ITEM || kind == CgEntityQuery.KIND_PROJECTILE) return;
            if (!entities.pose(id, partialTick, pose)) return;
            double x = pose[CgEntityQuery.X], y = pose[CgEntityQuery.Y], z = pose[CgEntityQuery.Z];
            double dx = x - cameraX, dy = y + pose[CgEntityQuery.HEIGHT] * 0.5 - cameraY, dz = z - cameraZ;
            double d = dx * dx + dy * dy + dz * dz;
            int at = count;
            if (at == MAX) {
                if (d >= distance[MAX - 1]) return;
                at = MAX - 1;
            } else {
                count++;
            }
            // Insertion by distance: at most MAX.
            while (at > 0 && distance[at - 1] > d) {
                distance[at] = distance[at - 1];
                System.arraycopy(words, (at - 1) * 8, words, at * 8, 8);
                at--;
            }
            distance[at] = d;
            float half = (float) (pose[CgEntityQuery.WIDTH] * 0.5);
            float ex = (float) (x - anchorX), ey = (float) (y - anchorY), ez = (float) (z - anchorZ);
            int w = at * 8;
            words[w] = Float.floatToRawIntBits(ex - half);
            words[w + 1] = Float.floatToRawIntBits(ey);
            words[w + 2] = Float.floatToRawIntBits(ez - half);
            words[w + 3] = id;
            words[w + 4] = Float.floatToRawIntBits(ex + half);
            words[w + 5] = Float.floatToRawIntBits(ey + (float) pose[CgEntityQuery.HEIGHT]);
            words[w + 6] = Float.floatToRawIntBits(ez + half);
            words[w + 7] = 0;
        }
    }

    /** The gathered boxes' words, {@code count() * 8}. Tests. */
    static int[] words() {
        return Arrays.copyOf(GATHER.words, count * 8);
    }

    static int count() {
        return count;
    }
}
