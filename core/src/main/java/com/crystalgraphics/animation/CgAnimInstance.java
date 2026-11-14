package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMirrorTable;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What a skeletal mesh plays: clips as sections on a timeline of its own, montage slot entries, layers over a branch,
 * bone writes, and each frame the pose at its time with its skinning matrices. Unreal's {@code UAnimInstance} with
 * Sequencer's sections: sections cross-fade by their weights, normalised as Sequencer's {@code MultiWayBlend} does
 * (AnimNode_MultiWayBlend.cpp:69-78), the rest pose where none plays. Where it stands and what it is drawn with are its
 * component's ({@code CgSkeletalMeshComponent}), which makes one and hands it out:
 *
 * <pre>{@code
 * CgAnimInstance anim = goku.anim();                                    // goku: scene.skeletal(mesh)...spawn()
 * anim.play(CgAnimSection.of(mesh.sequence("idle")).loop().build());
 * anim.play(CgAnimSection.of(punch).at(anim.time()).until(anim.time() + 0.6).easeOut(0.1f).build());
 * anim.layer("FacialSlot", "facialbase", 1);                            // a slot over a branch
 * anim.modify("spine_02").rotation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.PARENT, twist);
 * anim.rate(0.5f);                                                      // slow motion
 * }</pre>
 *
 * <p>Headless, on a dedicated server, with no component: posed each game tick for its bones and sockets.</p>
 *
 * <pre>{@code
 * CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh));
 * body.socket(body.socket("hand_r"), handInMesh);   // this tick's pose, in the mesh's space
 * }</pre>
 *
 * <p>Seeking: the pose is a pure function of the time, so a seek lands exactly where playing would.</p>
 *
 * <pre>{@code
 * anim.seek(3.25);            // the pose playing from 0 reaches at 3.25 s
 * anim.playing(false);        // held there
 * }</pre>
 *
 * <ul>
 *   <li>Changed from the world's thread; evaluated on a worker inside {@code POSE}, never touched there by anything
 *       else.</li>
 *   <li>Allocates nothing per frame once its sections are added.</li>
 * </ul>
 */
public final class CgAnimInstance {

    /** {@link #socket(String)}'s answer for a name it has neither as a socket nor a bone: the mesh's origin. */
    public static final int NO_SOCKET = -1;
    private static final int SOCKET = 1 << 30;

    private final CgSkeletalMesh mesh;
    private boolean posed;
    private final List<CgAnimSection> sections = new ArrayList<>();
    private final List<CgMirrorTable> sectionMirrors = new ArrayList<>();
    private final CgPose pose;
    private final Matrix4f[] skinning;
    private final Matrix4f socketScratch = new Matrix4f();
    private final List<CgBoneWrite> writes = new ArrayList<>();
    private CgPose[] scratch = new CgPose[0];
    private float[] weights = new float[0];
    /** This frame's slot entries, cleared by {@link #evaluate()}: Unreal's montage slot. */
    private CgAnimSequence[] slotClips = new CgAnimSequence[0];
    private CgMirrorTable[] slotMirrors = new CgMirrorTable[0];
    private float[] slotTimes = new float[0], slotWeights = new float[0];
    private int slotCount;
    private double time;
    private float rate = 1f;
    private boolean playing = true;
    private final CgMaterialCurves curves = new CgMaterialCurves();
    @Nullable
    private CgAnimCurveTarget curveTarget;
    /** The clip and its time behind each of this frame's blended poses, for its curves. */
    private CgAnimSequence[] sampledClips = new CgAnimSequence[0];
    private float[] sampledTimes = new float[0];
    /** Its layered slots, blended over the body in the order added (Unreal's Layered blend per bone nodes). */
    private final List<CgAnimLayer> layers = new ArrayList<>();
    /** How many times it has been evaluated. */
    private long evaluations;

    public CgAnimInstance(CgSkeletalMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.pose = new CgPose(mesh.skeleton());
        this.skinning = new Matrix4f[mesh.skeleton().boneCount()];
        for (int b = 0; b < skinning.length; b++) skinning[b] = new Matrix4f();
        pose.resolve();
        pose.skinning(mesh, skinning);
        reserve();
    }

    public CgSkeletalMesh mesh() {
        return mesh;
    }

    /** Adds a section to its timeline. */
    public CgAnimInstance play(CgAnimSection section) {
        return play(section, null);
    }

    /**
     * Adds a section whose pose is mirrored by {@code mirror}, null for as authored: Unreal's mirror node over it.
     *
     * <pre>{@code
     * anim.play(CgAnimSection.of(swingRight).build(), mirror);   // authored for the right arm, swung with the left
     * }</pre>
     */
    public CgAnimInstance play(CgAnimSection section, @Nullable CgMirrorTable mirror) {
        sections.add(Objects.requireNonNull(section, "section"));
        sectionMirrors.add(mirror);
        reserve();
        return this;
    }

    /**
     * Plays {@code clip} at {@code position} seconds with {@code weight} over its own sections, this frame's pose only:
     * a montage slot's entry, what a sequence's {@code animation} track sets every frame it is inside a section
     * (Unreal's {@code UpdateAnimTrack}). Its sections keep {@code 1 - } the slot's total weight; past 1 the slot's
     * entries are normalised and its sections are gone (AnimInstanceProxy.cpp:2170, 2021-2033).
     *
     * <pre>{@code
     * anim.slot(roar, 0.4f, 1f);        // this frame: roar at 0.4 s, whatever its sections play
     * anim.slot(roar, 0.4f, 0.25f);     // a quarter roar over three quarters of its sections
     * }</pre>
     *
     * Entries are cleared by the next {@link #evaluate()}: set them every frame they should hold. Two set before it
     * both count.
     */
    public CgAnimInstance slot(CgAnimSequence clip, float position, float weight) {
        return slot(clip, position, weight, null);
    }

    /** As {@link #slot(CgAnimSequence, float, float)}, the clip's pose mirrored by {@code mirror} (null for as authored). */
    public CgAnimInstance slot(CgAnimSequence clip, float position, float weight, @Nullable CgMirrorTable mirror) {
        if (slotCount == slotClips.length) {
            int n = Math.max(2, slotCount * 2);
            slotClips = Arrays.copyOf(slotClips, n);
            slotMirrors = Arrays.copyOf(slotMirrors, n);
            slotTimes = Arrays.copyOf(slotTimes, n);
            slotWeights = Arrays.copyOf(slotWeights, n);
        }
        slotClips[slotCount] = Objects.requireNonNull(clip, "clip");
        slotMirrors[slotCount] = mirror;
        slotTimes[slotCount] = position;
        slotWeights[slotCount++] = weight;
        reserve();
        return this;
    }

    /** A scratch pose and weight for every section, slot entry and the rest pose. */
    private void reserve() {
        int n = sections.size() + slotCount + 1;
        if (scratch.length >= n) return;
        scratch = Arrays.copyOf(scratch, n);
        for (int i = 0; i < n; i++) if (scratch[i] == null) scratch[i] = new CgPose(mesh.skeleton());
        weights = Arrays.copyOf(weights, n);
        sampledClips = Arrays.copyOf(sampledClips, n);
        sampledTimes = Arrays.copyOf(sampledTimes, n);
    }

    public CgAnimInstance remove(CgAnimSection section) {
        int at = sections.indexOf(section);
        if (at >= 0) {
            sections.remove(at);
            sectionMirrors.remove(at);
        }
        return this;
    }

    /** Takes every section off its timeline, leaving its time. */
    public CgAnimInstance clear() {
        sections.clear();
        sectionMirrors.clear();
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
    CgAnimInstance advance(double seconds) {
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
     * normalised, under this frame's {@link #slot} entries, then its layers and bone writes; then resolved and its
     * skinning matrices made. What {@code POSE} runs; by hand for a tool or a test.
     */
    public CgAnimInstance evaluate() {
        float slotTotal = 0f;
        for (int i = 0; i < slotCount; i++) slotTotal += Math.max(0f, slotWeights[i]);
        float source = Math.min(1f, Math.max(0f, 1f - slotTotal));
        int n = 0;
        if (source > 0f) {
            float total = 0f;
            for (int s = 0; s < sections.size(); s++) {
                CgAnimSection section = sections.get(s);
                float w = section.weight(time);
                if (w <= 0f) continue;
                float at = section.animationTime(time);
                section.sequence().sample(scratch[n].rest(), at);
                if (sectionMirrors.get(s) != null) scratch[n].mirror(sectionMirrors.get(s));
                sampledClips[n] = section.sequence();
                sampledTimes[n] = at;
                weights[n++] = w;
                total += w;
            }
            if (n == 0) {
                scratch[0].rest();
                sampledClips[n] = null;
                weights[n++] = source;
            } else {
                for (int i = 0; i < n; i++) weights[i] = weights[i] / total * source;
            }
        }
        float scale = slotTotal > 1f ? 1f / slotTotal : 1f;
        for (int i = 0; i < slotCount; i++) {
            if (slotWeights[i] <= 0f) continue;
            slotClips[i].sample(scratch[n].rest(), slotTimes[i]);
            if (slotMirrors[i] != null) scratch[n].mirror(slotMirrors[i]);
            sampledClips[n] = slotClips[i];
            sampledTimes[n] = slotTimes[i];
            weights[n++] = slotWeights[i] * scale;
        }
        // Curves blend with the pose's weights (Unreal's BlendPosesTogether blends a pose's curves with it)
        curves.begin();
        for (int i = 0; i < n; i++) if (sampledClips[i] != null) curves.add(sampledClips[i], sampledTimes[i], weights[i]);
        Arrays.fill(sampledClips, 0, n, null);
        Arrays.fill(slotClips, 0, slotCount, null);
        Arrays.fill(slotMirrors, 0, slotCount, null);
        slotCount = 0;
        if (n == 0) {
            pose.rest();
        } else {
            pose.blend(scratch, weights, n);
        }
        for (int i = 0; i < layers.size(); i++) layers.get(i).apply(time, pose, curves);
        for (int i = 0; i < writes.size(); i++) {
            CgBoneWrite write = writes.get(i);
            if (write.needsComponent()) pose.resolve();
            write.apply(pose);
        }
        pose.resolve();
        pose.skinning(mesh, skinning);
        evaluations++;
        return this;
    }

    /**
     * A bone write on {@code bone}, applied every frame after the clips and layers in the order made ({@link
     * CgBoneWrite}, Unreal's modify bone). It does nothing until one of its parts is set.
     *
     * <pre>{@code
     * CgBoneWrite drop = anim.modify("model");
     * drop.translation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, 0f, -crouch, 0f);   // each frame
     * }</pre>
     *
     * @throws IllegalArgumentException where the skeleton has no {@code bone}
     */
    public CgBoneWrite modify(String bone) {
        CgBoneWrite write = new CgBoneWrite(bone(bone));
        writes.add(write);
        return write;
    }

    /** Stops applying {@code write}. */
    public CgAnimInstance remove(CgBoneWrite write) {
        writes.remove(write);
        return this;
    }

    /**
     * Bone {@code name}'s index, ignoring case.
     *
     * @throws IllegalArgumentException where the skeleton has none
     */
    public int bone(String name) {
        int b = mesh.skeleton().indexOfIgnoringCase(name);
        if (b < 0) throw new IllegalArgumentException(this + ": the skeleton has no bone " + name);
        return b;
    }

    /** The pose {@link #evaluate()} last made: local and, resolved, in the mesh's space. */
    public CgPose pose() {
        return pose;
    }

    /** Its skinning matrices as of the last {@link #evaluate()}, one a bone: the pose times the inverse bind. */
    public Matrix4f[] skinning() {
        return skinning;
    }

    /** How many times it has been evaluated: moves each time its pose and skinning do. */
    public long evaluations() {
        return evaluations;
    }

    /**
     * Where its clips' material curves are set each frame: its component's materials (a {@code CgSkeletalMeshComponent}
     * sets itself). Null, as on a server, evaluates them and sets nothing.
     */
    public CgAnimInstance curveTarget(@Nullable CgAnimCurveTarget target) {
        curveTarget = target;
        return this;
    }

    /** Applies this frame's material curves: after {@code POSE}'s evaluation, on the world's thread ({@link CgAnimation}). */
    void applyCurves() {
        if (curveTarget != null) curves.apply(curveTarget);
    }

    /**
     * A slot named {@code slot} blended over {@code bone}'s branch, the new one blended over those before it
     * ({@link CgAnimLayer}). Kakarot's face: {@code layer("FacialSlot", "facialbase", 1)}.
     *
     * @throws IllegalArgumentException where the skeleton has no {@code bone}, or a layer of that slot exists
     */
    public CgAnimLayer layer(String slot, String bone, int blendDepth) {
        bone(bone);   // before the layer is added
        return addLayer(slot).branch(bone, blendDepth);
    }

    /**
     * A slot named {@code slot} with an empty mask, blended over the layers before it: give it branches or bone weights.
     *
     * <pre>{@code
     * CgAnimLayer arms = anim.addLayer("Arms").branch("right_arm", 0).branch("left_arm", 0);
     * CgAnimLayer lean = anim.addLayer("Lean").bone("body", 0.5f).bone("head", 0.25f);
     * }</pre>
     *
     * @throws IllegalArgumentException where a layer of that slot exists
     */
    public CgAnimLayer addLayer(String slot) {
        if (layer(slot) != null) throw new IllegalArgumentException(this + " has a layer " + slot + " already");
        CgAnimLayer layer = new CgAnimLayer(mesh.skeleton(), slot);
        layers.add(layer);
        return layer;
    }

    /** Its layer for slot {@code slot}, or null: where a sequence's section on that slot plays. */
    @Nullable
    public CgAnimLayer layer(String slot) {
        for (int i = 0; i < layers.size(); i++) if (layers.get(i).slot().equals(slot)) return layers.get(i);
        return null;
    }

    // ---- sockets, in the mesh's space

    /**
     * The socket named {@code name}, as Unreal finds one (USkinnedMeshComponent::GetSocketTransform,
     * SkinnedMeshComponent.cpp:3525-3587): its mesh's socket of that name, else the bone of that name, ignoring case;
     * {@link #NO_SOCKET} for neither, the mesh's origin. Look it up once and keep it.
     *
     * <pre>{@code
     * int hand = anim.socket("hand_r");
     * anim.socket(hand, handInMesh);          // this frame's, once POSE has run
     * }</pre>
     */
    public int socket(String name) {
        List<CgSocket> sockets = mesh.sockets();
        for (int i = 0; i < sockets.size(); i++) if (sockets.get(i).name().equalsIgnoreCase(name)) return SOCKET | i;
        int bone = mesh.skeleton().indexOfIgnoringCase(name);
        return bone >= 0 ? bone : NO_SOCKET;
    }

    /**
     * Socket {@code socket} in the mesh's space into {@code out}, from the pose {@link #evaluate()} last made: its bone
     * times the socket's own transform; identity for {@link #NO_SOCKET}. Its component multiplies its placement in.
     */
    public Matrix4f socket(int socket, Matrix4f out) {
        if (socket == NO_SOCKET) return out.identity();
        if ((socket & SOCKET) != 0) {
            CgSocket s = mesh.sockets().get(socket & ~SOCKET);
            return out.set(pose.component(mesh.skeleton().indexOfIgnoringCase(s.bone()))).mul(s.relative(socketScratch));
        }
        return out.set(pose.component(socket));
    }

    /** Whether {@code CgAnimation} poses it: false once removed. */
    public boolean posed() {
        return posed;
    }

    void posed(boolean posed) {
        this.posed = posed;
    }

    @Override
    public String toString() {
        return "CgAnimInstance(" + mesh + " at " + time + ")";
    }
}
