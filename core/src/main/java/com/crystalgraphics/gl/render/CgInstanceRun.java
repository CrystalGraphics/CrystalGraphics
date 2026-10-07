package com.crystalgraphics.gl.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgChunkSink;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What turns a renderer's queued records into recorded draws: each run of records submitted under one
 * {@code useMaterial} becomes one draw, under the material's pipeline and a snapshot of it taken at that call, and a
 * flush draws them now through {@link CgImmediate} — the same executor a frame graph runs on.
 *
 * <ul>
 *   <li>A run draws under the material as the LAST {@code useMaterial} before its draw found it, which is what a
 *       bind used to upload: records queued earlier take values set after them.</li>
 *   <li>Recording, a chunk is one spatial node's, ended where the records' node changes, and each draw carries the
 *       bounds of its records in that node -- what lets a lookback pass move it past draws it does not touch.</li>
 *   <li>Recording, the snapshots are the sink's recording's, and live as long as the chunks that name them.
 *       Immediate, they are the run's own, dropped at the first {@code begin()} of a frame.</li>
 * </ul>
 */
final class CgInstanceRun {

    private final CgInstanceKind kind;
    /** Immediate draws' snapshots: executed before the frame ends, so dropped once a frame. */
    private final CgBindingTable own = new CgBindingTable();
    private final CgChunkBuilder chunk = new CgChunkBuilder(own);
    private boolean chunkOpen;
    private long ownFrame = -1;
    /** The table {@link #materialBinding} and {@link #binding} are ids in. */
    private CgBindingTable captured = own;
    /** Where a flush hands its chunk; null executes it at once. */
    @Nullable
    CgChunkSink sink;

    private CgMaterial material;
    @Nullable
    private CgRenderState state;
    private CgPipeline pipeline;
    /** The material's own snapshot, and the one drawn: that with any hand-bound texture laid over it. */
    private int materialBinding = -1;
    private int binding = -1;

    /** The binding and pipeline of the open draw {@link #records} may still append to; -1 once anything comes between. */
    private int appendBinding = -1;
    @Nullable
    private CgPipeline appendPipeline;

    /** The spatial node queued records are in, and their bounds there while any are. */
    private int spatial;
    private float boundsX0, boundsY0, boundsX1, boundsY1;
    private boolean bounded;

    /** Textures bound by hand per unit, and whether each came after the last {@code useMaterial}. */
    private final CgTexture[] handBound = new CgTexture[CgBindingTable.MAX_TEXTURES];
    private final boolean[] boundSinceUse = new boolean[CgBindingTable.MAX_TEXTURES];

    CgInstanceRun(CgInstanceKind kind) {
        this.kind = kind;
    }

    /** A new window; the run's own snapshots are dropped at the first of a frame. */
    void begin() {
        // Once a frame, not per begin: a chunk a deferral holds still names this table's snapshots. A recording's
        // table is never reset here -- a document thread records across the render thread's frames.
        long frame = CgFrameRing.frame();
        if (frame != ownFrame) {
            own.reset();
            ownFrame = frame;
        }
        chunk.reset();
        chunkOpen = false;
        materialBinding = -1;
        binding = -1;
        appendBinding = -1;
    }

    /**
     * Snapshots {@code next} for the records queued since the last flush: the same material re-snapshots them,
     * because a bind used to upload what was set after the records were queued and before the draw (a caller
     * sets a drawable's properties between two {@code useMaterial}s); another material ends their run first.
     */
    void use(CgMaterial next, @Nullable CgRenderState override, CgStagingBuffer pending) {
        if (next != material) close(pending);
        material = next;
        state = override;
        Arrays.fill(boundSinceUse, false);
        capture();
    }

    /**
     * A texture bound to {@code unit} by hand, as GL's last bind wins: it takes the unit from the material's own
     * sampler when bound after the last {@code useMaterial}, and fills a unit the material leaves empty either way.
     */
    void handBind(int unit, CgTexture texture) {
        handBound[unit] = texture;
        boundSinceUse[unit] = true;
        if (materialBinding >= 0) binding = withHandBound(materialBinding);
    }

    /** The material as it is now, under the caller's render state when it gave one. */
    private void capture() {
        pipeline = material.pipeline(kind);
        if (pipeline != null && state != null) pipeline = pipeline.withState(state);
        captured = table();
        materialBinding = material.captureBindings(captured);
        binding = withHandBound(materialBinding);
    }

    /** Where snapshots go: the recording a sink records into, or this run's own for an immediate draw. */
    private CgBindingTable table() {
        return sink != null ? sink.bindings() : own;
    }

    private int withHandBound(int snapshot) {
        int result = snapshot;
        for (int unit = 0; unit < handBound.length; unit++) {
            CgTexture texture = handBound[unit];
            if (texture != null && (boundSinceUse[unit] || !captured.bindsUnit(snapshot, unit))) {
                result = captured.withTexture(result, unit, texture);
            }
        }
        return result;
    }

    /**
     * Records queued from now are positioned in spatial node {@code node}. Recording, a chunk is one node's, so a
     * change ends the open one; an immediate draw takes every node in one chunk.
     */
    void spatial(int node, CgStagingBuffer pending) {
        if (node == spatial) return;
        if (sink != null) {
            close(pending);
            if (chunkOpen) {
                chunkOpen = false;
                appendBinding = -1;
                sink.add(chunk.end());
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
    void close(CgStagingBuffer pending) {
        if (pending.isEmpty()) return;
        // After a begin(), or once the sink records elsewhere: the material as it is now, in the current table.
        if ((binding < 0 || captured != table()) && material != null) capture();
        if (pipeline != null) {
            if (!chunkOpen) {
                chunk.bindings(captured).begin(sink != null ? spatial : 0, 0, 0);
                chunkOpen = true;
            } else if (chunk.bindings() != captured) {
                throw new IllegalStateException("the sink's recording changed under an open chunk: flush before");
            }
            chunk.draw(pipeline, binding).instances(pending.rawData(), 0, pending.vertexCount());
            // An immediate chunk mixes nodes: its draws stay unbounded, covering everything.
            if (bounded && sink != null) chunk.bounds(boundsX0, boundsY0, boundsX1, boundsY1);
        }
        appendBinding = -1;
        bounded = false;
        pending.reset();
    }

    /**
     * Records already in the kind's layout, {@code count} of them from float {@code from} of {@code src}, bounded by
     * the rectangle given: after anything staged, and into the open draw while nothing has come between.
     */
    void records(float[] src, int from, int count, float x0, float y0, float x1, float y1, CgStagingBuffer pending) {
        close(pending);
        if ((binding < 0 || captured != table()) && material != null) capture();
        if (pipeline == null) return;
        if (!chunkOpen) {
            chunk.bindings(captured).begin(sink != null ? spatial : 0, 0, 0);
            chunkOpen = true;
            appendBinding = -1;
        } else if (chunk.bindings() != captured) {
            throw new IllegalStateException("the sink's recording changed under an open chunk: flush before");
        }
        if (appendBinding != binding || appendPipeline != pipeline) {
            chunk.draw(pipeline, binding);
            appendBinding = binding;
            appendPipeline = pipeline;
        }
        chunk.instances(src, from, count);
        if (sink != null) chunk.bounds(x0, y0, x1, y1);
    }

    /** Whether recorded draws await a flush. */
    boolean pending() {
        return chunkOpen;
    }

    /** Draws everything recorded since the last flush, in submission order, into the bound framebuffer. */
    void flush(CgStagingBuffer pending) {
        close(pending);
        if (!chunkOpen) return;
        chunkOpen = false;
        appendBinding = -1;
        if (sink != null) sink.add(chunk.end());
        else CgImmediate.flush(chunk.endInPlace(), CgOrder.SORTED);
    }
}
