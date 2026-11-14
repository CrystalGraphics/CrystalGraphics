package com.crystalgraphics.animation.graph;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMirrorTable;

/**
 * A slot's entries this frame: the clips a montage or a sequence's animation section plays there, each at a position
 * and a weight. Unreal's montage slot as its slot node reads it ({@code FAnimInstanceProxy::GetSlotWeight},
 * {@code SlotEvaluatePose}). A graph's {@link CgSlotNode} blends them over its source.
 *
 * <pre>{@code
 * CgAnimSlot slot = anim.slot("DefaultSlot");      // null where the graph has no such slot
 * slot.play(roar, 0.4f, 1f);                       // this frame: roar at 0.4 s, whatever the graph plays under it
 * slot.play(roar, 0.4f, 0.25f);                    // a quarter roar over three quarters of the source
 * anim.slot("FacialSlot").stop(1f);                // its entries blended out: the source alone
 * }</pre>
 *
 * <ul>
 *   <li>Entries are this frame's: set them every frame they should hold. The evaluate clears them.</li>
 *   <li>Past a total weight of 1 the entries are normalised and the source is gone (AnimInstanceProxy.cpp:2103,
 *       {@code GetSlotWeight}).</li>
 *   <li>Set on the game thread, before the frame's update.</li>
 * </ul>
 */
public final class CgAnimSlot {

    private final String name;
    CgAnimSequence[] clips = new CgAnimSequence[2];
    CgMirrorTable[] mirrors = new CgMirrorTable[2];
    float[] positions = new float[2], weights = new float[2];
    int count;
    float stop;

    CgAnimSlot(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** {@code clip} at {@code position} seconds with {@code weight}, this frame. */
    public CgAnimSlot play(CgAnimSequence clip, float position, float weight) {
        return play(clip, position, weight, null);
    }

    /** As {@link #play(CgAnimSequence, float, float)}, its pose mirrored by {@code mirror} (null as authored). */
    public CgAnimSlot play(CgAnimSequence clip, float position, float weight, @Nullable CgMirrorTable mirror) {
        if (count == clips.length) {
            int n = count * 2;
            clips = Arrays.copyOf(clips, n);
            mirrors = Arrays.copyOf(mirrors, n);
            positions = Arrays.copyOf(positions, n);
            weights = Arrays.copyOf(weights, n);
        }
        clips[count] = Objects.requireNonNull(clip, "clip");
        mirrors[count] = mirror;
        positions[count] = position;
        weights[count++] = weight;
        return this;
    }

    /**
     * This frame, the slot's entries blend out by {@code weight}, 0 to 1, and its source shows through: Unreal's
     * {@code StopSlotAnimation}, its montages blending out (Kakarot's {@code FacialOff}). A full stop leaves the source
     * alone; stops add, at most 1.
     */
    public CgAnimSlot stop(float weight) {
        stop += Math.max(0f, weight);
        return this;
    }

    /** What a stop leaves of each entry's weight. */
    float kept() {
        return 1f - Math.min(1f, stop);
    }

    /** The entries' total weight after the stop: Unreal's slot node weight before normalising. */
    float total() {
        float total = 0f;
        for (int i = 0; i < count; i++) total += Math.max(0f, weights[i]);
        return total * kept();
    }

    void clear() {
        Arrays.fill(clips, 0, count, null);
        Arrays.fill(mirrors, 0, count, null);
        count = 0;
        stop = 0f;
    }
}
