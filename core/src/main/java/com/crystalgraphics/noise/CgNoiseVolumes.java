package com.crystalgraphics.noise;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgEngineTextures;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine's noise volumes: tiling RGBA16F cubes of gradient noise, value noise, cellular noise and a curl flow,
 * baked once ({@link CgNoiseBake}) and sampled by any shader or kernel through a sampler property whose default names
 * one, so a shader reads a texel where it hashed eight corners.
 *
 * <pre>{@code
 * Properties {
 *     _Noise   ("Noise",   sampler3D) = "cg_noise"         // gradient, about -1..1, four decorrelated channels
 *     _Voronoi ("Voronoi", sampler3D) = "cg_voronoi"       // F1, F2, the cell's hash
 *     _VoronoiNearest ("Cells", sampler3D) = "cg_voronoi_nearest"   // F1's gradient, F1: a normal from cells
 * }
 * float n = cg_noise3(_Noise, p);                           // lib/noise_volume.glsl
 * }</pre>
 *
 * <ul>
 *   <li>{@link #PERIOD} lattice cells across every volume: sample at {@code p / PERIOD}, repeating.
 *       {@code lib/noise_volume.glsl} does it.</li>
 *   <li>Baked on a worker at the first {@link #install()}, mip chain included, about half a second at 64; until it
 *       lands every name reads zero.</li>
 *   <li>{@code -Dcrystalgraphics.noise.size=64|128}: texels a side, 64 by default (2 MB a volume).</li>
 * </ul>
 */
public final class CgNoiseVolumes {

    private static final Logger LOGGER = Logger.getLogger(CgNoiseVolumes.class.getName());

    /** The engine texture names, in {@link CgEngineTextures}. */
    public static final String GRADIENT = "cg_noise", VALUE = "cg_value_noise", VORONOI = "cg_voronoi", CURL = "cg_curl",
            VORONOI_NEAREST = "cg_voronoi_nearest";

    /** Lattice cells across a volume; {@code CG_NOISE_PERIOD} in {@code lib/noise_volume.glsl}. */
    public static final int PERIOD = 16;

    public static final int SIZE = Integer.getInteger("crystalgraphics.noise.size", 64);

    private static final String[] NAMES = {GRADIENT, VALUE, VORONOI, CURL, VORONOI_NEAREST};
    private static final CgTextureSpec SPEC = CgTextureSpec.builder().type(CgTextureType.RGBA16F)
            .minFilter(CgGL.GL_LINEAR).magFilter(CgGL.GL_LINEAR)
            .wrapS(CgGL.GL_REPEAT).wrapT(CgGL.GL_REPEAT).wrapR(CgGL.GL_REPEAT).build();

    private static final CgTexture3D[] VOLUMES = new CgTexture3D[NAMES.length];
    /** One zero texel, answering every name until the bake lands, so no sampler3D is left unbound. */
    private static CgTexture3D pending;
    /** Kept for the next context: baked once a process. Per volume, its levels from 0. */
    private static ByteBuffer[][] baked;
    private static boolean baking;
    /** Bumped by {@link #release()}, so a bake landing after it uploads nothing. */
    private static int generation;

    static {
        for (int i = 0; i < NAMES.length; i++) {
            int at = i;
            CgEngineTextures.register(NAMES[i], () -> VOLUMES[at] != null ? VOLUMES[at] : pending);
        }
    }

    private CgNoiseVolumes() {
    }

    /** Makes the volumes for the current context, baking them first if this process has not. Render thread. */
    public static synchronized void install() {
        if (VOLUMES[0] != null) return;
        if (pending == null) {
            pending = CgTexture3D.createEmpty(1, 1, 1, SPEC, 1);
            pending.uploadRegion(0, 0, 0, 0, 1, 1, 1, ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()),
                    CgGL.GL_RGBA, CgGL.GL_HALF_FLOAT);
        }
        if (baked != null) {
            upload(baked);
            return;
        }
        if (baking) return;
        baking = true;
        int expected = generation;
        Thread worker = new Thread(() -> {
            ByteBuffer[][] done;
            try {
                long start = System.nanoTime();
                done = bake();
                LOGGER.fine(() -> "[crystalgraphics] noise volumes baked, " + SIZE + "^3, in "
                        + (System.nanoTime() - start) / 1_000_000 + " ms");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[crystalgraphics] noise volumes failed to bake", e);
                synchronized (CgNoiseVolumes.class) {
                    baking = false;
                }
                return;
            }
            synchronized (CgNoiseVolumes.class) {
                baking = false;
                baked = done;
                if (generation == expected) upload(done);
            }
        }, "CrystalGraphics noise bake");
        worker.setDaemon(true);
        worker.start();
    }

    /** Frees the volumes; the baked bytes stay for the next {@link #install()}. Render thread, with the context current. */
    public static synchronized void release() {
        generation++;
        for (int i = 0; i < VOLUMES.length; i++) {
            if (VOLUMES[i] != null) VOLUMES[i].delete();
            VOLUMES[i] = null;
        }
        if (pending != null) pending.delete();
        pending = null;
    }

    private static ByteBuffer[][] bake() {
        float[] gradient = CgNoiseBake.gradient(SIZE, PERIOD);
        return new ByteBuffer[][]{chain(gradient), chain(CgNoiseBake.value(SIZE, PERIOD)),
                chain(CgNoiseBake.voronoi(SIZE, PERIOD)), chain(CgNoiseBake.curl(gradient, SIZE, PERIOD)),
                chain(CgNoiseBake.voronoiNearest(SIZE, PERIOD))};
    }

    /**
     * Every level of a volume as RGBA16F, averaged on the bake's thread. Left to the driver, NVIDIA's GL stalled the
     * render thread 6-24 ms a volume generating a 3D float chain.
     */
    private static ByteBuffer[] chain(float[] level0) {
        ByteBuffer[] levels = new ByteBuffer[CgTexture3D.fullChain(SIZE, SIZE, SIZE)];
        float[] level = level0;
        for (int l = 0, size = SIZE; l < levels.length; l++, size /= 2) {
            levels[l] = CgNoiseBake.toHalf(level);
            if (l + 1 < levels.length) level = CgNoiseBake.halve(level, size);
        }
        return levels;
    }

    /** Any thread: the texture's work waits for the render thread. A name resolves once its upload is queued. */
    private static void upload(ByteBuffer[][] levels) {
        for (int i = 0; i < NAMES.length; i++) {
            CgTexture3D volume = CgTexture3D.createEmpty(SIZE, SIZE, SIZE, SPEC, levels[i].length);
            for (int l = 0, size = SIZE; l < levels[i].length; l++, size /= 2) {
                volume.uploadRegion(l, 0, 0, 0, size, size, size, levels[i][l].duplicate(), CgGL.GL_RGBA,
                        CgGL.GL_HALF_FLOAT);
            }
            VOLUMES[i] = volume;
        }
    }
}
