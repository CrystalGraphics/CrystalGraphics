package com.crystalgraphics.render.canvas;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.staging.CgStagingBuffer;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A batcher's queued records as draws (Unreal's {@code FCanvasBatchedElementRenderItem}): consecutive items under one
 * material, as it was when the run started, one render state and one set of textures are one draw; any difference
 * starts the next. Sealed into its canvas's passes, or drawn at once where the canvas records nothing.
 *
 * <ul>
 *   <li>Recording, a chunk is one spatial node's, ended where the records' node changes, and each draw carries the
 *       bounds of its records in that node -- what lets a lookback pass move it past draws it does not touch.</li>
 *   <li>A snapshot lives in the table of the recording it lands in, or the run's own for an immediate draw (reset once
 *       a frame), and is taken again once that table was reset.</li>
 * </ul>
 */
final class CgInstanceRun implements CgCanvas.Drawing {

    private final CgInstanceKind kind;
    private final CgCanvas canvas;
    /** The batcher's queued records, not yet a draw. */
    private final CgStagingBuffer pending;
    /** Immediate draws' snapshots: executed before the frame ends, so dropped once a frame. */
    private final CgBindingTable own = new CgBindingTable();
    private final CgChunkBuilder chunk = new CgChunkBuilder(own);
    private boolean chunkOpen;
    private long ownFrame = -1;
    /** The table {@link #binding} is an id in, and its reset count then. */
    private CgBindingTable captured = own;
    private int capturedResets = -1;

    @Nullable
    private CgMaterial material;
    private int materialChanges;
    @Nullable
    private CgRenderState state;
    private CgPipeline pipeline;
    private int binding = -1;
    /** The textures laid over the material's own at each unit: the items', winning the unit. */
    private final CgTexture[] textures = new CgTexture[CgBindingTable.MAX_TEXTURES];
    /** Each laid-over texture's sampler, null for its spec's. */
    private final CgSampler[] samplers = new CgSampler[CgBindingTable.MAX_TEXTURES];

    /** The binding and pipeline of the open draw {@link #records} may still append to; -1 once anything comes between. */
    private int appendBinding = -1;
    @Nullable
    private CgPipeline appendPipeline;

    /** The spatial node queued records are in, and their bounds there while any are. */
    private int spatial;
    private float boundsX0, boundsY0, boundsX1, boundsY1;
    private boolean bounded;

    CgInstanceRun(CgInstanceKind kind, CgCanvas canvas, CgStagingBuffer pending) {
        this.kind = kind;
        this.canvas = canvas;
        this.pending = pending;
    }

    /**
     * Before an item is queued: draws under {@code next}, {@code override}, {@code units} and their {@code sampled}
     * from here. The run of records before ends where any differs, or the material changed since the run began; the
     * canvas seals what another batcher drew first.
     */
    void item(CgMaterial next, @Nullable CgRenderState override, CgTexture[] units, CgSampler[] sampled, int unitCount) {
        canvas.claim(this);
        if (!canvas.recording()) {
            // Once a frame, not per draw: a chunk a deferral holds still names this table's snapshots.
            long frame = CgFrameRing.frame();
            if (frame != ownFrame && !chunkOpen && pending.isEmpty()) {
                own.reset();
                ownFrame = frame;
            }
        }
        if (next == material && next.changes() == materialChanges && override == state
                && sameTextures(units, sampled, unitCount) && !stale()) {
            return;
        }
        close();
        material = next;
        materialChanges = next.changes();
        state = override;
        Arrays.fill(textures, null);
        Arrays.fill(samplers, null);
        System.arraycopy(units, 0, textures, 0, unitCount);
        System.arraycopy(sampled, 0, samplers, 0, unitCount);
        capture();
    }

    private boolean sameTextures(CgTexture[] units, CgSampler[] sampled, int unitCount) {
        for (int unit = 0; unit < textures.length; unit++) {
            boolean set = unit < unitCount;
            if (textures[unit] != (set ? units[unit] : null) || samplers[unit] != (set ? sampled[unit] : null)) {
                return false;
            }
        }
        return true;
    }

    /** The material as it is now, under the item's render state, its textures laid over. */
    private void capture() {
        pipeline = CgMaterialProxy.of(material).pipeline(kind);
        if (pipeline != null && state != null) pipeline = pipeline.withState(state);
        captured = canvas.recording() ? canvas.bindings() : own;
        capturedResets = captured.resets();
        int snapshot = CgMaterialProxy.of(material).captureBindings(captured);
        for (int unit = 0; unit < textures.length; unit++) {
            if (textures[unit] != null) snapshot = captured.withTexture(snapshot, unit, textures[unit], samplers[unit]);
        }
        binding = snapshot;
    }

    /** Whether {@link #binding} is no id in the table its draws land in now. */
    private boolean stale() {
        return binding < 0 || captured != (canvas.recording() ? canvas.bindings() : own) || captured.resets() != capturedResets;
    }

    /**
     * Records queued from now are positioned in spatial node {@code node}. Recording, a chunk is one node's, so a
     * change ends the open one; an immediate draw takes every node in one chunk.
     */
    void spatial(int node) {
        if (node == spatial) return;
        if (canvas.recording()) {
            close();
            if (chunkOpen) {
                chunkOpen = false;
                appendBinding = -1;
                canvas.add(chunk.end());
            }
        }
        spatial = node;
    }

    /** Unions one queued record's ink, in its node's space, into its draw's bounds. */
    void bounds(float x0, float y0, float x1, float y1) {
        if (!bounded) {
            boundsX0 = x0;
            boundsY0 = y0;
            boundsX1 = x1;
            boundsY1 = y1;
            bounded = true;
            return;
        }
        boundsX0 = Math.min(boundsX0, x0);
        boundsY0 = Math.min(boundsY0, y0);
        boundsX1 = Math.max(boundsX1, x1);
        boundsY1 = Math.max(boundsY1, y1);
    }

    /** Moves the queued records into the chunk as one draw. */
    private void close() {
        if (pending.isEmpty()) return;
        if (stale() && material != null) capture();
        if (pipeline != null) {
            openChunk();
            chunk.draw(pipeline, binding).instances(pending.rawData(), 0, pending.vertexCount());
            // An immediate chunk mixes nodes: its draws stay unbounded, covering everything.
            if (bounded && canvas.recording()) chunk.bounds(boundsX0, boundsY0, boundsX1, boundsY1);
        }
        appendBinding = -1;
        bounded = false;
        pending.reset();
    }

    private void openChunk() {
        if (!chunkOpen) {
            chunk.bindings(captured).begin(canvas.recording() ? spatial : 0, 0, 0);
            chunkOpen = true;
            appendBinding = -1;
        }
    }

    /**
     * Records already in the kind's layout, {@code count} of them from float {@code from} of {@code src}, bounded by
     * the rectangle given: after anything staged, and into the open draw while nothing has come between. After
     * {@link #item}.
     */
    void records(float[] src, int from, int count, float x0, float y0, float x1, float y1) {
        close();
        if (stale() && material != null) capture();
        if (pipeline == null) return;
        openChunk();
        if (appendBinding != binding || appendPipeline != pipeline) {
            chunk.draw(pipeline, binding);
            appendBinding = binding;
            appendPipeline = pipeline;
        }
        chunk.instances(src, from, count);
        if (canvas.recording()) chunk.bounds(x0, y0, x1, y1);
    }

    /** Whether draws or records wait to land. */
    boolean pending() {
        return chunkOpen || !pending.isEmpty();
    }

    /** Lands what is drawn: into the canvas's passes while it records, else drawn now through {@link CgRenderer#drawNow}. */
    @Override
    public void seal() {
        if (!pending()) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "canvas.seal")) {
            close();
            if (!chunkOpen) return;
            chunkOpen = false;
            appendBinding = -1;
            if (canvas.recording()) canvas.add(chunk.end());
            else CgRenderer.drawNow(chunk.endInPlace(), canvas.nowCamera(), CgOrder.SORTED);
        }
    }

    /** Forgets what is drawn: its recording is being discarded. */
    @Override
    public void drop() {
        pending.reset();
        chunk.reset();
        chunkOpen = false;
        appendBinding = -1;
        bounded = false;
        binding = -1;
    }
}
