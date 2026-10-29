package com.crystalgraphics.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3d;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.world.CgCamera;
import com.crystalgraphics.world.CgEntityQueries;

/**
 * A viewer's camera: Gameplay Cameras' {@code FCameraSystemEvaluator}. A context stack with the host's own camera at
 * the bottom, the active context's director choosing a rig, a transient blend stack blending rigs in and out, and a
 * persistent stack of additive modifiers, composed each frame into one {@link #pose()}.
 *
 * <pre>{@code
 * CgCameraSystem cameras = CgWorld.client().add(new CgCameraSystem());
 * cameras.host(pose -> pose.location(eye.x, eye.y, eye.z).fov(hostFov).rotation().set(hostTurn));   // the host's camera, each frame
 *
 * CgCameraContext move = new CgCameraContext("mymod:ultimate").director(CgCameraDirector.single(rig));
 * cameras.push(move);                       // the rig blends in by its enter transition
 * cameras.remove(move);                     // and out to the host by its exit transition
 * CgCameraPose view = cameras.pose();       // after SHAKE: what the host was handed
 * }</pre>
 *
 * <ul>
 *   <li>Its rigs run in {@code CAMERA} and its modifiers in {@code SHAKE}, on the viewer's clock, never a character's.</li>
 *   <li>While {@link #controls()}, the pose is handed to the host each frame after {@code SHAKE} ({@link CgCamera#hold}),
 *       which locks the player's look and movement out; the frame it stops, the camera is released.</li>
 *   <li>A rig whose {@code requires} the host cannot apply plays by its {@code on_missing}: {@code refuse} leaves the
 *       host its own camera (and says so once), {@code drop_roll} plays it level, {@code offset_only} keeps the host's
 *       own position.</li>
 *   <li>A held position stays within the render distance, less a chunk, of the {@link #anchor} (the local player):
 *       the client has no chunks past it. A rig that goes further is held at that edge, and the log says so once.</li>
 *   <li>Client worlds only; on a server it schedules nothing.</li>
 * </ul>
 */
public final class CgCameraSystem extends CgWorldSubsystem {

    /** The host's own camera this frame, into {@code out}. */
    @FunctionalInterface
    public interface Host {
        void read(CgCameraPose out);
    }

    private final CgCameraContext host = new CgCameraContext("host");
    private final List<CgCameraContext> contexts = new ArrayList<>();
    private final CgCameraBlendStack stack = new CgCameraBlendStack();
    private final List<CgCameraModifier> modifiers = new ArrayList<>();
    private final CgCameraPose pose = new CgCameraPose();
    /** Where the viewer's world is loaded around, into {@code out}: false when there is none this frame. */
    @FunctionalInterface
    public interface Anchor {
        boolean read(Vector3d out);
    }

    private Host source = out -> {
    };
    private Anchor anchor = CgCameraSystem::localPlayer;
    private final Vector3d around = new Vector3d();
    private static final double[] PLAYER = new double[CgEntityQuery.POSE_LENGTH];
    private Predicate<Object> involved = owner -> true;
    @Nullable
    private CgCameraContext active = host;
    @Nullable
    private CgCameraRig rig;
    private CgTickFunction camera, shake;
    @Nullable
    private CgCameraRigDefinition refused;
    private boolean warnedHide, warnedReach;

    /** Blocks inside the render distance a held pose keeps: one chunk, which the client may not have yet. */
    private static final double REACH_MARGIN = 16;
    private static final Logger LOGGER = LogManager.getLogger("CgCamera");

    public CgCameraSystem() {
        contexts.add(host);
    }

    @Override
    protected void initialize() {
        if (world().server()) return;
        camera = world().schedule().add(CgTickGroup.CAMERA, "crystalgraphics:camera", this::evaluateRigs);
        // While paused the pose stays as it was and is still handed over: a held camera stays held through a pause.
        shake = world().schedule().add(CgTickGroup.SHAKE, "crystalgraphics:camera.modifiers", this::applyModifiers)
                .whilePaused(true);
    }

    @Override
    protected void deinitialize() {
        if (camera != null) camera.close();
        if (shake != null) shake.close();
        CgCamera.release();
    }

    /** Where the host's own camera is read from each frame. */
    public CgCameraSystem host(Host source) {
        this.source = Objects.requireNonNull(source, "source");
        return this;
    }

    /**
     * Where the viewer's world is loaded around, which a held position stays within reach of: the local player unless
     * set. A server loads chunks around its player, never around the camera.
     *
     * <pre>{@code
     * cameras.anchor(out -> { out.set(spectated.x, spectated.y, spectated.z); return true; });   // a spectator's target
     * }</pre>
     */
    public CgCameraSystem anchor(Anchor anchor) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        return this;
    }

    /** The client's local player, where the frame draws it. Render thread only: one scratch pose. */
    private static boolean localPlayer(Vector3d out) {
        CgEntityQuery entities = CgEntityQueries.client();
        int player = entities.localPlayer();
        if (player < 0 || !entities.pose(player, CgRenderStage.WORLD_OPAQUE.host().partialTick(), PLAYER)) return false;
        out.set(PLAYER[CgEntityQuery.X], PLAYER[CgEntityQuery.Y], PLAYER[CgEntityQuery.Z]);
        return true;
    }

    /** The host's context, at the bottom: its pose is the host's camera. */
    public CgCameraContext hostContext() {
        return host;
    }

    /** Pushes {@code context} on top: its director's rig blends in. */
    public CgCameraSystem push(CgCameraContext context) {
        Objects.requireNonNull(context, "context");
        if (context == host) throw new IllegalArgumentException("the host context is always at the bottom");
        contexts.remove(context);
        contexts.add(context);
        return this;
    }

    /** Takes {@code context} out of the stack: what is then on top blends back in by the leaving rig's exit. */
    public CgCameraSystem remove(CgCameraContext context) {
        if (context != host) contexts.remove(context);
        return this;
    }

    /** Adds {@code modifier} to the persistent stack, after those already there. */
    public CgCameraSystem add(CgCameraModifier modifier) {
        modifiers.add(Objects.requireNonNull(modifier, "modifier"));
        return this;
    }

    public CgCameraSystem remove(CgCameraModifier modifier) {
        modifiers.remove(modifier);
        return this;
    }

    /**
     * Whether this viewer is in {@code owner}'s camera at all, a sequence's play: a play whose camera does not involve
     * the viewer never takes it. Every play involves it until set.
     */
    public CgCameraSystem involved(Predicate<Object> involved) {
        this.involved = Objects.requireNonNull(involved, "involved");
        return this;
    }

    public boolean involves(Object owner) {
        return involved.test(owner);
    }

    /**
     * The weight at which {@code owner}'s camera (a sequence's play) is in this viewer's view this frame, 0 to 1: its
     * contexts' share of the blended rigs, or its modifiers'. What that camera's own post settings blend at, as
     * {@code APlayerCameraManager} blends a view target camera's {@code PostProcessSettings}. 0 for a camera not in the
     * view. Read once a frame, after {@code SEQUENCES}; allocates nothing.
     */
    public float weightOf(Object owner) {
        float w = stack.weightOf(owner);
        for (int i = 0; i < modifiers.size(); i++) {
            CgCameraModifier m = modifiers.get(i);
            if (m.owner() == owner) w = Math.max(w, m.weight());
        }
        return Math.min(1f, w);
    }

    /** This frame's camera, composed: valid after {@code SHAKE}. */
    public CgCameraPose pose() {
        return pose;
    }

    /** Whether a context above the host's, a rig or a modifier moved the camera from the host's own this frame. */
    public boolean controls() {
        // A rigless context popped in leaves one entry with no rig, so the stack alone cannot tell it from the host.
        return active != host || stack.top() != null || stack.size() > 1 || !modifiers.isEmpty();
    }

    /** Both phases at once, for a host or a test with no schedule: the rigs, then the modifiers. */
    public void update(float dt) {
        evaluateRigs(dt);
        applyModifiers(dt);
    }

    /** {@code UpdateImpl}: the director of the active context, then the transient stack. */
    private void evaluateRigs(float dt) {
        source.read(host.initial());
        // The host's own camera is always the bottom entry, so the first rig blends in over it.
        if (stack.size() == 0) stack.push(null, host);
        CgCameraContext top = contexts.get(contexts.size() - 1);
        CgCameraRig wanted = top.director().rig();
        if (top != active || wanted != rig) {
            stack.push(wanted, top);
            active = top;
            rig = wanted;
        }
        stack.run(dt, pose);
    }

    private void applyModifiers(float dt) {
        // Paused, the rigs did not run: the pose is last frame's, its modifiers already in it.
        if (world() != null && world().paused()) {
            present();
            return;
        }
        for (int i = 0; i < modifiers.size(); i++) {
            CgCameraModifier m = modifiers.get(i);
            if (m.done()) {
                modifiers.remove(i--);
                continue;
            }
            m.apply(pose, dt);
        }
        present();
    }

    /** Hands the pose to the host while this system controls the camera, and gives it back once it does not. */
    private void present() {
        if (!controls()) {
            CgCamera.release();
            return;
        }
        CgCameraRigDefinition d = rig == null ? null : rig.definition();
        int missing = d == null ? 0 : parts(d) & ~CgCamera.capabilities();
        String on = d == null ? "" : d.onMissing();
        if (missing != 0 && "refuse".equals(on)) {
            if (refused != d) {
                refused = d;
                LOGGER.warn("Camera rig {} needs {} this host cannot apply; the host keeps its camera", d.id(), d.requires());
            }
            CgCamera.release();
            return;
        }
        int flags = flags();
        if ((flags & CgHostCamera.HIDE_PLAYER) != 0 && (CgCamera.capabilities() & CgHostCamera.HIDES_PLAYER) == 0 && !warnedHide) {
            warnedHide = true;
            LOGGER.warn("A play asks its viewer's player hidden; this host cannot hide it, so the player stays drawn");
        }
        boolean keepPosition = missing != 0 && "offset_only".equals(on);
        CgCameraPose at = keepPosition ? host.initial() : pose;
        double x = at.location().x, z = at.location().z;
        // The client has chunks only within its render distance of the player: a pose past the last full chunk would
        // see the world end, so it is drawn back toward the player.
        double reach = CgRenderStage.WORLD_OPAQUE.host().environment().renderDistance() - REACH_MARGIN;
        double dx = 0, dz = 0, d2 = 0;
        if (reach > 0 && anchor.read(around)) {
            dx = x - around.x;
            dz = z - around.z;
            d2 = dx * dx + dz * dz;
        }
        if (reach > 0 && d2 > reach * reach) {
            double s = reach / Math.sqrt(d2);
            x = around.x + dx * s;
            z = around.z + dz * s;
            if (!warnedReach) {
                warnedReach = true;
                LOGGER.warn("Camera rig {} went {} blocks from the player, past the {} the client has loaded; held at the edge",
                        d == null ? "(none)" : d.id(), (int) Math.sqrt(d2), (int) reach);
            }
        }
        CgCamera.hold(x, at.location().y, z, pose.rotation(), pose.fov(), (missing & CgHostCamera.ROLL) == 0, flags);
    }

    /**
     * What the plays moving the view ask of the viewer, every context above the host's and every modifier together:
     * overlapping plays release the viewer's HUD and player only once none asks.
     */
    private int flags() {
        int f = 0;
        for (int i = 1; i < contexts.size(); i++) f |= contexts.get(i).flags();
        for (int i = 0; i < modifiers.size(); i++) f |= modifiers.get(i).flags();
        return f;
    }

    /** {@code d}'s {@code requires} as {@link CgHostCamera} capability bits. */
    static int parts(CgCameraRigDefinition d) {
        int bits = 0;
        for (int i = 0; i < d.requires().size(); i++) {
            switch (d.requires().get(i)) {
                case "rotation": bits |= CgHostCamera.ROTATION; break;
                case "roll": bits |= CgHostCamera.ROLL; break;
                case "fov": bits |= CgHostCamera.FOV; break;
                case "position": bits |= CgHostCamera.POSITION; break;
                case "lock": bits |= CgHostCamera.LOCK; break;
                default: throw new IllegalStateException("Camera rig " + d.id() + ": unknown requires " + d.requires().get(i));
            }
        }
        return bits;
    }

    /** The transient stack's depth: rigs blending in, and what they still cover. */
    public int blending() {
        return stack.size();
    }

    /** The top rig's blend factor this frame. */
    public float blendFactor() {
        return stack.topFactor();
    }
}
