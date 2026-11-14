package com.crystalgraphics.render.view;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.host.CgHostEnvironment;
import com.crystalgraphics.render.host.CgHostTextures;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.host.CgWorldAtmosphere;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One camera's picture of a scene, frame after frame (Unreal's {@code FSceneView} and its {@code FSceneViewState}): its
 * camera, its size, the world's sky and light it is drawn under, its scene textures this frame and what it keeps across
 * frames ({@link #state()}). The game's is {@link #main()}; a portal, an anaglyph's second eye or an editor's viewport
 * is a view of its own.
 *
 * <pre>{@code
 * CgCamera world = CgView.main().camera();                 // where the game sees from, as of its latest frame
 * CgHostEnvironment sky = CgView.main().environment();     // its sun, fog and weather
 *
 * // A host drawing the world twice a frame, once per eye: a view for the second, made once
 * static final CgView RIGHT_EYE = CgView.create("anaglyph.right");
 * CgStageHost.frame(CgStageHost.WORLD_OPAQUE).view(eye == 0 ? CgView.main() : RIGHT_EYE);
 * }</pre>
 *
 * <pre>{@code
 * // A stage handing a later stage of the same view a value this frame
 * frame.view().publish(MASK, mask);
 * CgGraphTexture mask = frame.view().read(MASK);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread.</li>
 *   <li>Make a view once and keep it: each holds its own histories, and one made per frame keeps none.</li>
 *   <li>A view draws once a frame: a second firing of a world callback in one frame for the same view draws as
 *       {@link #unkept()}.</li>
 *   <li>The main view's camera, size and environment are its host's, refilled each frame: read them, never set them.</li>
 * </ul>
 */
public final class CgView {

    /** Every view made, held weakly: what {@link #forgetAll} clears. */
    private static final Set<CgView> ALL = Collections.newSetFromMap(new WeakHashMap<>());
    private static final CgView MAIN = new CgView("main", null, true);
    private static final CgView UNKEPT = new CgView("unkept", null, false);
    /** How far behind an orthographic eye a pick starts, in blocks. */
    private static final float PICK_REACH = 10_000f;
    @Nullable
    private CgViewPicture picture;
    private final Matrix4f pickInverse = new Matrix4f();
    private final Vector3f pickPoint = new Vector3f();

    /** How far the camera may move in a frame, and turn (as the cosine), and still be the last frame's view. */
    private static final double TELEPORT = 8.0;
    private static final float TURN_COS = 0.866f, PROJECTION_JUMP = 0.1f;

    private final String name;
    /** Its scene; null for the client's, which is made after the views. */
    @Nullable
    private final CgScene scene;
    private final CgViewState state;
    private final CgCamera camera = new CgCamera(this::writeState);
    private final CgHostEnvironment environment = new CgHostEnvironment();
    private final CgHostTextures hostTextures = new CgHostTextures();
    private final CgSceneTextures textures = new CgSceneTextures();
    private final Map<CgViewKey<?>, Object> published = new IdentityHashMap<>();
    private final Map<CgVar<?>, Object> overrides = new IdentityHashMap<>();
    private final CgShowFlags showFlags = new CgShowFlags();
    private int width, height;
    private long frame = Long.MIN_VALUE, draws;

    // Last frame's camera, which this frame's is compared with (Unreal's FPreviousViewInfo)
    private final Matrix4f previous = new Matrix4f(), viewProjection = new Matrix4f(), lastViewProjection = new Matrix4f();
    private double lastX, lastY, lastZ;
    private float lens00, lens11, lens22;
    private int lastWidth, lastHeight;
    @Nullable
    private Object lastTarget;
    private long lastFrame = Long.MIN_VALUE;
    private boolean historyValid;

    private CgView(String name, @Nullable CgScene scene, boolean keepsHistory) {
        this.name = name;
        this.scene = scene;
        this.state = new CgViewState(keepsHistory);
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    /** Engine side, at context teardown: every view's state forgets what it kept, whose storage goes with the context. */
    public static void forgetAll() {
        synchronized (ALL) {
            for (CgView view : ALL) view.state.forget();
        }
    }

    /** The game's view of the client scene: what the hosts' world callbacks draw unless a host names another. */
    public static CgView main() {
        return MAIN;
    }

    /** A view of the client scene of the host's own, keeping its own histories: an anaglyph eye, a portal. Make it once. */
    public static CgView create(String name) {
        return new CgView(name, null, true);
    }

    /** A view of {@code scene}: an editor's viewport, a preview, a capture. Make it once and keep it. */
    public static CgView of(CgScene scene) {
        return new CgView("view", Objects.requireNonNull(scene, "scene"), true);
    }

    /**
     * The view a firing draws as when it is nobody's: a second firing of a world callback in one frame for a view that
     * already drew. It hands one firing's work to the next of its frame and keeps no history.
     */
    public static CgView unkept() {
        return UNKEPT;
    }

    /**
     * Engine side: the unkept view standing in for {@code named}'s repeat draw, with its flags, overrides and show
     * flags: what it does not keep is histories, never how the view draws.
     */
    public static CgView unkeptFor(CgView named) {
        UNKEPT.overrides.clear();
        UNKEPT.overrides.putAll(named.overrides);
        UNKEPT.showFlags.set(named.showFlags);
        return UNKEPT;
    }

    public String name() {
        return name;
    }

    /** The scene it sees. */
    public CgScene scene() {
        return scene != null ? scene : CgScene.client();
    }

    /** What it keeps across its firings and frames. */
    public CgViewState state() {
        return state;
    }

    /**
     * Where it sees from, with this frame's time and its size: the host's camera for the main view, camera-relative as
     * the host draws its world. A pass given it takes the view's sun and fog, its last frame's camera and the scene's
     * encoding too. One object, refilled each frame.
     *
     * <pre>{@code
     * rec.raster("mymod.glow").color(frame.target(), CgLoad.load()).camera(frame.view().camera());
     * if (CgView.main().camera().project(x, y, z, w, h, at)) { ... }
     * }</pre>
     */
    public CgCamera camera() {
        return camera;
    }

    /** Its width in pixels: the host target's for the main view. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The world's sky, weather and fog, the player's settings and the clock it is drawn under. */
    public CgHostEnvironment environment() {
        return environment;
    }

    /** The host's lightmap and block atlas, borrowed for sampling; 0 where the host gives none. */
    public CgHostTextures hostTextures() {
        return hostTextures;
    }

    /** Its scene textures this frame: colour, depth, normals, custom depth and data, the depth pyramid. */
    public CgSceneTextures textures() {
        return textures;
    }

    /** Hands {@code value} to a later stage of this view this frame, under {@code key}. */
    public <T> void publish(CgViewKey<T> key, T value) {
        published.put(key, value);
    }

    /** What an earlier stage of this view published under {@code key} this frame, or null. */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T read(CgViewKey<T> key) {
        return (T) published.get(key);
    }

    /** What this view draws: lights, media, post, labels and the rest, each on unless turned off here. */
    public CgShowFlags showFlags() {
        return showFlags;
    }

    /**
     * Sets {@code flag} for this view alone over the value its level and the player's settings give it: an editor's
     * slider, a preview without half-size smoke. {@link #clearOverride} hands it back.
     *
     * <pre>{@code
     * labView.override(CgSceneRenderer.SCENE_EMISSION, 1.5f);      // the lab's glow; the game's view keeps its own
     * labView.override(CgSceneRenderer.HALF_RESOLUTION, false);
     * }</pre>
     */
    public CgView override(CgVar.Bool flag, boolean value) {
        overrides.put(flag, value);
        return this;
    }

    public CgView override(CgVar.Num flag, float value) {
        overrides.put(flag, value);
        return this;
    }

    public CgView override(CgVar.Int flag, int value) {
        overrides.put(flag, value);
        return this;
    }

    /** {@code flag} back to its level's value for this view. */
    public CgView clearOverride(CgVar<?> flag) {
        overrides.remove(flag);
        return this;
    }

    /** {@code flag} as this view reads it: its override, else the flag's value. What the renderer reads, never a setter. */
    public boolean flag(CgVar.Bool flag) {
        Object o = overrides.get(flag);
        return o != null ? (Boolean) o : flag.get();
    }

    public float flag(CgVar.Num flag) {
        Object o = overrides.get(flag);
        return o != null ? (Float) o : flag.get();
    }

    public int flag(CgVar.Int flag) {
        Object o = overrides.get(flag);
        return o != null ? (Integer) o : flag.get();
    }

    /**
     * Whether last frame's picture of this view is one a temporal effect may blend over ({@code CG_HISTORY_VALID}): false
     * after a teleport (8 blocks), a turn past 30 degrees, a change of projection, target or size, or a skipped frame.
     */
    public boolean historyValid() {
        return historyValid;
    }

    /** Last frame's view-projection of this frame's camera-relative points: what reprojects a history. */
    public Matrix4f previousViewProjection() {
        return previous;
    }

    /**
     * Draws this view's whole frame into {@code target} now: both world callbacks, its stages and post, through its own
     * camera at the target's size. An editor's viewport, a portal, a capture; the game's view is the hosts' to draw.
     *
     * <pre>{@code
     * CgView portal = CgView.of(CgScene.client());                      // made once
     * portal.camera().set(x, y, z, viewRotation, projection);           // each frame, before drawing it
     * portal.renderInto(portalFramebuffer.external());
     * }</pre>
     *
     * <ul>
     *   <li>Render thread. Inside a stage firing too: the view's frame executes there and then, into its own target.</li>
     *   <li>A view draws once a frame: drawing it twice in one frame keeps no history the second time.</li>
     * </ul>
     */
    public void renderInto(CgExternalTarget target) {
        if (this == MAIN) throw new IllegalStateException("the main view is drawn by its host's world callbacks");
        CgStageHost.renderView(this, target);
    }

    /**
     * Draws the view whole into a picture of its own, {@code width} x {@code height} pixels cleared to {@code argb}
     * first, and answers its colour: Unreal's scene capture into its render target. The picture is kept from frame to
     * frame and remade only when the size changes.
     *
     * <pre>{@code
     * CgTexture picture = viewport.renderPicture(w, h, 0xFF3D3D3D);   // each frame, on the render thread
     * ui.drawImage(picture, x, y, w, h, 0f, 1f, 1f, 0f);              // its origin is bottom left: v flipped
     * frame.canvas().view(viewport, w, h).at(x, y).size(w, h).submit();   // or as a canvas item, flipped for you
     * viewport.releasePicture();                                        // when the viewport goes
     * }</pre>
     *
     * <ul>
     *   <li>Not the main view: its host draws it.</li>
     *   <li>The texture answered is replaced when the size changes; read it again each frame.</li>
     * </ul>
     */
    public CgTexture2D renderPicture(int width, int height, int argb) {
        if (picture == null) picture = new CgViewPicture("cg_view." + name);
        renderInto(picture.begin(width, height, argb));
        return picture.colour();
    }

    /** Frees {@link #renderPicture}'s picture. Render thread; the next call makes it again. */
    public void releasePicture() {
        if (picture != null) picture.release();
        picture = null;
    }

    /**
     * What of its scene is under pixel {@code (x, y)} of its picture (top left the origin, in its size's pixels): the
     * nearest visible primitive whose world box the ray through that pixel meets, through its camera as last set; null
     * for none. An editor's click selection, as Godot's editor casts it.
     *
     * <pre>{@code
     * CgPrimitiveComponent<?> clicked = viewport.pick(mouseX, mouseY);
     * if (clicked == rock) select(rock);
     * }</pre>
     *
     * <ul>
     *   <li>A box hit, not a triangle: a primitive's box can hide one behind it inside the box.</li>
     *   <li>Render thread, as its scene. A view never drawn has no size: it picks nothing.</li>
     * </ul>
     */
    @Nullable
    public CgPrimitiveComponent<?> pick(float x, float y) {
        if (width <= 0 || height <= 0) return null;
        float nx = 2f * (x + 0.5f) / width - 1f, ny = 1f - 2f * (y + 0.5f) / height;
        Matrix4fc projection = camera.projection();
        pickInverse.set(projection).mul(camera.view()).invert();   // camera-relative clip space to camera-relative world
        Matrix4fc view = camera.view();
        float fx = -view.m02(), fy = -view.m12(), fz = -view.m22();   // the camera's forward
        pickInverse.transformProject(nx, ny, 0.5f, pickPoint);       // mid-depth in either depth convention
        double ox, oy, oz, dx, dy, dz;
        if (projection.m23() != 0f) {                                // perspective: from the eye through the pixel
            ox = oy = oz = 0.0;
            dx = pickPoint.x;
            dy = pickPoint.y;
            dz = pickPoint.z;
            if (dx * fx + dy * fy + dz * fz < 0.0) {
                dx = -dx;
                dy = -dy;
                dz = -dz;
            }
        } else {                                                    // orthographic: along the forward, from behind the eye
            dx = fx;
            dy = fy;
            dz = fz;
            ox = pickPoint.x - fx * PICK_REACH;
            oy = pickPoint.y - fy * PICK_REACH;
            oz = pickPoint.z - fz * PICK_REACH;
        }
        return scene().pick(ox + camera.x(), oy + camera.y(), oz + camera.z(), dx, dy, dz);
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    /** Engine side, the hosts': its size this frame. */
    public CgView size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * Engine side, a world callback's at its start: the view's frame {@code hostFrame} begins with its camera as set,
     * drawn into {@code target} (compared by identity: a changed target is no history). The first call of a frame
     * advances its state, compares the camera with last frame's and drops last frame's textures and values; a later
     * one of the same frame does nothing.
     */
    /**
     * Engine side, at a draw's first callback (the opaque one, or {@code renderInto}): what its stages hand on starts
     * empty and what it sees is made again, so a view drawn twice in one host frame (a test, a capture) sees the second
     * time's scene. The translucent callback continues the draw.
     */
    public void beginDraw() {
        draws++;
        textures.reset();
        published.clear();
    }

    /** Engine side: how many draws it has begun, what its visibility is kept by. */
    public long draws() {
        return draws;
    }

    public void beginFrame(long hostFrame, @Nullable Object target) {
        camera.time(scene().clock().seconds()).resolution(width, height);
        if (hostFrame == frame) return;
        frame = hostFrame;
        state.begin(hostFrame);
        textures.reset();
        published.clear();
        remember(hostFrame, target);
    }

    /** Compares this frame's camera with last frame's, then keeps it for the next. */
    private void remember(long hostFrame, @Nullable Object target) {
        viewProjection.set(camera.projection()).mul(camera.view());
        historyValid = false;
        if (!state.keepsHistory()) {
            lastFrame = hostFrame;
            return;
        }
        double dx = camera.x() - lastX, dy = camera.y() - lastY, dz = camera.z() - lastZ;
        if (lastFrame == hostFrame - 1 && lastTarget == target && lastWidth == width && lastHeight == height
                && dx * dx + dy * dy + dz * dz < TELEPORT * TELEPORT && sameLens()) {
            previous.set(lastViewProjection).translate((float) dx, (float) dy, (float) dz);
            historyValid = forward() >= TURN_COS;
        }
        lastViewProjection.set(viewProjection);
        lens00 = camera.projection().m00();
        lens11 = camera.projection().m11();
        lens22 = camera.projection().m22();
        lastX = camera.x();
        lastY = camera.y();
        lastZ = camera.z();
        lastTarget = target;
        lastWidth = width;
        lastHeight = height;
        lastFrame = hostFrame;
    }

    private boolean sameLens() {
        float p00 = camera.projection().m00(), p11 = camera.projection().m11(), p22 = camera.projection().m22();
        return Math.abs(p00 - lens00) < PROJECTION_JUMP * Math.abs(p00)
                && Math.abs(p11 - lens11) < PROJECTION_JUMP * Math.abs(p11)
                && Math.signum(p22) == Math.signum(lens22);
    }

    /** The cosine between last frame's forward and this one's: the w row of a view-projection is the forward. */
    private float forward() {
        float ax = viewProjection.m03(), ay = viewProjection.m13(), az = viewProjection.m23();
        float bx = lastViewProjection.m03(), by = lastViewProjection.m13(), bz = lastViewProjection.m23();
        float n = (float) Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
        return n > 0f ? (ax * bx + ay * by + az * bz) / n : 0f;
    }

    /** What the view adds to a pass of its camera: its scene, the scene's encoding, last frame's view, the sun and fog. */
    private void writeState(CgPassConstants constants) {
        constants.scene(scene().gpu()).linearScene(textures.linear()).history(previous, state.frameIndex(), historyValid);
        CgWorldAtmosphere.apply(environment, constants);
    }

    @Override
    public String toString() {
        return name;
    }
}
