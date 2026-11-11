package com.crystalgraphics.api.texture;

import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import javax.annotation.Nullable;

/**
 * A texture a shader reads: an engine texture (a file's, or one made by hand), or a frame graph's texture, whose
 * storage exists only while the passes using it execute (Unreal's RHI texture beside an RDG one). What a material
 * property, a pass's or a dispatch's texture binding takes.
 *
 * <pre>{@code
 * material.texture("_Noise", CgTextures.of("mymod:textures/noise.png"));   // an engine texture
 * material.texture("_Glow", glow);                                         // a CgGraphTexture, read when its pass runs
 * }</pre>
 *
 * <ul>
 *   <li>{@link #rhiView()} is null where there is no storage now: a graph texture outside its passes, a file not yet
 *       loaded.</li>
 *   <li>Loading, reloading and releasing are the engine texture's own ({@code CgTexture2D} and its kin); a graph
 *       texture is the graph's.</li>
 * </ul>
 */
public interface CgTexture {

    /** The device texture a set binds now; null where it has no storage now. Render thread. */
    @Nullable
    CgTextureView rhiView();

    /**
     * How it is sampled where a binding names no sampler. The same object each call; null for the device's defaults,
     * which repeat: an imported texture's, sampled as its owner left it.
     */
    @Nullable
    default CgSampler sampler() {
        return null;
    }

    /** Its width at level 0, in texels. */
    int getWidth();

    /** Its height at level 0, in texels. */
    int getHeight();

    /** The mip levels a binding samples: 1 for none. Level {@code l} is {@code max(1, size >> l)} on each axis. */
    default int getLevels() {
        return 1;
    }

    /** Levels in a full mip chain of a {@code width} by {@code height} texture, down to 1x1. */
    static int fullChain(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
    }
}
