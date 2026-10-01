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
 *       {@link #scissor} and {@link #noScissor}. A pass that never sets one leaves GL's scissor as it finds it. A
 *       scissor given in a spatial node's space moves with the node, as its draws do.</li>
 *   <li>A pass into a layer names where the layer sits in the recording's root space with {@link #view}: what a
 *       record under a spatial node is placed through. Records at node 0 are already in the target's space.</li>
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
    /** Per scissor, the spatial node its box is in, or -1 for a rect in target pixels. */
    private int[] scissorNodes = new int[4];
    private float[] scissorBoxes = new float[16];
    private int scissorCount;
    private int scissor = INHERIT;

    private int viewOwner;
    private float viewX, viewY;

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
        if (scissor >= 0 && scissorNodes[scissor] < 0 && scissorRects[set] == x && scissorRects[set + 1] == y
                && scissorRects[set + 2] == w && scissorRects[set + 3] == h) return this;
        int at = nextScissor(-1);
        scissorRects[at] = x;
        scissorRects[at + 1] = y;
        scissorRects[at + 2] = w;
        scissorRects[at + 3] = h;
        return this;
    }

    /**
     * Draws the chunks added from now on inside {@code (x0, y0)-(x1, y1)} in spatial node {@code node}'s space,
     * top-down: the box's bounds in the target as the node stands when the pass executes, whole pixels outward.
     */
    public CgRasterPass scissor(int node, float x0, float y0, float x1, float y1) {
        int set = scissor * 4;
        if (scissor >= 0 && scissorNodes[scissor] == node && scissorBoxes[set] == x0 && scissorBoxes[set + 1] == y0
                && scissorBoxes[set + 2] == x1 && scissorBoxes[set + 3] == y1) return this;
        int at = nextScissor(node);
        scissorBoxes[at] = x0;
        scissorBoxes[at + 1] = y0;
        scissorBoxes[at + 2] = x1;
        scissorBoxes[at + 3] = y1;
        return this;
    }

    /**
     * Places this pass's target at {@code (originX, originY)} in the recording's root space, moving with spatial node
     * {@code owner}: a layer whose content was recorded under {@code owner} and is composited back through it. A
     * pass with no view is the root's own target, at the origin.
     */
    public CgRasterPass view(int owner, float originX, float originY) {
        viewOwner = owner;
        viewX = originX;
        viewY = originY;
        return this;
    }

    int viewOwner() {
        return viewOwner;
    }

    float viewX() {
        return viewX;
    }

    float viewY() {
        return viewY;
    }

    private int nextScissor(int node) {
        if ((scissorCount + 1) * 4 > scissorRects.length) {
            scissorRects = Arrays.copyOf(scissorRects, scissorRects.length * 2);
            scissorBoxes = Arrays.copyOf(scissorBoxes, scissorRects.length);
        }
        if (scissorCount == scissorNodes.length) scissorNodes = Arrays.copyOf(scissorNodes, scissorNodes.length * 2);
        scissorNodes[scissorCount] = node;
        scissor = scissorCount++;
        return scissor * 4;
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

    /** Scissor {@code i}'s spatial node, or -1 when it is a rect in {@link #scissorRects}. */
    int scissorNode(int i) {
        return scissorNodes[i];
    }

    /** The boxes of node scissors, four floats each at the scissor's index: {@code x0, y0, x1, y1}. */
    float[] scissorBoxes() {
        return scissorBoxes;
    }
}
