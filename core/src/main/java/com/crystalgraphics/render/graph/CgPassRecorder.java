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
 * recorder.recordInto(frame, layer, CgLoad.clear(0, 0, 0, 0), layerOrtho);     // another target; the pass ends
 * recorder.recordInto(frame, frameTarget, CgLoad.load(), screen);              // back, in a pass after the layer
 * recorder.stop();
 * CgImmediate.execute(frame);
 * }</pre>
 *
 * <ul>
 *   <li>A chunk takes the scissor and constants set when it is added; nothing is read from GL, so recording
 *       touches none.</li>
 *   <li>The first pass on a target takes the load given; a clearing load opens it at once, so a target nothing draws
 *       into is still cleared. Later passes on it load what the earlier ones drew.</li>
 *   <li>One recorder per recording thread; a recorder holds no GL and may live on any thread.</li>
 * </ul>
 */
public final class CgPassRecorder implements CgChunkSink {

    private final float[] passBlock = new float[CgPassConstants.FLOATS];
    private final float[] pendingBlock = new float[CgPassConstants.FLOATS];
    private final CgPassConstants passConstants = new CgPassConstants();
    private final int[] scissorRect = new int[4];
    private boolean scissored;
    @Nullable
    private CgRecording recording;
    @Nullable
    private CgGraphTexture target;
    private CgLoad load = CgLoad.load();
    @Nullable
    private CgRasterPass pass;

    /**
     * Records later chunks into raster passes on {@code target} in {@code recording}, under {@code constants}, and
     * ends the pass that was open.
     */
    public void recordInto(CgRecording recording, CgGraphTexture target, CgLoad load, CgPassConstants constants) {
        endPass();
        this.recording = recording;
        this.target = target;
        this.load = load;
        constants.write(pendingBlock, 0);
        if (load.mask() != 0) openPass();
    }

    /** The constants later chunks draw under; a change ends the open pass, since one pass is one block. */
    public void constants(CgPassConstants constants) {
        constants.write(pendingBlock, 0);
    }

    /** Draws the chunks added from now on inside {@code (x, y, w, h)}, in the target's bottom-left pixels. */
    public void scissor(int x, int y, int w, int h) {
        scissored = true;
        scissorRect[0] = x;
        scissorRect[1] = y;
        scissorRect[2] = w;
        scissorRect[3] = h;
    }

    /** Draws the chunks added from now on unscissored. */
    public void noScissor() {
        scissored = false;
    }

    /** Ends the open pass: the next chunk opens another on the same target, loading what this one drew. */
    public void endPass() {
        if (pass == null) return;
        pass.end();
        pass = null;
    }

    /** Ends the open pass and records nowhere; a chunk added now is an error. */
    public void stop() {
        endPass();
        recording = null;
        target = null;
    }

    /** Records nowhere, without ending the open pass: for a recording its owner is discarding. */
    public void abandon() {
        pass = null;
        recording = null;
        target = null;
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
        if (recording == null) throw new IllegalStateException("a chunk with nowhere to record it");
        if (pass != null && !Arrays.equals(passBlock, pendingBlock)) endPass();
        if (pass == null) openPass();
        if (scissored) pass.scissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3]);
        else pass.noScissor();
        pass.add(chunk);
    }

    private void openPass() {
        System.arraycopy(pendingBlock, 0, passBlock, 0, CgPassConstants.FLOATS);
        pass = recording.raster(target, load, passConstants.read(passBlock, 0), null, CgOrder.SORTED);
        load = CgLoad.load();
    }
}
