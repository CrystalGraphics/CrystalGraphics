package com.crystalgraphics.gl.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
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
 *   <li>Snapshots live until the renderer's next {@code begin()}; a flush keeps them.</li>
 * </ul>
 */
final class CgInstanceRun {

    private final CgInstanceKind kind;
    private final CgBindingTable bindings = new CgBindingTable();
    private final CgChunkBuilder chunk = new CgChunkBuilder(bindings);
    private boolean chunkOpen;

    private CgMaterial material;
    @Nullable
    private CgRenderState state;
    private CgPipeline pipeline;
    /** The material's own snapshot, and the one drawn: that with any hand-bound texture laid over it. */
    private int materialBinding = -1;
    private int binding = -1;

    /** Textures bound by hand per unit, and whether each came after the last {@code useMaterial}. */
    private final CgTexture[] handBound = new CgTexture[CgBindingTable.MAX_TEXTURES];
    private final boolean[] boundSinceUse = new boolean[CgBindingTable.MAX_TEXTURES];

    CgInstanceRun(CgInstanceKind kind) {
        this.kind = kind;
    }

    /** A new window: earlier snapshots are dropped. */
    void begin() {
        bindings.reset();
        chunk.reset();
        chunkOpen = false;
        materialBinding = -1;
        binding = -1;
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
        materialBinding = material.captureBindings(bindings);
        binding = withHandBound(materialBinding);
    }

    private int withHandBound(int snapshot) {
        int result = snapshot;
        for (int unit = 0; unit < handBound.length; unit++) {
            CgTexture texture = handBound[unit];
            if (texture != null && (boundSinceUse[unit] || !bindings.bindsUnit(snapshot, unit))) {
                result = bindings.withTexture(result, unit, texture);
            }
        }
        return result;
    }

    /** Moves the queued records into the chunk as one draw. */
    void close(CgStagingBuffer pending) {
        if (pending.isEmpty()) return;
        if (binding < 0 && material != null) capture();   // after a begin(): the material as it is now
        if (pipeline != null) {
            if (!chunkOpen) {
                chunk.begin();
                chunkOpen = true;
            }
            chunk.draw(pipeline, binding).instances(pending.rawData(), 0, pending.vertexCount());
        }
        pending.reset();
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
        CgImmediate.flush(chunk.end(), CgOrder.SORTED);
    }
}
