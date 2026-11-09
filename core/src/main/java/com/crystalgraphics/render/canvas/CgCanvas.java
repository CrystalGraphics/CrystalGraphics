package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.CgViewFrustum;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.text.render.CgTextBatch;
import com.crystalgraphics.text.render.CgTextDraw;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What 2D draws go into -- quads, curves, cells, triangles, text -- in the world or on the screen (Unreal's
 * {@code FCanvas} over its render graph). Guide: {@code docs/CANVAS.md}. Each item names its material and textures; consecutive items that match are
 * one draw, and items keep the order they were submitted in. A canvas records into raster passes of a recording, one
 * target at a time, or, recording nothing, draws at {@link #flush()}.
 *
 * <p>At a render stage the firing gives one: drawn into during a renderer's turn, it lands in that firing under its
 * camera -- a world stage's camera-relative space, a GUI stage's pixels -- with nothing to begin, end or flush.</p>
 *
 * <pre>{@code
 * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> {
 *     CgCanvas canvas = frame.canvas();
 *     canvas.quad(DIGIT).texture(0, DIGITS).at(x, y).size(w, h).uv(u0, v0, u1, v1).color(argb).submit();
 *     canvas.curve(LINE).line(x0, y0, x1, y1).width(2f).color(argb).submit();
 *     canvas.text().text("12").font(font).at(x, y).stroke(0.1f, 0xFF000000).submit();
 * });
 *
 * CgRenderStage.WORLD_TRANSPARENT.register(0, frame -> {
 *     CgCanvas canvas = frame.canvas();
 *     canvas.quad(GLOW).world(x, y, z).pose(facingCamera).at(-0.5f, -0.5f).size(1f, 1f).submit();   // absolute
 * });
 * }</pre>
 *
 * <p>A canvas of your own records where you say: the set-target, set-scissor and set-constants of a command buffer.</p>
 *
 * <pre>{@code
 * CgCanvas canvas = new CgCanvas();
 * canvas.recordInto(frame, frameTarget, CgLoad.clear(0, 0, 0, 0), screen);   // the frame's passes start here
 * canvas.scissor(x, y, w, h);                                                // what draws after this is clipped
 * canvas.quad(box).at(x, y).size(w, h).submit();
 * canvas.view(window, layerX, layerY);                                       // the layer's place in root space
 * canvas.recordInto(frame, layer, CgLoad.clear(0, 0, 0, 0), layerOrtho);     // another target; the pass ends
 * canvas.view(0, 0f, 0f);
 * canvas.recordInto(frame, frameTarget, CgLoad.load(), screen);              // back, in a pass after the layer
 * canvas.stop();                                                             // everything drawn lands
 * CgImmediate.execute(frame);
 *
 * CgCanvas now = new CgCanvas();                  // recording nothing: a preview, a harness scene
 * now.quad(box).at(x, y).size(w, h).submit();
 * now.flush();                                    // drawn into the bound target under CgImmediate.constants()
 * }</pre>
 *
 * <p>Scissors nest as a chain, each cut by the ones before it; one in a spatial node's space moves with the node:</p>
 *
 * <pre>{@code
 * canvas.noScissor();
 * canvas.pushScissor(x, y, w, h);                       // the target's pixels
 * canvas.pushScissor(scroll, 0f, 0f, 300f, 200f);        // the scroll node's space, inside the first
 * canvas.popScissor();
 * }</pre>
 *
 * <ul>
 *   <li>An item draws under its material as it is when the item is submitted: a value set between two items applies
 *       from the second. {@code #pragma cg_use quad} (or {@code curve}) in the material's shader declares the record.</li>
 *   <li>Every state call -- target, constants, view, scissor -- lands what was drawn before it under the old state.</li>
 *   <li>A chunk takes the scissor, the constants and the view set when it lands; nothing is read from GL.</li>
 *   <li>Its passes are {@link CgOrder#LOOKBACK}: a draw moves back past draws it does not overlap, by the bounds each
 *       item gives its draw.</li>
 *   <li>The first pass on a target takes the load given; a clearing load opens it at once, so a target nothing draws
 *       into is still cleared. Later passes on it load what the earlier ones drew.</li>
 *   <li>One thread at a time; it holds no GPU object and may live on any thread.</li>
 * </ul>
 */
public final class CgCanvas {

    /** How deep a scissor chain goes. */
    public static final int MAX_SCISSORS = 16;

    /** Opens an {@linkplain #arm armed} canvas at its first draw: where, and under what, it records. */
    public interface Source {
        /** Calls {@code canvas.recordInto(...)}. */
        void open(CgCanvas canvas);

        /** The constants it will record under, read before it opens (text culls and sizes by them). */
        CgPassConstants constants();
    }

    /** A batcher's draws not yet landed. */
    interface Drawing {
        /** Lands them now. */
        void seal();

        /** Forgets them: their recording is being discarded. */
        void drop();
    }

    @Nullable
    private Source source;
    @Nullable
    private Drawing active;
    private double originX, originY, originZ;

    private final CgQuadBatch quads = new CgQuadBatch(this);
    private final CgCurveBatch curves = new CgCurveBatch(this);
    @Nullable
    private CgTextBatch text;

    /** The camera later items draw under, as last recorded into or set. @see #cameraProjection */
    private final Matrix4f cameraProjection = new Matrix4f(), cameraView = new Matrix4f();
    private float cameraWidth = 1f, cameraHeight = 1f;
    private final CgViewFrustum frustum = new CgViewFrustum();
    private final Matrix4f localToClip = new Matrix4f();

    // ── Drawing ─────────────────────────────────────────────────────────

    /**
     * A quad under {@code material}: the canvas's one scratch builder, to build and {@link CgQuadBatch.Quad#submit()}
     * in one expression.
     */
    public CgQuadBatch.Quad quad(CgMaterial material) {
        return quads.quad(material);
    }

    /** A quad builder of the caller's own, held across frames: {@link CgQuadBatch.Quad#material} before each submit. */
    public CgQuadBatch.Quad retainedQuad() {
        return quads.retainedQuad();
    }

    /**
     * {@code count} quad records already in {@link CgQuadRecord}'s layout, from record {@code from} of {@code src}, in
     * spatial node {@code spatial}, {@code texture} at unit 0 where not null: for code that bakes its own, as text does,
     * so a run of them is one copy. {@code origin}, {@code right} and {@code up} are final.
     */
    public CgCanvas quads(CgMaterial material, @Nullable CgRenderState state, @Nullable CgTexture texture, float[] src,
                          int from, int count, int spatial) {
        quads.records(material, state, texture, src, from, count, spatial);
        return this;
    }

    /**
     * A text item: the canvas's one scratch draw, to build and {@link CgTextDraw#submit()} in one expression.
     *
     * <pre>{@code
     * canvas.text().text("Ready").font(font).at(x, y).color(0xFFFFFFFF).submit();
     * }</pre>
     */
    public CgTextDraw text() {
        return textBatch().draw();
    }

    /** A text draw of the caller's own on this canvas, held across frames: change what changed, submit each frame. */
    public CgTextDraw retainedText() {
        return textBatch().retained();
    }

    /** Draws {@code draw}, made anywhere, on this canvas. @return whether everything drew as asked */
    public boolean text(CgTextDraw draw) {
        return textBatch().draw(draw);
    }

    /**
     * How many text draws on this canvas resolved a glyph or shadow below what they asked for, still generating.
     * Rises only: a damage-driven painter compares it around its paint and paints again when it moved.
     */
    public long textDegradedDraws() {
        return text == null ? 0 : text.degradedDraws();
    }

    private CgTextBatch textBatch() {
        CgTextBatch t = text;
        if (t == null) text = t = new CgTextBatch(this);
        return t;
    }

    /** A Bézier stroke or a line under {@code material}: the canvas's one scratch builder. */
    public CgCurveBatch.Curve curve(CgMaterial material) {
        return curves.curve(material);
    }

    /** A filled triangle under {@code material}: the canvas's one scratch builder. */
    public CgCurveBatch.Triangle triangle(CgMaterial material) {
        return curves.triangle(material);
    }

    /** A filled scanline cell under {@code material}: the canvas's one scratch builder. */
    public CgCurveBatch.Cell cell(CgMaterial material) {
        return curves.cell(material);
    }

    /** Builders of the caller's own, held across frames. */
    public CgCurveBatch.Curve retainedCurve() {
        return curves.retainedCurve();
    }

    public CgCurveBatch.Triangle retainedTriangle() {
        return curves.retainedTriangle();
    }

    public CgCurveBatch.Cell retainedCell() {
        return curves.retainedCell();
    }

    /**
     * Lands everything drawn now: into the passes while it records, else drawn into the bound target through
     * {@code CgImmediate} under {@code CgImmediate.constants()}. Recording, only to bracket a stretch exactly
     * ({@link #chunksTaken}); state calls and {@link #stop} land what is drawn anyway.
     */
    public void flush() {
        sealActive();
    }

    /** Whether anything drawn waits to land. */
    public boolean pending() {
        return quads.pending() || curves.pending();
    }

    // ── Camera ──────────────────────────────────────────────────────────

    /**
     * The projection later items draw under: the pass's while recording, an armed stage's before it opens, else
     * {@code CgImmediate.constants()}'s. Read-only.
     */
    public Matrix4fc cameraProjection() {
        refreshCamera();
        return cameraProjection;
    }

    /** The view later items draw under. @see #cameraProjection */
    public Matrix4fc cameraView() {
        refreshCamera();
        return cameraView;
    }

    /** The target's width in pixels later items draw at. @see #cameraProjection */
    public float cameraWidth() {
        refreshCamera();
        return cameraWidth;
    }

    /** The target's height in pixels later items draw at. @see #cameraProjection */
    public float cameraHeight() {
        refreshCamera();
        return cameraHeight;
    }

    /**
     * Whether the rect {@code (x0, y0)-(x1, y1)} in {@code pose}'s space can reach the target: false only when it lies
     * wholly outside the camera's view. For an item kind that skips its work for what is off screen, as text resolves
     * no glyph for it. A rect in a spatial node's space cannot be tested: the node moves it after recording.
     *
     * <pre>{@code
     * if (!canvas.visible(pose, x - slack, y - slack, x + w + slack, y + h + slack)) return;
     * }</pre>
     */
    public boolean visible(@Nullable Matrix4fc pose, float x0, float y0, float x1, float y1) {
        refreshCamera();
        localToClip.set(cameraProjection).mul(cameraView);
        if (pose != null) localToClip.mul(pose);
        return frustum.set(localToClip).testRect(x0, y0, x1, y1);
    }

    private void refreshCamera() {
        if (recording != null) return;
        takeCamera(source != null ? source.constants() : CgImmediate.constants());
    }

    private void takeCamera(CgPassConstants constants) {
        cameraProjection.set(constants.projection);
        cameraView.set(constants.view);
        cameraWidth = Math.max(1f, constants.width());
        cameraHeight = Math.max(1f, constants.height());
    }

    // ── World space ─────────────────────────────────────────────────────

    /**
     * Where its space's origin is, absolute: a world stage's camera, so {@code world(x, y, z)} places an item at an
     * absolute position in doubles as {@code CgWorldRenderer} does. 0 for a screen.
     */
    public CgCanvas origin(double x, double y, double z) {
        originX = x;
        originY = y;
        originZ = z;
        return this;
    }

    double originX() {
        return originX;
    }

    double originY() {
        return originY;
    }

    double originZ() {
        return originZ;
    }

    // ── Recording ───────────────────────────────────────────────────────

    /**
     * Arms the canvas with nowhere chosen yet: the first draw has {@code source} choose ({@link Source#open}), so a
     * stretch that draws nothing records nothing. Ended by {@link #stop}.
     */
    public void arm(Source source) {
        sealActive();
        this.source = source;
    }

    /** {@code drawing} draws next: what another batcher drew is landed first, and an armed canvas opens. */
    void claim(Drawing drawing) {
        if (active == drawing) return;
        sealActive();
        if (recording == null && source != null) {
            Source s = source;
            source = null;
            s.open(this);
        }
        active = drawing;
    }

    private void sealActive() {
        Drawing a = active;
        if (a == null) return;
        active = null;
        a.seal();
    }

    private final float[] passBlock = new float[CgPassConstants.FLOATS];
    private final float[] pendingBlock = new float[CgPassConstants.FLOATS];
    private final CgPassConstants passConstants = new CgPassConstants();
    @Nullable
    private CgRecording recording;
    @Nullable
    private CgGraphTexture target;
    private CgLoad load = CgLoad.load();
    @Nullable
    private CgRasterPass pass;

    /** The scissor chain: per entry its node, -1 for a rect in target pixels, and its four values. */
    private int chain;
    /** Chunks taken so far. @see #chunksTaken */
    private long taken;
    /** The chunks taken since the last {@link #stop}: the one taken at reading {@code n} is at {@code n - tapeFrom}. */
    private CgDrawChunk[] tape = new CgDrawChunk[256];
    private long tapeFrom;
    /** Bumped by every call that makes later chunks land under other state. @see #stateChanges */
    private long stateChanges;
    private final int[] chainNodes = new int[MAX_SCISSORS];
    private final float[] chainValues = new float[MAX_SCISSORS * 4];
    /** Per entry, its index in the open pass: valid for the first {@link #issued}. */
    private final int[] chainIndex = new int[MAX_SCISSORS];
    private int issued;

    private int viewOwner, pendingOwner;
    private float viewX, viewY, pendingX, pendingY;

    /**
     * Records later chunks into raster passes on {@code target} in {@code recording}, under {@code constants}, and
     * ends the pass that was open.
     */
    public void recordInto(CgRecording recording, CgGraphTexture target, CgLoad load, CgPassConstants constants) {
        sealActive();
        source = null;
        endPass();
        stateChanges++;
        this.recording = recording;
        this.target = target;
        this.load = load;
        constants.write(pendingBlock, 0);
        takeCamera(constants);
        if (load.mask() != 0) openPass();
    }

    /** The constants later chunks draw under; a change ends the open pass, since one pass is one block. */
    public void constants(CgPassConstants constants) {
        constants.write(scratchBlock, 0);
        if (Arrays.equals(scratchBlock, pendingBlock)) return;
        sealActive();
        System.arraycopy(scratchBlock, 0, pendingBlock, 0, CgPassConstants.FLOATS);
        takeCamera(constants);
        stateChanges++;
    }

    private final float[] scratchBlock = new float[CgPassConstants.FLOATS];

    /**
     * Where later passes' targets sit in the recording's root space: at {@code (x, y)}, moving with spatial node
     * {@code owner}. A change ends the open pass. @see CgRasterPass#view
     */
    public void view(int owner, float x, float y) {
        if (owner == pendingOwner && x == pendingX && y == pendingY) return;
        sealActive();
        stateChanges++;
        pendingOwner = owner;
        pendingX = x;
        pendingY = y;
    }

    /** Draws the chunks added from now on inside {@code (x, y, w, h)}, in the target's bottom-left pixels. */
    public void scissor(int x, int y, int w, int h) {
        sealActive();
        chain = 0;
        stateChanges++;
        pushScissor(x, y, w, h);
    }

    /** Draws the chunks added from now on unscissored. */
    public void noScissor() {
        sealActive();
        if (chain != 0) stateChanges++;
        chain = 0;
    }

    /** Cuts the scissor by {@code (x, y, w, h)} in the target's bottom-left pixels. */
    public void pushScissor(int x, int y, int w, int h) {
        push(-1, x, y, w, h);
    }

    /** Cuts the scissor by {@code (x0, y0)-(x1, y1)} in spatial node {@code node}'s space, top-down. */
    public void pushScissor(int node, float x0, float y0, float x1, float y1) {
        push(node, x0, y0, x1, y1);
    }

    /** Takes back the last {@link #pushScissor}. */
    public void popScissor() {
        if (chain == 0) throw new IllegalStateException("no scissor to pop");
        sealActive();
        chain--;
        stateChanges++;
    }

    private void push(int node, float a, float b, float c, float d) {
        if (chain == MAX_SCISSORS) throw new IllegalStateException("scissors nest " + MAX_SCISSORS + " deep at most");
        sealActive();
        stateChanges++;
        int at = chain * 4;
        if (chainNodes[chain] != node || chainValues[at] != a || chainValues[at + 1] != b || chainValues[at + 2] != c
                || chainValues[at + 3] != d) {
            chainNodes[chain] = node;
            chainValues[at] = a;
            chainValues[at + 1] = b;
            chainValues[at + 2] = c;
            chainValues[at + 3] = d;
            issued = Math.min(issued, chain);
        }
        chain++;
    }

    /** Ends the open pass: the next chunk opens another on the same target, loading what this one drew. */
    public void endPass() {
        sealActive();
        if (pass == null) return;
        stateChanges++;
        closePass();
    }

    private void closePass() {
        pass.end();
        pass = null;
    }

    /**
     * Lands everything drawn, ends the open pass and records nowhere: what is drawn after it draws at {@link #flush()}.
     * Drops the chunks {@link #taken} holds.
     */
    public void stop() {
        endPass();
        recording = null;
        target = null;
        source = null;
        clearTape();
    }

    /** Records nowhere, without ending the open pass or landing what is drawn: for a recording its owner is discarding. */
    public void abandon() {
        if (active != null) active.drop();
        active = null;
        pass = null;
        recording = null;
        target = null;
        source = null;
        clearTape();
    }

    private void clearTape() {
        stateChanges++;
        Arrays.fill(tape, 0, (int) (taken - tapeFrom), null);
        tapeFrom = taken;
    }

    /**
     * The chunk taken at {@link #chunksTaken} reading {@code at}, since the last {@link #stop}; null before it. What
     * lets a stretch between two readings be kept. @see CgReplay
     */
    @Nullable
    public CgDrawChunk taken(long at) {
        return at >= tapeFrom && at < taken ? tape[(int) (at - tapeFrom)] : null;
    }

    /**
     * Counts every call that makes later chunks land under other state: a target, constants or view set, a pass ended,
     * a scissor changed. Two equal readings mean every chunk between them went into the pass and scissor that were
     * current at the first, as a chunk added again in their place would.
     */
    public long stateChanges() {
        return stateChanges;
    }

    /** How many chunks this recorder has taken: two readings bracket what a stretch of drawing recorded. */
    public long chunksTaken() {
        return taken;
    }

    /** Whether chunks are being recorded. */
    public boolean recording() {
        return recording != null;
    }

    /** The recording's table: a chunk's snapshots live as long as the recording that holds it. */
    public CgBindingTable bindings() {
        if (recording == null) throw new IllegalStateException("drawing with nowhere to record");
        return recording.bindings();
    }

    /** Takes {@code chunk}, sealed, into the open pass under the scissor and view set now. */
    public void add(CgDrawChunk chunk) {
        if (chunk.draws() == 0) return;
        taken++;
        if (recording == null) throw new IllegalStateException("a chunk with nowhere to record it");
        if (pass != null && (!Arrays.equals(passBlock, pendingBlock)
                || viewOwner != pendingOwner || viewX != pendingX || viewY != pendingY)) closePass();
        if (pass == null) openPass();
        if (chain == 0) {
            pass.noScissor();
        } else {
            for (int d = issued; d < chain; d++) {
                int parent = d == 0 ? -1 : chainIndex[d - 1], at = d * 4;
                int node = chainNodes[d];
                chainIndex[d] = node < 0
                        ? pass.scissor(parent, (int) chainValues[at], (int) chainValues[at + 1], (int) chainValues[at + 2],
                                (int) chainValues[at + 3])
                        : pass.scissor(parent, node, chainValues[at], chainValues[at + 1], chainValues[at + 2],
                                chainValues[at + 3]);
            }
            issued = Math.max(issued, chain);
            pass.useScissor(chainIndex[chain - 1]);
        }
        pass.add(chunk);
        int at = (int) (taken - 1 - tapeFrom);
        if (at == tape.length) tape = Arrays.copyOf(tape, at * 2);
        tape[at] = chunk;
    }

    private void openPass() {
        System.arraycopy(pendingBlock, 0, passBlock, 0, CgPassConstants.FLOATS);
        pass = recording.raster(target, load, passConstants.read(passBlock, 0), null, CgOrder.LOOKBACK);
        viewOwner = pendingOwner;
        viewX = pendingX;
        viewY = pendingY;
        pass.view(viewOwner, viewX, viewY);
        issued = 0;
        load = CgLoad.load();
    }
}
