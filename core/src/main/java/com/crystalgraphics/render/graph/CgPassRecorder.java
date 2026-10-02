package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkSink;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Turns the chunks renderers flush into raster passes of a recording, one target at a time: the set-target,
 * set-scissor and set-constants of a command buffer, for drawing code written against immediate renderers.
 *
 * <pre>{@code
 * CgPassRecorder recorder = new CgPassRecorder();
 * quadRenderer.sink(recorder);
 *
 * recorder.recordInto(frame, frameTarget, CgLoad.clear(0, 0, 0, 0), screen);   // the frame's passes start here
 * recorder.scissor(x, y, w, h);                                                // chunks after this are clipped
 * ...                                                                         // renderers draw as ever
 * recorder.view(window, layerX, layerY);                                       // the layer's place in root space
 * recorder.recordInto(frame, layer, CgLoad.clear(0, 0, 0, 0), layerOrtho);     // another target; the pass ends
 * recorder.view(0, 0f, 0f);
 * recorder.recordInto(frame, frameTarget, CgLoad.load(), screen);              // back, in a pass after the layer
 * recorder.stop();
 * CgImmediate.execute(frame);
 * }</pre>
 *
 * <p>Scissors nest as a chain, each cut by the ones before it; one in a spatial node's space moves with the node:</p>
 *
 * <pre>{@code
 * recorder.noScissor();
 * recorder.pushScissor(x, y, w, h);                       // the target's pixels
 * recorder.pushScissor(scroll, 0f, 0f, 300f, 200f);        // the scroll node's space, inside the first
 * recorder.popScissor();
 * }</pre>
 *
 * <ul>
 *   <li>A chunk takes the scissor, the constants and the view set when it is added; nothing is read from GL, so
 *       recording touches none.</li>
 *   <li>Its passes are {@link CgOrder#LOOKBACK}: a draw moves back past draws it does not overlap, so a chunk's draws
 *       need bounds, and one without covers everything. The renderers' runs bound theirs.
 *       {@code -Dcrystalgraphics.recorder.lookback=false} joins neighbours only.</li>
 *   <li>The first pass on a target takes the load given; a clearing load opens it at once, so a target nothing draws
 *       into is still cleared. Later passes on it load what the earlier ones drew.</li>
 *   <li>One recorder per recording thread; a recorder holds no GL and may live on any thread.</li>
 * </ul>
 */
public final class CgPassRecorder implements CgChunkSink {

    /** How deep a scissor chain goes. */
    public static final int MAX_SCISSORS = 16;

    /** {@code -Dcrystalgraphics.recorder.lookback=false}: passes join neighbouring draws only, as recorded. */
    private static final CgOrder ORDER =
            "false".equals(System.getProperty("crystalgraphics.recorder.lookback")) ? CgOrder.SORTED : CgOrder.LOOKBACK;

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
        endPass();
        stateChanges++;
        this.recording = recording;
        this.target = target;
        this.load = load;
        constants.write(pendingBlock, 0);
        if (load.mask() != 0) openPass();
    }

    /** The constants later chunks draw under; a change ends the open pass, since one pass is one block. */
    public void constants(CgPassConstants constants) {
        constants.write(scratchBlock, 0);
        if (Arrays.equals(scratchBlock, pendingBlock)) return;
        System.arraycopy(scratchBlock, 0, pendingBlock, 0, CgPassConstants.FLOATS);
        stateChanges++;
    }

    private final float[] scratchBlock = new float[CgPassConstants.FLOATS];

    /**
     * Where later passes' targets sit in the recording's root space: at {@code (x, y)}, moving with spatial node
     * {@code owner}. A change ends the open pass. @see CgRasterPass#view
     */
    public void view(int owner, float x, float y) {
        if (owner == pendingOwner && x == pendingX && y == pendingY) return;
        stateChanges++;
        pendingOwner = owner;
        pendingX = x;
        pendingY = y;
    }

    /** Draws the chunks added from now on inside {@code (x, y, w, h)}, in the target's bottom-left pixels. */
    public void scissor(int x, int y, int w, int h) {
        chain = 0;
        stateChanges++;
        pushScissor(x, y, w, h);
    }

    /** Draws the chunks added from now on unscissored. */
    public void noScissor() {
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
        chain--;
        stateChanges++;
    }

    private void push(int node, float a, float b, float c, float d) {
        if (chain == MAX_SCISSORS) throw new IllegalStateException("scissors nest " + MAX_SCISSORS + " deep at most");
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
        if (pass == null) return;
        stateChanges++;
        closePass();
    }

    private void closePass() {
        pass.end();
        pass = null;
    }

    /** Ends the open pass and records nowhere; a chunk added now is an error. Drops the chunks {@link #taken} holds. */
    public void stop() {
        endPass();
        recording = null;
        target = null;
        clearTape();
    }

    /** Records nowhere, without ending the open pass: for a recording its owner is discarding. */
    public void abandon() {
        pass = null;
        recording = null;
        target = null;
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
    @Override
    public CgBindingTable bindings() {
        if (recording == null) throw new IllegalStateException("drawing with nowhere to record");
        return recording.bindings();
    }

    @Override
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
        pass = recording.raster(target, load, passConstants.read(passBlock, 0), null, ORDER);
        viewOwner = pendingOwner;
        viewX = pendingX;
        viewY = pendingY;
        pass.view(viewOwner, viewX, viewY);
        issued = 0;
        load = CgLoad.load();
    }
}
