package com.crystalgraphics.vfx.particle.gpu.draw;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Range (vfx-gpu §13.4): a particle pool's draws for one view. Each live particle is culled against the view; those
 * left are grouped by slot and written as the particle records a look reads through {@code CG_PARTICLE_*}, each slot's
 * from its list base, interpolated and given its curves as the CPU path's records are. A slot's draws read
 * {@link #drawn()} from {@link #base} and draw as many as {@link #visible()} counted for it.
 *
 * <pre>{@code
 * CgVfxRange range = CgVfxRange.of(pool);
 * range.frame(frame.particleAlpha(), frame.particleAlpha() * particleDt);   // each frame, before the world records
 * world.draw(quads, spark)                                                  // per slot and layer
 *      .buffer(CgBindingPoints.PARTICLES, range.drawn())
 *      .indirect(range.visible(), slot * 4L, CgIndirect.INDICES, 6)
 *      .custom(0, range.base(slot), capacity, radius, parameter)
 *      .at(ox, oy, oz).gpuCulled().group(ox, oy, oz).submit();
 * }</pre>
 *
 * <p>A mesh per particle ({@code MESHES}) draws the same particles as object records, culled and given levels again on
 * the GPU; each layer scales them and states its own customs:</p>
 *
 * <pre>{@code
 * world.draw(billow, smoke)
 *      .instances(range.objects(), range.base(slot), CgGpuCount.at(range.visible(), slot, capacity))
 *      .instanceScale(layer.radius()).custom(0, radius, parameter, age, seed)
 *      .at(ox, oy, oz).gpuCulled().group(ox, oy, oz).submit();
 * }</pre>
 *
 * <ul>
 *   <li>It records on every firing of {@link CgRenderStage#WORLD_OPAQUE} at {@link #ORDER}, after the pools step and
 *       before the world renderer: a second firing with another view culls again.</li>
 *   <li>Ask {@link #drawn()} and {@link #visible()} after the frame's slots open: they grow to the pool's capacity
 *       then, and a draw holding an older handle reads what it held.</li>
 *   <li>Positions are relative to each instance's origin, as on the CPU path; draw at the origin.</li>
 * </ul>
 */
public final class CgVfxRange {

    /** Where ranges record on {@link CgRenderStage#WORLD_OPAQUE}: after the pools' steps, before the world renderer. */
    public static final int ORDER = CgWorldRenderer.ORDER - 50;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/range.compute";
    private static final String[] PLANES = {"_Plane0", "_Plane1", "_Plane2", "_Plane3", "_Plane4", "_Plane5"};
    private static final int GPU_RANGE = CgGpuTrace.name("vfx.pool.range");
    private static final int DRAWN_BYTES = 64;

    /** Every range, by pool, and in the order made. Render thread. */
    private static final Map<CgVfxParticlePool, CgVfxRange> RANGES = new IdentityHashMap<>();
    private static final List<CgVfxRange> ALL = new ArrayList<>();
    private static CgRenderStage.Registration registration;

    private final CgVfxParticlePool pool;
    private final String passName, keysName, indicesName, startsName, drawnName, visibleName, slotsName, basesName,
            objectsName;
    private CgGraphBuffer drawn, visible, slots, bases, objects;
    /** Whether {@link #objects()} was asked for since the last recording. */
    private boolean objectsAsked;
    private final List<CgGraphBuffer> retired = new ArrayList<>();
    private float alpha = 1f, ahead;

    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f plane = new Vector4f();
    private ByteBuffer staging = ByteBuffer.allocate(0);

    private CgVfxRange(CgVfxParticlePool pool, int index) {
        this.pool = pool;
        String name = "vfx.range" + index;
        passName = name;
        keysName = name + ".keys";
        indicesName = name + ".indices";
        startsName = name + ".starts";
        drawnName = name + ".drawn";
        visibleName = name + ".visible";
        slotsName = name + ".slots";
        basesName = name + ".bases";
        objectsName = name + ".objects";
    }

    /** {@code pool}'s range, made the first time; the first made registers ranges' recording. Render thread. */
    public static CgVfxRange of(CgVfxParticlePool pool) {
        CgVfxRange range = RANGES.get(pool);
        if (range == null) {
            RANGES.put(pool, range = new CgVfxRange(pool, ALL.size()));
            ALL.add(range);
            if (registration == null) registration = CgRenderStage.WORLD_OPAQUE.register(ORDER, CgVfxRange::recordAll);
        }
        return range;
    }

    /** Forgets every range and stops recording. Tests, and context teardown. */
    static void forgetAll() {
        RANGES.clear();
        ALL.clear();
        if (registration != null) {
            registration.close();
            registration = null;
        }
    }

    /**
     * Where this frame draws its particles: {@code alpha} of the way from their last step to the next, the CPU path's
     * particle alpha, with spin pushed {@code aheadSeconds} past the last step. Each frame, before the world records.
     */
    public CgVfxRange frame(float alpha, float aheadSeconds) {
        this.alpha = alpha;
        this.ahead = aheadSeconds;
        return this;
    }

    /** The particle records a slot's draws read, {@code CgParticleBuffer}'s layout, from {@link #base}. */
    public CgGraphBuffer drawn() {
        int need = Math.max(1, pool.capacity());
        if (drawn == null || drawn.size() < (long) need * DRAWN_BYTES) {
            if (drawn != null) retired.add(drawn);
            drawn = CgGraphBuffer.persistent(drawnName,
                    CgBufferDesc.elements(sizeClass(need), DRAWN_BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        return drawn;
    }

    /**
     * The same particles as {@link #drawn()}, as {@code CgInstanceKind.OBJECT} records from {@link #base}: each turned
     * by its seed and spin and sized by its size over life, as {@code CgVfxFrame.particleMeshes} draws them, custom 1
     * its progress, seed, opacity and heat. Written only in a frame that asks for it.
     */
    public CgGraphBuffer objects() {
        objectsAsked = true;
        int need = Math.max(1, pool.capacity());
        if (objects == null || objects.size() < (long) need * CgGpuOps.cullRecordBytes()) {
            if (objects != null) retired.add(objects);
            objects = CgGraphBuffer.persistent(objectsName,
                    CgBufferDesc.elements(sizeClass(need), CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        return objects;
    }

    /** How many of each slot's particles are visible: a {@code uint} at {@code slot * 4}, a draw's indirect count. */
    public CgGraphBuffer visible() {
        int need = pool.slotCount() + 1;
        if (visible == null || visible.size() < need * 4L) {
            if (visible != null) retired.add(visible);
            visible = CgGraphBuffer.persistent(visibleName,
                    CgBufferDesc.elements(sizeClass(need), 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        return visible;
    }

    /** Where {@code slot}'s records start in {@link #drawn()}: its draws' base. */
    public int base(int slot) {
        return pool.listBase(slot);
    }

    private static void recordAll(CgStageFrame frame) {
        for (int i = ALL.size() - 1; i >= 0; i--) {
            CgVfxRange range = ALL.get(i);
            if (range.pool.isReleased()) {
                range.release(frame.recording());
                RANGES.remove(range.pool);
                ALL.remove(i);
            }
        }
        for (int i = 0; i < ALL.size(); i++) ALL.get(i).record(frame.recording(), frame.host().view());
    }

    /**
     * Records this range for {@code view} into {@code recording}. Ranges record themselves on every firing of
     * {@link CgRenderStage#WORLD_OPAQUE}; call it only where that stage does not fire, as a check scene. Render thread.
     */
    public void record(CgRecording recording, CgHostView view) {
        for (int i = 0; i < retired.size(); i++) recording.release(retired.get(i));
        retired.clear();
        CgGraphBuffer drawn = drawn(), visible = visible();
        int slotCount = pool.slotCount();
        CgGraphBuffer records = pool.records();
        if (records == null || pool.openSlots() == 0) {
            recording.fill(visible, 0);
            return;
        }
        uploadSlots(recording, view, slotCount);
        viewProjection.set(view.projection()).mul(view.view());
        CgCompute kernels = CgCompute.load(KERNELS);
        CgKernel key = kernels.kernel("Key"), place = kernels.kernel("Place");
        int storage = pool.storage();
        CgGraphBuffer keys = recording.scratch(keysName, storage * 4L, CgBufferUsage.STORAGE);
        CgGraphBuffer indices = recording.scratch(indicesName, storage * 4L, CgBufferUsage.STORAGE);
        CgGraphBuffer starts = recording.scratch(startsName, sizeClass(slotCount + 1) * 4L, CgBufferUsage.STORAGE);
        CgGpuCount live = CgGpuCount.at(pool.live(), 0, storage);

        CgComputePass pass = recording.compute(passName).timed(GPU_RANGE);
        CgDispatch keyed = pass.dispatch(key, storage).bind("RECORDS", records).bind("LIVE", pool.live()).bind("SLOTS", slots)
                .bind("KEYS", keys).bind("INDICES", indices).set("_Slots", slotCount);
        for (int p = 0; p < 6; p++) {
            viewProjection.frustumPlane(p, plane);
            keyed.set(PLANES[p], plane.x, plane.y, plane.z, plane.w);
        }
        CgGpuOps.sort(pass, 32 - Integer.numberOfLeadingZeros(slotCount), CgGpuOps.Order.ASCENDING, keys, indices, live);
        CgGpuOps.histogram(pass, keys, live, visible, slotCount + 1, 0);
        CgGpuOps.scan(pass, CgGpuOps.Scan.EXCLUSIVE, CgGpuOps.Fold.SUM, CgGpuOps.Element.UINT, visible,
                CgGpuCount.of(slotCount + 1), starts);
        if (pool.capacity() > 0) pass.dispatch(place, pool.capacity()).bind("RECORDS", records).bind("INDICES", indices).bind("VISIBLE", visible)
                .bind("STARTS", starts).bind("BASES", bases).bind("CURVES", pool.curves()).bind("DRAWN", drawn)
                .set("_Slots", slotCount).set("_Alpha", alpha).set("_Ahead", ahead)
                .set("_Texels", CgVfxGpuEmitter.CURVE_TEXELS);
        if (objectsAsked && pool.capacity() > 0) {
            pass.dispatch(kernels.kernel("Objects"), pool.capacity()).bind("RECORDS", records).bind("INDICES", indices)
                    .bind("VISIBLE", visible).bind("STARTS", starts).bind("BASES", bases).bind("CURVES", pool.curves())
                    .bind("OBJECTS", objects).set("_Slots", slotCount).set("_Alpha", alpha)
                    .set("_Texels", CgVfxGpuEmitter.CURVE_TEXELS);
        }
        objectsAsked = false;
        pass.end();
    }

    /** Each slot's origin from the camera and its reach, and its list base, for this view. */
    private void uploadSlots(CgRecording recording, CgHostView view, int slotCount) {
        slots = fit(recording, slots, slotsName, slotCount * 16L);
        bases = fit(recording, bases, basesName, slotCount * 4L);
        if (staging.capacity() < slotCount * 16) staging = ByteBuffer.allocate(Math.max(slotCount * 16, staging.capacity() * 2)).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int s = 0; s < slotCount; s++) {
            boolean open = pool.isOpen(s);
            staging.putFloat(s * 16, open ? (float) (pool.origin(s, 0) - view.x()) : 0f);
            staging.putFloat(s * 16 + 4, open ? (float) (pool.origin(s, 1) - view.y()) : 0f);
            staging.putFloat(s * 16 + 8, open ? (float) (pool.origin(s, 2) - view.z()) : 0f);
            staging.putFloat(s * 16 + 12, open ? pool.cullRadius(s) : 0f);
        }
        staging.limit(slotCount * 16);
        recording.update(slots, 0, staging);
        staging.clear();
        for (int s = 0, base = 0; s < slotCount; s++) {
            staging.putInt(s * 4, base);
            if (pool.isOpen(s)) base += pool.capacity(s);
        }
        staging.limit(slotCount * 4);
        recording.update(bases, 0, staging);
    }

    private void release(CgRecording recording) {
        for (int i = 0; i < retired.size(); i++) recording.release(retired.get(i));
        retired.clear();
        CgGraphBuffer[] owned = {drawn, visible, slots, bases, objects};
        for (CgGraphBuffer buffer : owned) if (buffer != null) recording.release(buffer);
        drawn = visible = slots = bases = objects = null;
    }

    /** {@code buffer}, or one of the next size class in its place when it holds fewer than {@code bytes}. */
    private static CgGraphBuffer fit(CgRecording recording, CgGraphBuffer buffer, String name, long bytes) {
        long size = Math.max(256L, Long.highestOneBit(Math.max(bytes, 1L) - 1) << 1);
        if (buffer != null && buffer.size() >= bytes) return buffer;
        if (buffer != null) recording.release(buffer);
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(size, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }

    private static int sizeClass(int n) {
        return Math.max(64, Integer.highestOneBit(Math.max(n, 1) - 1) << 1);
    }
}
