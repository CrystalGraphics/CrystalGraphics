package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.buffer.CgBufferRecords;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.canvas.CgShapeTable;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.property.CgEffectTree;
import com.crystalgraphics.render.property.CgSpatialTree;
import com.crystalgraphics.util.CgBoxedInts;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

/**
 * What one recorder produces for a frame: passes, the chunks drawn in them, the snapshots they bind, and the
 * requests they make. Built on any thread with no GL, {@linkplain #seal() sealed}, then handed to a
 * {@link CgFrameGraph} — a value from then on, read by the frame builder and referring back to nothing.
 *
 * <pre>{@code
 * CgRecording rec = new CgRecording();
 * CgGraphTexture layer = CgGraphTexture.transientTexture("fade", desc);
 *
 * CgRasterPass frame = rec.raster("recording.surface").color(surface, CgLoad.load()).constants(constants).state(uiState).order(CgOrder.LOOKBACK);
 * CgRasterPass inner = rec.raster("recording.layer").color(layer, CgLoad.CLEAR).constants(constants).state(uiState).order(CgOrder.LOOKBACK);
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

    private final CgClipTable clips = new CgClipTable();
    private final CgShapeTable shapes = new CgShapeTable();
    private final CgSpatialTree spatial = new CgSpatialTree();
    private final CgEffectTree effects = new CgEffectTree();
    private final CgPrimitiveTable primitives = new CgPrimitiveTable();
    private final CgChunkBuilder chunks = CgChunkBuilder.pooling(primitives);
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

    /**
     * Per resource slot, the last read logged: its pass index + 1, the write count it read, what it named, and every
     * access bit logged for them. A read adding no bit is not logged again, since the builder joins a pass's reads.
     */
    private int[] readPass = new int[16], readVersion = new int[16], readBits = new int[16];
    private CgGraphResource[] readView = new CgGraphResource[16];
    /** Raster passes made so far, reused after a {@link #reset()}; the first {@link #rastersUsed} are this use's. */
    private final List<CgRasterPass> rasters = new ArrayList<>();
    private int rastersUsed;
    /** As {@link #rasters}, for compute passes, fills and updates. */
    private final List<CgComputePass> computes = new ArrayList<>();
    private int computesUsed;    /** What it releases ({@link #release}) and compiles ({@link #compile}): carried for the frame, not passes. */    private final List<CgGraphResource> releases = new ArrayList<>();    private final List<CgPipeline> compiles = new ArrayList<>();    private final List<CgRequest> compileRequests = new ArrayList<>();
    private final List<CgPass.BufferFill> fills = new ArrayList<>();
    private int fillsUsed;
    /**
     * {@link #fetch}'s passes: [0, {@link #fetchesUsed}) this recording's, the rest free or held by a delivery in flight,
     * each reused once delivered (Niagara's readback staging, reused once read). Grows to the frames in flight and stops.
     */
    private final List<CgPass.Readback> fetches = new ArrayList<>();
    private int fetchesUsed;
    private final List<CgPass.BufferUpdate> updates = new ArrayList<>();
    private int updatesUsed;
    /** {@link #upload}'s slots, reused frame to frame: each its records and the buffer they last filled. */
    private final List<Upload> uploads = new ArrayList<>();
    private int uploadsUsed;

    private static final class Upload {
        CgBufferRecords records;
        CgGraphBuffer buffer;
        String name;
        long bytes;
    }
    private final List<CgPass.Include> includes = new ArrayList<>();
    private int includesUsed;
    /** {@link #compute(String)}'s frame block: identity, its time set per call. */
    private final CgPassConstants identity = new CgPassConstants();

    /** A pass a built frame still holds stays that frame's; the pool keeps the rest, cleared, for the next use. */
    private static <P extends CgPass> void recycle(List<P> pool, int used) {
        int kept = 0;
        // Only [0, used) is cleared, before the caller resets used: a pass past it was cleared when it was last used,
        // so a pool grown to a peak (a rehearsal) costs a frame only what that frame used.
        for (int i = 0; i < pool.size(); i++) {
            P pass = pool.get(i);
            if (i < used) {
                if (pass.held()) continue;
                pass.clearReferences();
            }
            if (kept != i) pool.set(kept, pass);
            kept++;
        }
        while (pool.size() > kept) pool.remove(pool.size() - 1);
    }

    /** The compute passes kept for reuse, in use or not. */
    List<CgComputePass> computePool() {
        return computes;
    }

    private boolean sealed;

    /** What {@link #scratch} made, kept across {@link #reset()}, and how many of them this use has taken. */
    private final List<CgGraphResource> scratch = new ArrayList<>();
    private int scratchUsed;

    /** The rounded clips its chunks name, by index. */
    public CgClipTable clips() {
        return clips;
    }

    /** The boxes its quads are drawn as, by index. */
    public CgShapeTable shapes() {
        return shapes;
    }

    /**
     * The firing's object records, each written once and named by slot in every pass that draws it: what its chunks'
     * OBJECT ids name. A builder recording here from outside takes it with {@code chunks.primitives(...)}.
     */
    public CgPrimitiveTable primitives() {
        return primitives;
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
     * A transient texture of one level for this recording's own passes: scratch an op makes for itself. The n-th
     * asked for since {@link #reset()} is the n-th before it where the name, size and format match, so a recording
     * reused each frame makes none after its first.
     *
     * <pre>{@code
     * CgGraphTexture across = recording.scratch("blur.across", w, h, format);   // distinct from every other this frame
     * }</pre>
     */
    public CgGraphTexture scratch(String name, int width, int height, CgFormat format) {
        requireOpen();
        if (scratchUsed < scratch.size() && scratch.get(scratchUsed) instanceof CgGraphTexture kept) {
            CgTextureDesc d = kept.desc();
            if (kept.name().equals(name) && d.width() == width && d.height() == height && d.levels() == 1
                    && d.format() == format) {
                scratchUsed++;
                return kept;
            }
        }
        return keep(CgGraphTexture.transientTexture(name, new CgTextureDesc(width, height, format)));
    }

    /** A transient buffer of {@code bytes} for {@code usage}, kept as {@link #scratch(String, int, int, CgFormat)} keeps a texture. */
    public CgGraphBuffer scratch(String name, long bytes, CgBufferUsage usage) {
        requireOpen();
        if (scratchUsed < scratch.size() && scratch.get(scratchUsed) instanceof CgGraphBuffer kept) {
            CgBufferDesc d = kept.desc();
            if (kept.name().equals(name) && d.bytes() == bytes && d.usages().size() == 1 && d.has(usage)) {
                scratchUsed++;
                return kept;
            }
        }
        return keep(CgGraphBuffer.transientBuffer(name, CgBufferDesc.of(bytes, usage)));
    }

    private <T extends CgGraphResource> T keep(T made) {
        if (scratchUsed < scratch.size()) scratch.set(scratchUsed, made);
        else scratch.add(made);
        scratchUsed++;
        return made;
    }

    /**
     * A raster pass named {@code name}, which declares what it draws into and reads before its first draw
     * ({@link CgRasterPass}): Unreal's {@code AddPass} with its parameters.
     *
     * <pre>{@code
     * CgRasterPass pass = rec.raster("bloom.source")
     *         .color(glow, CgLoad.CLEAR)
     *         .reads(sceneColor)
     *         .constants(constants);
     * pass.add(chunk);
     * pass.end();
     * }</pre>
     *
     * <ul>
     *   <li>{@code name} is what traces and logs call it: a constant, so a steady frame allocates none.</li>
     *   <li>Its constants are the identity block at the time now until {@link CgRasterPass#constants} sets them.</li>
     * </ul>
     */
    public CgRasterPass raster(String name) {
        requireOpen();
        CgPassConstants constants = identity.time(CgFrameClock.seconds());
        CgRasterPass pass;
        if (rastersUsed < rasters.size()) {
            pass = rasters.get(rastersUsed);
            pass.reuse(name, constants);
        } else {
            pass = new CgRasterPass(this, name, constants);
            rasters.add(pass);
        }
        rastersUsed++;
        add(pass);
        return pass;
    }


    /** A compute pass whose kernels read the frame block as identity matrices and the time now. */
    public CgComputePass compute(String name) {
        return compute(name, identity.time(CgFrameClock.seconds()));
    }

    /** A compute pass whose kernels read {@code constants} as the frame block: time, camera, resolution. */
    public CgComputePass compute(String name, CgPassConstants constants) {
        requireOpen();
        CgComputePass pass;
        if (computesUsed < computes.size()) {
            pass = computes.get(computesUsed);
            pass.reuse(name, constants);
        } else {
            pass = new CgComputePass(this, name, constants);
            computes.add(pass);
        }
        computesUsed++;
        add(pass);
        return pass;
    }

    /**
     * Copies a region of {@code from} into a region of {@code to}, scaling when they differ in size: bottom-left pixels,
     * colour slot 0. {@code from} may be {@link CgGraphTexture#current()}, as it stands after every write recorded
     * before: what is behind a UI, read into a texture of its own.
     *
     * <pre>{@code
     * rec.copy(CgGraphTexture.current(), x, y, w, h, capture, 0, 0, w, h, false);
     * }</pre>
     */
    public void copy(CgGraphTexture from, int x, int y, int w, int h, CgGraphTexture to, int tx, int ty, int tw, int th,
                     boolean linear) {
        requireOpen();
        if (to.isCurrent()) {
            throw new IllegalArgumentException("a copy into the current target: draw into it instead");
        }
        if (from.isVolume() || to.isVolume() || from.isArray() || to.isArray()) {
            throw new IllegalArgumentException("a copy is between 2D textures, not volumes or arrays");
        }
        CgPass.TextureCopy copy = new CgPass.TextureCopy(from, x, y, w, h, to, tx, ty, tw, th, linear);
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

    /**
     * Executes {@code frame}, recorded and built elsewhere, at this point of the recording: a step of the frame this
     * recording builds into, after what it touches was written and before what reads it after. What a UI recording on
     * its own thread draws with inside a stage's firing: the firing is built and executed once, and the included frame
     * executes at its step on the executor's next depth, never built again.
     *
     * <pre>{@code
     * UiFrame ui = sequence.poll();                                     // built on the UI's thread
     * recording.include(ui.frame()).timed(GPU_UI);                      // its passes, here
     * canvas.quad(COMPOSITE).texture(0, ui.picture()).submit();          // after them: it reads what they wrote
     * }</pre>
     *
     * <ul>
     *   <li>Ordered by what {@code frame} touches that outlives it -- the current target, requested, imported,
     *       persistent and history resources -- as a pass reading and writing them would be. Its transients are its own.</li>
     *   <li>The caller keeps {@code frame}, and recycles it once this recording's frame has executed.</li>
     * </ul>
     *
     * @return the step, to time or follow: valid until this recording resets
     */
    public CgPass.Include include(CgFrame frame) {
        return addInclude(frame, false, false);
    }

    /**
     * {@link #include} of a frame already executed, executed again with what its passes read as it stands now
     * ({@link CgExecutor#executeAgain}): a compositor's move of what a frame drew, with nothing recorded or built.
     * Render thread, which knows whether {@code frame} has executed.
     *
     * @param keepRequested skip every pass writing a requested texture, leaving it as the last execution did
     */
    public CgPass.Include includeAgain(CgFrame frame, boolean keepRequested) {
        if (frame.executions == 0) throw new IllegalStateException("includeAgain of a frame that has not executed");
        return addInclude(frame, true, keepRequested);
    }

    private CgPass.Include addInclude(CgFrame frame, boolean again, boolean keepRequested) {
        requireOpen();
        CgPass.Include include;
        if (includesUsed < includes.size()) include = includes.get(includesUsed);
        else includes.add(include = new CgPass.Include());
        includesUsed++;
        include.reuse(frame, again, keepRequested);
        add(include);
        for (int i = 0; i < frame.accessCount; i++) {
            CgGraphResource view = frame.accessView[i];
            if (view.isTransient()) continue;
            int bits = frame.accessBits[i];
            if ((bits & ~CgResourceStates.WRITES) != 0) read(include, view, bits & ~CgResourceStates.WRITES);
            if ((bits & CgResourceStates.WRITES) != 0) write(include, view, bits & CgResourceStates.WRITES);
        }
        return include;
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
        CgPass.BufferFill fill;
        if (fillsUsed < fills.size()) (fill = fills.get(fillsUsed)).reuse(buffer, offset, size, value);
        else fills.add(fill = new CgPass.BufferFill(buffer, offset, size, value));
        fillsUsed++;
        add(fill);
        write(fill, buffer, CgAccess.COPY_WRITE);
    }

    /**
     * {@code count} records of {@code layout} written now, uploaded into this frame's memory: a buffer for this frame
     * only, which a material or a kernel binds by name.
     *
     * <pre>{@code
     * static final CgBufferLayout SPARK = CgBufferLayout.std430("Spark").vec4("positionLife").vec4("velocitySeed").build();
     *
     * CgGraphBuffer sparks = rec.upload("mymod.sparks", SPARK, live.size(), records -> {
     *     for (Spark s : live) {
     *         records.next().vec4("positionLife", s.x, s.y, s.z, s.life).vec4("velocitySeed", s.vx, s.vy, s.vz, s.seed);
     *     }
     * });
     * sparkMaterial.buffer("SPARKS", sparks);
     * }</pre>
     *
     * <ul>
     *   <li>Allocates nothing once warm: the records and the handle are this recording's, reused frame to frame.</li>
     *   <li>Records left unwritten are zero. For data kept across frames, {@code CgBuffers.create}.</li>
     * </ul>
     */
    public CgGraphBuffer upload(String name, CgBufferLayout layout, int count, Consumer<CgBufferRecords> writer) {
        requireOpen();
        Upload u;
        if (uploadsUsed < uploads.size()) u = uploads.get(uploadsUsed);
        else uploads.add(u = new Upload());
        uploadsUsed++;
        if (u.records == null || u.records.layout() != layout) u.records = new CgBufferRecords(layout);
        int records = Math.max(count, 1);
        long bytes = (long) layout.getStride() * records;
        if (u.buffer == null || u.bytes != bytes || !u.name.equals(name)) {
            u.buffer = CgGraphBuffer.transientBuffer(name,
                    CgBufferDesc.elements(records, layout.getStride(), CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            u.name = name;
            u.bytes = bytes;
        }
        u.records.begin(count);
        writer.accept(u.records);
        int floats = u.records.end();
        if (floats > 0) update(u.buffer, 0, u.records.floats(), 0, floats);
        else fill(u.buffer, 0);
        return u.buffer;
    }

    /** Writes {@code data}'s remaining bytes into {@code buffer} at {@code offset}, copied now. */
    public void update(CgGraphBuffer buffer, long offset, ByteBuffer data) {
        requireOpen();
        requireWritable(buffer, CgBufferUsage.COPY);
        // A reused update's own array: no allocation a frame once warm.
        CgPass.BufferUpdate update;
        if (updatesUsed < updates.size()) (update = updates.get(updatesUsed)).reuse(buffer, offset, data);
        else {
            updates.add(update = new CgPass.BufferUpdate(buffer, offset, null, null, 0, 0));
            update.reuse(buffer, offset, data);
        }
        updatesUsed++;
        add(update);
        write(update, buffer, CgAccess.COPY_WRITE);
    }

    /**
     * Writes {@code count} floats of {@code src} from {@code from} into {@code buffer} at {@code offset}, read when the
     * frame executes rather than copied now: for a buffer rewritten every frame, which a copy would allocate for.
     *
     * <pre>{@code
     * recording.update(labels, 0, matrices, 0, n * 48);   // matrices untouched until the frame has executed
     * }</pre>
     *
     * <ul>
     *   <li>{@code src} must not change until the recording has executed.</li>
     * </ul>
     */
    public void update(CgGraphBuffer buffer, long offset, float[] src, int from, int count) {
        requireOpen();
        requireWritable(buffer, CgBufferUsage.COPY);
        if (from < 0 || count < 0 || from + count > src.length) {
            throw new IndexOutOfBoundsException("floats " + from + " to " + (from + count) + " of " + src.length);
        }
        updateWords(buffer, offset, src, null, from, count);
    }

    /**
     * Writes {@code count} words of {@code src} from {@code from} into {@code buffer} at {@code offset}, read when the
     * frame executes: as the float form, for words a CPU writes as ints (a GPU table's rows).
     *
     * <ul>
     *   <li>{@code src} must not change until the recording has executed.</li>
     * </ul>
     */
    public void update(CgGraphBuffer buffer, long offset, int[] src, int from, int count) {
        updateWords(buffer, offset, null, src, from, count);
    }

    private void updateWords(CgGraphBuffer buffer, long offset, float[] floats, int[] ints, int from, int count) {
        requireOpen();
        requireWritable(buffer, CgBufferUsage.COPY);
        int length = floats != null ? floats.length : ints.length;
        if (from < 0 || count < 0 || from + count > length) {
            throw new IndexOutOfBoundsException("words " + from + " to " + (from + count) + " of " + length);
        }
        CgPass.BufferUpdate update;
        if (updatesUsed < updates.size()) (update = updates.get(updatesUsed)).reuse(buffer, offset, floats, ints, from, count);
        else updates.add(update = new CgPass.BufferUpdate(buffer, offset, floats, ints, from, count));
        updatesUsed++;
        add(update);
        write(update, buffer, CgAccess.COPY_WRITE);
    }

    /**
     * Reads {@code size} bytes of {@code buffer} from {@code offset} back to the CPU, as everything recorded before it
     * left them: {@code sink} gets them on the render thread a few frames after the frame executes, and the request is
     * done once it has. The buffer needs {@code COPY}.
     *
     * <pre>{@code
     * CgRequest got = recording.readback(counts, 0, 4, data -> alive = data.getInt(0));
     * }</pre>
     */
    public CgRequest readback(CgGraphBuffer buffer, long offset, long size, CgReadback.Sink sink) {
        CgRequest request = new CgRequest("readback " + buffer.name());
        readback(buffer, offset, size, sink, request);
        return request;
    }

    /**
     * {@link #readback}, where nothing waits on a request: a value a frame reads back every frame (a count, a budget)
     * records no allocation once its pass is warm, Unreal's reused {@code FRHIGPUBufferReadback}. {@code sink} is called
     * as {@link #readback}'s, a few frames later; keep it in a field.
     *
     * <pre>{@code
     * private final CgReadback.Sink readCount = data -> lastCount = data.getInt(0);
     * recording.fetch(counts, 0, 4, readCount);   // every frame
     * }</pre>
     */
    public void fetch(CgGraphBuffer buffer, long offset, long size, CgReadback.Sink sink) {
        CgPass.Readback pass = null;
        for (int i = fetchesUsed; i < fetches.size(); i++) {
            CgPass.Readback free = fetches.get(i);
            if (free.held()) continue;
            fetches.set(i, fetches.get(fetchesUsed));
            fetches.set(fetchesUsed, free);
            pass = free;
            break;
        }
        if (pass == null) {
            pass = new CgPass.Readback(buffer, offset, size, sink, null);
            fetches.add(pass);
            Collections.swap(fetches, fetches.size() - 1, fetchesUsed);
        } else {
            pass.reuse(buffer, offset, size, sink);
        }
        fetchesUsed++;
        readback(buffer, offset, size, sink, pass);
    }

    private void readback(CgGraphBuffer buffer, long offset, long size, CgReadback.Sink sink, CgRequest request) {
        readback(buffer, offset, size, sink, new CgPass.Readback(buffer, offset, size, sink, request));
    }

    private void readback(CgGraphBuffer buffer, long offset, long size, CgReadback.Sink sink, CgPass.Readback pass) {
        requireOpen();
        requireUse(buffer, CgBufferUsage.COPY);
        if (offset < 0 || size <= 0 || offset + size > buffer.size()) {
            throw new IllegalArgumentException("a readback of " + size + " bytes at " + offset + " in " + buffer);
        }
        add(pass);
        read(pass, buffer, CgAccess.COPY_READ);
    }

    /**
     * Reads a {@code w} x {@code h} region at {@code (x, y)} of level {@code level} of {@code texture}'s first colour
     * attachment back to the CPU, as its texture type ({@link CgReadback#pixels}): rows bottom first, tightly packed.
     */
    public CgRequest readback(CgGraphTexture texture, int level, int x, int y, int w, int h, CgReadback.Sink sink) {
        return readback(texture, level, x, y, 0, w, h, 1, sink);
    }

    /**
     * Reads a {@code w} x {@code h} x {@code d} box at {@code (x, y, z)} of a volume back to the CPU: slices from
     * {@code z} up, each as the 2D form answers a region. On a 2D texture {@code z} is 0 and {@code d} 1.
     *
     * <pre>{@code
     * recording.readback(voxels, 0, 0, 0, 40, 128, 96, 2, data -> check(data));   // slices 40 and 41
     * }</pre>
     */
    public CgRequest readback(CgGraphTexture texture, int level, int x, int y, int z, int w, int h, int d,
                              CgReadback.Sink sink) {
        requireOpen();
        if (texture.isCurrent()) {
            throw new IllegalArgumentException("the current target is no texture; import it with CgTextures.target and read it with rec.readback");
        }
        region(texture, level, x, y, z, w, h, d, "a readback");
        CgRequest request = new CgRequest("readback " + texture.name());
        CgPass.Readback pass = new CgPass.Readback(texture, level, x, y, z, w, h, d, sink, request);
        add(pass);
        read(pass, texture, CgAccess.COPY_READ);
        return request;
    }

    /**
     * Writes {@code data}'s remaining bytes, copied now, into a {@code w} x {@code h} region at {@code (x, y)} of level
     * {@code level} of {@code texture}'s first colour attachment: rows bottom first, tightly packed, as its texture type,
     * which is how {@link #readback(CgGraphTexture, int, int, int, int, int, CgReadback.Sink)} answers them. The rest
     * keeps what it held.
     *
     * <pre>{@code
     * recording.update(heights, 0, 32, 0, 16, 16, slab);   // a 16x16 R32F slab at (32, 0)
     * }</pre>
     *
     * <ul>
     *   <li>{@code data} holds {@code w * h * CgReadback.pixelBytes(type)} bytes, or it throws.</li>
     *   <li>Written once when a frame executes again.</li>
     * </ul>
     */
    public CgRequest update(CgGraphTexture texture, int level, int x, int y, int w, int h, ByteBuffer data) {
        return update(texture, level, x, y, 0, w, h, 1, data);
    }

    /**
     * Writes a {@code w} x {@code h} x {@code d} box at {@code (x, y, z)} of a volume, laid out as
     * {@link #readback(CgGraphTexture, int, int, int, int, int, int, int, CgReadback.Sink)} answers one: what a window
     * scrolling through a world uploads, a slab of new slices. On a 2D texture {@code z} is 0 and {@code d} 1.
     *
     * <pre>{@code
     * recording.update(voxels, 0, 0, 0, 32, 128, 96, 16, slab);   // 16 slices from 32, R8UI: 128 * 96 * 16 bytes
     * }</pre>
     */
    public CgRequest update(CgGraphTexture texture, int level, int x, int y, int z, int w, int h, int d, ByteBuffer data) {
        requireOpen();
        if (texture.isArray()) throw new IllegalArgumentException(texture + " is an array: draw its layers");
        CgFormat format = region(texture, level, x, y, z, w, h, d, "an update");
        if (format == null || texture.desc().isMultisampled()) {
            throw new IllegalArgumentException(texture + " has no single-sampled colour texture of a known format to update");
        }
        long want = format.bytes(w, h, d);
        if (data.remaining() != want) {
            throw new IllegalArgumentException(w + "x" + h + "x" + d + " of " + format + " is " + want + " bytes, not "
                    + data.remaining());
        }
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        CgRequest request = new CgRequest("update " + texture.name());
        CgPass.TextureUpload pass = new CgPass.TextureUpload(texture, level, x, y, z, w, h, d, bytes, request);
        add(pass);
        write(pass, texture, CgAccess.COPY_WRITE);
        return request;
    }

    /** The format of {@code texture}, null where unknown, after checking the box lies in level {@code level}. */
    @Nullable
    private static CgFormat region(CgGraphTexture texture, int level, int x, int y, int z, int w, int h, int d,
                                   String what) {
        if (texture.isCurrent()) throw new IllegalArgumentException("the current target is no texture");
        if (level < 0 || level >= texture.getLevels()) {
            throw new IllegalArgumentException(texture + " has " + texture.getLevels() + " levels, not level " + level);
        }
        CgFormat format = texture.desc() != null ? texture.desc().format() : null;
        if (format != null && (format.hasDepth() || format.hasStencil())) {
            throw new IllegalArgumentException(texture + " is a " + format + " texture, not a colour one");
        }
        int lw = Math.max(1, texture.getWidth() >> level), lh = Math.max(1, texture.getHeight() >> level);
        int ld = texture.isArray() ? texture.getLayers() : texture.getDepth();
        if (x < 0 || y < 0 || z < 0 || w <= 0 || h <= 0 || d <= 0 || x + w > lw || y + h > lh || z + d > ld) {
            throw new IllegalArgumentException(what + " of " + w + "x" + h + "x" + d + " at " + x + "," + y + "," + z
                    + " in level " + level + " of " + texture + ", " + lw + "x" + lh + "x" + ld);
        }
        return format;
    }

    /**
     * Runs {@code body} on the render thread with {@code target} bound (or nothing bound, for null) and GL state
     * restored after: the escape hatch for drawing that is not recorded yet.
     *
     * @param reads graph textures the body samples, so it runs after whatever writes them
     */
    public CgRequest callback(String name, @Nullable CgGraphTexture target, Runnable body, CgGraphTexture... reads) {
        return callback(name, target, null, body, reads);
    }

    /**
     * As {@link #callback(String, CgGraphTexture, Runnable, CgGraphTexture...)}, drawing with {@code depth}'s depth
     * beside {@code target}: what a host draws into the stage's target with the stage's depth.
     */
    public CgRequest callback(String name, @Nullable CgGraphTexture target, @Nullable CgGraphTexture depth, Runnable body,
                              CgGraphTexture... reads) {
        requireOpen();
        CgRequest request = new CgRequest(name);
        CgPass.Callback pass = new CgPass.Callback(name, target, depth == target ? null : depth, body, request);
        add(pass);
        for (CgGraphTexture read : reads) read(pass, read, CgAccess.SAMPLED_READ);
        if (pass.depth != null) {
            read(pass, pass.depth, CgAccess.SAMPLED_READ);
            write(pass, pass.depth, CgAccess.COLOR_WRITE);
        }
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
        compiles.add(pipeline);
        compileRequests.add(request);
        return request;
    }

    /**
     * Frees a {@linkplain CgGraphTexture#requested requested} texture's storage after the last step of this frame that
     * uses it, or before the first where none does. Not a pass: the frame frees it.
     */
    public void release(CgGraphTexture requested) {
        requireOpen();
        if (requested.kind() != CgGraphTexture.Kind.REQUESTED
                && (requested.kind() != CgGraphTexture.Kind.HISTORY || requested.isPreviousVersion())) {
            throw new IllegalArgumentException("only a requested texture or a history is released: " + requested);
        }
        releases.add(requested);
    }

    /**
     * A persistent or history buffer at another size: a new handle of {@code buffer}'s kind and name, made at
     * {@code desc}, holding what each of {@code buffer}'s versions held up to the smaller of the two sizes; then
     * {@code buffer} is released. A pool grows this way when the CPU's count outgrows it, and shrinks on a quiet frame.
     *
     * <pre>{@code
     * if (needed > capacity) {
     *     capacity = sizeClass(needed);
     *     particles = recording.resize(particles, CgBufferDesc.elements(capacity, 64, STORAGE, COPY));
     *     particleMaterial.buffer("PARTICLES", particles);   // a binding of the old handle reads nothing
     * }
     * }</pre>
     *
     * <ul>
     *   <li>Use the handle it answers from here on, in this recording and every later one; the old one is gone once
     *       this recording executes.</li>
     *   <li>Past the old size the new storage holds whatever the driver gives, as new storage does.</li>
     *   <li>Both descs need {@code COPY}, since the bytes move by copies.</li>
     * </ul>
     */
    public CgGraphBuffer resize(CgGraphBuffer buffer, CgBufferDesc desc) {
        requireOpen();
        CgRehearsal.grew("resizes", buffer);
        boolean history = buffer.kind() == CgGraphBuffer.Kind.HISTORY;
        if (!history && buffer.kind() != CgGraphBuffer.Kind.PERSISTENT || buffer.isPreviousVersion()) {
            throw new IllegalArgumentException("only a persistent or history buffer is resized: " + buffer);
        }
        CgGraphBuffer next = history ? CgGraphBuffer.history(buffer.name(), desc) : CgGraphBuffer.persistent(buffer.name(), desc);
        long bytes = Math.min(buffer.size(), desc.bytes());
        // Each copy into a history makes its next version, so the previous version goes first and stays previous.
        if (history) copy(buffer.previous(), 0, next, 0, bytes);
        copy(buffer, 0, next, 0, bytes);
        release(buffer);
        return next;
    }

    /** Frees a persistent or history buffer's storage after the last step of this frame that uses it, as a texture's. */
    public void release(CgGraphBuffer buffer) {
        requireOpen();
        CgRehearsal.grew("releases", buffer);
        if (buffer.kind() != CgGraphBuffer.Kind.PERSISTENT && buffer.kind() != CgGraphBuffer.Kind.HISTORY
                || buffer.isPreviousVersion()) {
            throw new IllegalArgumentException("only a persistent or history buffer is released: " + buffer);
        }
        releases.add(buffer);
    }

    /**
     * How many passes and requests it holds: two readings tell whether a stretch of drawing recorded anything but
     * chunks into the pass that was open.
     */
    public int operations() {
        return passes.size() + releases.size() + compiles.size();
    }

    /** The pass made {@code index}-th, {@code 0} to {@link #passCount()}. */
    public CgPass pass(int index) {
        return passes.get(index);
    }

    /** Freezes it: every raster and compute pass must have ended. Answers itself, for {@code graph.add(rec.seal())}. */
    public CgRecording seal() {
        if (sealed) return this;
        for (int i = 0; i < passes.size(); i++) {
            CgPass pass = passes.get(i);
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
        int used = slots.size();
        slots.clear();
        Arrays.fill(writes, 0);
        Arrays.fill(readPass, 0, used, 0);
        Arrays.fill(readView, 0, used, null);
        Arrays.fill(eventViews, 0, eventCount, null);
        eventCount = 0;
        recycle(rasters, rastersUsed);
        rastersUsed = 0;
        recycle(computes, computesUsed);
        computesUsed = 0;
        recycle(fills, fillsUsed);
        fillsUsed = 0;
        for (int i = 0; i < fetchesUsed; i++) {
            CgPass.Readback pass = fetches.get(i);
            if (!pass.held()) pass.clearReferences();   // one in flight keeps its sink until delivered
        }
        fetchesUsed = 0;
        recycle(updates, updatesUsed);
        updatesUsed = 0;
        uploadsUsed = 0;
        recycle(includes, includesUsed);
        includesUsed = 0;
        chunks.reset();
        chunks.recycle();
        primitives.reset();
        clips.reset();
        shapes.reset();
        spatial.reset();
        effects.reset();
        scratchUsed = 0;
        sealed = false;
    }

    // ── package: what the frame builder reads ────────────────────────────────

    /** How many passes it holds: {@link #pass}'s indices. Releases and compiles are not passes ({@link #operations}). */
    public int passCount() {
        return passes.size();
    }

    int releaseCount() {
        return releases.size();
    }

    CgGraphResource releaseAt(int i) {
        return releases.get(i);
    }

    int compileCount() {
        return compiles.size();
    }

    CgPipeline compileAt(int i) {
        return compiles.get(i);
    }

    CgRequest compileRequest(int i) {
        return compileRequests.get(i);
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
        if (readPass[slot] == p + 1 && readVersion[slot] == writes[slot] && readView[slot] == view) {
            if ((access & ~readBits[slot]) == 0) return;   // this version, these bits, logged
            readBits[slot] |= access;
        } else {
            readPass[slot] = p + 1;
            readVersion[slot] = writes[slot];
            readView[slot] = view;
            readBits[slot] = access;
        }
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
        if (!buffer.allows(usage)) {
            throw new IllegalArgumentException(buffer + " has no " + usage + " use");
        }
    }

    private static void requireWritable(CgGraphBuffer buffer, CgBufferUsage usage) {
        if (buffer.isPreviousVersion()) throw new IllegalArgumentException(buffer + ": a previous version is read-only");
        requireUse(buffer, usage);
    }

    private void add(CgPass pass) {
        passes.add(pass);
    }

    private int indexOf(CgPass pass) {
        // The pass being read or written is nearly always the newest.
        for (int i = passes.size() - 1; i >= 0; i--) if (passes.get(i) == pass) return i;
        throw new IllegalArgumentException(pass + " is not this recording's");
    }

    /** The slot of what {@code view} is ordered on: a previous version's history. */
    private int slot(CgGraphResource view) {
        CgGraphResource resource = view instanceof CgGraphBuffer buffer ? buffer.resource()
                : view instanceof CgGraphTexture texture ? texture.resource() : view;
        Integer slot = slots.get(resource);
        if (slot != null) return slot;
        int s = resources.size();
        resources.add(resource);
        slots.put(resource, CgBoxedInts.of(s));
        if (s == writes.length) {
            writes = Arrays.copyOf(writes, s * 2);
            readPass = Arrays.copyOf(readPass, s * 2);
            readVersion = Arrays.copyOf(readVersion, s * 2);
            readBits = Arrays.copyOf(readBits, s * 2);
            readView = Arrays.copyOf(readView, s * 2);
        }
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
