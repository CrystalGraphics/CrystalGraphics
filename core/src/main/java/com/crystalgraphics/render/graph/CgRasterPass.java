package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.device.command.CgAccess;
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
 *   <li>A chunk whose bindings read a {@link CgGraphTexture}, or bind a {@link CgGraphBuffer} as storage, or whose
 *       indirect draw takes its count from one, makes this pass read it as of {@link #add}: the graph runs the pass
 *       that wrote it first, whenever either was made.</li>
 *   <li>A recorder that must read its own target mid-pass (a backdrop) ends the pass, copies, and opens another
 *       on the same target with {@link CgLoad#load()}.</li>
 *   <li>A chunk is drawn under the scissor set when it was added, as a command buffer's set-scissor works:
 *       {@link #scissor(int, int, int, int)}, {@link #useScissor} and {@link #noScissor}. A pass that never sets
 *       one leaves GL's scissor as it finds it. A scissor given in a spatial node's space moves with the node, as
 *       its draws do, and one made inside another is cut by it wherever either moves.</li>
 *   <li>A pass into a layer names where the layer sits in the recording's root space with {@link #view}: what a
 *       record under a spatial node is placed through. Records at node 0 are already in the target's space.</li>
 *   <li>{@link #texture} binds a texture for the whole pass, with its constants: what every draw of it samples at a
 *       fixed unit, as a world pass binds the scene's depth.</li>
 *   <li>{@link #damage} limits the pass to what changed in a target that keeps its contents: its clear and every
 *       draw are cut to the rect, and an empty rect executes nothing at all.</li>
 * </ul>
 */
public final class CgRasterPass extends CgPass {

    final CgRecording recording;
    /** The mip level of the target it draws into. */
    final int level;
    final CgLoad load;
    final float[] constants;
    @Nullable
    final CgRenderState state;
    final CgOrder order;
    private final List<CgDrawChunk> chunks = new ArrayList<>();
    private boolean ended;
    /** What changed in the target, in its bottom-left pixels -- x, y, width, height -- or null for all of it. */
    @Nullable
    private int[] damage;


    /** A chunk's scissor: an index into {@link #scissorRects}, {@link #NO_SCISSOR}, or {@link #INHERIT}. */
    static final int NO_SCISSOR = -1, INHERIT = -2;
    private int[] chunkScissor = new int[16];
    private int[] scissorRects = new int[16];
    /** Per scissor, the spatial node its box is in, or -1 for a rect in target pixels; and the one it is inside. */
    private int[] scissorNodes = new int[4];
    private int[] scissorParents = new int[4];
    private float[] scissorBoxes = new float[16];
    private int scissorCount;
    private int scissor = INHERIT;

    private int viewOwner;
    private float viewX, viewY;

    /** Textures bound with the pass's constants: units and textures, in parallel. */
    private int[] textureUnits = new int[0];
    private CgTexture[] textures = new CgTexture[0];

    CgRasterPass(CgRecording recording, String name, CgGraphTexture target, int level, CgLoad load, float[] constants,
                 @Nullable CgRenderState state, CgOrder order) {
        super(name, target, null);
        this.recording = recording;
        this.level = level;
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
            if (chunk.indirectCount(d) instanceof CgGraphBuffer count) recording.read(this, count, CgAccess.COMPUTE_READ);
            int id = chunk.binding(d);
            for (int t = 0; t < table.textures(id); t++) {
                CgGraphTexture graph = CgGraphTexture.sampled(table.texture(id, t));
                if (graph != null) recording.read(this, graph, CgAccess.SAMPLED_READ);
            }
            for (int s = 0; s < table.storages(id); s++) {
                if (table.storage(id, s) instanceof CgGraphBuffer buffer) {
                    recording.read(this, buffer, CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ);
                }
            }
            for (int s = 0; s < table.texelBuffers(id); s++) {
                if (table.texelBuffer(id, s) instanceof CgGraphBuffer buffer) recording.read(this, buffer, CgAccess.SAMPLED_READ);
            }
        }
        return this;
    }

    /** Draws the chunks added from now on inside {@code (x, y, w, h)}, in the target's bottom-left pixels. */
    public CgRasterPass scissor(int x, int y, int w, int h) {
        return useScissor(scissor(-1, x, y, w, h));
    }

    /**
     * A scissor inside scissor {@code parent} (-1 for none): {@code (x, y, w, h)} in the target's bottom-left pixels.
     * Answers its index for {@link #useScissor}, and for a scissor made inside it.
     */
    public int scissor(int parent, int x, int y, int w, int h) {
        int last = scissorCount - 1, set = last * 4;
        if (last >= 0 && scissorNodes[last] < 0 && scissorParents[last] == parent && scissorRects[set] == x
                && scissorRects[set + 1] == y && scissorRects[set + 2] == w && scissorRects[set + 3] == h) return last;
        int at = nextScissor(-1, parent);
        scissorRects[at] = x;
        scissorRects[at + 1] = y;
        scissorRects[at + 2] = w;
        scissorRects[at + 3] = h;
        return at / 4;
    }

    /**
     * A scissor inside scissor {@code parent} (-1 for none): {@code (x0, y0)-(x1, y1)} in spatial node {@code node}'s
     * space, top-down, whose bounds in the target are taken as the node stands when the pass executes, whole pixels
     * outward. Answers its index.
     */
    public int scissor(int parent, int node, float x0, float y0, float x1, float y1) {
        int last = scissorCount - 1, set = last * 4;
        if (last >= 0 && scissorNodes[last] == node && scissorParents[last] == parent && scissorBoxes[set] == x0
                && scissorBoxes[set + 1] == y0 && scissorBoxes[set + 2] == x1 && scissorBoxes[set + 3] == y1) return last;
        int at = nextScissor(node, parent);
        scissorBoxes[at] = x0;
        scissorBoxes[at + 1] = y0;
        scissorBoxes[at + 2] = x1;
        scissorBoxes[at + 3] = y1;
        return at / 4;
    }

    /** Draws the chunks added from now on under scissor {@code index}, which this pass answered. */
    public CgRasterPass useScissor(int index) {
        if (index < 0 || index >= scissorCount) throw new IllegalArgumentException("no scissor " + index + " in " + this);
        scissor = index;
        return this;
    }

    /**
     * Places this pass's target at {@code (originX, originY)} in the recording's root space, moving with spatial node
     * {@code owner}: a layer whose content was recorded under {@code owner} and is composited back through it. A
     * pass with no view is the root's own target, at the origin.
     */
    /**
     * Executes only what lies in {@code (x, y, width, height)}, the target's bottom-left pixels: the clear and every
     * draw cut to it, the rest of the target kept as it was -- a surface whose window changed in one place. An empty
     * rect executes nothing. May be set after {@link #end}, until the recording is sealed.
     *
     * <pre>{@code
     * pass.end();
     * pass.damage(x, y, w, h);     // a caret blinked: 2 x 18 pixels of a window drawn again
     * }</pre>
     */
    public CgRasterPass damage(int x, int y, int width, int height) {
        recording.requireOpen();
        damage = new int[] {x, y, Math.max(0, width), Math.max(0, height)};
        return this;
    }

    /** The rect {@link #damage} limits the pass to, x, y, width, height; null when it draws the whole target. */
    @Nullable
    public int[] damage() {
        return damage;
    }

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

    private int nextScissor(int node, int parent) {
        if (parent < -1 || parent >= scissorCount) throw new IllegalArgumentException("no scissor " + parent + " in " + this);
        if ((scissorCount + 1) * 4 > scissorRects.length) {
            scissorRects = Arrays.copyOf(scissorRects, scissorRects.length * 2);
            scissorBoxes = Arrays.copyOf(scissorBoxes, scissorRects.length);
        }
        if (scissorCount == scissorNodes.length) {
            scissorNodes = Arrays.copyOf(scissorNodes, scissorNodes.length * 2);
            scissorParents = Arrays.copyOf(scissorParents, scissorNodes.length);
        }
        scissorNodes[scissorCount] = node;
        scissorParents[scissorCount] = parent;
        return scissorCount++ * 4;
    }

    /** Binds {@code texture} at {@code unit} for every draw of this pass. A graph texture is read as of this call. */
    public CgRasterPass texture(int unit, CgTexture texture) {
        if (ended) throw new IllegalStateException(this + " has ended");
        int n = textureUnits.length;
        textureUnits = Arrays.copyOf(textureUnits, n + 1);
        textures = Arrays.copyOf(textures, n + 1);
        textureUnits[n] = unit;
        textures[n] = texture;
        CgGraphTexture graph = CgGraphTexture.sampled(texture);
        if (graph != null) recording.read(this, graph, CgAccess.SAMPLED_READ);
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
        recording.write(this, target, CgAccess.COLOR_WRITE);
    }

    /** The mip level of its target it draws into: 0 unless made with one. */
    public int level() {
        return level;
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

    /** The scissor scissor {@code i} was made inside, or -1. */
    int scissorParent(int i) {
        return scissorParents[i];
    }

    /** The boxes of node scissors, four floats each at the scissor's index: {@code x0, y0, x1, y1}. */
    float[] scissorBoxes() {
        return scissorBoxes;
    }
}
