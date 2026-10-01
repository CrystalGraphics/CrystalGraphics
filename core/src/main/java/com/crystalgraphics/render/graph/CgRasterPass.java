package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chunks drawn into one target: its load, its pass constants, the render state its pipelines' unset slots take,
 * and how its draws may be batched. Made with {@link CgRecording#raster}; ended before the recording is sealed.
 *
 * <pre>{@code
 * CgRasterPass pass = recording.raster(layer, CgLoad.clear(0, 0, 0, 0), constants, uiState, CgOrder.LOOKBACK);
 * pass.add(chunk);
 * pass.add(otherChunk);
 * pass.end();                // the layer's contents are complete: a draw recorded after this reads them
 * }</pre>
 *
 * <ul>
 *   <li>A chunk whose bindings read a {@link CgGraphTexture} makes this pass read it as of {@link #add}: the
 *       graph runs the pass that wrote it first, whenever either was made.</li>
 *   <li>A recorder that must read its own target mid-pass (a backdrop) ends the pass, copies, and opens another
 *       on the same target with {@link CgLoad#load()}.</li>
 * </ul>
 */
public final class CgRasterPass extends CgPass {

    final CgRecording recording;
    final CgLoad load;
    final float[] constants;
    @Nullable
    final CgRenderState state;
    final CgOrder order;
    private final List<CgDrawChunk> chunks = new ArrayList<>();
    private boolean ended;

    CgRasterPass(CgRecording recording, String name, CgGraphTexture target, CgLoad load, float[] constants,
                 @Nullable CgRenderState state, CgOrder order) {
        super(name, target, null);
        this.recording = recording;
        this.load = load;
        this.constants = constants;
        this.state = state;
        this.order = order;
    }

    /** Appends a chunk, in painter's order. */
    public CgRasterPass add(CgDrawChunk chunk) {
        if (ended) throw new IllegalStateException(this + " has ended");
        recording.requireOpen();
        chunks.add(chunk);
        CgBindingTable table = chunk.bindings();
        for (int d = 0; d < chunk.draws(); d++) {
            int id = chunk.binding(d);
            for (int t = 0; t < table.textures(id); t++) {
                CgTexture texture = table.texture(id, t);
                if (texture instanceof CgGraphTexture graph) recording.read(this, graph);
            }
        }
        return this;
    }

    /** Ends the pass: what it drew is now what a later read of its target sees. */
    public void end() {
        if (ended) throw new IllegalStateException(this + " has already ended");
        recording.requireOpen();
        ended = true;
        recording.write(this, target);
    }

    public List<CgDrawChunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    boolean ended() {
        return ended;
    }

    List<CgDrawChunk> chunkList() {
        return chunks;
    }
}
