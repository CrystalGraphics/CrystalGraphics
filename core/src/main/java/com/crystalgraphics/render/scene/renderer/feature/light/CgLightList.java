package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.component.CgDynamicLights;
import com.crystalgraphics.render.scene.renderer.feature.field.CgVoxelWindow;
import com.crystalgraphics.render.view.CgView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.CAPACITY;
import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.FLOATS;

/**
 * The firing's light list, Unreal's forward light data: the frame's lights from the CPU (the scene's components, each
 * dynamic source, the frame's once lights), Minecraft's light-giving blocks round the eye, then each appender's from
 * its kernel, {@link CgSceneLights#FLOATS} floats a light, camera-relative. What it holds decides which shadow and
 * shape paths the firing runs.
 */
final class CgLightList {

    /** How far a kernel's light may reach, unknown here: what a tile keeps entities within while any appends. */
    private static final float KERNEL_REACH = 64f;
    /** The most of Minecraft's light-giving blocks a frame, nearest found first only by the window's order. */
    private static final int BLOCK_MOST = 1024;
    /** Firings between reads of the count, for what was dropped. */
    private static final int COUNT_EVERY = 30;

    private final List<CgSceneLights.Appender> appenders = new CopyOnWriteArrayList<>();
    /** {@link #appenders} as a frame walks them: replaced on each add and remove, so a walk allocates no iterator. */
    private volatile CgSceneLights.Appender[] appending = new CgSceneLights.Appender[0];
    private final List<CgDynamicLights> dynamic = new CopyOnWriteArrayList<>();
    private final CgDynamicLights components;
    final CgLightCollector frame = new CgLightCollector();
    private long collected = -1;

    CgGraphBuffer list, count;
    private ByteBuffer staging;
    private final int[] start = new int[4];
    /** Lights listed this firing, the CPU's and the blocks'. */
    int listed;
    /** This firing's CPU lights that cast shadows, and the farthest a shadowed light's ray runs, blocks. */
    int shadowed;
    float rayReach;
    /**
     * Whether a spot or a tube may be among this firing's lights shaded exactly ({@code shapedExact}), or among all of
     * them ({@code shapedAny}): the apply's program then carries their path ({@code CG_LIGHTS_SHAPED}).
     */
    boolean shapedExact, shapedAny;

    /** This firing's light-giving blocks, x, y, z and level each, and how many: the list's last lights. */
    private int[] blocks = new int[4 * 64];
    int blockCount;
    private float blockReach;
    private double eyeX, eyeY, eyeZ;
    private final CgVoxelWindow.Emitters gatherBlock = (x, y, z, level) -> {
        double dx = x + 0.5 - eyeX, dy = y + 0.5 - eyeY, dz = z + 0.5 - eyeZ;
        if (blockCount == BLOCK_MOST || dx * dx + dy * dy + dz * dz > (double) blockReach * blockReach) return;
        if (blockCount * 4 == blocks.length) blocks = Arrays.copyOf(blocks, blocks.length * 2);
        int b = blockCount++ * 4;
        blocks[b] = x;
        blocks[b + 1] = y;
        blocks[b + 2] = z;
        blocks[b + 3] = level;
    };

    private int firings;
    volatile long dropped;
    /** The CPU's dropped as of the count being read back. */
    private long readCpuDropped, cpuDropped;
    private final CgReadback.Sink readCount =
            data -> dropped = readCpuDropped + Math.max(0L, Integer.toUnsignedLong(data.getInt(0)) - CAPACITY);
    /** Zeros: the light list, cells and entities when none were made this firing. */
    private CgGraphBuffer zeroes;

    CgLightList(CgDynamicLights components) {
        this.components = components;
    }

    void add(CgDynamicLights source) {
        dynamic.add(source);
    }

    void remove(CgDynamicLights source) {
        dynamic.remove(source);
    }

    void add(CgSceneLights.Appender appender) {
        synchronized (appenders) {
            appenders.add(appender);
            appending = appenders.toArray(new CgSceneLights.Appender[0]);
        }
    }

    void remove(CgSceneLights.Appender appender) {
        synchronized (appenders) {
            appenders.remove(appender);
            appending = appenders.toArray(new CgSceneLights.Appender[0]);
        }
    }

    CgSceneLights.Appender[] appenders() {
        return appending;
    }

    /** The frame's lights from the CPU, once a ring frame: the scene's components, then each dynamic source. */
    void collect(CgView view) {
        long now = CgFrameRing.frame();
        if (collected == now) return;
        collected = now;
        frame.open();
        components.collect(view, frame);
        for (CgDynamicLights d : dynamic) d.collect(view, frame);
    }

    /**
     * The firing begun from the grid's eye: Minecraft's light-giving blocks gathered when {@code blockShadows} (only
     * where something of ours can shadow them), nothing listed yet. Whether there is anything to list.
     */
    boolean begin(CgLightGrid grid, boolean blockShadows, boolean hostWorld) {
        eyeX = grid.cameraX;
        eyeY = grid.cameraY;
        eyeZ = grid.cameraZ;
        listed = shadowed = 0;
        shapedExact = shapedAny = false;
        blockCount = 0;
        blockReach = CgLightSettings.BLOCK_REACH.get();
        // Minecraft's light-giving blocks are the host world's: a scene of your own has none
        if (hostWorld && CgLightSettings.SHADOWS.get() && blockReach > 0f && blockShadows) {
            CgVoxelWindow window = CgVoxelWindow.get();
            window.useDistance();
            window.emitters(gatherBlock);
        }
        rayReach = appenders.isEmpty() ? 0f : KERNEL_REACH;
        return frame.count() > 0 || blockCount > 0 || !appenders.isEmpty();
    }

    /** The list's buffers and its 6 MB staging, made while no light is up: the first light's frame took them. */
    void allocate() {
        if (list != null) return;
        list = CgGraphBuffer.persistent("cg_world_lights",
                CgBufferDesc.of((long) CAPACITY * FLOATS * 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        count = CgGraphBuffer.persistent("cg_world_lights.count",
                CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        staging = ByteBuffer.allocateDirect(CAPACITY * FLOATS * 4).order(ByteOrder.nativeOrder());
    }

    /** The CPU's lights then the blocks', from the eye, and the count they leave for the appenders to append past. */
    void upload(CgRecording recording) {
        int cpu = frame.count();
        double[] at = frame.positions();
        float[] light = frame.rows();
        listed = Math.min(cpu, CAPACITY);
        cpuDropped = cpu - listed;
        blockCount = Math.min(blockCount, CAPACITY - listed);
        if (listed + blockCount > 0) {
            staging.clear();
            for (int i = 0; i < listed; i++) {
                int p = i * 3, l = i * FLOATS;
                if (light[l + 19] > 0.5f) {
                    shadowed++;
                    // A lit surface is within its radius of the centre; the ray ends on its line or its sphere.
                    float s0 = light[l + 12], s1 = light[l + 13], s2 = light[l + 14];
                    rayReach = Math.max(rayReach, light[l + 3] + 0.5f * (float) Math.sqrt(s0 * s0 + s1 * s1 + s2 * s2)
                            + light[l + 18]);
                }
                // lights.glsl's CG_VIEW_SPOT and CG_VIEW_TUBE; CG_LIGHT_SAMPLED.
                if (light[l + 11] > -1.5f || light[l + 12] != 0f || light[l + 13] != 0f || light[l + 14] != 0f) {
                    shapedAny = true;
                    if (light[l + 19] <= 0.5f && light[l + 22] <= 0.5f) shapedExact = true;
                }
                staging.putFloat((float) (at[p] - eyeX)).putFloat((float) (at[p + 1] - eyeY))
                        .putFloat((float) (at[p + 2] - eyeZ));
                for (int w = 3; w < FLOATS; w++) staging.putFloat(light[l + w]);
            }
            // CG_LIGHT_MINECRAFT: black, reaching its level, its ambient.w 2.
            for (int i = 0; i < blockCount; i++) {
                int b = i * 4;
                rayReach = Math.max(rayReach, blocks[b + 3] + 0.5f);
                staging.putFloat((float) (blocks[b] + 0.5 - eyeX)).putFloat((float) (blocks[b + 1] + 0.5 - eyeY))
                        .putFloat((float) (blocks[b + 2] + 0.5 - eyeZ)).putFloat(blocks[b + 3] + 0.5f)
                        .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
                        .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(-2f)
                        .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
                        .putFloat(-1f).putFloat(-1f).putFloat(0.5f).putFloat(2f)
                        .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
            }
            staging.flip();
            recording.update(list, 0, staging);
            listed += blockCount;
        }
        start[0] = listed;
        recording.update(count, 0, start, 0, 4);
    }

    /** What was dropped past {@link CgSceneLights#CAPACITY}: the kernels' read back now and then, the CPU's at once. */
    void readDropped(CgRecording recording, boolean appended) {
        if (appended && firings++ % COUNT_EVERY == 0) {
            readCpuDropped = cpuDropped;
            recording.fetch(count, 0, 4, readCount);
        } else if (!appended) {
            dropped = cpuDropped;
        }
    }

    /** A kilobyte of zeros, made once: no lights, no cells, no entities. */
    CgGraphBuffer zeroes(CgRecording recording) {
        if (zeroes == null) {
            zeroes = CgGraphBuffer.persistent("cg_world_lights.zeroes", CgBufferDesc.of(1024, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            recording.fill(zeroes, 0);
        }
        return zeroes;
    }
}
