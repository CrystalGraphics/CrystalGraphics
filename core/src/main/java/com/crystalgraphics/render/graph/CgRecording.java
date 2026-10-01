package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.property.CgEffectTree;
import com.crystalgraphics.render.property.CgSpatialTree;

import javax.annotation.Nullable;
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
 * <ul>
 *   <li>Order is decided by reads and writes: a read sees the last write made before it, in this recording or in
 *       one added to the graph before it, and the passes run accordingly — creation order breaks ties.</li>
 *   <li>Every raster pass must be {@linkplain CgRasterPass#end() ended} before {@link #seal()}.</li>
 *   <li>One thread at a time until sealed; after, any thread may read it and nobody may change it.</li>
 * </ul>
 */
public final class CgRecording {

    static final int READ = 0;
    static final int WRITE = 1;

    private final CgBindingTable bindings = new CgBindingTable();
    private final CgClipTable clips = new CgClipTable();
    private final CgShapeTable shapes = new CgShapeTable();
    private final CgSpatialTree spatial = new CgSpatialTree();
    private final CgEffectTree effects = new CgEffectTree();
    private final CgChunkBuilder chunks = new CgChunkBuilder(bindings);
    private final List<CgPass> passes = new ArrayList<>();

    private final List<CgGraphTexture> textures = new ArrayList<>();
    private final IdentityHashMap<CgGraphTexture, Integer> slots = new IdentityHashMap<>();
    /** Per texture slot, how many writes it has had: what keeps a repeated read from being logged twice. */
    private int[] writes = new int[16];

    /** (type, pass index, texture slot) per event, in the order they happened. */
    private int[] events = new int[3 * 64];
    private int eventCount;

    /** Per pass, the slots it read and the write count each was at, so a read is logged once per version. */
    private final List<int[]> readsSeen = new ArrayList<>();

    private boolean sealed;

    /** The table every chunk of this recording takes its binding ids from. */
    /** The rounded clips its chunks name, by index. */
    public CgClipTable clips() {
        return clips;
    }

    /** The boxes its quads are drawn as, by index. */
    public CgShapeTable shapes() {
        return shapes;
    }

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
        float[] block = new float[CgPassConstants.FLOATS];
        constants.write(block, 0);
        CgRasterPass pass = new CgRasterPass(this, "raster " + target.name(), target, load, block, state, order);
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
        read(copy, from);
        write(copy, to);
    }

    /** Writes into {@code target} on the render thread, before any later reader. */
    public CgRequest upload(CgGraphTexture target, CgUpload upload) {
        requireOpen();
        CgRequest request = new CgRequest("upload " + target.name());
        CgPass.Upload pass = new CgPass.Upload(target, upload, request);
        add(pass);
        write(pass, target);
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
        for (CgGraphTexture read : reads) read(pass, read);
        if (target != null) write(pass, target);
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
        write(pass, requested);
    }

    /** Freezes it: every raster pass must have ended. Answers itself, for {@code graph.add(rec.seal())}. */
    public CgRecording seal() {
        if (sealed) return this;
        for (CgPass pass : passes) {
            if (pass instanceof CgRasterPass raster && !raster.ended()) {
                throw new IllegalStateException(raster + " was never ended");
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
        textures.clear();
        slots.clear();
        Arrays.fill(writes, 0);
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

    CgPass pass(int index) {
        return passes.get(index);
    }

    int eventCount() {
        return eventCount;
    }

    int eventType(int event) {
        return events[event * 3];
    }

    int eventPass(int event) {
        return events[event * 3 + 1];
    }

    CgGraphTexture eventTexture(int event) {
        return textures.get(events[event * 3 + 2]);
    }

    void requireOpen() {
        if (sealed) throw new IllegalStateException("the recording is sealed");
    }

    void read(CgPass pass, CgGraphTexture texture) {
        int slot = slot(texture);
        int p = indexOf(pass);
        int[] seen = readsSeen.get(p);
        for (int i = 1; i < seen[0]; i += 2) {
            if (seen[i] == slot && seen[i + 1] == writes[slot]) return;   // this version, already logged
        }
        if (seen[0] + 2 > seen.length) {
            seen = Arrays.copyOf(seen, seen.length * 2);
            readsSeen.set(p, seen);
        }
        seen[seen[0]] = slot;
        seen[seen[0] + 1] = writes[slot];
        seen[0] += 2;
        log(READ, p, slot);
    }

    void write(CgPass pass, CgGraphTexture texture) {
        int slot = slot(texture);
        writes[slot]++;
        log(WRITE, indexOf(pass), slot);
    }

    private void add(CgPass pass) {
        passes.add(pass);
        readsSeen.add(new int[]{1, 0, 0, 0, 0});
    }

    private int indexOf(CgPass pass) {
        // The pass being read or written is nearly always the newest.
        for (int i = passes.size() - 1; i >= 0; i--) if (passes.get(i) == pass) return i;
        throw new IllegalArgumentException(pass + " is not this recording's");
    }

    private int slot(CgGraphTexture texture) {
        Integer slot = slots.get(texture);
        if (slot != null) return slot;
        int s = textures.size();
        textures.add(texture);
        slots.put(texture, s);
        if (s == writes.length) writes = Arrays.copyOf(writes, s * 2);
        return s;
    }

    private void log(int type, int pass, int slot) {
        if ((eventCount + 1) * 3 > events.length) events = Arrays.copyOf(events, events.length * 2);
        events[eventCount * 3] = type;
        events[eventCount * 3 + 1] = pass;
        events[eventCount * 3 + 2] = slot;
        eventCount++;
    }
}
