package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * One texture the graph allocates: its size, its format and its levels or layers. Equal descriptions share a pool
 * bucket, so two transients that never live at once may be one texture.
 *
 * <pre>{@code
 * CgTextureDesc layer = CgTextureDesc.of(w, h, CgFormat.RGBA8_UNORM);
 * CgTextureDesc bloom = CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT).withMips();     // the full chain, to 1x1
 * CgTextureDesc five  = new CgTextureDesc(w, h, CgFormat.RGBA16_FLOAT, 5);
 * CgTextureDesc depth = CgTextureDesc.of(w, h, CgFormat.DEPTH32_FLOAT);               // a depth a colour draws with
 * CgTextureDesc grid  = CgTextureDesc.volume(64, 64, 64, CgFormat.R16_FLOAT);          // a 3D texture of 64 slices
 * CgTextureDesc field = CgTextureDesc.array(w, h, 5, CgFormat.RGBA16_FLOAT);           // a 2D array of 5 layers
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
 *       pass draws into it. One colour level.</li>
 *   <li>An <b>array</b> is drawn into a layer at a time ({@code rec.raster(texture, 0, layer, ...)}) and sampled whole as
 *       a {@code sampler2DArray}. One colour level.</li>
 *   <li>A depth format is one 2D level.</li>
 *   <li>A <b>multisampled</b> texture ({@link #withSamples}) is one 2D level a pass draws into and nothing samples: what
 *       a pass resolves from ({@code rec.copy}) into a texture of one sample.</li>
 * </ul>
 *
 * @param depth 1 for a 2D texture; a volume's slices, 2 or more
 * @param layers 1 for a texture that is not an array; an array's layers, 2 or more
 * @param samples 1, or a multisampled texture's samples
 */
public record CgTextureDesc(int width, int height, int depth, CgFormat format, int levels, int layers, int samples) {

    /** Spelled out: the Java 8 copy's record hash boxes every field, and a pool hashes a desc each acquire. */
    @Override
    public int hashCode() {
        int h = width;
        h = 31 * h + height;
        h = 31 * h + depth;
        h = 31 * h + format.hashCode();
        h = 31 * h + levels;
        h = 31 * h + layers;
        return 31 * h + samples;
    }

    public CgTextureDesc {
        if (format == null) throw new IllegalArgumentException("a texture with no format");
        if (width <= 0 || height <= 0 || depth <= 0 || layers <= 0) {
            throw new IllegalArgumentException("a texture of " + width + "x" + height + "x" + depth + ", " + layers + " layers");
        }
        if (levels < 1 || levels > CgTexture.fullChain(width, height)) {
            throw new IllegalArgumentException(levels + " levels for " + width + "x" + height + ": it holds 1 to "
                    + CgTexture.fullChain(width, height));
        }
        if (format.compressed()) throw new IllegalArgumentException("the graph draws into no compressed " + format);
        if (depth > 1 && layers > 1) throw new IllegalArgumentException("a volume is not an array");
        if ((depth > 1 || layers > 1) && levels > 1) throw new IllegalArgumentException("a volume or an array holds one level");
        boolean depthStencil = format.hasDepth() || format.hasStencil();
        if (depthStencil && (depth > 1 || layers > 1 || levels > 1)) {
            throw new IllegalArgumentException("a " + format + " texture is one 2D level");
        }
        if (samples < 1 || samples > 1 && (depth > 1 || layers > 1 || levels > 1)) {
            throw new IllegalArgumentException(samples + " samples: a multisampled texture is one 2D level");
        }
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
        if (depth < 2) throw new IllegalArgumentException("a volume of " + depth + " slices: describe a 2D texture for one");
        return new CgTextureDesc(width, height, depth, format, 1, 1);
    }

    /** Whether it is a 3D texture. */
    public boolean isVolume() {
        return depth > 1;
    }

    /**
     * A 2D array of {@code layers} layers. Keep the count fixed from frame to frame, so the pool hands back the same
     * storage.
     *
     * <pre>{@code
     * CgGraphTexture field = CgGraphTexture.transientTexture("field", CgTextureDesc.array(w, h, 5, CgFormat.RGBA16_FLOAT));
     * CgRasterPass slot2 = rec.raster(field, 0, 2, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.SORTED);
     * }</pre>
     */
    public static CgTextureDesc array(int width, int height, int layers, CgFormat format) {
        if (layers < 2) throw new IllegalArgumentException("an array of " + layers + " layers: describe a 2D texture for one");
        return new CgTextureDesc(width, height, 1, format, 1, layers);
    }

    /** Whether it is a 2D array. */
    public boolean isArray() {
        return layers > 1;
    }

    /** This description with {@code samples} samples: drawn into, never sampled. */
    public CgTextureDesc withSamples(int samples) {
        return new CgTextureDesc(width, height, depth, format, levels, layers, samples);
    }

    /** Whether it holds more than one sample. */
    public boolean isMultisampled() {
        return samples > 1;
    }

    /** Whether it is a depth, stencil or depth-stencil texture. */
    public boolean isDepth() {
        return format.hasDepth() || format.hasStencil();
    }

    /** This description with every level down to 1x1. */
    public CgTextureDesc withMips() {
        if (isVolume() || isArray()) throw new IllegalArgumentException("a volume or an array holds one level");
        if (samples > 1) throw new IllegalArgumentException("a multisampled texture holds one level");
        return new CgTextureDesc(width, height, 1, format, CgTexture.fullChain(width, height), 1);
    }
}
