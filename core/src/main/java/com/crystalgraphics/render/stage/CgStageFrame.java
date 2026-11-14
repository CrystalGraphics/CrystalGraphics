package com.crystalgraphics.render.stage;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.host.CgWorldAtmosphere;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.meshpass.CgMeshPassType;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.view.CgView;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * One stage's turn in a firing: the recording its renderers record into, the target it draws onto and the view it
 * draws. Built and executed once every stage of the firing has recorded.
 *
 * <pre>{@code
 * CgRenderStage.TRANSLUCENT.register(frame -> {
 *     CgRasterPass pass = frame.recording().raster("mymod.glow").color(frame.target(), CgLoad.load())
 *             .depth(frame.depth(), CgLoad.load(), CgDepthAccess.READ).camera(frame.camera());
 *     pass.draw(mesh, material).transform(model);
 *     pass.end();
 * });
 *
 * CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
 *     frame.canvas().image(BADGE).at(8, 8).size(32, 32).submit();       // device pixels
 *     frame.scaled().text("Ready").at(4, 44).submit();                  // the host GUI's pixels
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, and only inside {@link CgStageRenderer#render}: the recording is executed and reset after.</li>
 *   <li>During a renderer's turn, {@link #canvas()} draws into this firing on {@link #target()}: under the view's
 *       camera at a world stage, in the window's pixels at a GUI stage. What a turn drew lands when it ends.</li>
 *   <li>What one stage hands a later one, and what a temporal effect keeps, lives in the view
 *       ({@code view().publish}, {@code view().state()}), never in a renderer's field.</li>
 * </ul>
 */
public final class CgStageFrame {

    final CgRecording recording = new CgRecording();
    final CgPassConstants hostConstants = new CgPassConstants();
    private final CgCamera gui = new CgCamera(), guiScaled = new CgCamera();
    private final CgStageMeshPass meshPass = new CgStageMeshPass(this);
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

    CgHostFrame host;
    /** The view a world firing draws; null at a GUI or custom stage's. */
    @Nullable
    CgView view;
    /** The stage whose turn it is. */
    CgRenderStage stage;
    @Nullable
    CgGraphTexture target, depth;
    /** Whether this turn's canvas draws in the host GUI's pixels ({@link #scaled}). */
    private boolean guiPixels;

    /** The firing it belongs to: what builds the view's depth pyramid. */
    private final CgStageFiring firing;

    CgStageFrame(CgStageFiring firing) {
        this.firing = firing;
    }

    /** The stage whose turn it is. */
    public CgRenderStage stage() {
        return stage;
    }

    /**
     * The view this world stage draws: its camera, environment, scene textures and what it keeps. A GUI stage has none.
     *
     * @throws IllegalStateException at a GUI stage
     */
    public CgView view() {
        if (view == null) throw new IllegalStateException(stage + " runs once a frame: it draws no view");
        return view;
    }

    /** The scene this world stage's view sees. */
    public CgScene scene() {
        return view().scene();
    }

    @Nullable
    CgScene sceneOrNull() {
        return view == null ? null : view.scene();
    }

    /** What the stage's renderers record into. */
    public CgRecording recording() {
        return recording;
    }

    /**
     * What this stage draws onto: the host's target before our opaque scene and at a GUI stage, the HDR scene from
     * {@code SURFACES} to {@code TRANSLUCENT}, the final picture at {@code OVERLAY}.
     */
    public CgGraphTexture target() {
        return target != null ? target : CgGraphTexture.current();
    }

    /** The depth {@link #target()} is drawn with: a pass into it that tests depth declares it. */
    public CgGraphTexture depth() {
        return depth != null ? depth : CgGraphTexture.current();
    }

    /**
     * The pass constants of this stage's camera, target size and the host's sun and fog: what a raster pass of the
     * stage's draws with. One object, refilled per ask.
     *
     * <pre>{@code
     * rec.raster("mymod.outline").color(frame.target(), CgLoad.load()).constants(frame.constants());
     * }</pre>
     */
    public CgPassConstants constants() {
        return firing.constants();
    }

    /**
     * Fills {@code constants} with the frame's time, the target's size and the view's sun and fog
     * ({@link CgWorldAtmosphere}); the camera is the caller's. A compute pass's constants, which no raster camera sets.
     *
     * <pre>{@code
     * CgComputePass step = frame.recording().compute("mymod.fog.step", frame.defaults(constants));
     * }</pre>
     */
    public CgPassConstants defaults(CgPassConstants constants) {
        constants.time(CgFrameClock.seconds()).resolution(width(), height()).linearScene(target != null);
        return CgWorldAtmosphere.apply(view != null ? view.environment() : host.environment(), constants);
    }

    /**
     * The view's furthest-depth pyramid this frame (HZB): built at its first ask, from the depth drawn by then, and
     * shared by every later ask of the view that frame. World stages only.
     *
     * <pre>{@code
     * CgRenderStage.PREPARE.register(frame -> cullSparks(frame.recording(), frame.camera(), frame.depthPyramid()));
     * }</pre>
     *
     * <ul>
     *   <li>At {@code PREPARE} it holds the host's opaque world alone: what the scene draws later is not in it.</li>
     * </ul>
     */
    public CgGraphTexture depthPyramid() {
        if (view == null) throw new IllegalStateException("a GUI stage has no depth pyramid");
        return firing.depthPyramid();
    }

    /**
     * This stage's camera: the view's at a world stage, camera-relative as the host draws its world; the window's
     * pixels at a GUI stage. One object, refilled per firing.
     */
    public CgCamera camera() {
        if (view != null) return view.camera();
        return gui.set(host.camera()).time(CgFrameClock.seconds()).resolution(host.width(), host.height());
    }

    /** {@link #target()}'s width in pixels. */
    public int width() {
        return view != null ? view.width() : host.width();
    }

    public int height() {
        return view != null ? view.height() : host.height();
    }

    /** The host GUI's device pixels per GUI pixel (Minecraft's GUI scale); 1 at a world stage. */
    public float scale() {
        return view != null ? 1f : host.guiScale();
    }

    /**
     * A mesh pass of {@code type} over this world stage's view: declare its attachments, then draw it. A consumer's own
     * pass type runs here.
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

    /**
     * What a renderer draws quads, curves and text into during its turn (Unreal's canvas): on {@link #target()} under
     * {@link #camera()}. What a turn draws lands when the turn ends, in a pass of its own.
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame ->
     *         frame.canvas().quad(DIGIT).texture(0, DIGITS).at(x, y).size(w, h).color(argb).submit());
     * }</pre>
     */
    public CgCanvas canvas() {
        return canvasIn(false);
    }

    /**
     * {@link #canvas()} in the host GUI's pixels: scaled by {@link #scale()}, so it sits at the size of Minecraft's own
     * interface. Text still rasters at the window's pixels. Taking {@link #canvas()} later in the turn goes back to
     * window pixels for what follows.
     *
     * <pre>{@code
     * CgCanvas gui = frame.scaled();
     * gui.quad(ICON).at(4, 4).size(16, 16).submit();     // 16 GUI pixels: 64 on the window at scale 4
     * }</pre>
     *
     * @throws IllegalStateException at a world stage
     */
    public CgCanvas scaled() {
        if (view != null) throw new IllegalStateException(stage + " is not a GUI stage: it has no GUI pixels");
        return canvasIn(true);
    }

    /**
     * Where the absolute world point ({@code x}, {@code y}, {@code z}) is on this GUI stage's canvas, into {@code out}:
     * seen by the game's view, in the canvas's pixels, the GUI's after {@link #scaled()}. False behind the camera.
     *
     * <pre>{@code
     * if (frame.project(x, y + 2, z, at)) frame.canvas().quad(MARKER).at(at.x - 8, at.y - 8).size(16, 16).submit();
     * }</pre>
     *
     * @throws IllegalStateException at a world stage
     */
    public boolean project(double x, double y, double z, Vector2f out) {
        if (view != null) throw new IllegalStateException(stage + " is not a GUI stage: project with its camera instead");
        float s = guiPixels ? host.guiScale() : 1f;
        return CgView.main().camera().project(x, y, z, host.width() / s, host.height() / s, out);
    }

    /**
     * Executes {@code frame}, recorded and built elsewhere, at this point in the firing, on the firing's target as
     * {@code CgGraphTexture.current()}: what a UI that records on its own thread draws its frame with. The caller keeps
     * the frame and recycles it once the firing has executed.
     *
     * <pre>{@code
     * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
     *     frame.include(ui.built()).timed(GPU_UI);
     *     frame.canvas().quad(COMPOSITE).texture(0, ui.picture()).at(0, 0).size(w, h).submit();
     * });
     * }</pre>
     *
     * @return the step, to time or follow: valid until the firing executes
     */
    public CgPass.Include include(CgFrame frame) {
        canvas.endPass();
        return recording.include(frame);
    }

    /**
     * As {@link #include}, for a frame already executed: executed again with what its passes read as it stands now
     * ({@link CgExecutor#executeAgain}).
     *
     * @param keepRequested skip every pass writing a requested texture, leaving it as the last execution did
     */
    public CgPass.Include includeAgain(CgFrame frame, boolean keepRequested) {
        canvas.endPass();
        return recording.includeAgain(frame, keepRequested);
    }

    private CgCanvas canvasIn(boolean scaledPixels) {
        if (scaledPixels != guiPixels) {
            guiPixels = scaledPixels;
            if (canvas.recording()) canvas.constants(canvasCamera());   // lands what was drawn under the other camera
        }
        return canvas;
    }

    private CgPassConstants canvasCamera() {
        CgCamera camera = camera();
        if (guiPixels) {
            float s = host.guiScale();
            guiScaled.set(camera).projection().scale(s, s, 1f);
            camera = guiScaled;
        }
        return hostConstants.seenThrough(camera, width(), height());
    }

    private void openCanvas(CgCanvas canvas) {
        canvas.recordInto(recording, target(), depth(), CgLoad.load(), canvasCamera());
        CgCamera camera = camera();
        canvas.origin(camera.x(), camera.y(), camera.z());
    }

    /** A renderer's turn starts: what it draws on the canvas records into this firing, in window pixels. */
    void turnBegins(CgRenderStage stage) {
        this.stage = stage;
        guiPixels = false;
        canvas.arm(canvasOpener);
    }

    /** A renderer's turn ends: what it drew on the canvas lands, in a pass of its own. */
    void turnEnds() {
        canvas.stop();
    }
}
