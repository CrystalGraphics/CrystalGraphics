package com.crystalgraphics.render.stage;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.texture.CgHostSamplers;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;

/**
 * One firing of a {@link CgRenderStage}: the recording every {@link CgStageRenderer} of it records into, on the host's
 * target, built and executed once they all have.
 *
 * <pre>{@code
 * // Under the host's camera
 * CgRasterPass pass = frame.pass(frame.constants(), CgOrder.SORTED);
 * // ... chunks ...
 * pass.end();
 *
 * // Under a camera of your own
 * CgPassConstants camera = frame.defaults(myConstants);   // time, size, depth convention
 * camera.view.set(viewMatrix);
 * camera.projection.set(projectionMatrix);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, and only inside {@link CgStageRenderer#render}: the recording is executed and reset after.</li>
 *   <li>The stage parks the host's sampler objects and turns its scissor off around execution: what the host leaves
 *       there overrides a texture's own filtering, and clips every draw to its box.</li>
 *   <li>{@link #callback} draws immediately at its place in the stage, with GL state restored after — for work that
 *       is not recorded yet.</li>
 * </ul>
 */
public final class CgStageFrame {

    private final CgRecording recording = new CgRecording();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgFrameResources resources = new CgFrameResources();
    private final CgRenderStage stage;
    private CgHostFrame host;
    private final CgPassConstants hostConstants = new CgPassConstants();
    private CgGraphTexture pyramid;
    private boolean pyramidBuilt;

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

    /**
     * This firing's blackboard: what one renderer publishes for another recording later in the same firing, by
     * {@link CgFrameKey}. Empty at the start of every firing.
     */
    public CgFrameResources resources() {
        return resources;
    }

    /** The host's target, as the recording names it: whatever the host has bound when the stage executes. */
    public CgGraphTexture target() {
        return CgGraphTexture.current();
    }

    /**
     * Fills {@code constants} with the frame's time, the host target's size and depth convention, and the world's sun
     * and fog ({@link CgWorldAtmosphere}); the camera is the caller's.
     */
    public CgPassConstants defaults(CgPassConstants constants) {
        constants.time(CgFrameClock.seconds())
                .resolution(host.width(), host.height())
                .depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne());
        return CgWorldAtmosphere.apply(host.environment(), constants);
    }

    /**
     * The host's camera as pass constants, camera-relative as the host draws its world: its view and projection, the
     * eye at its view's origin, {@code cg_WorldOrigin} at its absolute position, and {@link #defaults}. One instance,
     * refilled per call; {@link CgRecording#raster} copies it.
     */
    public CgPassConstants constants() {
        CgHostView view = host.view();
        defaults(hostConstants);
        hostConstants.view.set(view.view());
        hostConstants.projection.set(view.projection());
        return hostConstants.cameraFromView().origin(view.x(), view.y(), view.z());
    }

    /** A raster pass onto the host's target under {@code constants}, loading what is there. */
    public CgRasterPass pass(CgPassConstants constants, CgOrder order) {
        return recording.raster(target(), CgLoad.load(), constants, null, order);
    }

    /**
     * The depth pyramid of the host's target as recorded so far, built at the first ask of a firing and shared by every
     * renderer that asks after ({@link CgGpuOps#depthPyramid}: each level the farthest eye depth it covers). What a GPU
     * cull tests occlusion against.
     *
     * <pre>{@code
     * cull.view(view.view(), view.projection()).pyramid(frame.depthPyramid());
     * }</pre>
     *
     * <ul>
     *   <li>Holds the depth of what was recorded before the first ask: asked ahead of a renderer's own draws, it hides
     *       nothing behind them. At {@code WORLD_OPAQUE}, everything the host drew before firing it.</li>
     * </ul>
     */
    public CgGraphTexture depthPyramid() {
        if (!pyramidBuilt) {
            int w = (int) host.width(), h = (int) host.height();
            if (pyramid == null || pyramid.getWidth() != w || pyramid.getHeight() != h) {
                pyramid = CgGraphTexture.transientTexture("stage.pyramid", new CgTextureDesc(w, h, CgGpuOps.PYRAMID_FORMAT).withMips());
            }
            CgGpuOps.depthPyramid(recording, target(), constants(), pyramid);
            pyramidBuilt = true;
        }
        return pyramid;
    }

    /** Runs {@code body} at this point in the stage, on the host's target, with GL state restored after. */
    public CgRequest callback(String name, Runnable body) {
        return recording.callback(name, target(), body);
    }

    void begin(CgHostFrame host) {
        this.host = host;
        recording.reset();
        pyramidBuilt = false;
    }

    /** Builds and executes what was recorded, then resets for the next firing. */
    void execute() {
        try {
            graph.add(recording.seal());
            CgFrame frame = builder.build(graph);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "stage.parkSamplers")) {
                CgHostSamplers.park();
            }
            try (CgGlScope ignored = CgGlState.save(CgGlSlot.SCISSOR)) {
                CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
                CgExecutor.execute(frame);
            } finally {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "stage.unparkSamplers")) {
                    CgHostSamplers.unpark();
                }
                builder.recycle(frame);
            }
        } finally {
            graph.clear();
            recording.reset();
            // A transient published here dies with this firing's graph: the next firing must not see it.
            resources.clear();
        }
    }
}
