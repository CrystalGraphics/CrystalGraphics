package com.crystalgraphics.render.scene.renderer.feature.distortion;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.view.CgViewKey;

/**
 * Where the scene was bent from this firing: an RGBA16F array of {@link #MAX} layers, the first {@link #count()} each
 * added into by its own hazes, so their sum at a texel is that point's whole bend: xy in UV units, z the split times w
 * its weight. The scene renderer publishes it on the view under {@link #KEY} once it has applied it to the target.
 *
 * <pre>{@code
 * // A post effect bends a side input of its own (a mask, a subject) as the scene was bent:
 * CgGraphTexture subject = post.distorted(post.textures().subject());
 *
 * // Or reads the offsets itself, a sampler2DArray summed over the layers in use:
 * CgDistortionField field = post.view().read(CgDistortionField.KEY);
 * instance.sampler("_Fields", 0, field.offsets()).set1i("_FieldCount", field.count());
 * }</pre>
 *
 * <ul>
 *   <li>One instance, refilled every firing: read it during the firing, never keep it.</li>
 *   <li>Layers from {@link #count()} up hold whatever the pool's storage last held: never read them.</li>
 *   <li>The array is the view's {@code CgSceneRenderer.DISTORTION_SCALE} of the target's size; sample it by normalized uv.</li>
 * </ul>
 */
public final class CgDistortionField {

    /** The array's layers, and the most a field uses. */
    public static final int MAX = 5;

    /** Where the scene renderer publishes it in its view, a frame where something distorts. */
    public static final CgViewKey<CgDistortionField> KEY = new CgViewKey<>("crystalgraphics:distortion");

    private CgGraphTexture offsets;
    private int count;

    /** How many layers hold offsets, at least 1 once published. */
    public int count() {
        return count;
    }

    /** The array, {@link #MAX} layers deep. */
    public CgGraphTexture offsets() {
        return offsets;
    }

    /** The producer's: {@code offsets}, whose first {@code count} layers it drew into. */
    public void set(CgGraphTexture offsets, int count) {
        if (count < 0 || count > MAX) throw new IllegalArgumentException("a distortion field of " + count + " layers: at most " + MAX);
        this.offsets = offsets;
        this.count = count;
    }
}
