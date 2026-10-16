package com.crystalgraphics.noise;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgEngineTextures;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wavelet noise (Cook and DeRose, 2005): band-limited noise, all of its detail within one octave, so octaves of it
 * stack without the blur and aliasing Perlin noise brings. Its tile is baked once, as Mantaflow bakes it (Apache-2.0,
 * {@code source/noisefield.cpp}, {@code WaveletNoiseField::generateTile}, tile 0), and sampled through a sampler whose
 * default names it; {@code lib/vfx/fx_wavelet.glsl} reads it as Mantaflow's {@code WNoise} does.
 *
 * <pre>{@code
 * Properties {
 *     _Wavelet ("Wavelet noise", sampler3D) = "cg_wavelet_noise"
 * }
 * float n = fx_wavelet(_Wavelet, p);           // p in lattice units, repeating every TILE
 * }</pre>
 *
 * <ul>
 *   <li>{@link #TILE} lattice units a side, R16F, repeating: 4 MB.</li>
 *   <li>Baked on a worker at the first {@link #install()}, about a second; until it lands the name reads zero.</li>
 *   <li>Mantaflow fills its tile from its own Mersenne Twister, so the values differ from its; the spectrum is the same.</li>
 * </ul>
 */
public final class CgWaveletNoise {

    private static final Logger LOGGER = Logger.getLogger(CgWaveletNoise.class.getName());

    /** The name a sampler's default gives to read it. */
    public static final String NAME = "cg_wavelet_noise";
    /** Lattice units a side: Mantaflow's {@code NOISE_TILE_SIZE}. */
    public static final int TILE = 128;
    private static final long SEED = 0x5EEDL;
    private static final CgTextureSpec SPEC = CgTextureSpec.builder().type(CgTextureType.R16F)
            .minFilter(CgGL.GL_LINEAR).magFilter(CgGL.GL_LINEAR)
            .wrapS(CgGL.GL_REPEAT).wrapT(CgGL.GL_REPEAT).wrapR(CgGL.GL_REPEAT).build();

    /** Mantaflow's {@code _aCoeffs}: the downsampling filter, 32 taps. */
    private static final float[] A = {
            0.000334f, -0.001528f, 0.000410f, 0.003545f, -0.000938f, -0.008233f, 0.002172f, 0.019120f,
            -0.005040f, -0.044412f, 0.011655f, 0.103311f, -0.025936f, -0.243780f, 0.033979f, 0.655340f,
            0.655340f, 0.033979f, -0.243780f, -0.025936f, 0.103311f, 0.011655f, -0.044412f, -0.005040f,
            0.019120f, 0.002172f, -0.008233f, -0.000938f, 0.003546f, 0.000410f, -0.001528f, 0.000334f};
    /** Mantaflow's {@code _pCoeffs}: the upsampling filter. */
    private static final float[] P = {0.25f, 0.75f, 0.75f, 0.25f};

    private static CgTexture3D volume, pending;
    private static ByteBuffer baked;
    private static boolean baking;
    private static int generation;

    static {
        CgEngineTextures.register(NAME, () -> volume != null ? volume : pending);
    }

    private CgWaveletNoise() {
    }

    /** Makes the tile's texture for the current context, baking it first if this process has not. Render thread. */
    public static synchronized void install() {
        if (volume != null) return;
        if (pending == null) {
            pending = CgTexture3D.createEmpty(1, 1, 1, SPEC, 1);
            pending.uploadRegion(0, 0, 0, 0, 1, 1, 1, ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()),
                    CgGL.GL_RED, CgGL.GL_HALF_FLOAT);
        }
        if (baked != null) {
            upload(baked);
            return;
        }
        if (baking) return;
        baking = true;
        int expected = generation;
        Thread worker = new Thread(() -> {
            ByteBuffer done;
            try {
                done = CgNoiseBake.toHalf(tile());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[crystalgraphics] wavelet noise failed to bake", e);
                synchronized (CgWaveletNoise.class) {
                    baking = false;
                }
                return;
            }
            synchronized (CgWaveletNoise.class) {
                baking = false;
                baked = done;
                if (generation == expected) upload(done);
            }
        }, "CrystalGraphics wavelet noise bake");
        worker.setDaemon(true);
        worker.start();
    }

    /** Frees the texture; the baked bytes stay for the next {@link #install()}. Render thread, with the context current. */
    public static synchronized void release() {
        generation++;
        if (volume != null) volume.delete();
        if (pending != null) pending.delete();
        volume = pending = null;
    }

    private static void upload(ByteBuffer texels) {
        CgTexture3D v = CgTexture3D.createEmpty(TILE, TILE, TILE, SPEC, 1);
        v.uploadRegion(0, 0, 0, 0, TILE, TILE, TILE, texels.duplicate(), CgGL.GL_RED, CgGL.GL_HALF_FLOAT);
        volume = v;
    }

    /**
     * The tile, x fastest: Mantaflow's {@code generateTile} for one tile. Random normal values; each axis downsampled and
     * upsampled in turn, which leaves the coarse half of the spectrum; that subtracted; then a copy offset by an odd
     * number of cells added, which evens the variance between even and odd cells.
     */
    public static float[] tile() {
        int n = TILE, n3 = n * n * n;
        float[] noise = new float[n3], temp1 = new float[n3], temp2 = new float[n3];
        Random random = new Random(SEED);
        for (int i = 0; i < n3; i++) noise[i] = (float) random.nextGaussian();
        for (int iy = 0; iy < n; iy++) {
            for (int iz = 0; iz < n; iz++) {
                int i = iy * n + iz * n * n;
                downsample(noise, temp1, i, n, 1);
                upsample(temp1, temp2, i, n, 1);
            }
        }
        for (int ix = 0; ix < n; ix++) {
            for (int iz = 0; iz < n; iz++) {
                int i = ix + iz * n * n;
                downsample(temp2, temp1, i, n, n);
                upsample(temp1, temp2, i, n, n);
            }
        }
        for (int ix = 0; ix < n; ix++) {
            for (int iy = 0; iy < n; iy++) {
                int i = ix + iy * n;
                downsample(temp2, temp1, i, n, n * n);
                upsample(temp1, temp2, i, n, n * n);
            }
        }
        for (int i = 0; i < n3; i++) noise[i] -= temp2[i];
        int offset = n / 2;
        if (offset % 2 == 0) offset++;
        // Mantaflow writes the offset copy z-fastest and reads it x-fastest: the copy it adds is transposed. Kept.
        int at = 0;
        for (int ix = 0; ix < n; ix++) {
            for (int iy = 0; iy < n; iy++) {
                for (int iz = 0; iz < n; iz++) {
                    temp1[at++] = noise[mod(ix + offset, n) + mod(iy + offset, n) * n + mod(iz + offset, n) * n * n];
                }
            }
        }
        for (int i = 0; i < n3; i++) noise[i] += temp1[i];
        return noise;
    }

    private static void downsample(float[] from, float[] to, int base, int n, int stride) {
        for (int i = 0; i < n / 2; i++) {
            float sum = 0f;
            for (int k = 2 * i - 16; k < 2 * i + 16; k++) sum += A[16 + k - 2 * i] * from[base + mod(k, n) * stride];
            to[base + i * stride] = sum;
        }
    }

    private static void upsample(float[] from, float[] to, int base, int n, int stride) {
        for (int i = 0; i < n; i++) {
            float sum = 0f;
            for (int k = i / 2 - 1; k < i / 2 + 3; k++) sum += 0.5f * P[1 + k - i / 2] * from[base + mod(k, n / 2) * stride];
            to[base + i * stride] = sum;
        }
    }

    private static int mod(int x, int n) {
        int m = x % n;
        return m < 0 ? m + n : m;
    }
}
