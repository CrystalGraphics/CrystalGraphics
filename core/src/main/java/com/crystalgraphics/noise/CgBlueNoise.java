package com.crystalgraphics.noise;

import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgEngineTextures;
import com.crystalgraphics.gpu.resource.CgTexture3D;

/**
 * The engine's spatiotemporal blue noise, for anything that takes a random choice per pixel per frame and averages the
 * frames afterwards: a light sample's pick, a ray within a light, a stochastic upsample. EA SEED's FAST noise (BSD-3,
 * {@code THIRD-PARTY.md}), 128x128 texels and {@link #FRAMES} frames, made to be blue in space under a 3x3 binomial
 * filter and blue in time under an exponential moving average, which is what a temporal history is: its frames
 * average out where white noise or a per-pixel hash leaves a pattern behind.
 *
 * <pre>{@code
 * Properties {
 *     _BlueNoise ("Blue noise", sampler3D) = "cg_blue_noise"   // no binding needed: the engine names it
 * }
 * #include "crystalgraphics:shaders/lib/blue_noise.glsl"
 * float pick = cg_blue_noise(_BlueNoise, px, CG_FRAME_INDEX, 0);    // uniform in [0, 1)
 * vec2 u = cg_blue_noise2(_BlueNoise, px, CG_FRAME_INDEX, 1);        // two more, uncorrelated with it
 * }</pre>
 *
 * <ul>
 *   <li>A 128x128x32 volume, a frame a slice: R a scalar, G and B a 2D vector. Shipped as one 8x4 sheet of tiles
 *       ({@link CgTextures#volume(String, int, int)}). {@code lib/blue_noise.glsl} wraps the pixel and the frame and
 *       gives each use of it in one shader its own stream, read far enough away to be uncorrelated.</li>
 *   <li>Loaded from its file at its first bind, as any texture from files.</li>
 * </ul>
 */
public final class CgBlueNoise {

    /** The name a sampler property's default gives to read it. */
    public static final String NAME = "cg_blue_noise";
    /** Frames before the noise repeats: the volume's slices. */
    public static final int FRAMES = 32;
    private static final String SHEET = "crystalgraphics:textures/noise/blue_noise.png";

    private static volatile CgTexture3D volume;

    static {
        CgEngineTextures.register(NAME, () -> volume);
    }

    private CgBlueNoise() {
    }

    /** Names the volume for the current context; its file loads at its first bind. Render thread. */
    public static void install() {
        volume = CgTextures.volume(SHEET, 8, FRAMES / 8);
    }
}
