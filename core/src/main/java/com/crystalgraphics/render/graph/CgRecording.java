package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.property.CgEffectTree;
import com.crystalgraphics.render.property.CgSpatialTree;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * What one recorder produces for a frame: passes, the chunks drawn in them, the snapshots they bind, and the
 * requests they make. Built on any thread with no GL, {@linkplain #seal() sealed}, then handed to a
 * {@link CgFrameGraph} — a value from then on, read by the frame builder and referring back to nothing.
 *
 * <pre>{@code
 * CgRecording rec = new CgRecording();
 * CgGraphTexture layer = CgGraphTexture.transientTexture("fade", desc);
 *
 * CgRasterPass frame = rec.raster(surface, CgLoad.load(), constants, uiState, CgOrder.LOOKBACK);
 * CgRasterPass inner = rec.raster(layer, CgLoad.clear(0, 0, 0, 0), constants, uiState, CgOrder.LOOKBACK);
 * inner.add(rec.chunks().begin() ... .end());
 * inner.end();                                     // the layer is written
 * frame.add(compositeOf(layer));                   // reads it: the graph runs `inner` first
 * frame.end();
 *
 * graph.add(rec.seal());
 * }</pre>
 *
 * <p>Kernels, and the buffers they work on:</p>
 * <pre>{@code
 * rec.fill(counts, 0);                                          // zeroed before the kernel adds to it
 * CgComputePass bin = rec.compute("histogram", constants);
 * bin.dispatch(histogram, count).bind("VALUES", values).bind("COUNTS", counts);
 * bin.end();                                                    // a later read of COUNTS runs after it
 * }</pre>
 *
 * <ul>
 *   <li>Order is decided by reads and writes: a read sees the last write made before it, in this recording or in
 *       one added to the graph before it, and the passes run accordingly — creation order breaks ties.</li>
 *   <li>Every raster and compute pass must be ended before {@link #seal()}.</li>
 *   <li>One thread at a time until sealed; after, any thread may read it and nobody may change it.</li>
 * </ul>
 */
public final class CgRecording {

    static final int READ = 0;
    static final int WRITE = 1;
    private static final int EVENT_INTS = 4;

    private final CgBindingTable bindings = new CgBindingTable();
    private final CgClipTable clips = new CgClipTable();
    private final CgShapeTable shapes = new CgShapeTable();
    private final CgSpatialTree spatial = new CgSpatialTree();
    private final CgEffectTree effects = new CgEffectTree();
    private final CgChunkBuilder chunks = new CgChunkBuilder(bindings);
    private final List<CgPass> passes = new ArrayList<>();

    private final List<CgGraphResource> resources = new ArrayList<>();
    private final IdentityHashMap<CgGraphResource, Integer> slots = new IdentityHashMap<>();
    /** Per resource slot, how many writes it has had: what keeps a repeated read from being logged twice. */
    private int[] writes = new int[16];

    /** (type, pass index, resource slot, access bits) per event, in the order they happened. */
    private int[] events = new int[EVENT_INTS * 64];
    /** Per event, what was named: a resource, or a history's previous version, which is ordered on the history. */
    private CgGraphResource[] eventViews = new CgGraphResource[64];
    private int eventCount;

    /** Per pass, the (slot, write count, access) of each read it logged, so a read is logged once per version. */
    private final List<int[]> readsSeen = new ArrayList<>();

    private boolean sealed;

    /** The rounded clips its chunks name, by index. */
    public CgClipTable clips() {
        return clips;
    }

    /** The boxes its quads are drawn as, by index. */
    public CgShapeTable shapes() {
        return shapes;
    }

    /** The table every chunk of this recording takes its binding ids from. */
    public CgBindingTable bindings() {
        return bindings;
    }

    /** The spatial nodes its chunks are positioned in. @see CgSpatialTree */
    public CgSpatialTree spatial() {
        return spatial;
    }

    /** The opacity groups its chunks are drawn in. @see CgEffectTree */
    public CgEffectTree effects() {
        return effects;
    }

    /** The builder this recording's chunks are written with. */
    public CgChunkBuilder chunks() {
        return chunks;
    }

    /**
     * A raster pass into {@code target}.
     *
     * @param state the render state a pipeline's unset slots take, or null to leave them as the target has them
     */
    public CgRasterPass raster(CgGraphTexture target, CgLoad load, CgPassConstants constants,
                               @Nullable CgRenderState state, CgOrder order) {
        requireOpen();
        CgRasterPass pass = new CgRasterPass(this, "raster " + target.name(), target, load, block(constants), state, order);
        add(pass);
        return pass;
    }

    /** A compute pass whose kernels read the frame block as identity matrices and the time now. */
    public CgComputePass compute(String name) {
        return compute(name, new CgPassConstants().time(CgFrameClock.seconds()));
    }

    /** A compute pass whose kernels read {@code constants} as the frame block: time, camera, resolution. */
    public CgComputePass compute(String name, CgPassConstants constants) {
        requireOpen();
        return compute(name, block(constants));
    }

    private CgComputePass compute(String name, float[] block) {
        CgComputePass pass = new CgComputePass(this, name, block);
        add(pass);
        return pass;
    }

    /** Copies a region of {@code from} into a region of {@code to}, scaling when they differ in size. */
    public void copy(CgGraphTexture from, int x, int y, int w, int h, CgGraphTexture to, int tx, int ty, int tw, int th,
                     boolean linear) {
        requireOpen();
        if (from.kind() == CgGraphTexture.Kind.CURRENT || to.kind() == CgGraphTexture.Kind.CURRENT) {
            throw new IllegalArgumentException("a copy names its framebuffers; the current target has none");
        }
        CgPass.Copy copy = new CgPass.Copy(from, x, y, w, h, to, tx, ty, tw, th, linear);
        add(copy);
        read(copy, from, CgAccess.COPY_READ);
        write(copy, to, CgAccess.COPY_WRITE);
    }

    /** Copies {@code size} bytes of {@code from} at {@code fromOffset} into {@code to} at {@code toOffset}. */
    public void copy(CgGraphBuffer from, long fromOffset, CgGraphBuffer to, long toOffset, long size) {
        requireOpen();
        requireUse(from, CgBufferUsage.COPY);
        requireWritable(to, CgBufferUsage.COPY);
        CgPass.BufferCopy copy = new CgPass.BufferCopy(from, fromOffset, to, toOffset, size);
        add(copy);
        read(copy, from, CgAccess.COPY_READ);
        write(copy, to, CgAccess.COPY_WRITE);
    }

    /** Sets all of {@code buffer} to {@code value} in every 32-bit word. */
    public void fill(CgGraphBuffer buffer, int value) {
        fill(buffer, 0, buffer.size(), value);
    }

    /** Sets {@code size} bytes of {@code buffer} from {@code offset}, both multiples of 4, to {@code value} in every word. */
    public void fill(CgGraphBuffer buffer, long offset, long size, int value) {
        requireOpen();
        requireWritable(buffer, CgBufferUsage.COPY);
        if (((offset | size) & 3) != 0) throw new IllegalArgumentException("a fill covers whole 32-bit words");
        CgPass.Fill fill = new CgPass.Fill(buffer, offset, size, value);
        add(fill);
        write(fill, buffer, CgAccess.COPY_WRITE);
    }

    /** Writes {@code data}'s remaining bytes into {@code buffer} at {@code offset}, copied now. */
    public void update(CgGraphBuffer buffer, long offset, ByteBuffer data) {
        requireOpen();
        requireWritable(buffer, CgBufferUsage.COPY);
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        CgPass.Update update = new CgPass.Update(buffer, offset, bytes);
        add(update);
        write(update, buffer, CgAccess.COPY_WRITE);
    }

    /** Writes into {@code target} on the render thread, before any later reader. */
    public CgRequest upload(CgGraphTexture target, CgUpload upload) {
        requireOpen();
        CgRequest request = new CgRequest("upload " + target.name());
        CgPass.Upload pass = new CgPass.Upload(target, upload, request);
        add(pass);
        write(pass, target, CgAccess.COPY_WRITE);
        return request;
    }

    /**
     * Runs {@code body} on the render thread with {@code target} bound (or nothing bound, for null) and GL state
     * restored after: the escape hatch for drawing that is not recorded yet.
     *
     * @param reads graph textures the body samples, so it runs after whatever writes them
     */
    public CgRequest callback(String name, @Nullable CgGraphTexture target, Runnable body, CgGraphTexture... reads) {
        requireOpen();
        CgRequest request = new CgRequest(name);
        CgPass.Callback pass = new CgPass.Callback(name, target, body, request);
        add(pass);
        for (CgGraphTexture read : reads) read(pass, read, CgAccess.SAMPLED_READ);
        if (target != null) write(pass, target, CgAccess.COLOR_WRITE);
        return request;
    }

    /**
     * Compiles {@code pipeline}'s program without the frame waiting where the driver can: done once it is ready,
     * failed with the driver's reason; still pending if it is not ready yet, when the owner asks again next frame.
     */
    public CgRequest compile(CgPipeline pipeline) {
        requireOpen();
        CgRequest request = new CgRequest("compile " + pipeline);
        add(new CgPass.Compile(pipeline, request));
        return request;
    }

    /** Frees a {@linkplain CgGraphTexture#requested requested} texture's storage once everything before it read it. */
    public void release(CgGraphTexture requested) {
        requireOpen();
        if (requested.kind() != CgGraphTexture.Kind.REQUESTED) {
            throw new IllegalArgumentException("only a requested texture is released: " + requested);
        }
        CgPass.Release pass = new CgPass.Release(requested);
        add(pass);
        write(pass, requested, 0);
    }

    /** Frees a persistent or history buffer's storage once everything before it used it. */
    public void release(CgGraphBuffer buffer) {
        requireOpen();
        if (buffer.kind() != CgGraphBuffer.Kind.PERSISTENT && buffer.kind() != CgGraphBuffer.Kind.HISTORY
                || buffer.isPreviousVersion()) {
            throw new IllegalArgumentException("only a persistent or history buffer is released: " + buffer);
        }
        CgPass.BufferRelease pass = new CgPass.BufferRelease(buffer);
        add(pass);
        write(pass, buffer, 0);
    }

    /**
     * How many passes and requests it holds: two readings tell whether a stretch of drawing recorded anything but
     * chunks into the pass that was open.
     */
    public int operations() {
        return passes.size();
    }

    /** The pass or request made {@code index}-th, counting as {@link #operations} does. */
    public CgPass pass(int index) {
        return passes.get(index);
    }

    /** Freezes it: every raster and compute pass must have ended. Answers itself, for {@code graph.add(rec.seal())}. */
    public CgRecording seal() {
        if (sealed) return this;
        for (CgPass pass : passes) {
            if (pass instanceof CgRasterPass raster && !raster.ended()) {
                throw new IllegalStateException(raster + " was never ended");
            }
            if (pass instanceof CgComputePass compute && !compute.ended()) {
                throw new IllegalStateException(compute + " was never ended");
            }
        }
        sealed = true;
        return this;
    }

    public boolean isSealed() {
        return sealed;
    }

    /**
     * Empties it for reuse. Only for an owner that knows nothing else holds it — an immediate recording executed and
     * dropped; a recording handed to a compositor is never reset.
     */
    public void reset() {
        passes.clear();
        resources.clear();
        slots.clear();
        Arrays.fill(writes, 0);
        Arrays.fill(eventViews, 0, eventCount, null);
        eventCount = 0;
        readsSeen.clear();
        chunks.reset();
        bindings.reset();
        clips.reset();
        shapes.reset();
        spatial.reset();
        effects.reset();
        sealed = false;
    }

    // ── package: what the frame builder reads ────────────────────────────────

    int passCount() {
        return passes.size();
    }

    int eventCount() {
        return eventCount;
    }

    int eventType(int event) {
        return events[event * EVENT_INTS];
    }

    int eventPass(int event) {
        return events[event * EVENT_INTS + 1];
    }

    /** The resource the event is ordered on. */
    CgGraphResource eventResource(int event) {
        return resources.get(events[event * EVENT_INTS + 2]);
    }

    /** What the event named: its resource, or a history's previous version. */
    CgGraphResource eventView(int event) {
        return eventViews[event];
    }

    int eventAccess(int event) {
        return events[event * EVENT_INTS + 3];
    }

    void requireOpen() {
        if (sealed) throw new IllegalStateException("the recording is sealed");
    }

    void read(CgPass pass, CgGraphResource view, int access) {
        int slot = slot(view);
        int p = indexOf(pass);
        int[] seen = readsSeen.get(p);
        for (int i = 1; i < seen[0]; i += 3) {
            if (seen[i] == slot && seen[i + 1] == writes[slot] && seen[i + 2] == access) return;   // this version, logged
        }
        if (seen[0] + 3 > seen.length) {
            seen = Arrays.copyOf(seen, seen.length * 2);
            readsSeen.set(p, seen);
        }
        seen[seen[0]] = slot;
        seen[seen[0] + 1] = writes[slot];
        seen[seen[0] + 2] = access;
        seen[0] += 3;
        log(READ, p, slot, access, view);
    }

    void write(CgPass pass, CgGraphResource view, int access) {
        int slot = slot(view);
        writes[slot]++;
        log(WRITE, indexOf(pass), slot, access, view);
    }

    private static float[] block(CgPassConstants constants) {
        float[] block = new float[CgPassConstants.FLOATS];
        constants.write(block, 0);
        return block;
    }

    private static void requireUse(CgGraphBuffer buffer, CgBufferUsage usage) {
        if (buffer.desc() != null && !buffer.desc().has(usage)) {
            throw new IllegalArgumentException(buffer + " has no " + usage + " use");
        }
    }

    private static void requireWritable(CgGraphBuffer buffer, CgBufferUsage usage) {
        if (buffer.isPreviousVersion()) throw new IllegalArgumentException(buffer + ": a previous version is read-only");
        requireUse(buffer, usage);
    }

    private void add(CgPass pass) {
        passes.add(pass);
        readsSeen.add(new int[]{1, 0, 0, 0, 0, 0, 0});
    }

    private int indexOf(CgPass pass) {
        // The pass being read or written is nearly always the newest.
        for (int i = passes.size() - 1; i >= 0; i--) if (passes.get(i) == pass) return i;
        throw new IllegalArgumentException(pass + " is not this recording's");
    }

    /** The slot of what {@code view} is ordered on: a previous version's history. */
    private int slot(CgGraphResource view) {
        CgGraphResource resource = view instanceof CgGraphBuffer buffer ? buffer.resource() : view;
        Integer slot = slots.get(resource);
        if (slot != null) return slot;
        int s = resources.size();
        resources.add(resource);
        slots.put(resource, s);
        if (s == writes.length) writes = Arrays.copyOf(writes, s * 2);
        return s;
    }

    private void log(int type, int pass, int slot, int access, CgGraphResource view) {
        if ((eventCount + 1) * EVENT_INTS > events.length) events = Arrays.copyOf(events, events.length * 2);
        if (eventCount == eventViews.length) eventViews = Arrays.copyOf(eventViews, eventViews.length * 2);
        int at = eventCount * EVENT_INTS;
        events[at] = type;
        events[at + 1] = pass;
        events[at + 2] = slot;
        events[at + 3] = access;
        eventViews[eventCount] = view;
        eventCount++;
    }
}
