package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.graph.CgGraphTexture;

/**
 * Where the scene was bent from this firing: up to {@link #MAX} RGBA16F offset targets, each added into by its own
 * hazes, so their sum at a texel is that point's whole bend: xy in UV units, z the split times w its weight. The world
 * renderer publishes it under {@link CgFrameKeys#DISTORTION} once it has applied it to the target.
 *
 * <pre>{@code
 * // A post effect bends a side input of its own (emission, a mask) as the scene was bent:
 * CgGraphTexture emission = post.distorted(post.resources().get(CgFrameKeys.EMISSION));
 *
 * // Or reads the offsets itself, summing every target:
 * CgDistortionField field = post.resources().get(CgFrameKeys.DISTORTION);
 * for (int i = 0; i < field.count(); i++) sampler(i, field.offsets(i));
 * }</pre>
 *
 * <ul>
 *   <li>One instance, refilled every firing: read it during the firing, never keep it.</li>
 *   <li>The targets are {@code CgWorldRenderer.distortionScale} of the target's size; sample them by normalized uv.</li>
 * </ul>
 */
public final class CgDistortionField {

    /** The most targets a field holds. */
    public static final int MAX = 5;

    private final CgGraphTexture[] offsets = new CgGraphTexture[MAX];
    private int count;

    /** How many targets it holds, at least 1 once published. */
    public int count() {
        return count;
    }

    /** Target {@code i}, below {@link #count()}. */
    public CgGraphTexture offsets(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException("target " + i + " of " + count);
        return offsets[i];
    }

    /** Empties it, for the producer to refill. */
    public void clear() {
        count = 0;
    }

    /** Adds a target the producer drew offsets into. */
    public void add(CgGraphTexture target) {
        if (count == MAX) throw new IllegalStateException("a distortion field holds at most " + MAX + " targets");
        offsets[count++] = target;
    }
}
