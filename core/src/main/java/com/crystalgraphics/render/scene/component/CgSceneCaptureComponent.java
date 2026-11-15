package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.target.CgRenderTarget;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix3f;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A camera standing in a scene that draws what it sees into a render target, which a material then samples: Unreal's
 * {@code USceneCaptureComponent2D}. A mirror, a portal, a security camera, a minimap, an effect reading a copy of the
 * screen. It looks along its +x with +y up (the engine's camera convention), through a horizontal field of view.
 *
 * <pre>{@code
 * CgRenderTarget picture = CgRenderTarget.create("mymod:monitor", 256, 256, CgRenderTarget.Format.RGBA8);
 * CgSceneCaptureComponent camera = scene.capture().target(picture).fov(70f).everyFrame(true)
 *         .at(x, y, z).rotation(facing).spawn();
 * screenMaterial.texture("_Picture", picture.texture());   // the monitor shows what it sees
 * }</pre>
 *
 * <p>On demand, as Sparking! Zero's {@code BP_CopyCameraCapture}: the game's view copied, then captured once.</p>
 *
 * <pre>{@code
 * CgSceneCaptureComponent copy = scene.capture().target(frozen).source(CgSceneCaptureComponent.Source.FINAL_COLOR).spawn();
 * copy.copyCamera(CgView.main()).captureScene();          // the frame as the player sees it, at the next frame
 * }</pre>
 *
 * <ul>
 *   <li>It captures before its scene's first view of a frame (Unreal's scene capture update), when asked
 *       ({@link #captureScene}), every frame, or on a frame after it moved; never with no target. One asked for while
 *       a pipeline it draws with is still building draws again each frame until nothing is left out.</li>
 *   <li>{@link Source#SCENE_COLOR_HDR}, Unreal's default, is the scene before post, linear: give it a float target.
 *       {@link Source#FINAL_COLOR} is the frame after post, as the screen shows it.</li>
 *   <li>Its {@link #view()} is a view like any: show flags, flag overrides, and the {@code hide} and {@code showOnly}
 *       lists (Unreal's hidden and show-only components) are set on it.</li>
 *   <li>It draws nothing itself. Render thread, as its scene.</li>
 * </ul>
 */
public final class CgSceneCaptureComponent extends CgPrimitiveComponent<CgSceneCaptureComponent> {

    /** What it writes into its target: two of Unreal's {@code ESceneCaptureSource}. */
    public enum Source {
        /** The scene's colour before post, linear: {@code SCS_SceneColorHDR}. */
        SCENE_COLOR_HDR,
        /** The frame after post, as the screen shows it: {@code SCS_FinalColorLDR}. */
        FINAL_COLOR
    }

    /** Its near plane, in blocks; it has no far plane. An orthographic one sees this deep. */
    private static final float NEAR = 0.05f, ORTHOGRAPHIC_DEPTH = 16_384f;

    private final CgView view;
    @Nullable
    private CgRenderTarget target;
    private float fov = CgCameraPose.DEFAULT_FOV, orthographicWidth;
    private Source source = Source.SCENE_COLOR_HDR;
    private boolean everyFrame, onMovement, pending, moved;
    private long captures;
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f forward = new Vector3f(), up = new Vector3f(), side = new Vector3f();
    private final Matrix4f look = new Matrix4f(), lens = new Matrix4f();
    private final Matrix3f basis = new Matrix3f();

    CgSceneCaptureComponent(CgScenePrimitives primitives, CgScene scene) {
        super(primitives);
        view = CgView.of(scene);
        view.showFlags().post(false);
    }

    /** What it draws into; null draws nothing. */
    public CgSceneCaptureComponent target(@Nullable CgRenderTarget target) {
        this.target = target;
        return this;
    }

    @Nullable
    public CgRenderTarget target() {
        return target;
    }

    /** Its horizontal field of view in degrees, 90 unless set: Unreal's {@code FOVAngle}. Perspective again. */
    public CgSceneCaptureComponent fov(float degrees) {
        if (!(degrees > 0f && degrees < 180f)) throw new IllegalArgumentException("a field of view of " + degrees + " degrees");
        fov = degrees;
        orthographicWidth = 0f;
        return this;
    }

    public float fov() {
        return fov;
    }

    /** Orthographic, {@code width} blocks across, its height by its target's shape: Unreal's {@code OrthoWidth}. */
    public CgSceneCaptureComponent orthographic(float width) {
        if (!(width > 0f)) throw new IllegalArgumentException("an orthographic width of " + width);
        orthographicWidth = width;
        return this;
    }

    /** What it writes: {@link Source#SCENE_COLOR_HDR} unless set. Sets its view's post show flag to match. */
    public CgSceneCaptureComponent source(Source source) {
        this.source = Objects.requireNonNull(source, "source");
        view.showFlags().post(source == Source.FINAL_COLOR);
        return this;
    }

    public Source source() {
        return source;
    }

    /** Captures every frame: Unreal's {@code bCaptureEveryFrame}. */
    public CgSceneCaptureComponent everyFrame(boolean on) {
        everyFrame = on;
        return this;
    }

    /** Captures on the frame after it moves: Unreal's {@code bCaptureOnMovement}. */
    public CgSceneCaptureComponent onMovement(boolean on) {
        onMovement = on;
        return this;
    }

    /** Captures once, before its scene's next frame: Unreal's {@code CaptureScene}. */
    public CgSceneCaptureComponent captureScene() {
        pending = true;
        return this;
    }

    /**
     * Stands it where {@code from}'s camera stands, looking where it looks through the same field of view or width, and
     * takes its show flags and overrides: Unreal's {@code CopyCameraSettingsToSceneCapture}, what
     * {@code BP_CopyCameraCapture} does before it captures. A snapshot: the camera moving on leaves it where it was.
     *
     * @throws IllegalStateException where it rides something: the camera's place is the world's
     */
    public CgSceneCaptureComponent copyCamera(CgView from) {
        if (attachParent() != null) throw new IllegalStateException(this + " rides " + attachParent() + ": copyCamera places it in the world");
        CgCamera camera = from.camera();
        Matrix4fc v = camera.view(), p = camera.projection();
        // A view matrix's rows are the camera's right, up and back in the world; its turn sends +x forward and +y up
        forward.set(-v.m02(), -v.m12(), -v.m22()).normalize();
        up.set(v.m01(), v.m11(), v.m21()).normalize();
        forward.cross(up, side);
        basis.setColumn(0, forward).setColumn(1, up).setColumn(2, side).getNormalizedRotation(turn);
        at(camera.x(), camera.y(), camera.z()).rotation(turn);
        if (p.m23() == 0f) orthographic(2f / p.m00());
        else fov((float) Math.toDegrees(2.0 * Math.atan(1.0 / p.m00())));
        view.copySettings(from);
        view.showFlags().post(source == Source.FINAL_COLOR);
        return this;
    }

    /** The view it draws through: its show flags, overrides, and hidden and show-only lists. */
    public CgView view() {
        return view;
    }

    /** How many times it has drawn into its target. */
    public long captures() {
        return captures;
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    boolean due() {
        return target != null && (pending || everyFrame || onMovement && moved);
    }

    /** Draws its view into its target now, through its place as of the last {@code ATTACH}. */
    void capture() {
        CgRenderTarget into = Objects.requireNonNull(target);
        Matrix4dc m = worldTransform();
        m.getNormalizedRotation(turn);
        turn.transform(forward.set(1f, 0f, 0f));
        turn.transform(up.set(0f, 1f, 0f));
        look.setLookAlong(forward, up);
        float aspect = (float) into.width() / into.height();
        if (orthographicWidth > 0f) {
            float w = orthographicWidth * 0.5f, h = w / aspect;
            CgCamera.orthographicMatrix(lens, -w, w, -h, h, 0f, ORTHOGRAPHIC_DEPTH);
        } else {
            float fovY = (float) Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(fov) * 0.5) / aspect));
            CgCamera.perspectiveMatrix(lens, fovY, aspect, NEAR, Float.POSITIVE_INFINITY);
        }
        view.camera().set(m.m30(), m.m31(), m.m32(), look, lens);
        long unready = CgViewPasses.unreadyDraws();
        view.renderInto(into.beginDraw(true));
        into.drawn();
        // A draw left out while its pipeline builds would leave a capture taken once with a hole: asked again until whole
        if (CgViewPasses.unreadyDraws() == unready) pending = moved = false;
        captures++;
    }

    @Override
    void onSpawn() {
        scene.captures.add(this);
        moved = true;
    }

    @Override
    void onRemove() {
        scene.captures.remove(this);
    }

    @Override
    void onMoved() {
        moved = true;
    }

    @Override
    void onLooked() {
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    @Override
    public String toString() {
        return "CgSceneCaptureComponent(" + target + ")";
    }
}
