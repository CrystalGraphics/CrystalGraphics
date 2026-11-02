package com.crystalgraphics.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMirrorTable;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSkeleton;

/**
 * A named slot blended over part of an instance's skeleton: Unreal's montage slot feeding a Layered blend per bone
 * (Kakarot's face: {@code FacialSlot} over the {@code facialbase} branch). What it plays replaces the body's pose where
 * its mask reaches, by the mask's weight times the layer's, and its material curves replace the body's
 * ({@code CurveBlendOption MaxWeight}); with nothing playing, the body's pose stays.
 *
 * <pre>{@code
 * CgAnimLayer face = goku.layer("FacialSlot", "facialbase", 1);
 * face.play(CgAnimSection.of(battleFace).until(Double.POSITIVE_INFINITY).loop().build());   // the role's default face
 * sequencer.play(move, Map.of("caster", goku));   // a section on slot FacialSlot plays here instead, over it
 * }</pre>
 *
 * <p>A mask of several branches, or of a weight per bone (Unreal's blend mask), and a weight for the whole layer:</p>
 *
 * <pre>{@code
 * CgAnimLayer arms = role.addLayer("Arms").branch("right_arm", 0).branch("left_arm", 0);
 * CgAnimLayer lean = role.addLayer("Lean").bone("body", 0.5f).bone("head", 0.25f);
 * arms.weight(0.6f);                                       // each frame, as the code driving it decides
 * arms.slot(swing, phase, 1f, mirror);                     // the right arm's swing played on the left
 * }</pre>
 *
 * <ul>
 *   <li>A branch is Unreal's {@code FBranchFilter} ({@code FAnimationRuntime::CreateMaskWeights},
 *       AnimationRuntime.cpp:2421-2466): a bone {@code d} levels below takes {@code (d + 1) / blendDepth}, 0 taking the
 *       whole branch at once; branches add, at most 1. A bone weight is a blend mask entry (2468-2504), replacing what
 *       the branches gave that bone.</li>
 *   <li>A bone's weight is the layer's weight times its mask weight ({@code UpdateDesiredBoneWeight}, 1821-1840).</li>
 *   <li>Changed on the client world's thread; posed with its instance in {@code POSE}. Allocates nothing per frame
 *       once its sections are added.</li>
 * </ul>
 */
public final class CgAnimLayer {

    private final String slot;
    final float[] boneWeights;
    private final float[] effective;
    private final List<CgAnimSection> sections = new ArrayList<>();
    private final List<CgMirrorTable> sectionMirrors = new ArrayList<>();
    final CgPose pose;
    private CgPose[] scratch = new CgPose[0];
    private float[] weights = new float[0];
    private CgAnimSequence[] clips = new CgAnimSequence[0];
    private float[] times = new float[0];
    private CgAnimSequence[] slotClips = new CgAnimSequence[0];
    private CgMirrorTable[] slotMirrors = new CgMirrorTable[0];
    private float[] slotTimes = new float[0], slotWeights = new float[0];
    private int slotCount;
    /** This frame's {@link #stop} weight. */
    private float stopWeight;
    private float weight = 1f;
    private final CgSkeleton skeleton;
    private boolean ownCurves = true;

    CgAnimLayer(CgSkeleton skeleton, String slot) {
        this.skeleton = skeleton;
        this.slot = Objects.requireNonNull(slot, "slot");
        boneWeights = new float[skeleton.boneCount()];
        effective = new float[boneWeights.length];
        pose = new CgPose(skeleton);
        reserve(1);
    }

    /**
     * Adds {@code bone}'s branch to its mask: the bone and every bone below it, ramping in over {@code blendDepth}
     * levels (0: all at once).
     *
     * @throws IllegalArgumentException where the skeleton has no {@code bone}
     */
    public CgAnimLayer branch(String bone, int blendDepth) {
        int branch = bone(bone);
        float perDepth = blendDepth != 0 ? 1f / blendDepth : 1f;
        for (int b = branch; b < boneWeights.length; b++) {
            int depth = 0;
            int at = b;
            while (at >= 0 && at != branch) {
                at = skeleton.parent(at);
                depth++;
            }
            if (at == branch) boneWeights[b] = Math.min(1f, Math.max(0f, boneWeights[b] + perDepth * (depth + 1)));
        }
        return this;
    }

    /** Sets {@code bone}'s mask weight alone, 0 to 1: a blend mask entry. */
    public CgAnimLayer bone(String bone, float weight) {
        boneWeights[bone(bone)] = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    /** The whole layer's weight, 0 to 1, scaling its mask: Unreal's {@code BlendWeights}. 1 by default. */
    public CgAnimLayer weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    public float weight() {
        return weight;
    }

    private int bone(String name) {
        int b = skeleton.indexOfIgnoringCase(name);
        if (b < 0) throw new IllegalArgumentException("layer " + slot + ": the skeleton has no bone " + name);
        return b;
    }

    /**
     * Its bones over the body, its curves not: Unreal's {@code CurveBlendOption UseBaseOnly} (Kakarot's lip-sync layer
     * at {@code fbLowerFace}). By default its curves replace the body's ({@code MaxWeight}).
     */
    public CgAnimLayer curvesFromBase() {
        ownCurves = false;
        return this;
    }

    /** The slot name a sequence's section names to play here. */
    public String slot() {
        return slot;
    }

    /** Plays {@code section} on the instance's timeline, as {@link CgAnimInstance#play} does. */
    public CgAnimLayer play(CgAnimSection section) {
        return play(section, null);
    }

    /** Plays {@code section}, its pose mirrored by {@code mirror} (null for as authored). */
    public CgAnimLayer play(CgAnimSection section, @Nullable CgMirrorTable mirror) {
        sections.add(Objects.requireNonNull(section, "section"));
        sectionMirrors.add(mirror);
        reserve(sections.size() + slotCount + 1);
        return this;
    }

    public CgAnimLayer clear() {
        sections.clear();
        sectionMirrors.clear();
        return this;
    }

    /**
     * This frame only: the slot plays nothing at {@code weight}, the body showing through over its sections: Unreal's
     * {@code StopSlotAnimation} (Kakarot's {@code FacialOff}).
     */
    public CgAnimLayer stop(float weight) {
        stopWeight += Math.max(0f, weight);
        return this;
    }

    /** This frame only: {@code clip} at {@code position} with {@code weight}, over its sections: a montage on the slot. */
    public CgAnimLayer slot(CgAnimSequence clip, float position, float weight) {
        return slot(clip, position, weight, null);
    }

    /** As {@link #slot(CgAnimSequence, float, float)}, the clip's pose mirrored by {@code mirror}. */
    public CgAnimLayer slot(CgAnimSequence clip, float position, float weight, @Nullable CgMirrorTable mirror) {
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
        reserve(sections.size() + slotCount + 1);
        return this;
    }

    private void reserve(int n) {
        if (scratch.length >= n) return;
        scratch = Arrays.copyOf(scratch, n);
        for (int i = 0; i < n; i++) if (scratch[i] == null) scratch[i] = new CgPose(skeleton);
        weights = Arrays.copyOf(weights, n);
        clips = Arrays.copyOf(clips, n);
        times = Arrays.copyOf(times, n);
    }

    /**
     * Blends what it plays at {@code time} into {@code body} over its mask, its curves into {@code curves}: as the
     * instance's own sections and slot, normalised; nothing playing leaves the body as it is.
     */
    void apply(double time, CgPose body, CgMaterialCurves curves) {
        int n = 0;
        float total = 0f;
        for (int s = 0; s < sections.size(); s++) {
            CgAnimSection section = sections.get(s);
            float w = section.weight(time);
            if (w <= 0f) continue;
            n = add(body, section.sequence(), section.animationTime(time), w, sectionMirrors.get(s), n);
            total += w;
        }
        // A stop is a montage of the body itself: it weighs against the slot's montages and its own sections alike
        float slotTotal = stopWeight;
        for (int i = 0; i < slotCount; i++) slotTotal += Math.max(0f, slotWeights[i]);
        float source = Math.min(1f, Math.max(0f, 1f - slotTotal));
        for (int i = 0; i < n; i++) weights[i] = weights[i] / total * source;
        int fromSections = n;
        float scale = slotTotal > 1f ? 1f / slotTotal : 1f;
        for (int i = 0; i < slotCount; i++) {
            if (slotWeights[i] <= 0f) continue;
            n = add(body, slotClips[i], slotTimes[i], slotWeights[i] * scale, slotMirrors[i], n);
        }
        Arrays.fill(slotClips, 0, slotCount, null);
        Arrays.fill(slotMirrors, 0, slotCount, null);
        slotCount = 0;
        float bodyWeight = stopWeight * scale + (fromSections == 0 ? source : 0f);
        stopWeight = 0f;
        if (n == 0 || weight <= 0f) {
            Arrays.fill(clips, 0, n, null);
            return;   // only the body: nothing to blend
        }
        if (bodyWeight > 0f) {
            scratch[n].set(body);   // the body showing through: stopped, or no section under the slot
            clips[n] = null;
            weights[n++] = bodyWeight;
        }
        pose.blend(scratch, weights, n);
        float[] mask = boneWeights;
        if (weight < 1f) {
            for (int b = 0; b < mask.length; b++) effective[b] = mask[b] * weight;
            mask = effective;
        }
        body.blendBones(pose, mask);
        if (ownCurves) {
            curves.beginLayer();
            for (int i = 0; i < n; i++) if (clips[i] != null && weights[i] > 0f) curves.addLayer(clips[i], times[i], weights[i] * weight);
        }
        Arrays.fill(clips, 0, n, null);
    }

    private int add(CgPose body, CgAnimSequence clip, float at, float weight, @Nullable CgMirrorTable mirror, int n) {
        if (mirror == null) {
            clip.sample(scratch[n].set(body), at);
        } else {
            // The clip's own pose mirrored, as Unreal's mirror node takes it: what it leaves unkeyed is the rest pose
            clip.sample(scratch[n].rest(), at);
            scratch[n].mirror(mirror);
        }
        clips[n] = clip;
        times[n] = at;
        weights[n] = weight;
        return n + 1;
    }
}
