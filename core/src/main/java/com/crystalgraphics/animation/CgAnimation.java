package com.crystalgraphics.animation;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgWorldQueries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Poses every {@link CgAnimInstance} of a world: each advanced by the world's time and evaluated, split across workers
 * by {@code CgJob.forEach} once there are enough to pay for waking them, then its material curves applied. On a
 * client, once a frame in {@code POSE}; skeletal mesh components place themselves and write their palettes from these
 * poses in {@code ATTACH}. Unreal's parallel animation evaluation with its default
 * {@code AlwaysTickPoseAndRefreshBones}: every instance is posed whether or not it is drawn.
 *
 * <pre>{@code
 * CgSkeletalMeshComponent kurama = CgScene.client().skeletal(mesh).materials(skins::get).spawn();   // added here
 * kurama.anim().play(CgAnimSection.of(mesh.sequence("roar")).build());
 * }</pre>
 *
 * <p>On a dedicated server, posed each game tick in {@code GAMEPLAY}, for its bones and sockets; nothing is drawn:</p>
 *
 * <pre>{@code
 * CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh));
 * body.socket(body.socket("hand_r"), handInMesh);   // this tick's pose
 * CgAnimation.server().remove(body);
 * }</pre>
 *
 * <ul>
 *   <li>Add, remove and change instances on the world's thread, outside its posing.</li>
 *   <li>A pose depends on no placement, so instances evaluate in any order, in parallel.</li>
 *   <li>While the game is paused every instance holds its time and is still posed, so a paused frame draws.</li>
 *   <li>A new level or dimension drops the client's instances.</li>
 *   <li>{@code crystalgraphics.animation.parallelAt} (8): the instances from which evaluation fans out; fewer run on
 *       the schedule's thread.</li>
 * </ul>
 */
public final class CgAnimation extends CgWorldSubsystem {

    /** Instances from which a frame's evaluation is split across workers. */
    public static final int PARALLEL_AT = Integer.getInteger("crystalgraphics.animation.parallelAt", 8);
    private static final int EVALUATE = CgTrace.name("animation.evaluate"), CURVES = CgTrace.name("animation.curves");

    private final List<CgAnimInstance> instances = new ArrayList<>();
    private final IntConsumer evaluateEach = i -> instances.get(i).evaluate();
    private CgTickFunction pose;
    private int levelEpoch;

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
            pose = world().schedule().add(CgTickGroup.GAMEPLAY, "crystalgraphics:animation", this::advanceAndEvaluate)
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

    /** Stops posing {@code instance}. */
    public void remove(CgAnimInstance instance) {
        if (instances.remove(instance)) instance.posed(false);
    }

    public int size() {
        return instances.size();
    }

    private void advanceAndEvaluate(float dt) {
        int n = instances.size();
        boolean paused = world().paused();
        for (int i = 0; i < n; i++) {
            CgAnimInstance instance = instances.get(i);
            if (!paused && instance.playing()) instance.advance(dt);
        }
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, EVALUATE)) {
            if (n >= PARALLEL_AT) {
                CgJob.forEach(n, evaluateEach);
            } else {
                for (int i = 0; i < n; i++) instances.get(i).evaluate();
            }
        }
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, CURVES)) {
            for (int i = 0; i < n; i++) instances.get(i).applyCurves();   // on this thread, as Unreal applies them after evaluation
        }
    }

    private void pose(float dt) {
        followLevel();
        advanceAndEvaluate(dt);
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
