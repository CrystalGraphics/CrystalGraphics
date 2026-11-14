package com.crystalgraphics.render.stage;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.platform.rhi.CgExternalTarget;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgFrameRing;
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
import com.crystalgraphics.render.graph.CgPass;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.meshpass.CgMeshPassType;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.visibility.CgViewVisibility;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * One firing of a {@link CgRenderStage}: the recording every {@link CgStageRenderer} of it records into, on the host's
 * target, built and executed once they all have.
 *
 * <pre>{@code
 * // Under the stage's camera: the host's, with the world's sun and fog
 * CgRasterPass pass = frame.recording().raster("mymod.glow").color(frame.target(), CgLoad.load()).camera(frame.camera());
 * pass.draw(mesh, material).transform(model);
 * pass.end();
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
 *   <li>Each firing draws a {@link #view()}: what one firing hands a later one, and what a temporal effect keeps for
 *       the next frame, lives in its state, never in a renderer's field.</li>
 *   <li>{@link #constants()} carries the last frame's camera of this stage's view ({@code CgPassConstants.history}),
 *       which is no history of it after a teleport (8 blocks), a turn past 30 degrees, a change of projection,
 *       framebuffer or size, or a skipped frame.</li>
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
    private final CgCamera camera = new CgCamera(this::viewState), guiCamera = new CgCamera(this::viewState);
    private CgGraphTexture pyramid;
    private boolean pyramidBuilt;
    /** The pyramid's description at the host's last size: made again only when it changes. */
    private CgTextureDesc pyramidDesc;
    /** Whether this firing is a rehearsal's, which builds its own pyramid and takes none of the frame's. */
    private boolean rehearsing;
    private final CgStageMeshPass meshPass = new CgStageMeshPass(this);

    /** A view's pyramid, the host frame it was built in and the host target it was built from: one a view a frame. */
    private static final class Pyramid {
        CgGraphTexture texture;
        CgExternalTarget host;
        long frame = Long.MIN_VALUE;
    }

    private static final CgViewState.Key<Pyramid> PYRAMID = new CgViewState.Key<>("stage.pyramid", Pyramid::new);
    /** Pyramids built, and firings asking for one: what each built before the frame shared one. */
    private static final int PYRAMIDS = CgTrace.name("stage.pyramids-built"), PYRAMID_ASKS = CgTrace.name("stage.pyramid-asks");
    @Nullable
    private CgGraphTexture target;
    @Nullable
    private CgGraphTexture depth;
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

    /** How far the camera may move in a frame, and turn (as the cosine), and still be the last frame's view. */
    private static final double TELEPORT = 8.0;
    private static final float TURN_COS = 0.866f, PROJECTION_JUMP = 0.1f;

    /** What this stage last saw of a view: the next frame's firing of it is compared with it (Unreal's PrevViewInfo). */
    private static final class Previous {
        final Matrix4f viewProjection = new Matrix4f();
        double x, y, z;
        float lens00, lens11, lens22;
        CgExternalTarget target;
        int width, height;
        long frame = Long.MIN_VALUE;
    }

    /** This stage's record in each view's state: a key per stage. */
    private final CgViewState.Key<Previous> previousKey = new CgViewState.Key<>("stage.previous", Previous::new);
    private CgView view = CgView.main();
    private final Matrix4f previous = new Matrix4f(), viewProjection = new Matrix4f();
    private boolean historyValid;

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

    /**
     * What the stage's renderers draw into: the host's target as the recording names it, whatever the host has bound
     * when the stage executes; or, between {@link #retarget} and its undoing, the texture given there.
     */
    public CgGraphTexture target() {
        return target != null ? target : CgGraphTexture.current();
    }

    /**
     * The depth the stage's renderers test and write: the host's target's, or, between {@link #retarget} and its
     * undoing, the depth named there. A pass into {@link #target()} that tests depth declares it.
     *
     * <pre>{@code
     * recording.raster("mymod.glow").color(frame.target(), CgLoad.load())
     *         .depth(frame.depth(), CgLoad.load(), CgDepthAccess.WRITE);
     * }</pre>
     */
    public CgGraphTexture depth() {
        return depth != null ? depth : CgGraphTexture.current();
    }

    /**
     * Makes {@link #target()} answer {@code target} and {@link #depth()} {@code depth} for the rest of this firing, or
     * the host's target again for null: every renderer recording after drawing into it unawares. The HDR scene is the
     * user.
     *
     * <pre>{@code
     * frame.retarget(scene, sceneDepth);   // scene in: the host's depth, or a depth of its own
     * frame.retarget(null, null);          // the composite, which encodes the scene back into the host's target
     * }</pre>
     */
    public void retarget(@Nullable CgGraphTexture target, @Nullable CgGraphTexture depth) {
        this.target = target;
        this.depth = target == null ? null : depth;
    }

    /**
     * Fills {@code constants} with the frame's time, the host target's size, and the world's sun and fog
     * ({@link CgWorldAtmosphere}); the camera is the caller's.
     */
    public CgPassConstants defaults(CgPassConstants constants) {
        constants.time(CgFrameClock.seconds())
                .resolution(host.width(), host.height())
                .linearScene(target != null);
        return CgWorldAtmosphere.apply(host.environment(), constants);
    }

    /**
     * This firing's camera: the host's, with the frame's time and the host target's size -- the world's at a world stage,
     * camera-relative as the host draws its world; the window's pixels at a GUI stage. A pass or a canvas given it also
     * takes the world's sun and fog, the last frame's view of this firing and the scene's encoding. One object, refilled
     * per call: use it, never a camera of your own, for what draws in the stage's view.
     *
     * <pre>{@code
     * rec.raster("mymod.glow").color(frame.target(), CgLoad.load())
     *         .depth(frame.depth(), CgLoad.load(), CgDepthAccess.WRITE).camera(frame.camera());
     * if (frame.camera().project(x, y, z, frame.host().width(), frame.host().height(), at)) { ... }
     * }</pre>
     */
    public CgCamera camera() {
        return camera.set(host.camera()).time(CgFrameClock.seconds()).resolution(host.width(), host.height());
    }

    /**
     * {@link #camera()} in the host GUI's pixels, scaled by {@link CgHostFrame#guiScale()}: what
     * {@link #guiScaledCanvas()} draws through. A GUI stage's only: at a world stage it throws.
     */
    public CgCamera guiCamera() {
        if (!host.camera().isOrthographic()) throw new IllegalStateException(stage.id() + " is not a GUI stage: it has no GUI pixels");
        float s = host.guiScale();
        guiCamera.set(camera()).projection().scale(s, s, 1f);
        return guiCamera;
    }

    /** What this firing adds to a pass of its camera: the scene's encoding, the last frame's view, the atmosphere. */
    private void viewState(CgPassConstants constants) {
        constants.linearScene(target != null).history(previous, view.state().frameIndex(), historyValid);
        CgWorldAtmosphere.apply(host.environment(), constants);
    }

    /**
     * {@link #camera()} as pass constants: what the engine's own passes record with. One instance, refilled per call;
     * {@link CgRecording#raster} copies it.
     */
    public CgPassConstants constants() {
        return hostConstants.seenThrough(camera(), host.width(), host.height());
    }

    /** Whether last frame's view of this firing is one a temporal effect may blend over: {@code CG_HISTORY_VALID}. */
    public boolean historyValid() {
        return historyValid;
    }

    /**
     * The view this firing draws: the host's ({@code host().view()}), or {@link CgView#unkept()} for a rehearsal and for
     * a second firing of this stage in one host frame for the same view. What it hands a later firing and keeps for the
     * next frame lives in its state.
     *
     * <pre>{@code
     * Kept kept = frame.view().state().get(KEPT);   // this view's, never another's
     * }</pre>
     */
    public CgView view() {
        return view;
    }

    /**
     * A mesh pass of {@code type} over the client scene for this firing's view: declare its attachments, then draw it
     * ({@link CgStageMeshPass}). A consumer's own pass type runs here.
     *
     * <pre>{@code
     * frame.meshPass(THERMAL).color(heat, CgLoad.clear(0f, 0f, 0f, 0f))
     *         .depth(frame.depth(), CgLoad.load(), CgDepthAccess.READ).draw();
     * }</pre>
     *
     * <p>One object, begun again by each call: finish one pass before asking for the next.</p>
     */
    public CgStageMeshPass meshPass(CgMeshPassType type) {
        return meshPass.begin(type);
    }

    /** A raster pass onto the host's target under {@code constants}, loading what is there. */
    public CgRasterPass pass(CgPassConstants constants, CgOrder order) {
        return recording.raster("stageFrame.target").color(target(), CgLoad.load()).constants(constants).order(order);
    }

    /**
     * The depth pyramid of the host's target, built at the view's first ask of the frame and shared by every renderer
     * and every later firing of the view that frame that asks after ({@link CgGpuOps#depthPyramid}: each level the
     * farthest eye depth it covers), as Unreal builds one furthest HZB a view a frame. What a GPU cull tests occlusion
     * against.
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
            // Its own target's depth, while the host frame still names the host's: never a view's pyramid
            if (pyramid == null || !desc.equals(pyramid.desc())) pyramid = CgGraphTexture.transientTexture("stage.pyramid", desc);
        } else {
            Pyramid kept = view.state().get(PYRAMID);
            boolean sized = kept.texture != null && desc.equals(kept.texture.desc());
            if (sized && kept.frame == view.state().frame() && kept.host == host.target()) {
                return pyramid = kept.texture;   // built by an earlier firing of this view's frame
            }
            if (!sized) {
                if (kept.texture != null) recording.release(kept.texture);
                kept.texture = CgGraphTexture.requested("stage.pyramid", desc);
            }
            kept.frame = view.state().frame();
            kept.host = host.target();
            pyramid = kept.texture;
        }
        CgGpuOps.depthPyramid(recording, depth(), constants(), pyramid);
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
        guiCamera();   // throws at a world stage
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
        if (!host.camera().isOrthographic()) {
            throw new IllegalStateException(stage.id() + " is not a GUI stage: project with its camera instead");
        }
        float s = guiPixels ? host.guiScale() : 1f;
        return CgRenderStage.WORLD_OPAQUE.host().camera().project(x, y, z, host.width() / s, host.height() / s, out);
    }

    private CgCanvas canvasIn(boolean gui) {
        if (gui != guiPixels) {
            guiPixels = gui;
            if (canvas.recording()) canvas.constants(canvasCamera());   // lands what was drawn under the other camera
        }
        return canvas;
    }

    /** The canvas's camera as constants: {@link #camera()}, or {@link #guiCamera()} for a {@link #guiScaledCanvas()}. */
    private CgPassConstants canvasCamera() {
        return hostConstants.seenThrough(guiPixels ? guiCamera() : camera(), host.width(), host.height());
    }

    private void openCanvas(CgCanvas canvas) {
        canvas.recordInto(recording, target(), depth(), CgLoad.load(), canvasCamera());
        CgCamera view = host.camera();
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
        return recording.callback(name, target(), depth(), body);
    }

    /**
     * Executes {@code frame}, recorded and built elsewhere, at this point in the firing: after everything recorded into
     * the firing before, and before everything after, on the firing's target as {@code CgGraphTexture.current()}. What a
     * UI that records on its own thread draws its frame with. The firing is still built and executed once: the frame is
     * a step of it ({@link CgRecording#include}), executed at its place and never built again. The caller keeps the frame:
     * recycle it with its builder once the firing has executed.
     *
     * <pre>{@code
     * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
     *     frame.include(ui.built()).timed(GPU_UI).then(UI_EXECUTED);              // the UI's own passes, then
     *     frame.canvas().quad(COMPOSITE).texture(0, ui.picture()).at(0, 0).size(w, h).submit();   // what reads them
     * });
     * }</pre>
     *
     * <ul>
     *   <li>What the turn drew on the canvas so far lands before it; what it draws after, after.</li>
     *   <li>It is ordered by what the frame touches that outlives it (the target, requested and imported textures), so a
     *       transient of the firing's never passes into it, nor one of its out.</li>
     *   <li>A built frame executes once with this; again with {@link #includeAgain}.</li>
     * </ul>
     *
     * @return the step, to time or follow: valid until the firing executes
     */
    public CgPass.Include include(CgFrame frame) {
        canvas.endPass();
        return recording.include(frame);
    }

    /**
     * As {@link #include}, for a frame already executed: executed again with what its passes read as it stands now
     * ({@link CgExecutor#executeAgain}). A compositor's move of what a frame drew, with nothing recorded or built.
     *
     * <pre>{@code
     * values.translate(window, dx, dy);
     * frame.includeAgain(last, true).timed(GPU_UI);   // requested textures (a window's surface) left as they were
     * }</pre>
     *
     * @param keepRequested skip every pass writing a requested texture, leaving it as the last execution did
     */
    public CgPass.Include includeAgain(CgFrame frame, boolean keepRequested) {
        canvas.endPass();
        return recording.includeAgain(frame, keepRequested);
    }

    void begin(CgHostFrame host) {
        this.host = host;
        recording.reset();
        pyramidBuilt = false;
        rehearsing = false;
        target = null;
        depth = null;
        long frame = CgFrameRing.frame();
        // A view draws each stage once a frame: a second firing for it is nobody's view, and keeps nothing
        CgView named = host.view();
        view = named.state().get(previousKey).frame == frame ? CgView.unkept() : named;
        view.state().begin(frame);
        remember(host.camera(), frame);
        // What the view sees of the client's scene, once a frame before its first world callback: Unreal's InitViews
        if ((stage == CgRenderStage.WORLD_OPAQUE || stage == CgRenderStage.WORLD_TRANSPARENT)
                && CgScene.client().primitives().size() > 0) {
            CgViewVisibility.of(view, CgScene.client(), host.camera());
        }
    }

    /** As {@link #begin} for a rehearsal's firing: it draws as {@link CgView#unkept()}, the real views left alone. */
    void beginRehearsal(CgHostFrame host) {
        this.host = host;
        recording.reset();
        pyramidBuilt = false;
        rehearsing = true;
        target = null;
        depth = null;
        view = CgView.unkept();
        view.state().begin(CgFrameRing.frame());
        viewProjection.set(host.camera().projection()).mul(host.camera().view());
        historyValid = false;
    }

    /** Compares this firing's camera with what this stage saw of its view last frame, then keeps it for the next. */
    private void remember(CgCamera camera, long frame) {
        viewProjection.set(camera.projection()).mul(camera.view());
        historyValid = false;
        Previous last = view.state().get(previousKey);
        if (!view.state().keepsHistory()) {
            last.frame = frame;
            return;
        }
        double dx = camera.x() - last.x, dy = camera.y() - last.y, dz = camera.z() - last.z;
        if (last.frame == frame - 1 && last.target == host.target() && last.width == host.width() && last.height == host.height()
                && dx * dx + dy * dy + dz * dz < TELEPORT * TELEPORT && sameLens(last, camera)) {
            // Last frame's view-projection of this frame's camera-relative points: moved by the camera's step.
            previous.set(last.viewProjection).translate((float) dx, (float) dy, (float) dz);
            historyValid = forward(last.viewProjection) >= TURN_COS;
        }
        last.viewProjection.set(viewProjection);
        last.lens00 = camera.projection().m00();
        last.lens11 = camera.projection().m11();
        last.lens22 = camera.projection().m22();
        last.x = camera.x();
        last.y = camera.y();
        last.z = camera.z();
        last.target = host.target();
        last.width = host.width();
        last.height = host.height();
        last.frame = frame;
    }

    /** Whether {@code last}'s lens is {@code camera}'s within a zoom's step: its focal lengths and depth mapping. */
    private static boolean sameLens(Previous last, CgCamera camera) {
        float p00 = camera.projection().m00(), p11 = camera.projection().m11(), p22 = camera.projection().m22();
        return Math.abs(p00 - last.lens00) < PROJECTION_JUMP * Math.abs(p00)
                && Math.abs(p11 - last.lens11) < PROJECTION_JUMP * Math.abs(p11)
                && Math.signum(p22) == Math.signum(last.lens22);
    }

    /** The cosine between {@code last}'s and this view's forward: the w row of a view-projection is the forward. */
    private float forward(Matrix4f last) {
        float ax = viewProjection.m03(), ay = viewProjection.m13(), az = viewProjection.m23();
        float bx = last.m03(), by = last.m13(), bz = last.m23();
        float n = (float) Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
        return n > 0f ? (ax * bx + ay * by + az * bz) / n : 0f;
    }

    /** As {@link #execute()}, with {@code target} the current target in place of the host's: a rehearsal's firing. */
    void executeInto(CgExternalTarget target) {
        try (CgExecutor.Into ignored = CgExecutor.into(target)) {
            execute();
        }
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
            // A transient published here dies with this firing's graph: the next firing must not see it.
            resources.clear();
        }
    }
}
