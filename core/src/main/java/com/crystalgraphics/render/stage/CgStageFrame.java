package com.crystalgraphics.render.stage;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
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
import com.crystalgraphics.render.graph.CgFrameScope;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import javax.annotation.Nullable;

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
 *   <li>During a renderer's turn, {@link #canvas()} -- quads, curves and text -- draws
 *       into this firing, on {@link #target()} under {@link #constants()}: the world's camera at a world stage, the
 *       GUI's pixels at a GUI stage. What a turn drew lands when it ends, in a pass of its own.</li>
 *   <li>The stage parks the host's sampler objects and turns its scissor off around execution: what the host leaves
 *       there overrides a texture's own filtering, and clips every draw to its box.</li>
 *   <li>{@link #callback} draws immediately at its place in the stage, with GL state restored after — for work that
 *       is not recorded yet.</li>
 *   <li>{@link #constants()} carries the last frame's view of this firing ({@code CgPassConstants.history}): the n-th
 *       firing of a host frame is compared with last frame's n-th, and is no history of it after a teleport (8
 *       blocks), a turn past 30 degrees, a change of projection, framebuffer or size, or a skipped frame.</li>
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
    /** The pyramid's description at the host's last size: made again only when it changes. */
    private CgTextureDesc pyramidDesc;
    /** Whether this firing is a rehearsal's, which builds its own pyramid and takes none of the frame's. */
    private boolean rehearsing;
    /** The frame's pyramid and the host whose target it was built from (L1: one a frame, every firing's). */
    private static final CgFrameScope.Key<CgGraphTexture> PYRAMID = new CgFrameScope.Key<>("stage.pyramid");
    private static final CgFrameScope.Key<Integer> PYRAMID_HOST = new CgFrameScope.Key<>("stage.pyramid.host");
    /** Pyramids built, and firings asking for one: what each built before the frame shared one. */
    private static final int PYRAMIDS = CgTrace.name("stage.pyramids-built"), PYRAMID_ASKS = CgTrace.name("stage.pyramid-asks");
    @Nullable
    private CgGraphTexture target;
    /** {@link #canvas()}: opened at its first draw in a turn, so a turn that draws nothing records nothing. */
    private final CgCanvas canvas = new CgCanvas();
    private final CgCanvas.Source canvasOpener = new CgCanvas.Source() {
        @Override
        public void open(CgCanvas canvas) {
            openCanvas(canvas);
        }

        @Override
        public CgPassConstants constants() {
            return canvasCamera();
        }
    };
    /** Whether this turn's canvas draws GUI-scaled ({@link #guiScaledCanvas}) rather than in the window's pixels. */
    private boolean guiPixels;

    /** Firings a host frame remembered for the next: the n-th is compared with last frame's n-th. */
    private static final int ORDINALS = 4;
    /** How far the camera may move in a frame, and turn (as the cosine), and still be the last frame's view. */
    private static final double TELEPORT = 8.0;
    private static final float TURN_COS = 0.866f, PROJECTION_JUMP = 0.1f;
    private final Matrix4f[] lastViewProjection = new Matrix4f[ORDINALS];
    private final double[] lastOrigin = new double[ORDINALS * 3];
    private final float[] lastLens = new float[ORDINALS * 3];
    private final int[] lastFramebuffer = new int[ORDINALS], lastWidth = new int[ORDINALS], lastHeight = new int[ORDINALS];
    private final long[] lastFrame = new long[ORDINALS];
    private long firingFrame = Long.MIN_VALUE;
    private int ordinal;
    private final Matrix4f previous = new Matrix4f(), viewProjection = new Matrix4f();
    private boolean historyValid;

    CgStageFrame(CgRenderStage stage) {
        this.stage = stage;
        for (int i = 0; i < ORDINALS; i++) {
            lastViewProjection[i] = new Matrix4f();
            lastFrame[i] = Long.MIN_VALUE;
        }
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

    /**
     * What the stage's renderers draw into: the host's target as the recording names it, whatever the host has bound
     * when the stage executes; or, between {@link #retarget} and its undoing, the texture given there.
     */
    public CgGraphTexture target() {
        return target != null ? target : CgGraphTexture.current();
    }

    /**
     * Makes {@link #target()} answer {@code target} for the rest of this firing, or the host's target again for null:
     * every renderer recording after drawing into it unawares. The HDR scene is the user.
     *
     * <pre>{@code
     * frame.retarget(scene);   // scene in
     * frame.retarget(null);    // the composite, which encodes the scene back into the host's target
     * }</pre>
     */
    public void retarget(@Nullable CgGraphTexture target) {
        this.target = target;
    }

    /**
     * Fills {@code constants} with the frame's time, the host target's size and depth convention, and the world's sun
     * and fog ({@link CgWorldAtmosphere}); the camera is the caller's.
     */
    public CgPassConstants defaults(CgPassConstants constants) {
        constants.time(CgFrameClock.seconds())
                .resolution(host.width(), host.height())
                .depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne())
                .linearScene(target != null);
        return CgWorldAtmosphere.apply(host.environment(), constants);
    }

    /**
     * The host's camera as pass constants, camera-relative as the host draws its world: its view and projection, the
     * eye at its view's origin, {@code cg_WorldOrigin} at its absolute position, the last frame's view of this firing,
     * and {@link #defaults}. One instance, refilled per call; {@link CgRecording#raster} copies it.
     */
    public CgPassConstants constants() {
        CgHostView view = host.view();
        defaults(hostConstants);
        hostConstants.view.set(view.view());
        hostConstants.projection.set(view.projection());
        hostConstants.history(previous, (int) firingFrame, historyValid);
        return hostConstants.cameraFromView().origin(view.x(), view.y(), view.z());
    }

    /** Whether last frame's view of this firing is one a temporal effect may blend over: {@code CG_HISTORY_VALID}. */
    public boolean historyValid() {
        return historyValid;
    }

    /** Which firing of its host frame this is, from 0: what a temporal effect keeps a history per. */
    public int ordinal() {
        return ordinal;
    }

    /** A raster pass onto the host's target under {@code constants}, loading what is there. */
    public CgRasterPass pass(CgPassConstants constants, CgOrder order) {
        return recording.raster(target(), CgLoad.load(), constants, null, order);
    }

    /**
     * The depth pyramid of the host's target, built at the frame's first ask and shared by every renderer and every
     * later firing of the host's frame that asks after ({@link CgGpuOps#depthPyramid}: each level the farthest eye depth
     * it covers), as Unreal builds one furthest HZB a view a frame. What a GPU cull tests occlusion against.
     *
     * <pre>{@code
     * cull.view(view.view(), view.projection()).pyramid(frame.depthPyramid());
     * }</pre>
     *
     * <ul>
     *   <li>Holds the depth of what was recorded before the frame's first ask: asked ahead of a renderer's own draws, it
     *       hides nothing behind them. At {@code WORLD_OPAQUE}, everything the host drew before firing it; a later firing
     *       culls against that, farther than its own depth, so it culls less and draws the same picture.</li>
     * </ul>
     */
    public CgGraphTexture depthPyramid() {
        if (pyramidBuilt) return pyramid;
        pyramidBuilt = true;
        CgTrace.add(CgChannels.GL, PYRAMID_ASKS, 1);
        int w = (int) host.width(), h = (int) host.height();
        if (pyramidDesc == null || pyramidDesc.width() != w || pyramidDesc.height() != h) {
            pyramidDesc = new CgTextureDesc(w, h, CgGpuOps.PYRAMID_FORMAT).withMips();
        }
        CgTextureDesc desc = pyramidDesc;
        if (rehearsing) {
            if (pyramid == null || !desc.equals(pyramid.desc())) pyramid = CgGraphTexture.transientTexture("stage.pyramid", desc);
        } else {
            CgGraphTexture shared = CgFrameScope.get(PYRAMID);
            Integer from = CgFrameScope.get(PYRAMID_HOST);   // the host's target it was built from: its framebuffer
            if (shared != null && from != null && from == host.mainFramebuffer() && desc.equals(shared.desc())) {
                return pyramid = shared;   // built by an earlier firing of this frame
            }
            pyramid = CgFrameScope.texture(PYRAMID, desc, recording);
            CgFrameScope.put(PYRAMID, pyramid);
            CgFrameScope.put(PYRAMID_HOST, host.mainFramebuffer());
        }
        CgGpuOps.depthPyramid(recording, target(), constants(), pyramid);
        CgTrace.add(CgChannels.GL, PYRAMIDS, 1);
        return pyramid;
    }

    /**
     * What a renderer draws quads, curves and text into during its turn (Unreal's canvas): on {@link #target()} under
     * {@link #constants()} -- at a world stage the camera's space, camera-relative, its origin the camera's absolute
     * position; at a GUI stage the window's pixels. What a turn draws lands when the turn ends, in a pass of its own.
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame ->
     *         frame.canvas().quad(DIGIT).texture(0, DIGITS).at(x, y).size(w, h).color(argb).submit());
     * }</pre>
     *
     * <ul>
     *   <li>Inside {@link CgStageRenderer#render} only: a {@link #callback}'s body runs at execution, outside any turn.</li>
     *   <li>The same canvas as {@link #guiScaledCanvas()}: asking for one after the other switches what later items
     *       draw under, earlier ones kept as drawn and in order.</li>
     * </ul>
     */
    public CgCanvas canvas() {
        return canvasIn(false);
    }

    /**
     * {@link #canvas()} in the host GUI's pixels: everything drawn on it is scaled by {@link CgHostFrame#guiScale()},
     * so it sits at the size of Minecraft's own interface and follows its GUI Scale setting. Text still rasters at the
     * window's pixels, so it stays sharp.
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
     *     CgCanvas gui = frame.guiScaledCanvas();             // once a turn: every item after is GUI-scaled
     *     gui.quad(ICON).at(4, 4).size(16, 16).submit();       // 16 GUI pixels: 64 on the window at scale 4
     *     gui.text().text("Ready").font(font).at(24, 6).submit();
     * });
     * }</pre>
     *
     * <ul>
     *   <li>A GUI stage's only: at a world stage it throws.</li>
     *   <li>Taking {@link #canvas()} later in the same turn goes back to window pixels for what follows.</li>
     * </ul>
     */
    public CgCanvas guiScaledCanvas() {
        if (host.view().projection().m23() != 0f) {
            throw new IllegalStateException(stage.id() + " is not a GUI stage: it has no GUI pixels");
        }
        return canvasIn(true);
    }

    /**
     * Where the absolute world point ({@code x}, {@code y}, {@code z}) is on this GUI stage's canvas, into {@code out}:
     * seen by the world's camera ({@code WORLD_OPAQUE}'s), in the canvas's pixels, the GUI's after
     * {@link #guiScaledCanvas()}. False behind the camera. What a {@link CgRenderStage#WORLD_WIDGETS} renderer follows.
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> {
     *     if (frame.projectWorld(x, y + 2, z, at)) frame.canvas().quad(MARKER).at(at.x - 8, at.y - 8).size(16, 16).submit();
     * });
     * }</pre>
     *
     * A GUI stage's only: at a world stage it throws.
     */
    public boolean projectWorld(double x, double y, double z, Vector2f out) {
        if (host.view().projection().m23() != 0f) {
            throw new IllegalStateException(stage.id() + " is not a GUI stage: project with its view instead");
        }
        float s = guiPixels ? host.guiScale() : 1f;
        return CgRenderStage.WORLD_OPAQUE.host().view().project(x, y, z, host.width() / s, host.height() / s, out);
    }

    private CgCanvas canvasIn(boolean gui) {
        if (gui != guiPixels) {
            guiPixels = gui;
            if (canvas.recording()) canvas.constants(canvasCamera());   // lands what was drawn under the other camera
        }
        return canvas;
    }

    /** The canvas's camera: {@link #constants()}, scaled to the host GUI's pixels for a {@link #guiScaledCanvas()}. */
    private CgPassConstants canvasCamera() {
        CgPassConstants camera = constants();
        if (guiPixels) {
            float s = host.guiScale();
            camera.projection.scale(s, s, 1f);
        }
        return camera;
    }

    private void openCanvas(CgCanvas canvas) {
        canvas.recordInto(recording, target(), CgLoad.load(), canvasCamera());
        CgHostView view = host.view();
        canvas.origin(view.x(), view.y(), view.z());
    }

    /** A renderer's turn starts: what it draws on the canvas records into this firing, in window pixels. */
    void turnBegins() {
        guiPixels = false;
        canvas.arm(canvasOpener);
    }

    /** A renderer's turn ends: what it drew on the canvas lands, in a pass of its own. */
    void turnEnds() {
        canvas.stop();
    }

    /** Runs {@code body} at this point in the stage, on the host's target, with GL state restored after. */
    public CgRequest callback(String name, Runnable body) {
        return recording.callback(name, target(), body);
    }

    void begin(CgHostFrame host) {
        this.host = host;
        recording.reset();
        pyramidBuilt = false;
        rehearsing = false;
        target = null;
        long frame = CgFrameRing.frame();
        ordinal = frame == firingFrame ? ordinal + 1 : 0;
        firingFrame = frame;
        remember(host.view());
    }

    /** As {@link #begin} for a rehearsal's firing: the stage's history, kept for the real frames, is left alone. */
    void beginRehearsal(CgHostFrame host) {
        this.host = host;
        recording.reset();
        pyramidBuilt = false;
        rehearsing = true;
        target = null;
        viewProjection.set(host.view().projection()).mul(host.view().view());
        historyValid = false;
    }

    /** Compares this firing's view with last frame's of the same ordinal, then keeps it for the next. */
    private void remember(CgHostView view) {
        viewProjection.set(view.projection()).mul(view.view());
        historyValid = false;
        if (ordinal >= ORDINALS) return;
        int at = ordinal;
        double dx = view.x() - lastOrigin[at * 3], dy = view.y() - lastOrigin[at * 3 + 1], dz = view.z() - lastOrigin[at * 3 + 2];
        Matrix4f last = lastViewProjection[at];
        if (lastFrame[at] == firingFrame - 1 && lastFramebuffer[at] == host.mainFramebuffer()
                && lastWidth[at] == host.width() && lastHeight[at] == host.height()
                && dx * dx + dy * dy + dz * dz < TELEPORT * TELEPORT && sameLens(at, view)) {
            // Last frame's view-projection of this frame's camera-relative points: moved by the camera's step.
            previous.set(last).translate((float) dx, (float) dy, (float) dz);
            historyValid = forward(last) >= TURN_COS;
        }
        last.set(viewProjection);
        lastLens[at * 3] = view.projection().m00();
        lastLens[at * 3 + 1] = view.projection().m11();
        lastLens[at * 3 + 2] = view.projection().m22();
        lastOrigin[at * 3] = view.x();
        lastOrigin[at * 3 + 1] = view.y();
        lastOrigin[at * 3 + 2] = view.z();
        lastFramebuffer[at] = host.mainFramebuffer();
        lastWidth[at] = host.width();
        lastHeight[at] = host.height();
        lastFrame[at] = firingFrame;
    }

    /** Whether last frame's lens at {@code at} is {@code view}'s within a zoom's step: its focal lengths and depth mapping. */
    private boolean sameLens(int at, CgHostView view) {
        float p00 = view.projection().m00(), p11 = view.projection().m11(), p22 = view.projection().m22();
        return Math.abs(p00 - lastLens[at * 3]) < PROJECTION_JUMP * Math.abs(p00)
                && Math.abs(p11 - lastLens[at * 3 + 1]) < PROJECTION_JUMP * Math.abs(p11)
                && Math.signum(p22) == Math.signum(lastLens[at * 3 + 2]);
    }

    /** The cosine between {@code last}'s and this view's forward: the w row of a view-projection is the forward. */
    private float forward(Matrix4f last) {
        float ax = viewProjection.m03(), ay = viewProjection.m13(), az = viewProjection.m23();
        float bx = last.m03(), by = last.m13(), bz = last.m23();
        float n = (float) Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
        return n > 0f ? (ax * bx + ay * by + az * bz) / n : 0f;
    }

    /** The slot an execution saves, as an array once: varargs make one a call. */
    private static final CgGlSlot[] SCISSOR = {CgGlSlot.SCISSOR};

    /** As {@link #execute()}, with {@code target} bound as the host's: a rehearsal's firing. */
    void executeInto(CgFrameBuffer target) {
        try (CgGlScope ignored = CgGlState.save(FRAMEBUFFER)) {
            target.bind();
            // The executor takes the bound viewport as the current target's size
            CgGL.glViewport(0, 0, target.getWidth(), target.getHeight());
            execute();
        }
    }

    /** The slot {@link #executeInto} restores. */
    private static final CgGlSlot[] FRAMEBUFFER = {CgGlSlot.FBO, CgGlSlot.VIEWPORT};

    /** Builds and executes what was recorded, then resets for the next firing. */
    void execute() {
        try {
            graph.add(recording.seal());
            CgFrame frame = builder.build(graph);
            try (CgGlScope ignored = CgGlState.save(SCISSOR)) {
                CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
                CgExecutor.execute(frame);
            } finally {
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
