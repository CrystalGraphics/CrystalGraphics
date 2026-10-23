package com.crystalgraphics.vfx.particle.ribbon;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;

/**
 * A pool's ribbons: each frame, Niagara's GPU ribbon init ({@code NiagaraRendererRibbons.cpp},
 * {@code InitializeVertexBuffersGPU}) over every slot a ribbon renderer draws, after Range has placed the frame's
 * particles, into one packed {@link #run()} the strip factory reads and an {@link #args()} word a slot that a draw counts
 * vertices by. A Cascade trail ({@link CgVfxRibbonRendererProperties#trail()}) runs the same stages with its own link
 * order and each segment's points counted as Cascade's {@code DetermineVertexAndTriangleCount} counts them.
 *
 * <pre>{@code
 * // each frame, as an emitter's ribbon renderer draws its slot (CgVfxFrame does this):
 * CgVfxRibbonGeneration ribbons = CgVfxRibbonGeneration.of(pool).ribbon(slot, renderer);
 * world.draw(CgMesh.vertices(ribbons.vertices(slot, renderer), CgMeshTopology.TRIANGLES), material)
 *      .buffer(CgBindingPoints.PARTICLE_BEAMS, ribbons.run())
 *      .indirect(ribbons.args(), ribbons.argsWord(slot), CgIndirect.VERTICES, 1)
 *      .custom(0, orderBase, ribbons.slotRecord(slot), size, ribbons.mode(renderer))...
 *
 * // Range then records every pool's generation in its own pass, after Place:
 * generation.upload(recording, view, slotFirst);
 * generation.dispatch(pass, order, orderOffset, visible);
 * }</pre>
 *
 * <ul>
 *   <li>Ask {@link #run()} and {@link #args()} after {@link #ribbon}: a pool that grew makes them anew, and a draw holding an
 *       older handle reads another frame's.</li>
 *   <li>A ribbon slot is culled as one sphere ({@code CgVfxParticlePool.cullAbout}): a particle culled mid-ribbon
 *       would join its neighbours across the gap.</li>
 *   <li>One ribbon renderer a slot: a second in the same frame throws, naming both.</li>
 * </ul>
 */
public final class CgVfxRibbonGeneration {

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/ribbon.compute";
    /** A slot's settings, FxRibbonSlot: seven vec4s. */
    private static final int SLOT_WORDS = 64, SLOT_RECORD = 10, POSITION_RECORD = 4;
    /** The most points between two anim trail samples the draw holds, where its renderer sets none. */
    private static final int ANIM_POINTS = 64;
    /** Niagara's CVars (NiagaraRendererRibbons.cpp:47-82): its 0.5 Unreal units of error in blocks. */
    private static final float MAX_TESSELLATION = 16f, MIN_ANGLE = (float) Math.toRadians(15), SCREEN = 0.002f, MIN_ERROR = 0.005f;
    private static final int FLAG_RIBBON = 1, FLAG_TRAIL = 2, FLAG_BY_ID = 4, FLAG_RESET = 8, FLAG_TANGENT_INTERP = 16,
            FLAG_EVERY_FRAME = 32, FLAG_ANIM = 64, FLAG_RECALCULATE_PREVIOUS = 128;

    private static final Map<CgVfxParticlePool, CgVfxRibbonGeneration> POOLS = new IdentityHashMap<>();

    private final CgVfxParticlePool pool;
    private final String name;
    private CgVfxRibbonRendererProperties[] lanes = new CgVfxRibbonRendererProperties[8];
    private CgVfxGpuEmitter[] seen = new CgVfxGpuEmitter[8];
    /** Each anim trail slot's alpha and width this frame. */
    private float[] fade = new float[16];
    private boolean any;
    private CgGraphBuffer run, args, state, slots;
    private CgGraphBuffer retiredRun, retiredArgs, retiredState;
    private int slotsCapacity, positions;
    /** The most trails a slot of this frame keeps: what a ribbon id spans. */
    private int trails = 1;
    private int[] words = new int[0];
    private CgGpuCount count;
    private CgKernel keys, links, init, propagate, finish, ribbons, argsKernel;
    // This frame's scratch, from upload to dispatch.
    private CgGraphBuffer linkKeys, keyBuffer, values, tA, tB, uA, uB, sA, sB;
    private int slotCount;

    private CgVfxRibbonGeneration(CgVfxParticlePool pool) {
        this.pool = pool;
        this.name = "vfx.ribbons." + System.identityHashCode(pool);
    }

    /** {@code pool}'s generation, made the first time. Render thread. */
    public static CgVfxRibbonGeneration of(CgVfxParticlePool pool) {
        return POOLS.computeIfAbsent(pool, CgVfxRibbonGeneration::new);
    }

    /** {@code pool}'s generation if a ribbon has drawn from it, else null. Render thread. */
    public static CgVfxRibbonGeneration existing(CgVfxParticlePool pool) {
        return POOLS.get(pool);
    }

    /** Every generation's buffers are gone with the context: each makes them again at its next ask. */
    public static void forgetAll() {
        for (CgVfxRibbonGeneration g : POOLS.values()) {
            g.run = g.args = g.state = g.slots = g.retiredRun = g.retiredArgs = g.retiredState = null;
            g.slotsCapacity = g.positions = 0;
            g.any = false;
        }
    }

    /** Forgets {@code pool}'s generation, its buffers released in {@code recording}. */
    public static void release(CgVfxParticlePool pool, CgRecording recording) {
        CgVfxRibbonGeneration g = POOLS.remove(pool);
        if (g == null) return;
        for (CgGraphBuffer b : new CgGraphBuffer[] {g.run, g.args, g.state, g.slots, g.retiredRun, g.retiredArgs, g.retiredState}) {
            if (b != null) recording.release(b);
        }
    }

    /** Starts its kernels' programs and the sorts it runs, ahead of the first ribbon. Render thread. */
    public static void prepare() {
        CgCompute file = CgCompute.load(KERNELS);
        for (String k : new String[] {"Keys", "Links", "Init", "Propagate", "Finish", "Ribbons", "Args"}) file.kernel(k).prepare();
        CgGpuOps.prepareSort(CgGpuOps.Element.FLOAT, CgGpuOps.Order.ASCENDING);
        CgGpuOps.prepareSort(CgGpuOps.Element.UINT, CgGpuOps.Order.ASCENDING);
    }

    /**
     * Has this frame generate {@code slot}'s ribbons as {@code renderer} draws them, and sizes {@link #run()} and
     * {@link #args()} to the pool as it stands. Each frame the slot draws, before Range records.
     */
    public CgVfxRibbonGeneration ribbon(int slot, CgVfxRibbonRendererProperties renderer) {
        if (slot >= lanes.length) {
            lanes = Arrays.copyOf(lanes, Math.max(slot + 1, lanes.length * 2));
            seen = Arrays.copyOf(seen, lanes.length);
        }
        if (lanes[slot] != null && lanes[slot] != renderer) {
            throw new IllegalStateException(pool.emitter(slot) + " draws two ribbon renderers, " + lanes[slot].common().id()
                    + " and " + renderer.common().id() + ": an emitter draws one");
        }
        if ((renderer.trail() != null || renderer.anim() != null) && !pool.shape().trails()) {
            throw new IllegalStateException(pool.emitter(slot) + " draws Cascade trail " + renderer.common().id()
                    + " from a pool that keeps no trail payload: its emitter must spawn by that trail");
        }
        lanes[slot] = renderer;
        if (fade.length < lanes.length * 2) fade = Arrays.copyOf(fade, lanes.length * 2);
        fade[slot * 2] = fade[slot * 2 + 1] = 1f;
        any = true;
        fit();
        return this;
    }

    /** The packed run every ribbon draw of the pool reads, at {@code CgBindingPoints.PARTICLE_BEAMS}. */
    public CgGraphBuffer run() {
        return run;
    }

    /** Each slot's vertex count, a uint a slot at {@link #argsWord}. */
    public CgGraphBuffer args() {
        return args;
    }

    /** The byte offset of {@code slot}'s vertex count in {@link #args()}. */
    public long argsWord(int slot) {
        return slot * 4L;
    }

    /** Sets anim trail {@code slot}'s alpha and width this frame, after {@link #ribbon}: 1 each until it fades. */
    public void fade(int slot, float alpha, float width) {
        fade[slot * 2] = alpha;
        fade[slot * 2 + 1] = width;
    }

    /** The vec4 of {@link #run()} that {@code slot}'s record starts at: what the factory reads its range from. */
    public int slotRecord(int slot) {
        return slot * SLOT_RECORD;
    }

    /** The vec4 of {@link #run()} that position records start at. */
    public int positionAt() {
        return slotsCapacity * SLOT_RECORD;
    }

    /** The vec4 of {@link #run()} that ribbon records start at. */
    public int ribbonAt() {
        return slotsCapacity * SLOT_RECORD + positions * POSITION_RECORD;
    }

    /** The most vertices {@code slot}'s draw can count: what its mesh must hold. */
    public int vertices(int slot, CgVfxRibbonRendererProperties renderer) {
        int capacity = Math.max(pool.capacity(slot), 2);
        int per = renderer.trail() != null ? Math.max(renderer.trail().maxTessellation(), 1)
                : renderer.anim() != null ? (renderer.anim().maxTessellation() > 0 ? renderer.anim().maxTessellation() : ANIM_POINTS)
                : (int) MAX_TESSELLATION;
        return (capacity - 1) * per * CgVfxRibbonShape.of(renderer).verticesPerSegment();
    }

    /** Whether any slot asked for ribbons this frame. */
    public boolean wanted() {
        return any;
    }

    private void fit() {
        int wantSlots = sizeClass(pool.slotCount()), wantPositions = sizeClass(pool.capacity());
        if (run != null && wantSlots <= slotsCapacity && wantPositions <= positions) return;
        slotsCapacity = Math.max(wantSlots, slotsCapacity);
        positions = Math.max(wantPositions, positions);
        retiredRun = run;
        retiredArgs = args;
        retiredState = state;
        long vectors = slotsCapacity * (long) SLOT_RECORD + positions * (POSITION_RECORD + 2L);
        run = CgGraphBuffer.persistent(name + ".run", CgBufferDesc.elements((int) vectors, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        args = CgGraphBuffer.persistent(name + ".args", CgBufferDesc.elements(slotsCapacity, 4,
                CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT, CgBufferUsage.COPY));
        state = CgGraphBuffer.persistent(name + ".state", CgBufferDesc.elements(slotsCapacity * 2, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        Arrays.fill(seen, null);
    }

    /** A frame Range keys no particle: every slot asking draws nothing. */
    public void skip(CgRecording recording) {
        if (!any) return;
        any = false;
        Arrays.fill(lanes, null);
        recording.fill(args, 0);
    }

    /**
     * Uploads this frame's slot settings and takes its scratch, for {@code view}: before the pass {@link #dispatch}
     * records into. {@code slotFirst} is the pool's first slot among Range's visible counts. Nothing when no slot asked.
     */
    public void upload(CgRecording recording, CgHostView view, int slotFirst) {
        if (!any) return;
        if (retiredRun != null) {
            recording.release(retiredRun);
            recording.release(retiredArgs);
            recording.release(retiredState);
            retiredRun = retiredArgs = retiredState = null;
            recording.fill(state, 0);
        }
        slotCount = pool.slotCount();
        trails = 1;
        if (words.length < slotCount * SLOT_WORDS) words = new int[Math.max(slotCount * SLOT_WORDS, words.length * 2)];
        Arrays.fill(words, 0, slotCount * SLOT_WORDS, 0);
        for (int s = 0, base = 0; s < slotCount; s++) {
            int o = s * SLOT_WORDS;
            boolean open = pool.isOpen(s);
            int capacity = open ? pool.capacity(s) : 0;
            words[o] = base;
            words[o + 1] = capacity;
            words[o + 2] = slotFirst + s;
            CgVfxRibbonRendererProperties r = s < lanes.length ? lanes[s] : null;
            if (open && r != null) {
                CgVfxGpuEmitter emitter = pool.emitter(s);
                CgVfxRibbonRendererProperties.AnimTrail anim = r.anim();
                int flags = FLAG_RIBBON | (r.trail() != null || anim != null ? FLAG_TRAIL : 0) | (r.linkOrderById() ? FLAG_BY_ID : 0)
                        | (anim != null ? FLAG_ANIM : 0) | (anim != null && anim.everyFrame() ? FLAG_EVERY_FRAME : 0)
                        | (anim != null && anim.recalculatePrevious() ? FLAG_RECALCULATE_PREVIOUS : 0)
                        | (seen[s] != emitter ? FLAG_RESET : 0)
                        | (r.trail() != null && r.trail().tangentInterp() ? FLAG_TANGENT_INTERP : 0)
                        | (r.trail() != null && r.trail().everyFrame() ? FLAG_EVERY_FRAME : 0);
                if (r.trail() != null) trails = Math.max(trails, r.trail().maxTrails());
                seen[s] = emitter;
                words[o + 3] = flags;
                double dx = pool.origin(s, 0) - view.x(), dy = pool.origin(s, 1) - view.y(), dz = pool.origin(s, 2) - view.z();
                put(o + 4, r.tessellation().tension(), r.maxLinkGap(), (float) (dx * dx + dy * dy + dz * dz), 0f);
                CgVfxRibbonRendererProperties.Tessellation t = r.tessellation();
                put(o + 8, t.mode().ordinal(), t.factor(), t.minAngle(), (t.constant() ? 1 : 0) | (t.screenSpace() ? 2 : 0));
                put(o + 12, r.uv0().distribution().ordinal(), r.uv0().leadingEdge().ordinal(), r.uv0().trailingEdge().ordinal(),
                        r.uv0().tilingLength());
                put(o + 16, r.uv1().distribution().ordinal(), r.uv1().leadingEdge().ordinal(), r.uv1().trailingEdge().ordinal(),
                        r.uv1().tilingLength());
                put(o + 20, r.uv0().scaleU(), r.uv0().offsetU(), r.uv1().scaleU(), r.uv1().offsetU());
                if (r.trail() != null) put(o + 24, r.trail().distanceStep(), r.trail().tangentScale(), r.trail().tilingDistance(), 0f);
                if (anim != null) {
                    put(o + 24, 0f, 0f, anim.tilingDistance(), 0f);
                    along(o + 28, anim.width());
                    along(o + 36, anim.colour());
                    put(o + 56, anim.distanceStep(), anim.angleStep(), anim.widthStep(), anim.maxTessellation());
                    put(o + 60, fade[s * 2], fade[s * 2 + 1], 0f, 0f);
                }
            } else {
                seen[s] = null;
            }
            base += capacity;
        }
        long bytes = Math.max(256L, slotsCapacity * (long) SLOT_WORDS * 4L);
        if (slots == null || slots.size() < bytes) {
            if (slots != null) recording.release(slots);
            slots = CgGraphBuffer.persistent(name + ".slots", CgBufferDesc.of(bytes, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        recording.update(slots, 0, words, 0, slotCount * SLOT_WORDS);
        int n = positions;
        linkKeys = recording.scratch(name + ".links", n * 4L, CgBufferUsage.STORAGE);
        keyBuffer = recording.scratch(name + ".keys", n * 4L, CgBufferUsage.STORAGE);
        values = recording.scratch(name + ".values", n * 4L, CgBufferUsage.STORAGE);
        tA = recording.scratch(name + ".tA", n * 16L, CgBufferUsage.STORAGE);
        tB = recording.scratch(name + ".tB", n * 16L, CgBufferUsage.STORAGE);
        uA = recording.scratch(name + ".uA", n * 16L, CgBufferUsage.STORAGE);
        uB = recording.scratch(name + ".uB", n * 16L, CgBufferUsage.STORAGE);
        sA = recording.scratch(name + ".sA", n * 32L, CgBufferUsage.STORAGE);
        sB = recording.scratch(name + ".sB", n * 32L, CgBufferUsage.STORAGE);
    }

    /**
     * Records this frame's generation into {@code pass}, after Range's Place: {@code order} at {@code orderOffset}
     * bytes is the pool's part of Range's order, {@code visible} its visible counts. Clears the frame's asks.
     */
    public void dispatch(CgComputePass pass, CgGraphBuffer order, long orderOffset, CgGraphBuffer visible) {
        if (!any) return;
        any = false;
        Arrays.fill(lanes, null);
        if (keys == null) {
            CgCompute file = CgCompute.load(KERNELS);
            keys = file.kernel("Keys");
            links = file.kernel("Links");
            init = file.kernel("Init");
            propagate = file.kernel("Propagate");
            finish = file.kernel("Finish");
            ribbons = file.kernel("Ribbons");
            argsKernel = file.kernel("Args");
        }
        int n = positions, capacity = Math.max(pool.capacity(), 1);
        if (count == null || count.capacity() != n) count = CgGpuCount.of(n);
        // Ids 0 to trails - 1, and all ones for none.
        int ribbonBits = 32 - Integer.numberOfLeadingZeros(trails), slotBits = 32 - Integer.numberOfLeadingZeros(Math.max(slotCount - 1, 1));
        long orderBytes = (long) capacity * 4L;
        CgVfxRecord record = pool.record();
        int vectors = record.vectors(), drawnStride = pool.shape().drawnStride(), tail = record.word(CgVfxRecord.Tail.RIBBON);
        pass.dispatch(keys, n).bind("SLOTS", slots).bind("VISIBLE", visible).bind("ORDER", order, orderOffset, orderBytes)
                .bind("DRAWN", pool.drawn()).bind("LINKS", linkKeys).bind("VALUES", values)
                .set("_Slots", slotCount).set("_Positions", n);
        CgGpuOps.sort(pass, CgGpuOps.Element.FLOAT, CgGpuOps.Order.ASCENDING, linkKeys, values, count);
        pass.dispatch(links, n).bind("SLOTS", slots).bind("VISIBLE", visible).bind("ORDER", order, orderOffset, orderBytes)
                .bind("KEYS", keyBuffer).bind("VALUES", values).bind("RECORDS", pool.records())
                .set("_Slots", slotCount).set("_Positions", n).set("_RibbonBits", ribbonBits)
                .set("_RecordVectors", vectors).set("_DrawnStride", drawnStride).set("_RibbonTail", tail);
        CgGpuOps.sort(pass, slotBits + ribbonBits, CgGpuOps.Order.ASCENDING, keyBuffer, values, count);
        pass.dispatch(init, n).bind("SLOTS", slots).bind("ORDER", order, orderOffset, orderBytes).bind("DRAWN", pool.drawn())
                .bind("KEYS", keyBuffer).bind("VALUES", values).bind("T_OUT", tA).bind("U_OUT", uA).bind("S_OUT", sA)
                .bind("RECORDS", pool.records())
                .set("_Slots", slotCount).set("_Positions", n).set("_RibbonBits", ribbonBits)
                .set("_RecordVectors", vectors).set("_DrawnStride", drawnStride).set("_RibbonTail", tail);
        CgGraphBuffer t = tA, u = uA, st = sA, t2 = tB, u2 = uB, st2 = sB;
        for (int stride = 1; stride < n; stride <<= 1) {
            pass.dispatch(propagate, n).bind("T_IN", t).bind("U_IN", u).bind("S_IN", st)
                    .bind("T_OUT", t2).bind("U_OUT", u2).bind("S_OUT", st2).set("_Positions", n).set("_Stride", stride);
            CgGraphBuffer swap = t; t = t2; t2 = swap;
            swap = u; u = u2; u2 = swap;
            swap = st; st = st2; st2 = swap;
        }
        int slotAt = 0, positionAt = positionAt(), ribbonAt = ribbonAt();
        pass.dispatch(finish, n).bind("KEYS", keyBuffer).bind("VALUES", values).bind("T_IN", t).bind("U_IN", u)
                .bind("RUN", run).bind("SLOTS", slots).bind("ORDER", order, orderOffset, orderBytes).bind("RECORDS", pool.records())
                .bind("DRAWN", pool.drawn())
                .set("_RecordVectors", vectors).set("_DrawnStride", drawnStride).set("_RibbonTail", tail)
                .set("_Slots", slotCount).set("_Positions", n).set("_RibbonBits", ribbonBits)
                .set("_PositionAt", positionAt).set("_RibbonAt", ribbonAt);
        pass.dispatch(ribbons, n).bind("SLOTS", slots).bind("ORDER", order, orderOffset, orderBytes).bind("DRAWN", pool.drawn())
                .bind("KEYS", keyBuffer).bind("VALUES", values).bind("U_IN", u).bind("RUN", run)
                .set("_Slots", slotCount).set("_Positions", n).set("_RibbonBits", ribbonBits)
                .set("_PositionAt", positionAt).set("_RibbonAt", ribbonAt);
        pass.dispatch(argsKernel, slotCount).bind("SLOTS", slots).bind("VISIBLE", visible).bind("U_IN", u).bind("S_IN", st)
                .bind("STATE", state).bind("RUN", run).bind("ARGS", args)
                .set("_Slots", slotCount).set("_Positions", n).set("_SlotAt", slotAt)
                .set("_PositionAt", positionAt).set("_RibbonAt", ribbonAt)
                .set("_Tessellation", MAX_TESSELLATION, MIN_ANGLE, SCREEN, MIN_ERROR);
    }

    /** {@code a}'s four keys from word {@code at}: their times, then their values, the last repeated past its end. */
    private void along(int at, CgVfxRibbonRendererProperties.Along a) {
        float[] t = a.times(), v = a.values();
        int n = t.length, c = a.components();
        for (int k = 0; k < 4; k++) {
            int i = Math.min(k, n - 1);
            words[at + k] = Float.floatToRawIntBits(t[i]);
            for (int j = 0; j < c; j++) words[at + 4 + (c == 1 ? k : k * 4 + j)] = Float.floatToRawIntBits(v[i * c + j]);
        }
    }

    private void put(int at, float a, float b, float c, float d) {
        words[at] = Float.floatToRawIntBits(a);
        words[at + 1] = Float.floatToRawIntBits(b);
        words[at + 2] = Float.floatToRawIntBits(c);
        words[at + 3] = Float.floatToRawIntBits(d);
    }

    private static int sizeClass(int n) {
        return Math.max(64, Integer.highestOneBit(Math.max(n, 1) - 1) << 1);
    }
}
