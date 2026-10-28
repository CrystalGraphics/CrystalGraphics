package com.crystalgraphics.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSkeleton;

/**
 * A named slot blended over part of an instance's skeleton: Unreal's montage slot feeding a Layered blend per bone
 * (Kakarot's face: {@code FacialSlot} over the {@code facialbase} branch). What it plays replaces the body's pose on
 * the branch, and its material curves replace the body's ({@code CurveBlendOption MaxWeight}); with nothing playing,
 * the branch keeps the body's pose, as a slot whose source is the cached body pose does.
 *
 * <pre>{@code
 * CgAnimLayer face = goku.layer("FacialSlot", "facialbase", 1);
 * face.play(CgAnimSection.of(battleFace).until(Double.POSITIVE_INFINITY).loop().build());   // the role's default face
 * sequencer.play(move, Map.of("caster", goku));   // a section on slot FacialSlot plays here instead, over it
 * }</pre>
 *
 * <ul>
 *   <li>Its bone weights are Unreal's mask ({@code FAnimationRuntime::CreateMaskWeights}): a bone {@code d} levels below
 *       the branch takes {@code (d + 1) / blendDepth}, at most 1; a depth of 0 takes the whole branch at once.</li>
 *   <li>Changed on the client world's thread; posed with its instance in {@code POSE}. Allocates nothing per frame
 *       once its sections are added.</li>
 * </ul>
 */
public final class CgAnimLayer {

    private final String slot;
    final float[] boneWeights;
    private final List<CgAnimSection> sections = new ArrayList<>();
    final CgPose pose;
    private CgPose[] scratch = new CgPose[0];
    private float[] weights = new float[0];
    private CgAnimSequence[] clips = new CgAnimSequence[0];
    private float[] times = new float[0];
    private CgAnimSequence[] slotClips = new CgAnimSequence[0];
    private float[] slotTimes = new float[0], slotWeights = new float[0];
    private int slotCount;
    private final CgSkeleton skeleton;
    private boolean ownCurves = true;

    CgAnimLayer(CgSkeleton skeleton, String slot, String bone, int blendDepth) {
        this.skeleton = skeleton;
        this.slot = Objects.requireNonNull(slot, "slot");
        int branch = skeleton.indexOfIgnoringCase(bone);
        if (branch < 0) throw new IllegalArgumentException("layer " + slot + ": the skeleton has no bone " + bone);
        boneWeights = new float[skeleton.boneCount()];
        float perDepth = blendDepth != 0 ? 1f / blendDepth : 1f;
        for (int b = branch; b < boneWeights.length; b++) {
            int depth = 0;
            int at = b;
            while (at >= 0 && at != branch) {
                at = skeleton.parent(at);
                depth++;
            }
            if (at == branch) boneWeights[b] = Math.min(1f, perDepth * (depth + 1));
        }
        pose = new CgPose(skeleton);
        reserve(1);
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
        sections.add(Objects.requireNonNull(section, "section"));
        reserve(sections.size() + slotCount + 1);
        return this;
    }

    public CgAnimLayer clear() {
        sections.clear();
        return this;
    }

    /** This frame only: {@code clip} at {@code position} with {@code weight}, over its sections: a montage on the slot. */
    public CgAnimLayer slot(CgAnimSequence clip, float position, float weight) {
        if (slotCount == slotClips.length) {
            int n = Math.max(2, slotCount * 2);
            slotClips = Arrays.copyOf(slotClips, n);
            slotTimes = Arrays.copyOf(slotTimes, n);
            slotWeights = Arrays.copyOf(slotWeights, n);
        }
        slotClips[slotCount] = Objects.requireNonNull(clip, "clip");
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
     * Blends what it plays at {@code time} into {@code body} over its branch, its curves into {@code curves}: as the
     * instance's own sections and slot, normalised; nothing playing leaves the body as it is.
     */
    void apply(double time, CgPose body, CgMaterialCurves curves) {
        int n = 0;
        float total = 0f;
        for (int s = 0; s < sections.size(); s++) {
            CgAnimSection section = sections.get(s);
            float w = section.weight(time);
            if (w <= 0f) continue;
            n = add(body, section.sequence(), section.animationTime(time), w, n);
            total += w;
        }
        float slotTotal = 0f;
        for (int i = 0; i < slotCount; i++) slotTotal += Math.max(0f, slotWeights[i]);
        float source = Math.min(1f, Math.max(0f, 1f - slotTotal));
        for (int i = 0; i < n; i++) weights[i] = weights[i] / total * source;
        int fromSections = n;
        float scale = slotTotal > 1f ? 1f / slotTotal : 1f;
        for (int i = 0; i < slotCount; i++) {
            if (slotWeights[i] <= 0f) continue;
            n = add(body, slotClips[i], slotTimes[i], slotWeights[i] * scale, n);
        }
        Arrays.fill(slotClips, 0, slotCount, null);
        slotCount = 0;
        if (n == 0) return;
        if (fromSections == 0 && source > 0f) {
            scratch[n].set(body);   // the slot over the body where no section plays under it
            clips[n] = null;
            weights[n++] = source;
        }
        pose.blend(scratch, weights, n);
        body.blendBones(pose, boneWeights);
        if (ownCurves) {
            curves.beginLayer();
            for (int i = 0; i < n; i++) if (clips[i] != null) curves.addLayer(clips[i], times[i], weights[i]);
        }
        Arrays.fill(clips, 0, n, null);
    }

    private int add(CgPose body, CgAnimSequence clip, float at, float weight, int n) {
        clip.sample(scratch[n].set(body), at);
        clips[n] = clip;
        times[n] = at;
        weights[n] = weight;
        return n + 1;
    }
}
