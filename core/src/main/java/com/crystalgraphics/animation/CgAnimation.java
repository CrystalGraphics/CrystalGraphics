package com.crystalgraphics.animation;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.job.CgJob;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Poses every {@link CgAnimInstance} of a world once a frame, in {@code POSE}: each advanced by the frame's time and
 * evaluated, split across workers by {@code CgJob.forEach} once there are enough to pay for waking them, then each
 * one's palette written on the schedule's thread, ready for draws submitted later in the frame. Unreal's parallel
 * animation evaluation: sockets ({@code ATTACH}) and draws ({@code SUBMIT}) read this frame's pose.
 *
 * <pre>{@code
 * CgAnimInstance kurama = CgAnimation.client().add(new CgAnimInstance(mesh));
 * kurama.play(CgAnimSection.of(mesh.sequence("roar")).build());
 * // ... later, SUBMIT: world.draw(mesh.mesh(), skin).bones(kurama.palette()).at(x, y, z).submit();
 * CgAnimation.client().remove(kurama);
 * }</pre>
 *
 * <ul>
 *   <li>Add, remove and change instances on the client world's thread, outside {@code POSE}.</li>
 *   <li>{@code crystalgraphics.animation.parallelAt} (8): the instances from which evaluation fans out; fewer run on
 *       the schedule's thread.</li>
 * </ul>
 */
public final class CgAnimation extends CgWorldSubsystem {

    /** Instances from which a frame's evaluation is split across workers. */
    public static final int PARALLEL_AT = Integer.getInteger("crystalgraphics.animation.parallelAt", 8);

    private final List<CgAnimInstance> instances = new ArrayList<>();
    private final IntConsumer evaluateEach = i -> instances.get(i).evaluate();
    private CgTickFunction pose;

    /** The client world's, added on first use. */
    public static CgAnimation client() {
        CgWorld world = CgWorld.client();
        CgAnimation existing = world.subsystem(CgAnimation.class);
        return existing != null ? existing : world.add(new CgAnimation());
    }

    @Override
    protected void initialize() {
        pose = world().schedule().add(CgTickGroup.POSE, "crystalgraphics:animation", this::pose).workers(true);
    }

    @Override
    protected void deinitialize() {
        pose.close();
    }

    /** Poses {@code instance} every frame from now on; answers it. */
    public CgAnimInstance add(CgAnimInstance instance) {
        if (!instances.contains(instance)) instances.add(instance);
        return instance;
    }

    public void remove(CgAnimInstance instance) {
        instances.remove(instance);
    }

    public int size() {
        return instances.size();
    }

    private void pose(float dt) {
        int n = instances.size();
        for (int i = 0; i < n; i++) {
            CgAnimInstance instance = instances.get(i);
            if (instance.playing()) instance.advance(dt);
        }
        if (n >= PARALLEL_AT) {
            CgJob.forEach(n, evaluateEach);
        } else {
            for (int i = 0; i < n; i++) instances.get(i).evaluate();
        }
        for (int i = 0; i < n; i++) {
            CgAnimInstance instance = instances.get(i);
            instance.palette(CgBonePalettes.write(instance.skinning(), instance.skinning().length));
        }
    }
}
