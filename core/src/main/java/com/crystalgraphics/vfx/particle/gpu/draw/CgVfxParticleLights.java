package com.crystalgraphics.vfx.particle.gpu.draw;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Particles as lights (clustered-lights-decals L4): the slots drawing a {@code light} renderer this frame, each of whose
 * live particles {@code env/compute/vfx/lights.compute} appends to the world's light list, as Cascade gathers a light
 * module's particles into simple lights ({@code ParticleSystemRender.cpp:870-935}).
 *
 * <pre>{@code
 * CgVfxParticleLights lights = CgVfxParticleLights.get();   // registers itself with the world's lights once
 * lights.note(pool, slot, 0.5f, true);                       // each frame a slot draws its light renderer
 * lights.publish();                                          // once the frame's draws are noted
 * }</pre>
 *
 * <ul>
 *   <li>Render thread for {@link #note} and {@link #publish}; the world renderer reads the last published frame when it
 *       records, on whichever thread that is.</li>
 *   <li>A slot noted twice in a frame (two light renderers) gives its particles two lights each, as Cascade's would.</li>
 * </ul>
 */
public final class CgVfxParticleLights implements CgWorldLights.Appender {

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/lights.compute";
    /** Floats a slot in {@code SLOTS}: its origin from the eye and its share, then 1 for the inverse square. */
    private static final int SLOT_FLOATS = 8;
    private static volatile CgVfxParticleLights instance;

    /** The frame being noted and the last published, swapped by {@link #publish}. */
    private Frame writing = new Frame(), published = new Frame();
    private ByteBuffer staging = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder());

    private CgVfxParticleLights() {
    }

    /** The one instance, added to {@link CgWorldRenderer#lights()} at its first ask. */
    public static CgVfxParticleLights get() {
        CgVfxParticleLights lights = instance;
        if (lights == null) {
            synchronized (CgVfxParticleLights.class) {
                if (instance == null) {
                    instance = new CgVfxParticleLights();
                    CgWorldRenderer.get().lights().add(instance);
                }
                lights = instance;
            }
        }
        return lights;
    }

    /** {@code slot} of {@code pool} gives light this frame: {@code share} of its particles, by the inverse square or its exponent. */
    public void note(CgVfxParticlePool pool, int slot, float share, float inverseSquared) {
        writing.add(pool, slot, share, inverseSquared);
    }

    /** Hands the frame's notes to the world renderer's next firings and starts the next frame's. */
    public void publish() {
        Frame done = writing;
        writing = published;
        writing.count = 0;
        published = done;
    }

    @Override
    public void upload(CgRecording recording, CgWorldLights lights) {
        Frame f = published;
        for (int i = 0; i < f.count; i++) {
            CgVfxParticlePool pool = f.pools[i];
            if (pool.isReleased()) continue;
            if (!first(f, i)) continue;
            int words = pool.slotCount() * SLOT_FLOATS;
            upload(recording, lights, f, pool, pool.lightSlots(recording, words), words * 4);
        }
    }

    @Override
    public boolean append(CgComputePass pass, CgWorldLights lights) {
        Frame f = published;
        boolean any = false;
        CgKernel kernel = null;
        for (int i = 0; i < f.count; i++) {
            CgVfxParticlePool pool = f.pools[i];
            if (pool.isReleased() || pool.records() == null || !first(f, i)) continue;
            if (kernel == null) kernel = CgCompute.load(KERNELS).kernel("Lights");
            CgVfxRecord record = pool.record();
            lights.into(pass.dispatch(kernel, pool.storage())).bind("RECORDS", pool.records()).bind("DRAWN", pool.drawn())
                    .bind("LIVE", pool.live()).bind("SLOTS", pool.lightSlots())
                    .set("_RecordVectors", record.vectors()).set("_DrawnStride", pool.shape().drawnStride())
                    .set("_LightTail", record.word(CgVfxRecord.Tail.LIGHT));
            any = true;
        }
        return any;
    }

    /** Whether note {@code i} is its pool's first in {@code f}: a pool uploads and dispatches once. */
    private static boolean first(Frame f, int i) {
        for (int j = 0; j < i; j++) if (f.pools[j] == f.pools[i]) return false;
        return true;
    }

    /** Every slot of {@code pool} noted in {@code f}, its origin from the firing's eye; the rest give no light. */
    private void upload(CgRecording recording, CgWorldLights lights, Frame f, CgVfxParticlePool pool, CgGraphBuffer buffer, int bytes) {
        if (staging.capacity() < bytes) staging = ByteBuffer.allocate(Math.max(bytes, staging.capacity() * 2)).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int b = 0; b < bytes; b += 4) staging.putFloat(b, 0f);
        for (int j = 0; j < f.count; j++) {
            if (f.pools[j] != pool || !pool.isOpen(f.slots[j])) continue;
            int s = f.slots[j], at = s * SLOT_FLOATS * 4;
            staging.putFloat(at, (float) (pool.origin(s, 0) - lights.cameraX()));
            staging.putFloat(at + 4, (float) (pool.origin(s, 1) - lights.cameraY()));
            staging.putFloat(at + 8, (float) (pool.origin(s, 2) - lights.cameraZ()));
            staging.putFloat(at + 12, f.shares[j]);
            staging.putFloat(at + 16, f.inverse[j]);
        }
        staging.limit(bytes);
        recording.update(buffer, 0, staging);
    }

    /** One frame's notes, grown as needed and kept. */
    private static final class Frame {
        CgVfxParticlePool[] pools = new CgVfxParticlePool[16];
        int[] slots = new int[16];
        float[] shares = new float[16];
        float[] inverse = new float[16];
        int count;

        void add(CgVfxParticlePool pool, int slot, float share, float inverseSquared) {
            if (count == pools.length) {
                pools = Arrays.copyOf(pools, count * 2);
                slots = Arrays.copyOf(slots, count * 2);
                shares = Arrays.copyOf(shares, count * 2);
                inverse = Arrays.copyOf(inverse, count * 2);
            }
            pools[count] = pool;
            slots[count] = slot;
            shares[count] = share;
            inverse[count] = inverseSquared;
            count++;
        }
    }
}
