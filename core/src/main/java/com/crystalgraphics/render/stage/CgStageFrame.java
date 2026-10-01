package com.crystalgraphics.render.stage;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;

/**
 * One firing of a {@link CgRenderStage}: the recording every {@link CgStageRenderer} of it records into, on the host's
 * target, built and executed once they all have.
 *
 * <pre>{@code
 * CgPassConstants camera = frame.defaults(new CgPassConstants());   // time, size, depth convention
 * camera.view.set(viewMatrix);
 * camera.projection.set(projectionMatrix);
 * CgRasterPass pass = frame.pass(camera, CgOrder.SORTED);
 * // ... chunks ...
 * pass.end();
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, and only inside {@link CgStageRenderer#render}: the recording is executed and reset after.</li>
 *   <li>{@link #callback} draws immediately at its place in the stage, with GL state restored after — for work that
 *       is not recorded yet.</li>
 * </ul>
 */
public final class CgStageFrame {

    private final CgRecording recording = new CgRecording();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRenderStage stage;
    private CgHostFrame host;

    CgStageFrame(CgRenderStage stage) {
        this.stage = stage;
    }

    /** The stage firing. */
    public CgRenderStage stage() {
        return stage;
    }

    /** What the host said about its frame. */
    public CgHostFrame host() {
        return host;
    }

    /** What the stage's renderers record into. */
    public CgRecording recording() {
        return recording;
    }

    /** The host's target, as the recording names it: whatever the host has bound when the stage executes. */
    public CgGraphTexture target() {
        return CgGraphTexture.current();
    }

    /** Fills {@code constants} with the frame's time, the host target's size and depth convention; the camera is the caller's. */
    public CgPassConstants defaults(CgPassConstants constants) {
        return constants.time(CgFrameClock.seconds())
                .resolution(host.width(), host.height())
                .depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne());
    }

    /** A raster pass onto the host's target under {@code constants}, loading what is there. */
    public CgRasterPass pass(CgPassConstants constants, CgOrder order) {
        return recording.raster(target(), CgLoad.load(), constants, null, order);
    }

    /** Runs {@code body} at this point in the stage, on the host's target, with GL state restored after. */
    public CgRequest callback(String name, Runnable body) {
        return recording.callback(name, target(), body);
    }

    void begin(CgHostFrame host) {
        this.host = host;
        recording.reset();
    }

    /** Builds and executes what was recorded, then resets for the next firing. */
    void execute() {
        try {
            graph.add(recording.seal());
            CgFrame frame = builder.build(graph);
            try {
                CgExecutor.execute(frame);
            } finally {
                builder.recycle(frame);
            }
        } finally {
            graph.clear();
            recording.reset();
        }
    }
}
