package com.crystalgraphics.noise;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgEngineTextures;
import com.crystalgraphics.gpu.resource.CgTexture3D;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.variable.CgVar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * The engine's noise volumes, baked once by kernels ({@code noise_bake.compute}) and sampled by any shader or kernel
 * through a sampler property whose default names one, so a shader reads a texel where it hashed eight corners:
 * tiling RGBA16F cubes of gradient noise, value noise, cellular noise and a curl flow, and wavelet noise's tile.
 *
 * <pre>{@code
 * Properties {
 *     _Noise   ("Noise",   sampler3D) = "cg_noise"         // gradient, about -1..1, four decorrelated channels
 *     _Voronoi ("Voronoi", sampler3D) = "cg_voronoi"       // F1, F2, the cell's hash
 *     _VoronoiNearest ("Cells", sampler3D) = "cg_voronoi_nearest"   // F1's gradient, F1: a normal from cells
 *     _ValueGradient ("Value slope", sampler3D) = "cg_value_gradient"   // value noise's gradient and value: a normal
 *     _Wavelet ("Wavelet noise", sampler3D) = "cg_wavelet_noise"
 * }
 * float n = cg_noise3(_Noise, p);                           // lib/noise_volume.glsl
 * float w = fx_wavelet(_Wavelet, p);                        // lib/vfx/fx_wavelet.glsl: p in lattice units
 * }</pre>
 *
 * <ul>
 *   <li>{@link #PERIOD} lattice cells across every RGBA volume: sample at {@code p / PERIOD}, repeating.
 *       {@code lib/noise_volume.glsl} does it.</li>
 *   <li>Each RGBA channel is the same noise with its own seed, so one fetch gives four independent values. At texel
 *       centres a volume holds {@code fx_common.glsl}'s functions at lattice coordinate {@code (x + 0.5) * PERIOD / SIZE},
 *       the lattice wrapped.</li>
 *   <li>Wavelet noise (Cook and DeRose, 2005) is band-limited, all of its detail within one octave, so octaves of it
 *       stack without the blur and aliasing Perlin noise brings. Its tile is baked as Mantaflow bakes it (Apache-2.0,
 *       {@code source/noisefield.cpp}, {@code WaveletNoiseField::generateTile}, tile 0), {@link #WAVELET_TILE} lattice
 *       units a side, R16F, repeating; {@code fx_wavelet.glsl} reads it as Mantaflow's {@code WNoise} does. Mantaflow
 *       fills its tile from its Mersenne Twister and the kernels from PCG, so the values differ; the spectrum is the
 *       same.</li>
 *   <li>Baked behind the loading screen, mip chains included, as the task {@code crystalgraphics:noise}, once its
 *       kernels are built. Until then every name reads zero.</li>
 *   <li>{@code -Dcrystalgraphics.noise.size=64|128}: texels a side of the RGBA volumes, 64 by default (2 MB a volume).</li>
 * </ul>
 */
public final class CgNoiseVolumes {

    /** The engine texture names, in {@link CgEngineTextures}. */
    public static final String GRADIENT = "cg_noise", VALUE = "cg_value_noise", VORONOI = "cg_voronoi", CURL = "cg_curl",
            VORONOI_NEAREST = "cg_voronoi_nearest", VALUE_GRADIENT = "cg_value_gradient", WAVELET = "cg_wavelet_noise";

    /** Lattice cells across an RGBA volume; {@code CG_NOISE_PERIOD} in {@code lib/noise_volume.glsl}. */
    public static final int PERIOD = 16;
    /** The wavelet tile's lattice units a side: Mantaflow's {@code NOISE_TILE_SIZE}. */
    public static final int WAVELET_TILE = 128;

    public static final int SIZE = CgVar.integer("crystalgraphics.noise.size", 64, 16, 256, CgVar.DEV | CgVar.RESTART,
            "Texels a side of each engine noise volume: 64 (2 MB a volume) or 128.").get();

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/noise_bake.compute";
    /** Each volume's name, then the kernel writing its level 0; the curl's reads the gradient's. The wavelet tile last. */
    private static final String[] NAMES = {GRADIENT, VALUE, VORONOI, CURL, VORONOI_NEAREST, VALUE_GRADIENT, WAVELET};
    private static final String[] LEVEL0 = {"Gradient", "Value", "Voronoi", "Curl", "VoronoiNearest", "ValueGradient"};
    private static final String[] KERNEL_NAMES = {"Gradient", "Value", "ValueGradient", "Voronoi", "VoronoiNearest",
            "Curl", "Halve", "WaveletSeed", "WaveletDown", "WaveletUp", "WaveletBand", "WaveletTile"};
    private static final int RGBA = LEVEL0.length, WAVELET_AT = RGBA;
    private static final int WAVELET_SEED = 0x5EED;

    /** Repeating, and read across a chain where it has one. */
    private static final CgSampler SAMPLER = CgSampler.TRILINEAR_WRAP;

    /** Each volume, made at install: what a kernel writes, answered by its name once baked. */
    private static final CgTexture3D[] MADE = new CgTexture3D[NAMES.length];
    /** Each volume once baked; until then its name answers {@link #pending} or {@link #pendingWavelet}. */
    private static final CgTexture3D[] VOLUMES = new CgTexture3D[NAMES.length];
    /** One zero texel each, answering a name until the bake lands, so no sampler3D is left unbound. */
    private static CgTexture3D pending, pendingWavelet;
    private static List<CgKernel> kernels;
    /** Bumped by {@link #release()}, so a bake task started before it records nothing. */
    private static int generation;

    static {
        for (int i = 0; i < NAMES.length; i++) {
            int at = i;
            CgEngineTextures.register(NAMES[i], () -> VOLUMES[at] != null ? VOLUMES[at]
                    : at == WAVELET_AT ? pendingWavelet : pending);
        }
    }

    private CgNoiseVolumes() {
    }

    /** Makes the volumes for the current context and starts their bake. Render thread. */
    public static void install() {
        if (MADE[0] != null) return;
        pending = zero("crystalgraphics.noise.pending", CgFormat.RGBA16_FLOAT, 8, GLC.GL_RGBA);
        pendingWavelet = zero("crystalgraphics.noise.pendingWavelet", CgFormat.R16_FLOAT, 2, GLC.GL_RED);
        for (int i = 0; i < RGBA; i++) {
            MADE[i] = CgTextures.volume(NAMES[i], CgTextureDesc.volume(SIZE, SIZE, SIZE, CgFormat.RGBA16_FLOAT).withMips())
                    .sampler(SAMPLER);
        }
        MADE[WAVELET_AT] = CgTextures.volume(WAVELET,
                CgTextureDesc.volume(WAVELET_TILE, WAVELET_TILE, WAVELET_TILE, CgFormat.R16_FLOAT)).sampler(SAMPLER);
        int expected = generation;
        CgLoadPlan.get().task("crystalgraphics:noise").render(task -> generation != expected || bake(task)).start();
    }

    /** Frees the volumes. Render thread, with the context current. */
    public static void release() {
        generation++;
        for (int i = 0; i < NAMES.length; i++) {
            if (MADE[i] != null) MADE[i].release();
            MADE[i] = null;
            VOLUMES[i] = null;
        }
        if (pending != null) pending.delete();
        if (pendingWavelet != null) pendingWavelet.delete();
        pending = pendingWavelet = null;
    }

    /** The task's step: the kernels precached at its first, every volume dispatched in one pass once they are built. */
    private static boolean bake(CgLoadTask task) {
        if (kernels == null) {
            kernels = new ArrayList<>(KERNEL_NAMES.length);
            for (String name : KERNEL_NAMES) kernels.add(kernel(name).precache());
        }
        for (CgKernel k : kernels) {
            if (!k.isReady()) {
                task.nextFrame();
                return false;
            }
        }
        CgGraphTexture[] volumes = new CgGraphTexture[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) volumes[i] = CgGraphTexture.imported(NAMES[i], CgTextures.target(NAMES[i], MADE[i], null));
        CgRecording bake = new CgRecording();
        CgComputePass run = bake.compute("crystalgraphics.noiseBake");
        rgba(run, volumes);
        wavelet(run, volumes[WAVELET_AT]);
        run.end();
        CgRenderer.executeNow(bake);
        System.arraycopy(MADE, 0, VOLUMES, 0, NAMES.length);
        return true;
    }

    /** Each RGBA volume's level 0 by its kernel, then each level the box average of the eight texels above it. */
    private static void rgba(CgComputePass run, CgGraphTexture[] volumes) {
        for (int i = 0; i < RGBA; i++) {
            var level0 = run.dispatch(kernel(LEVEL0[i]), SIZE, SIZE, SIZE).image("OUT", volumes[i], 0, -1)
                    .set("_Period", PERIOD);
            if (NAMES[i].equals(CURL)) level0.image("POTENTIAL", volumes[0], 0, -1);
        }
        int levels = CgTexture3D.fullChain(SIZE, SIZE, SIZE);
        for (int i = 0; i < RGBA; i++) {
            for (int l = 0, size = SIZE / 2; l + 1 < levels; l++, size /= 2) {
                run.dispatch(kernel("Halve"), size, size, size).image("SRC", volumes[i], l, -1)
                        .image("DST", volumes[i], l + 1, -1);
            }
        }
    }

    /**
     * The wavelet tile: a normal value a cell, each axis downsampled and upsampled in turn, which leaves the coarse half
     * of the spectrum; that subtracted; then a copy offset by an odd number of cells added, which evens the variance
     * between even and odd cells.
     */
    private static void wavelet(CgComputePass run, CgGraphTexture tile) {
        int n = WAVELET_TILE, cells = n * n * n;
        CgBufferDesc desc = CgBufferDesc.elements(cells, 4, CgBufferUsage.STORAGE);
        CgGraphBuffer seed = CgGraphBuffer.transientBuffer("wavelet.seed", desc);
        CgGraphBuffer half = CgGraphBuffer.transientBuffer("wavelet.half", desc);
        CgGraphBuffer coarse = CgGraphBuffer.transientBuffer("wavelet.coarse", desc);
        run.dispatch(kernel("WaveletSeed"), cells).bind("TO", seed).set("_Seed", WAVELET_SEED);
        // Each axis's stride, then the strides of the two it runs across: Mantaflow's three passes
        int[][] axes = {{1, n, n * n}, {n, 1, n * n}, {n * n, 1, n}};
        CgGraphBuffer from = seed;
        for (int[] axis : axes) {
            run.dispatch(kernel("WaveletDown"), cells / 2).bind("FROM", from).bind("TO", half)
                    .set("_Tile", n).set("_Along", axis[0]).set("_Across", axis[1], axis[2]);
            run.dispatch(kernel("WaveletUp"), cells).bind("FROM", half).bind("TO", coarse)
                    .set("_Tile", n).set("_Along", axis[0]).set("_Across", axis[1], axis[2]);
            from = coarse;
        }
        run.dispatch(kernel("WaveletBand"), cells).bind("FROM", seed).bind("BAND", coarse).bind("TO", half);
        run.dispatch(kernel("WaveletTile"), n, n, n).bind("BAND", half).image("TILE", tile).set("_Tile", n);
    }

    private static CgKernel kernel(String name) {
        return CgCompute.load(KERNELS).kernel(name);
    }

    private static CgTexture3D zero(String name, CgFormat texels, int bytes, int format) {
        CgTexture3D texel = CgTextures.volume(name, CgTextureDesc.volume(1, 1, 1, texels)).sampler(SAMPLER);
        texel.uploadRegion(0, 0, 0, 0, 1, 1, 1, ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()), format,
                GLC.GL_HALF_FLOAT);
        return texel;
    }
}
