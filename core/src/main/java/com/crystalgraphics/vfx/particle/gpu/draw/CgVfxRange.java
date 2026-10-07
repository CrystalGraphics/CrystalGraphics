package com.crystalgraphics.vfx.particle.gpu.draw;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Range (vfx-gpu §13.4): every particle pool's draws for one view. Each live particle is culled against the view;
 * those left are grouped by slot and written as the particle records a look reads through {@code CG_PARTICLE_*}, each
 * slot's from its base, interpolated and given its curves as the CPU path's records are. Every pool shares one sort and
 * one set of buffers, so a frame costs the same few dispatches however many pools draw. A slot's draws read
 * {@link #drawn()} from {@link #base} and draw as many as {@link #visible()} counted at {@link #visibleWord}.
 *
 * <pre>{@code
 * CgVfxRange range = CgVfxRange.of(pool);
 * range.frame(frame.particleAlpha(), frame.particleAlpha() * particleDt);   // each frame, before the world records
 * world.draw(quads, spark)                                                  // per slot and layer
 *      .buffer(CgBindingPoints.PARTICLES, range.drawn())
 *      .indirect(range.visible(), range.visibleWord(slot) * 4L, CgIndirect.INDICES, 6)
 *      .custom(0, range.base(slot), capacity, radius, parameter)
 *      .at(ox, oy, oz).gpuCulled().group(ox, oy, oz).submit();
 * }</pre>
 *
 * <p>A mesh per particle ({@code MESHES}) draws the same particles as object records, culled and given levels again on
 * the GPU; each layer scales them and states its own customs:</p>
 *
 * <pre>{@code
 * world.draw(billow, smoke)
 *      .instances(range.objects(), range.base(slot), CgGpuCount.at(range.visible(), range.visibleWord(slot), capacity))
 *      .instanceScale(layer.radius()).custom(0, radius, parameter, age, seed)
 *      .at(ox, oy, oz).gpuCulled().group(ox, oy, oz).submit();
 * }</pre>
 *
 * <ul>
 *   <li>It records on every firing of {@link CgRenderStage#WORLD_OPAQUE} at {@link #ORDER}, after the pools step and
 *       before the world renderer: a second firing with another view culls again.</li>
 *   <li>Ask {@link #drawn()}, {@link #visible()}, {@link #base} and {@link #visibleWord} after the frame's slots open:
 *       each pool's place in the shared buffers follows every pool's slots, and a draw holding an older answer reads
 *       another slot's particles.</li>
 *   <li>{@code visibleWord(slot)}, never {@code slot}: the counts of every pool's slots share {@link #visible()}.</li>
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
    /** The low key bits a sorting slot's view depth takes. */
    private static final int DEPTH_BITS = 16;
    /** Each pool's records start at a multiple of this in the shared buffers: a binding offset is a whole 256 bytes. */
    private static final int RECORD_ALIGN = 4;
    /** And its keys at a multiple of this. */
    private static final int KEY_ALIGN = 64;

    /** Every pool's range, by pool, and in the order made: the order their slots are numbered in. Render thread. */
    private static final Map<CgVfxParticlePool, CgVfxRange> RANGES = new IdentityHashMap<>();
    private static final List<CgVfxRange> ALL = new ArrayList<>();
    private static CgRenderStage.Registration registration;

    private static CgGraphBuffer drawn, visible, slots, bases, objects, sorts;
    private static final List<CgGraphBuffer> RETIRED = new ArrayList<>();
    private static boolean anySorted;
    /** Whether {@link #objects()} was asked for since the last recording. */
    private static boolean objectsAsked;

    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Vector4f PLANE = new Vector4f();
    private static ByteBuffer staging = ByteBuffer.allocate(0);

    private final CgVfxParticlePool pool;
    /** Per slot: whether this frame draws its particles far to near. */
    private boolean[] sortedSlots = new boolean[8];
    private float alpha = 1f, ahead;

    private CgVfxRange(CgVfxParticlePool pool) {
        this.pool = pool;
    }

    /** {@code pool}'s range, made the first time; the first made registers ranges' recording. Render thread. */
    public static CgVfxRange of(CgVfxParticlePool pool) {
        CgVfxRange range = RANGES.get(pool);
        if (range == null) {
            RANGES.put(pool, range = new CgVfxRange(pool));
            ALL.add(range);
            if (registration == null) registration = CgRenderStage.WORLD_OPAQUE.register(ORDER, CgVfxRange::recordAll);
        }
        return range;
    }

    /**
     * Starts the programs every range dispatches, ahead of the first frame that draws a GPU particle: below compute
     * they are some twenty lowered programs, otherwise built on that frame. Render thread, beside
     * {@link CgVfxParticlePool#prepare}.
     */
    public static void prepare() {
        CgCompute kernels = CgCompute.load(KERNELS);
        kernels.kernel("Key").prepare();
        kernels.kernel("Place").prepare();
        kernels.kernel("Objects").prepare();
        CgGpuOps.prepareSort(CgGpuOps.Element.UINT, CgGpuOps.Order.ASCENDING);
        CgGpuOps.prepareHistogram();
        CgGpuOps.prepareScan(CgGpuOps.Scan.EXCLUSIVE, CgGpuOps.Fold.SUM, CgGpuOps.Element.UINT);
        CgGpuOps.prepareDepthPyramid();
        CgGpuOps.prepareCull(true);   // MESHES slots, drawn through the world renderer's cull
    }

    /** Forgets every range and stops recording. Tests, and context teardown. */
    static void forgetAll() {
        RANGES.clear();
        ALL.clear();
        RETIRED.clear();
        drawn = visible = slots = bases = objects = sorts = null;
        anySorted = objectsAsked = false;
        if (registration != null) {
            registration.close();
            registration = null;
        }
    }

    /**
     * Where this frame draws the pool's particles: {@code alpha} of the way from their last step to the next, the CPU
     * path's particle alpha, with spin pushed {@code aheadSeconds} past the last step. Each frame, before the world
     * records.
     */
    public CgVfxRange frame(float alpha, float aheadSeconds) {
        this.alpha = alpha;
        this.ahead = aheadSeconds;
        return this;
    }

    /** The particle records every pool's draws read, {@code CgParticleBuffer}'s layout, a slot's from {@link #base}. */
    public CgGraphBuffer drawn() {
        int need = Math.max(1, drawnBefore(ALL.size()));
        if (drawn == null || drawn.size() < (long) need * DRAWN_BYTES) {
            if (drawn != null) RETIRED.add(drawn);
            drawn = CgGraphBuffer.persistent("vfx.range.drawn",
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
        int need = Math.max(1, drawnBefore(ALL.size()));
        if (objects == null || objects.size() < (long) need * CgGpuOps.cullRecordBytes()) {
            if (objects != null) RETIRED.add(objects);
            objects = CgGraphBuffer.persistent("vfx.range.objects",
                    CgBufferDesc.elements(sizeClass(need), CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        return objects;
    }

    /**
     * Has this frame's {@link #drawn()} hold {@code slot}'s particles far to near, for a layer that blends them in
     * order. The other slots keep the pool's order, and the sort takes 16 more bits in a frame any slot asks. Each
     * frame, before the world records.
     */
    public CgVfxRange sorted(int slot) {
        if (slot >= sortedSlots.length) sortedSlots = Arrays.copyOf(sortedSlots, Math.max(slot + 1, sortedSlots.length * 2));
        sortedSlots[slot] = true;
        anySorted = true;
        return this;
    }

    /**
     * How many of each slot's particles are visible, every pool's: a {@code uint} per slot at {@link #visibleWord}, a
     * draw's indirect count.
     */
    public CgGraphBuffer visible() {
        int need = slotsBefore(ALL.size()) + 1;
        if (visible == null || visible.size() < need * 4L) {
            if (visible != null) RETIRED.add(visible);
            visible = CgGraphBuffer.persistent("vfx.range.visible",
                    CgBufferDesc.elements(sizeClass(need), 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        return visible;
    }

    /** Which word of {@link #visible()} counts {@code slot}'s visible particles: its byte offset over 4. */
    public int visibleWord(int slot) {
        return slotsBefore(ALL.indexOf(this)) + slot;
    }

    /** Where {@code slot}'s records start in {@link #drawn()} and {@link #objects()}: its draws' base. */
    public int base(int slot) {
        return drawnBefore(ALL.indexOf(this)) + pool.listBase(slot);
    }

    /** The slots of the pools before the {@code n}th. */
    private static int slotsBefore(int n) {
        int at = 0;
        for (int i = 0; i < n; i++) {
            CgVfxParticlePool p = ALL.get(i).pool;
            if (!p.isReleased()) at += p.slotCount();
        }
        return at;
    }

    /** The records of the pools before the {@code n}th, each pool's aligned. */
    private static int drawnBefore(int n) {
        int at = 0;
        for (int i = 0; i < n; i++) {
            CgVfxParticlePool p = ALL.get(i).pool;
            if (!p.isReleased()) at += align(p.capacity(), RECORD_ALIGN);
        }
        return at;
    }

    /** Whether {@code p}'s particles are keyed this frame: it has records, and a slot to draw them. */
    private static boolean keyed(CgVfxParticlePool p) {
        return !p.isReleased() && p.records() != null && p.openSlots() > 0;
    }

    private static void recordAll(CgStageFrame frame) {
        for (int i = ALL.size() - 1; i >= 0; i--) {
            CgVfxRange range = ALL.get(i);
            if (range.pool.isReleased()) {
                RANGES.remove(range.pool);
                ALL.remove(i);
            }
        }
        if (ALL.isEmpty()) {
            release(frame.recording());
            return;
        }
        boolean any = false;
        for (int i = 0; i < ALL.size(); i++) any |= keyed(ALL.get(i).pool);
        record(frame.recording(), frame.host().view(), any ? frame.depthPyramid() : null);
    }

    /**
     * Records every range for {@code view} into {@code recording}. Ranges record themselves on every firing of
     * {@link CgRenderStage#WORLD_OPAQUE}; call it only where that stage does not fire, as a check scene. Render thread.
     */
    public static void record(CgRecording recording, CgHostView view) {
        record(recording, view, null);
    }

    /**
     * {@link #record(CgRecording, CgHostView)}, testing each particle's reach against {@code pyramid} too
     * ({@code CgStageFrame.depthPyramid}, under the same view): what hides behind the scene's depth is culled.
     */
    public static void record(CgRecording recording, CgHostView view, @Nullable CgGraphTexture pyramid) {
        for (int i = 0; i < RETIRED.size(); i++) recording.release(RETIRED.get(i));
        RETIRED.clear();
        if (ALL.isEmpty()) return;
        CgVfxRange first = ALL.get(0);
        CgGraphBuffer drawn = first.drawn(), visible = first.visible();
        CgGraphBuffer objects = objectsAsked ? first.objects() : null;
        int allSlots = slotsBefore(ALL.size()), keys = 0;
        for (int i = 0; i < ALL.size(); i++) {
            CgVfxParticlePool p = ALL.get(i).pool;
            if (keyed(p)) keys = align(keys, KEY_ALIGN) + p.storage();
        }
        if (keys == 0) {
            recording.fill(visible, 0);
            clearSorted();
            return;
        }
        uploadSlots(recording, view, allSlots);
        VIEW_PROJECTION.set(view.projection()).mul(view.view());
        CgCompute kernels = CgCompute.load(KERNELS);
        CgKernel key = kernels.kernel("Key"), place = kernels.kernel("Place"), objected = kernels.kernel("Objects");
        CgGraphBuffer keyBuffer = recording.scratch("vfx.range.keys", keys * 4L, CgBufferUsage.STORAGE);
        CgGraphBuffer indices = recording.scratch("vfx.range.indices", keys * 4L, CgBufferUsage.STORAGE);
        CgGraphBuffer starts = recording.scratch("vfx.range.starts", sizeClass(allSlots + 1) * 4L, CgBufferUsage.STORAGE);
        int depthBits = anySorted ? DEPTH_BITS : 0;
        Matrix4fc projection = view.projection();
        Matrix4f vp = VIEW_PROJECTION;

        CgComputePass pass = recording.compute("vfx.range").timed(GPU_RANGE);
        for (int i = 0, keyFirst = 0, slotFirst = 0; i < ALL.size(); i++) {
            CgVfxParticlePool p = ALL.get(i).pool;
            if (p.isReleased()) continue;
            if (keyed(p)) {
                keyFirst = align(keyFirst, KEY_ALIGN);
                CgDispatch keyed = pass.dispatch(key, p.storage()).bind("RECORDS", p.records()).bind("LIVE", p.live())
                        .bind("SLOTS", slots).bind("SORTS", sorts)
                        .bind("KEYS", keyBuffer, keyFirst * 4L, p.storage() * 4L)
                        .bind("INDICES", indices, keyFirst * 4L, p.storage() * 4L)
                        .set("_SlotFirst", slotFirst).set("_AllSlots", allSlots).set("_DepthBits", depthBits)
                        .set("_ClipX", vp.m00(), vp.m10(), vp.m20(), vp.m30()).set("_ClipY", vp.m01(), vp.m11(), vp.m21(), vp.m31())
                        .set("_ClipZ", vp.m02(), vp.m12(), vp.m22(), vp.m32()).set("_ClipW", vp.m03(), vp.m13(), vp.m23(), vp.m33())
                        .set("_Eye", projection.m22(), projection.m23(), projection.m32(), projection.m33());
                if (pyramid != null) {
                    keyed.texture("_Pyramid", pyramid).set("_PyramidSize", pyramid.getWidth(), pyramid.getHeight(), pyramid.getLevels(), 0f);
                } else {
                    keyed.texture("_Pyramid", CgFallbackTextures.WHITE_1x1).set("_PyramidSize", 0f, 0f, 0f, 0f);
                }
                for (int k = 0; k < 6; k++) {
                    VIEW_PROJECTION.frustumPlane(k, PLANE);
                    keyed.set(PLANES[k], PLANE.x, PLANE.y, PLANE.z, PLANE.w);
                }
                keyFirst += p.storage();
            }
            slotFirst += p.slotCount();
        }
        CgGpuCount all = CgGpuCount.of(keys);
        CgGpuOps.sort(pass, 32 - Integer.numberOfLeadingZeros(allSlots) + depthBits, CgGpuOps.Order.ASCENDING, keyBuffer,
                indices, all);
        CgGpuOps.histogram(pass, keyBuffer, all, visible, allSlots + 1, depthBits);
        CgGpuOps.scan(pass, CgGpuOps.Scan.EXCLUSIVE, CgGpuOps.Fold.SUM, CgGpuOps.Element.UINT, visible,
                CgGpuCount.of(allSlots + 1), starts);
        for (int i = 0, drawnFirst = 0, slotFirst = 0; i < ALL.size(); i++) {
            CgVfxRange range = ALL.get(i);
            CgVfxParticlePool p = range.pool;
            if (p.isReleased()) continue;
            int capacity = p.capacity();
            if (keyed(p) && capacity > 0) {
                pass.dispatch(place, capacity).bind("RECORDS", p.records()).bind("INDICES", indices).bind("VISIBLE", visible)
                        .bind("STARTS", starts).bind("BASES", bases).bind("CURVES", p.curves())
                        .bind("DRAWN", drawn, (long) drawnFirst * DRAWN_BYTES, (long) capacity * DRAWN_BYTES)
                        .set("_Slots", p.slotCount()).set("_SlotFirst", slotFirst).set("_Alpha", range.alpha)
                        .set("_Ahead", range.ahead).set("_Texels", CgVfxGpuEmitter.CURVE_TEXELS);
                if (objects != null) {
                    long bytes = CgGpuOps.cullRecordBytes();
                    pass.dispatch(objected, capacity).bind("RECORDS", p.records()).bind("INDICES", indices)
                            .bind("VISIBLE", visible).bind("STARTS", starts).bind("BASES", bases).bind("CURVES", p.curves())
                            .bind("OBJECTS", objects, drawnFirst * bytes, capacity * bytes)
                            .set("_Slots", p.slotCount()).set("_SlotFirst", slotFirst).set("_Alpha", range.alpha)
                            .set("_Texels", CgVfxGpuEmitter.CURVE_TEXELS);
                }
            }
            drawnFirst += align(capacity, RECORD_ALIGN);
            slotFirst += p.slotCount();
        }
        objectsAsked = false;
        clearSorted();
        pass.end();
    }

    private static void clearSorted() {
        if (!anySorted) return;
        for (int i = 0; i < ALL.size(); i++) Arrays.fill(ALL.get(i).sortedSlots, false);
        anySorted = false;
    }

    /** Each slot's origin from the camera and its reach, its list base in its pool, and whether it sorts, for this view. */
    private static void uploadSlots(CgRecording recording, CgHostView view, int allSlots) {
        slots = fit(recording, slots, "vfx.range.slots", allSlots * 16L);
        bases = fit(recording, bases, "vfx.range.bases", allSlots * 4L);
        sorts = fit(recording, sorts, "vfx.range.sorts", allSlots * 4L);
        if (staging.capacity() < allSlots * 16) staging = ByteBuffer.allocate(Math.max(allSlots * 16, staging.capacity() * 2)).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0, g = 0; i < ALL.size(); i++) {
            CgVfxParticlePool p = ALL.get(i).pool;
            if (p.isReleased()) continue;
            for (int s = 0; s < p.slotCount(); s++, g++) {
                boolean open = p.isOpen(s);
                staging.putFloat(g * 16, open ? (float) (p.origin(s, 0) - view.x()) : 0f);
                staging.putFloat(g * 16 + 4, open ? (float) (p.origin(s, 1) - view.y()) : 0f);
                staging.putFloat(g * 16 + 8, open ? (float) (p.origin(s, 2) - view.z()) : 0f);
                // Below 0: the slot is one sphere of that radius about its origin (cullAbout).
                float source = open ? p.cullSourceRadius(s) : 0f;
                staging.putFloat(g * 16 + 12, !open ? 0f : source > 0f ? -source : p.cullRadius(s));
            }
        }
        staging.limit(allSlots * 16);
        recording.update(slots, 0, staging);
        staging.clear();
        for (int i = 0, g = 0; i < ALL.size(); i++) {
            CgVfxParticlePool p = ALL.get(i).pool;
            if (p.isReleased()) continue;
            for (int s = 0, base = 0; s < p.slotCount(); s++, g++) {
                staging.putInt(g * 4, base);
                if (p.isOpen(s)) base += p.capacity(s);
            }
        }
        staging.limit(allSlots * 4);
        recording.update(bases, 0, staging);
        staging.clear();
        for (int i = 0, g = 0; i < ALL.size(); i++) {
            CgVfxRange range = ALL.get(i);
            if (range.pool.isReleased()) continue;
            boolean[] sorted = range.sortedSlots;
            for (int s = 0; s < range.pool.slotCount(); s++, g++) staging.putInt(g * 4, s < sorted.length && sorted[s] ? 1 : 0);
        }
        staging.limit(allSlots * 4);
        recording.update(sorts, 0, staging);
    }

    /** The shared buffers, once no pool is left. */
    private static void release(CgRecording recording) {
        for (int i = 0; i < RETIRED.size(); i++) recording.release(RETIRED.get(i));
        RETIRED.clear();
        CgGraphBuffer[] owned = {drawn, visible, slots, bases, objects, sorts};
        for (CgGraphBuffer buffer : owned) if (buffer != null) recording.release(buffer);
        drawn = visible = slots = bases = objects = sorts = null;
    }

    /** {@code buffer}, or one of the next size class in its place when it holds fewer than {@code bytes}. */
    private static CgGraphBuffer fit(CgRecording recording, CgGraphBuffer buffer, String name, long bytes) {
        long size = Math.max(256L, Long.highestOneBit(Math.max(bytes, 1L) - 1) << 1);
        if (buffer != null && buffer.size() >= bytes) return buffer;
        if (buffer != null) recording.release(buffer);
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(size, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }

    private static int align(int n, int to) {
        return (n + to - 1) / to * to;
    }

    private static int sizeClass(int n) {
        return Math.max(64, Integer.highestOneBit(Math.max(n, 1) - 1) << 1);
    }
}
