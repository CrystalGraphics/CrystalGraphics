package com.crystalgraphics.animation;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgWorldQueries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import org.joml.Matrix4f;

/**
 * Poses every {@link CgAnimInstance} of a world: each advanced by the world's time and evaluated, split across workers
 * by {@code CgJob.forEach} once there are enough to pay for waking them. On a client, once a frame in {@code POSE},
 * each one's palette is then written on the schedule's thread, ready for draws submitted later in the frame. Unreal's
 * parallel animation evaluation with its default {@code AlwaysTickPoseAndRefreshBones}: every instance is posed whether
 * or not it is drawn, so sockets ({@code ATTACH}) and draws ({@code SUBMIT}) read this frame's pose.
 *
 * <pre>{@code
 * CgAnimInstance kurama = CgAnimation.client().add(new CgAnimInstance(mesh));
 * kurama.play(CgAnimSection.of(mesh.sequence("roar")).build());
 * // ... later, SUBMIT: world.draw(mesh.mesh(), skin).bones(kurama.palette()).at(x, y, z).submit();
 * CgAnimation.client().remove(kurama);
 * }</pre>
 *
 * <p>On a dedicated server, posed each game tick in {@code GAMEPLAY}, for its bones and sockets; nothing is drawn:</p>
 *
 * <pre>{@code
 * CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh));
 * body.socket(body.socket("hand_r"), point, null);   // this tick's pose
 * }</pre>
 *
 * <ul>
 *   <li>Add, remove and change instances on the world's thread, outside its posing.</li>
 *   <li>An instance {@linkplain CgAnimInstance#attachTo attached} to another is posed after it, from its socket this
 *       frame, as Unreal's tick prerequisite orders an attached component.</li>
 *   <li>While the game is paused every instance holds its time and is still posed, so a paused frame draws.</li>
 *   <li>A new level or dimension drops the client's instances: what follows their sockets stops.</li>
 *   <li>{@code crystalgraphics.animation.parallelAt} (8): the instances from which evaluation fans out; fewer run on
 *       the schedule's thread.</li>
 * </ul>
 */
public final class CgAnimation extends CgWorldSubsystem {

    /** Instances from which a frame's evaluation is split across workers. */
    public static final int PARALLEL_AT = Integer.getInteger("crystalgraphics.animation.parallelAt", 8);
    private static final int EVALUATE = CgTrace.name("animation.evaluate"), PALETTES = CgTrace.name("animation.palettes"),
            CURVES = CgTrace.name("animation.curves");

    private final List<CgAnimInstance> instances = new ArrayList<>();
    private final IntConsumer evaluateEach = i -> instances.get(i).evaluate();
    private CgTickFunction pose;
    private int levelEpoch;
    /** The attachment depth {@link #evaluateAtDepth} poses this pass. */
    private int depth;
    private final IntConsumer evaluateAtDepth = i -> {
        CgAnimInstance instance = instances.get(i);
        if (instance.depth != depth) return;
        instance.follow();
        instance.evaluate();
    };

    /** The client world's, added on first use. */
    public static CgAnimation client() {
        return of(CgWorld.client());
    }

    /** A dedicated server's world's, added on first use; ended with the server's session. */
    public static CgAnimation server() {
        return of(CgWorld.serverWorld());
    }

    private static CgAnimation of(CgWorld world) {
        CgAnimation existing = world.subsystem(CgAnimation.class);
        return existing != null ? existing : world.add(new CgAnimation());
    }

    @Override
    protected void initialize() {
        if (world().server()) {
            pose = world().schedule().add(CgTickGroup.GAMEPLAY, "crystalgraphics:animation", this::poseHeadless)
                    .onServer(true).workers(true);
        } else {
            levelEpoch = CgWorldQueries.client().levelEpoch();
            pose = world().schedule().add(CgTickGroup.POSE, "crystalgraphics:animation", this::pose)
                    .workers(true).whilePaused(true);
        }
    }

    @Override
    protected void deinitialize() {
        pose.close();
        for (int i = 0; i < instances.size(); i++) instances.get(i).posed(false);
        instances.clear();
    }

    /** Poses {@code instance} every frame from now on; answers it. */
    public CgAnimInstance add(CgAnimInstance instance) {
        if (!instances.contains(instance)) instances.add(instance);
        instance.posed(true);
        return instance;
    }

    /** Stops posing {@code instance}: effects following one of its sockets stop. */
    public void remove(CgAnimInstance instance) {
        if (instances.remove(instance)) instance.posed(false);
    }

    public int size() {
        return instances.size();
    }

    private void advanceAndEvaluate(float dt) {
        int n = instances.size();
        int deepest = 0;
        boolean paused = world().paused();
        for (int i = 0; i < n; i++) {
            CgAnimInstance instance = instances.get(i);
            if (!paused && instance.playing()) instance.advance(dt);
            deepest = Math.max(deepest, instance.depthFromParents());
        }
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, EVALUATE)) {
            if (deepest == 0) {
                if (n >= PARALLEL_AT) {
                    CgJob.forEach(n, evaluateEach);
                } else {
                    for (int i = 0; i < n; i++) instances.get(i).evaluate();
                }
            } else {
                // Parents before children, as Unreal's tick prerequisite orders them: each depth placed from the
                // sockets the one above just posed, then evaluated.
                for (int d = 0; d <= deepest; d++) {
                    depth = d;
                    if (n >= PARALLEL_AT) {
                        CgJob.forEach(n, evaluateAtDepth);
                    } else {
                        for (int i = 0; i < n; i++) evaluateAtDepth.accept(i);
                    }
                }
            }
        }
    }

    private void poseHeadless(float dt) {
        advanceAndEvaluate(dt);
    }

    private void pose(float dt) {
        followLevel();
        advanceAndEvaluate(dt);
        int n = instances.size();
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, PALETTES)) {
            for (int i = 0; i < n; i++) {
                CgAnimInstance instance = instances.get(i);
                Matrix4f[] owner = instance.ownerSkinning();
                int base = CgBonePalettes.write(instance.skinning(), instance.skinning().length);
                instance.palette(base, owner == null ? -1 : CgBonePalettes.write(owner, owner.length));
            }
        }
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, CURVES)) {
            for (int i = 0; i < n; i++) instances.get(i).applyCurves();   // on this thread, as Unreal applies them after evaluation
        }
    }

    /** Drops every instance when the level changed, as the world's effects are cleared. */
    private void followLevel() {
        int now = CgWorldQueries.client().levelEpoch();
        if (now == levelEpoch) return;
        levelEpoch = now;
        for (int i = 0; i < instances.size(); i++) instances.get(i).posed(false);
        instances.clear();
    }
}
