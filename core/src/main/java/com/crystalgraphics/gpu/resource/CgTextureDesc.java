package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

/**
 * One texture: its kind, size, format and levels or layers. What a texture of yours is made from
 * ({@code CgTextures.of(name, desc)}), and what the frame graph allocates: equal descriptions share a pool bucket, so two
 * transients that never live at once may be one texture.
 *
 * <pre>{@code
 * CgTextureDesc layer = CgTextureDesc.of(w, h, CgFormat.RGBA8_UNORM);
 * CgTextureDesc bloom = CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT).withMips();     // the full chain, to 1x1
 * CgTextureDesc five  = new CgTextureDesc(w, h, CgFormat.RGBA16_FLOAT, 5);
 * CgTextureDesc depth = CgTextureDesc.of(w, h, CgFormat.DEPTH32_FLOAT);               // a depth a colour draws with
 * CgTextureDesc grid  = CgTextureDesc.volume(64, 64, 64, CgFormat.R16_FLOAT);          // a 3D texture of 64 slices
 * CgTextureDesc field = CgTextureDesc.array(w, h, 5, CgFormat.RGBA16_FLOAT);           // a 2D array of 5 layers
 * CgTextureDesc sky   = CgTextureDesc.cube(512, CgFormat.RGBA16_FLOAT);                // six square faces
 * }</pre>
 *
 * <p>A pass drawing into a colour and a depth names two textures: the colour drawn with the depth
 * ({@code CgGraphTexture.transientTexture(name, desc, depth)}).</p>
 *
 * <ul>
 *   <li>A pass draws into level 0; a kernel writes any level ({@code CgDispatch.image(name, texture, level, -1)}), and
 *       {@code CgGpuOps.downsample} fills each from the one above. A texture of more than one level samples
 *       trilinearly.</li>
 *   <li>A <b>volume</b> is written by kernels that declare it a {@code 3d} image and sampled as a {@code sampler3D}; no
 *       pass draws into it. It may hold levels, which its maker fills.</li>
 *   <li>An <b>array</b> is drawn into a layer at a time ({@code rec.raster(name).color(texture, 0, layer, load)}) and sampled whole as
 *       a {@code sampler2DArray}. One colour level.</li>
 *   <li>A <b>cube</b> is six square faces, sampled as a {@code samplerCube}: yours, never the graph's.</li>
 *   <li>A depth format is one 2D level.</li>
 *   <li>A <b>multisampled</b> texture ({@link #withSamples}) is one 2D level a pass draws into and nothing samples: what
 *       a pass resolves from ({@code rec.copy}) into a texture of one sample.</li>
 * </ul>
 *
 * @param depth a volume's slices; 1 for any other
 * @param layers an array's layers, a cube's 6; 1 for any other
 * @param samples 1, or a multisampled texture's samples
 * @param kind 2D, an array, a volume or a cube
 */
public record CgTextureDesc(int width, int height, int depth, CgFormat format, int levels, int layers, int samples,
                            CgGpuTexture.Kind kind) {

    /** Spelled out: the Java 8 copy's record hash boxes every field, and a pool hashes a desc each acquire. */
    @Override
    public int hashCode() {
        int h = width;
        h = 31 * h + height;
        h = 31 * h + depth;
        h = 31 * h + format.hashCode();
        h = 31 * h + levels;
        h = 31 * h + layers;
        h = 31 * h + samples;
        return 31 * h + kind.ordinal();
    }

    public CgTextureDesc {
        if (format == null || kind == null) throw new IllegalArgumentException("a texture with no format or kind");
        if (width <= 0 || height <= 0 || depth <= 0 || layers <= 0) {
            throw new IllegalArgumentException("a texture of " + width + "x" + height + "x" + depth + ", " + layers + " layers");
        }
        int chain = chain(width, height, depth);
        if (levels < 1 || levels > chain) {
            throw new IllegalArgumentException(levels + " levels for " + width + "x" + height
                    + (depth > 1 ? "x" + depth : "") + ": it holds 1 to " + chain);
        }
        if (format.compressed()) throw new IllegalArgumentException("no texture is made of compressed " + format);
        if (depth > 1 && kind != CgGpuTexture.Kind.D3) throw new IllegalArgumentException("only a volume has slices");
        if (layers > 1 && kind != CgGpuTexture.Kind.D2_ARRAY && kind != CgGpuTexture.Kind.CUBE) {
            throw new IllegalArgumentException("only an array or a cube has layers");
        }
        if (kind == CgGpuTexture.Kind.CUBE && (layers != 6 || width != height)) {
            throw new IllegalArgumentException("a cube is six square faces, not " + width + "x" + height + "x" + layers);
        }
        if (kind == CgGpuTexture.Kind.D2_ARRAY && levels > 1) throw new IllegalArgumentException("an array holds one level");
        boolean depthStencil = format.hasDepth() || format.hasStencil();
        if (depthStencil && (kind != CgGpuTexture.Kind.D2 || levels > 1)) {
            throw new IllegalArgumentException("a " + format + " texture is one 2D level");
        }
        if (samples < 1 || samples > 1 && (kind != CgGpuTexture.Kind.D2 || levels > 1)) {
            throw new IllegalArgumentException(samples + " samples: a multisampled texture is one 2D level");
        }
    }

    /** A volume where it has slices, an array where it has layers, else 2D. */
    public CgTextureDesc(int width, int height, int depth, CgFormat format, int levels, int layers, int samples) {
        this(width, height, depth, format, levels, layers, samples,
                depth > 1 ? CgGpuTexture.Kind.D3 : layers > 1 ? CgGpuTexture.Kind.D2_ARRAY : CgGpuTexture.Kind.D2);
    }

    /** A texture of one sample. */
    public CgTextureDesc(int width, int height, int depth, CgFormat format, int levels, int layers) {
        this(width, height, depth, format, levels, layers, 1);
    }

    /** A 2D texture of one level. */
    public static CgTextureDesc of(int width, int height, CgFormat format) {
        return new CgTextureDesc(width, height, 1, format, 1, 1);
    }

    /** A 2D texture of one level. */
    public CgTextureDesc(int width, int height, CgFormat format) {
        this(width, height, 1, format, 1, 1);
    }

    /** A 2D texture of {@code levels} levels. */
    public CgTextureDesc(int width, int height, CgFormat format, int levels) {
        this(width, height, 1, format, levels, 1);
    }

    /**
     * A 3D texture of {@code depth} slices.
     *
     * <pre>{@code
     * CgGraphTexture voxels = CgGraphTexture.requested("voxels", CgTextureDesc.volume(128, 96, 128, CgFormat.R8_UINT));
     * }</pre>
     */
    public static CgTextureDesc volume(int width, int height, int depth, CgFormat format) {
        return new CgTextureDesc(width, height, depth, format, 1, 1, 1, CgGpuTexture.Kind.D3);
    }

    /** Whether it is a 3D texture. */
    public boolean isVolume() {
        return kind == CgGpuTexture.Kind.D3;
    }

    /**
     * A 2D array of {@code layers} layers. Keep the count fixed from frame to frame, so the pool hands back the same
     * storage.
     *
     * <pre>{@code
     * CgGraphTexture field = CgGraphTexture.transientTexture("field", CgTextureDesc.array(w, h, 5, CgFormat.RGBA16_FLOAT));
     * CgRasterPass slot2 = rec.raster("textureDesc.field").color(field, 0, 2, CgLoad.clear(0, 0, 0, 0)).constants(constants);
     * }</pre>
     */
    public static CgTextureDesc array(int width, int height, int layers, CgFormat format) {
        return new CgTextureDesc(width, height, 1, format, 1, layers, 1, CgGpuTexture.Kind.D2_ARRAY);
    }

    /** Whether it is a 2D array. */
    public boolean isArray() {
        return kind == CgGpuTexture.Kind.D2_ARRAY;
    }

    /** A cube of six {@code size} x {@code size} faces. */
    public static CgTextureDesc cube(int size, CgFormat format) {
        return new CgTextureDesc(size, size, 1, format, 1, 6, 1, CgGpuTexture.Kind.CUBE);
    }

    /** Whether it is a cube. */
    public boolean isCube() {
        return kind == CgGpuTexture.Kind.CUBE;
    }

    /** This description with {@code samples} samples: drawn into, never sampled. */
    public CgTextureDesc withSamples(int samples) {
        return new CgTextureDesc(width, height, depth, format, levels, layers, samples, kind);
    }

    /** Whether it holds more than one sample. */
    public boolean isMultisampled() {
        return samples > 1;
    }

    /** Whether it is a depth, stencil or depth-stencil texture. */
    public boolean isDepth() {
        return format.hasDepth() || format.hasStencil();
    }

    /** Whether it is one 2D texture: not a volume, an array, a cube or multisampled. */
    public boolean is2D() {
        return kind == CgGpuTexture.Kind.D2 && samples == 1;
    }

    /** This description with every level down to 1x1. */
    public CgTextureDesc withMips() {
        return withMips(chain(width, height, depth));
    }

    /** This description with {@code levels} levels. */
    public CgTextureDesc withMips(int levels) {
        if (isArray()) throw new IllegalArgumentException("an array holds one level");
        if (samples > 1) throw new IllegalArgumentException("a multisampled texture holds one level");
        return new CgTextureDesc(width, height, depth, format, levels, layers, samples, kind);
    }

    private static int chain(int width, int height, int depth) {
        return CgTexture.fullChain(Math.max(width, depth), height);
    }
}
