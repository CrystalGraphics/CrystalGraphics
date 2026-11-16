package com.crystalgraphics.render.stage;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewRenderTarget;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;

/**
 * Engine side: one firing of a host's callback or a GUI stage, as the hosts and the scene renderer drive it. It begins
 * on a view (a world callback) or once a frame (a GUI stage), lets stages record into its {@link CgStageFrame} in the
 * order its driver runs them, and is built and executed once. What a renderer sees of it is the frame alone.
 *
 * <pre>{@code
 * firing.beginWorld(host, true);                 // the opaque callback: it opens the view's draw
 * firing.run(CgRenderStage.PREPARE);
 * // ... the renderer's own passes into firing.recording() ...
 * firing.run(CgRenderStage.OPAQUE);
 * firing.execute();
 * }</pre>
 *
 * <p>A view drawn below 100% ({@code view.screenPercentage}) draws under a host frame of its own: a target of the size
 * its world draws at standing in for the host's ({@link #host()}, {@link #scaled()}), which the caller makes the
 * execution's current target, and the host's own as {@link #output()}, which the post chain {@link #present presents}
 * into.</p>
 */
public final class CgStageFiring {

    private static final int PYRAMIDS = CgTrace.name("stage.pyramids-built"), PYRAMID_ASKS = CgTrace.name("stage.pyramid-asks");

    /** A view's pyramid, the frame it was built in and the host target it was built from: one a view a frame. */
    private static final class Pyramid {
        CgGraphTexture texture;
        CgExternalTarget host;
        long frame = Long.MIN_VALUE;
    }

    private static final CgViewState.Key<Pyramid> PYRAMID = new CgViewState.Key<>("stage.pyramid", Pyramid::new);
    /** The frame a firing of this kind last drew each view in: a second of the same frame is nobody's view. */
    private final CgViewState.Key<long[]> drawn;

    private final String name;
    private final CgStageFrame frame = new CgStageFrame(this);
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private CgTextureDesc pyramidDesc;
    private boolean pyramidBuilt;

    public CgStageFiring(String name) {
        this.name = name;
        this.drawn = new CgViewState.Key<>(name + ".drawn", () -> new long[]{Long.MIN_VALUE});
    }

    /** Engine side: a host's world callback, which nothing registers on and the scene renderer runs inside. */
    public static CgRenderStage hostStage(String id) {
        return CgRenderStage.define(id, CgRenderStage.Kind.HOST);
    }

    /** Engine side: a mod's own stage, fired from its own hook. */
    public static CgRenderStage customStage(String id) {
        return CgRenderStage.define(id, CgRenderStage.Kind.CUSTOM);
    }

    /** The frame its stages record into. */
    public CgStageFrame frame() {
        return frame;
    }

    public CgRecording recording() {
        return frame.recording;
    }

    /**
     * What the host said about the frame this firing draws: for a view drawn below 100%, the host frame of the target
     * standing in for the host's, whose target is the execution's current one.
     */
    public CgHostFrame host() {
        return frame.host;
    }

    /** Whether its view draws below 100%, into a target standing in for the host's. */
    public boolean scaled() {
        return frame.output != null;
    }

    /** The host's target, which the view's picture ends in: {@code CgGraphTexture.current()} unless it is scaled. */
    public CgGraphTexture output() {
        return frame.output != null ? frame.output : CgGraphTexture.current();
    }

    /** Whether the view's picture has been upscaled into {@link #output()}. */
    public boolean presented() {
        return frame.presented;
    }

    /**
     * The post chain's: the view's picture is upscaled into {@link #output()} from here, so what records after (the
     * rest of the chain where a temporal upscaler presented, {@code OVERLAY}) draws there at the output's size.
     */
    public void present() {
        if (frame.output == null) throw new IllegalStateException(frame.view + " draws at 100%: it has nothing to present");
        frame.presented = true;
        frame.view().camera().resolution(frame.view().outputWidth(), frame.view().outputHeight());
    }

    /** The view a world firing draws. */
    public CgView view() {
        return frame.view();
    }

    /**
     * A world callback begins for the host's view: the view takes the host's camera, size, environment and textures and
     * begins its frame, and its draw when the callback {@code opens} one (the opaque callback). A second firing of this
     * callback in one frame for the same view draws as {@link CgView#unkept()}.
     */
    public void beginWorld(CgHostFrame host, boolean opens) {
        long now = CgFrameRing.frame();
        CgView named = host.view();
        long[] last = named.state().get(drawn);
        CgView view = last[0] == now ? CgView.unkeptFor(named) : named;
        last[0] = now;
        view.size(host.width(), host.height(), !host.drawsWorld());
        view.camera().set(host.camera());
        view.environment().set(host.environment());
        view.hostTextures().set(host.textures());
        view.beginFrame(now, host.target());
        if (opens) view.beginDraw();
        begin(host, view, opens);
    }

    /**
     * A view of the engine's own begins a whole draw (a viewport, a capture): its camera is its owner's, its output the
     * host's target.
     */
    public void beginView(CgHostFrame host, CgView view) {
        view.size(host.width(), host.height(), !host.drawsWorld());
        view.beginFrame(CgFrameRing.frame(), host.target());
        view.beginDraw();
        begin(host, view, true);
    }

    /**
     * A world firing begins on {@code view}: under the host's frame, or for a view drawn below 100% under its target's
     * of that size, the draw's opening ({@code opens}) filling it with the host's picture so far.
     */
    private void begin(CgHostFrame host, CgView view, boolean opens) {
        if (!view.scaled()) {
            begin(host, view);
            return;
        }
        CgViewRenderTarget small = view.renderTarget();
        begin(small.host(host, view.width(), view.height()), view);
        frame.output = small.output(host.target());
        if (opens) small.fill(frame.recording, constants(), frame.output);
    }

    /** A GUI or custom stage's firing begins: once a frame, no view. */
    public void beginOnce(CgHostFrame host) {
        begin(host, null);
    }

    private void begin(CgHostFrame host, @Nullable CgView view) {
        frame.host = host;
        frame.view = view;
        frame.stage = null;
        frame.target = null;
        frame.depth = null;
        frame.output = null;
        frame.presented = false;
        frame.recording.reset();
        pyramidBuilt = false;
    }

    /** Lets {@code stage}'s renderers record into the firing at this point. */
    public void run(CgRenderStage stage) {
        stage.record(frame);
        frame.stage = null;
    }

    /**
     * Makes the frame's {@code target()} answer {@code target} and {@code depth()} {@code depth} for what records after,
     * or the host's target again for null: the HDR scene going in and out.
     */
    public void retarget(@Nullable CgGraphTexture target, @Nullable CgGraphTexture depth) {
        frame.target = target;
        frame.depth = target == null ? null : depth;
    }

    /** What the firing draws onto now. */
    public CgGraphTexture target() {
        return frame.target();
    }

    public CgGraphTexture depth() {
        return frame.depth();
    }

    /** The frame's camera as pass constants: what the engine's own passes record with. One instance, refilled per call. */
    /** As {@link CgStageFrame#defaults}. */
    public CgPassConstants defaults(CgPassConstants constants) {
        return frame.defaults(constants);
    }

    public CgPassConstants constants() {
        return frame.hostConstants.seenThrough(frame.camera(), frame.width(), frame.height());
    }

    /**
     * The depth pyramid of the firing's depth, built at the view's first ask of the frame and shared by every later
     * firing of the view that frame ({@link CgGpuOps#depthPyramid}), as Unreal builds one furthest HZB a view a frame.
     * Holds what was recorded before the frame's first ask.
     */
    public CgGraphTexture depthPyramid() {
        CgView view = frame.view();
        CgGraphTexture known = view.textures().hzb();
        if (pyramidBuilt && known != null) return known;
        pyramidBuilt = true;
        CgTrace.add(CgChannels.GL, PYRAMID_ASKS, 1);
        int w = view.width(), h = view.height();
        if (pyramidDesc == null || pyramidDesc.width() != w || pyramidDesc.height() != h) {
            pyramidDesc = new CgTextureDesc(w, h, CgGpuOps.PYRAMID_FORMAT).withMips();
        }
        Pyramid kept = view.state().get(PYRAMID);
        boolean sized = kept.texture != null && pyramidDesc.equals(kept.texture.desc());
        if (sized && kept.frame == view.state().frame() && kept.host == frame.host.target()) {
            view.textures().hzb(kept.texture);
            return kept.texture;   // built by an earlier firing of this view's frame
        }
        if (!sized) {
            if (kept.texture != null) frame.recording.release(kept.texture);
            kept.texture = CgGraphTexture.requested("stage.pyramid", pyramidDesc);
        }
        kept.frame = view.state().frame();
        kept.host = frame.host.target();
        CgGpuOps.depthPyramid(frame.recording, frame.depth(), constants(), kept.texture);
        CgTrace.add(CgChannels.GL, PYRAMIDS, 1);
        view.textures().hzb(kept.texture);
        return kept.texture;
    }

    /** Builds and executes what was recorded, then resets for the next firing. */
    public void execute() {
        try {
            graph.add(frame.recording.seal());
            CgFrame built = builder.build(graph);
            try {
                CgExecutor.execute(built);
            } finally {
                builder.recycle(built);
            }
        } finally {
            graph.clear();
            frame.recording.reset();
        }
    }

    /** As {@link #execute()}, into {@code target} in place of the host's: a view of the engine's own. */
    public void executeInto(CgExternalTarget target) {
        try (CgExecutor.Into ignored = CgExecutor.into(target)) {
            execute();
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
