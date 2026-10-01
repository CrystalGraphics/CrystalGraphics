package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>A chunk is drawn under the scissor set when it was added, as a command buffer's set-scissor works:
 *       {@link #scissor} and {@link #noScissor}. A pass that never sets one leaves GL's scissor as it finds it.</li>
 *   <li>{@link #texture} binds a texture for the whole pass, with its constants: what every draw of it samples at a
 *       fixed unit, as a world pass binds the scene's depth.</li>
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

    /** A chunk's scissor: an index into {@link #scissorRects}, {@link #NO_SCISSOR}, or {@link #INHERIT}. */
    static final int NO_SCISSOR = -1, INHERIT = -2;
    private int[] chunkScissor = new int[16];
    private int[] scissorRects = new int[16];
    private int scissorCount;
    private int scissor = INHERIT;

    /** Textures bound with the pass's constants: units and textures, in parallel. */
    private int[] textureUnits = new int[0];
    private CgTexture[] textures = new CgTexture[0];

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
        if (chunks.size() == chunkScissor.length) chunkScissor = Arrays.copyOf(chunkScissor, chunkScissor.length * 2);
        chunkScissor[chunks.size()] = scissor;
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

    /** Draws the chunks added from now on inside {@code (x, y, w, h)}, in the target's bottom-left pixels. */
    public CgRasterPass scissor(int x, int y, int w, int h) {
        int set = scissor * 4;
        if (scissor >= 0 && scissorRects[set] == x && scissorRects[set + 1] == y
                && scissorRects[set + 2] == w && scissorRects[set + 3] == h) return this;
        if ((scissorCount + 1) * 4 > scissorRects.length) scissorRects = Arrays.copyOf(scissorRects, scissorRects.length * 2);
        int at = scissorCount * 4;
        scissorRects[at] = x;
        scissorRects[at + 1] = y;
        scissorRects[at + 2] = w;
        scissorRects[at + 3] = h;
        scissor = scissorCount++;
        return this;
    }

    /** Binds {@code texture} at {@code unit} for every draw of this pass. A graph texture is read as of this call. */
    public CgRasterPass texture(int unit, CgTexture texture) {
        if (ended) throw new IllegalStateException(this + " has ended");
        int n = textureUnits.length;
        textureUnits = Arrays.copyOf(textureUnits, n + 1);
        textures = Arrays.copyOf(textures, n + 1);
        textureUnits[n] = unit;
        textures[n] = texture;
        if (texture instanceof CgGraphTexture graph) recording.read(this, graph);
        return this;
    }

    int textureCount() {
        return textureUnits.length;
    }

    int textureUnit(int i) {
        return textureUnits[i];
    }

    CgTexture texture(int i) {
        return textures[i];
    }

    /** Draws the chunks added from now on unscissored. */
    public CgRasterPass noScissor() {
        scissor = NO_SCISSOR;
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

    /** Chunk {@code i}'s scissor. @see #NO_SCISSOR */
    int chunkScissor(int i) {
        return chunkScissor[i];
    }

    /** The scissor rects {@link #chunkScissor} indexes, four ints each: {@code x, y, w, h}. */
    int[] scissorRects() {
        return scissorRects;
    }
}
