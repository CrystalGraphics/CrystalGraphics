package com.crystalgraphics.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorldSubsystem;

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
 * CgCameraPose view = cameras.pose();       // after SHAKE: what the host applies
 * }</pre>
 *
 * <ul>
 *   <li>Its rigs run in {@code CAMERA} and its modifiers in {@code SHAKE}, on the viewer's clock, never a character's.</li>
 *   <li>{@link #controls()} says whether anything moved the camera from the host's own: a host applies the pose only
 *       then.</li>
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
    private Host source = out -> {
    };
    private Predicate<Object> involved = owner -> true;
    @Nullable
    private CgCameraContext active = host;
    @Nullable
    private CgCameraRig rig;
    private CgTickFunction camera, shake;

    public CgCameraSystem() {
        contexts.add(host);
    }

    @Override
    protected void initialize() {
        if (world().server()) return;
        camera = world().schedule().add(CgTickGroup.CAMERA, "crystalgraphics:camera", this::evaluateRigs);
        shake = world().schedule().add(CgTickGroup.SHAKE, "crystalgraphics:camera.modifiers", this::applyModifiers);
    }

    @Override
    protected void deinitialize() {
        if (camera != null) camera.close();
        if (shake != null) shake.close();
    }

    /** Where the host's own camera is read from each frame. */
    public CgCameraSystem host(Host source) {
        this.source = Objects.requireNonNull(source, "source");
        return this;
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

    /** Whether a rig or a modifier moved the camera from the host's own this frame. */
    public boolean controls() {
        return stack.top() != null || stack.size() > 1 || !modifiers.isEmpty();
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
        for (int i = 0; i < modifiers.size(); i++) {
            CgCameraModifier m = modifiers.get(i);
            if (m.done()) {
                modifiers.remove(i--);
                continue;
            }
            m.apply(pose, dt);
        }
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
