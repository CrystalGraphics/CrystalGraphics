package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A skeletal mesh posed by this engine: the clips it plays as sections on a timeline of its own, and each frame the
 * pose at that time, its skinning matrices and the palette its draw names. Unreal's skeletal mesh component with
 * Sequencer's animation instance: sections cross-fade by their weights, normalised as Sequencer's
 * {@code MultiWayBlend} does (AnimNode_MultiWayBlend.cpp:69-78), the rest pose where none plays.
 *
 * <pre>{@code
 * CgAnimInstance kurama = CgAnimation.client().add(new CgAnimInstance(mesh));   // posed in POSE every frame
 * kurama.play(CgAnimSection.of(mesh.sequence("idle")).until(Double.POSITIVE_INFINITY).build());
 *
 * // in SUBMIT, the same frame:
 * world.draw(mesh.mesh(), skin).bones(kurama.palette()).at(x, y, z).submit();
 * }</pre>
 *
 * <p>Seeking: the pose is a pure function of the time, so a seek lands exactly where playing would.</p>
 *
 * <pre>{@code
 * kurama.seek(3.25);              // the same pose playing from 0 reaches at 3.25 s
 * kurama.playing(false);          // held there; advance() moves it by hand
 * }</pre>
 *
 * <ul>
 *   <li>Changed from the client world's thread (its schedule); evaluated on a worker inside {@code POSE}, never
 *       touched there by anything else.</li>
 *   <li>{@link #palette()} is valid the frame {@code POSE} wrote it; -1 before its first.</li>
 *   <li>Allocates nothing per frame once its sections are added.</li>
 * </ul>
 */
public final class CgAnimInstance {

    private final CgSkeletalMesh mesh;
    private final List<CgAnimSection> sections = new ArrayList<>();
    private final CgPose pose;
    private final Matrix4f[] skinning;
    private CgPose[] scratch = new CgPose[0];
    private float[] weights = new float[0];
    private double time;
    private float rate = 1f;
    private boolean playing = true;
    private int palette = -1;

    public CgAnimInstance(CgSkeletalMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.pose = new CgPose(mesh.skeleton());
        this.skinning = new Matrix4f[mesh.skeleton().boneCount()];
        for (int b = 0; b < skinning.length; b++) skinning[b] = new Matrix4f();
        pose.resolve();
        pose.skinning(mesh, skinning);
    }

    public CgSkeletalMesh mesh() {
        return mesh;
    }

    /** Adds a section to its timeline. */
    public CgAnimInstance play(CgAnimSection section) {
        sections.add(Objects.requireNonNull(section, "section"));
        int n = sections.size();
        if (scratch.length < n) {
            scratch = Arrays.copyOf(scratch, n);
            for (int i = 0; i < n; i++) if (scratch[i] == null) scratch[i] = new CgPose(mesh.skeleton());
            weights = Arrays.copyOf(weights, n);
        }
        return this;
    }

    public CgAnimInstance remove(CgAnimSection section) {
        sections.remove(section);
        return this;
    }

    /** Takes every section off its timeline, leaving its time. */
    public CgAnimInstance clear() {
        sections.clear();
        return this;
    }

    public List<CgAnimSection> sections() {
        return List.copyOf(sections);
    }

    /** Its timeline's time, seconds. */
    public double time() {
        return time;
    }

    /** Moves its timeline to {@code seconds}: the next pose is the one playing reaches there. */
    public CgAnimInstance seek(double seconds) {
        time = seconds;
        return this;
    }

    /** Moves its timeline by {@code seconds} times its rate: what {@code POSE} does each frame while it plays. */
    public CgAnimInstance advance(double seconds) {
        time += seconds * rate;
        return this;
    }

    /** Timeline seconds a second of play; 1 by default, 0 freezes it as {@code playing(false)} does. */
    public CgAnimInstance rate(float rate) {
        this.rate = rate;
        return this;
    }

    public float rate() {
        return rate;
    }

    /** Whether {@code POSE} advances it each frame. A paused instance is still posed at its time. */
    public CgAnimInstance playing(boolean playing) {
        this.playing = playing;
        return this;
    }

    public boolean playing() {
        return playing;
    }

    /**
     * Poses it at its time: each section's clip sampled at its clip time, the sections blended by their weights
     * normalised, then resolved and its skinning matrices made. What {@code POSE} runs; by hand for a tool or a test.
     */
    public CgAnimInstance evaluate() {
        int n = 0;
        float total = 0f;
        for (int s = 0; s < sections.size(); s++) {
            CgAnimSection section = sections.get(s);
            float w = section.weight(time);
            if (w <= 0f) continue;
            CgPose into = scratch[n].rest();
            section.sequence().sample(into, section.animationTime(time));
            weights[n++] = w;
            total += w;
        }
        if (n == 0) {
            pose.rest();
        } else {
            for (int i = 0; i < n; i++) weights[i] /= total;
            pose.blend(scratch, weights, n);
        }
        pose.resolve();
        pose.skinning(mesh, skinning);
        return this;
    }

    /** The pose {@link #evaluate()} last made: local and, resolved, in the mesh's space. */
    public CgPose pose() {
        return pose;
    }

    /** Its skinning matrices as of the last {@link #evaluate()}, one a bone. */
    public Matrix4f[] skinning() {
        return skinning;
    }

    /** Where its palette starts this frame, for its draw's {@code bones(base)}; -1 before {@code POSE} wrote one. */
    public int palette() {
        return palette;
    }

    void palette(int base) {
        palette = base;
    }

    @Override
    public String toString() {
        return "CgAnimInstance(" + mesh + " at " + time + ")";
    }
}
