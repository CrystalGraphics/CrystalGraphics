package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;

/**
 * What a graph texture the graph allocates is: its size, its attachments and the mip levels of its colour. Equal
 * descriptions share a pool bucket, so two transients that never live at once may be one texture.
 *
 * <pre>{@code
 * CgTextureDesc layer = new CgTextureDesc(w, h, CgTextureDesc.RGBA8);
 * CgTextureDesc bloom = new CgTextureDesc(w, h, CgTextureDesc.RGBA8).withMips();   // the full chain, to 1x1
 * CgTextureDesc five  = new CgTextureDesc(w, h, CgTextureDesc.RGBA8, 5);
 * CgTextureDesc grid  = CgTextureDesc.volume(64, 64, 64, r16f);                    // a 3D texture of 64 slices
 * CgTextureDesc field = CgTextureDesc.array(w, h, 5, rgba16f);                      // a 2D array of 5 layers
 * }</pre>
 *
 * <p>A pass draws into level 0; a kernel writes any level ({@code CgDispatch.image(name, texture, level, -1)}), and
 * {@code CgGpuOps.downsample} fills each from the one above. A texture of more than one level samples trilinearly.</p>
 *
 * <p>A <b>volume</b> is written by kernels that declare it a {@code 3d} image and sampled as a {@code sampler3D}; no
 * pass draws into it. It is one colour texture of one level.</p>
 *
 * <p>An <b>array</b> is drawn into a layer at a time ({@code rec.raster(texture, 0, layer, ...)}) and sampled whole as a
 * {@code sampler2DArray}. It is one colour texture of one level.</p>
 *
 * @param depth 1 for a 2D texture; a volume's slices, 2 or more
 * @param layers 1 for a texture that is not an array; an array's layers, 2 or more
 */
public record CgTextureDesc(int width, int height, int depth, CgFrameBufferFormat format, int levels, int layers) {

    /** One RGBA8 colour attachment: a UI layer, a backdrop. */
    public static final CgFrameBufferFormat RGBA8 = CgFrameBufferFormat.builder("cg_graph_rgba8")
            .color(0, CgTextureType.RGBA8)
            .build();

    public CgTextureDesc {
        if (width <= 0 || height <= 0 || depth <= 0 || layers <= 0) {
            throw new IllegalArgumentException("a texture of " + width + "x" + height + "x" + depth + ", " + layers + " layers");
        }
        if (levels < 1 || levels > CgTexture.fullChain(width, height)) {
            throw new IllegalArgumentException(levels + " levels for " + width + "x" + height + ": it holds 1 to "
                    + CgTexture.fullChain(width, height));
        }
        if (levels > 1 && format.isMultisampled()) throw new IllegalArgumentException("a multisampled texture has no mip levels");
        if (depth > 1 && layers > 1) throw new IllegalArgumentException("a volume is not an array");
        if (layers > 1) {
            if (levels > 1) throw new IllegalArgumentException("an array holds one level");
            if (format.colorSlotCount() != 1 || format.getColorSlot(0) == null || format.isColorRenderbuffer(0)
                    || format.hasDepth() || format.isMultisampled()) {
                throw new IllegalArgumentException("an array is one single-sampled colour texture at slot 0, not " + format);
            }
        }
        if (depth > 1) {
            if (levels > 1) throw new IllegalArgumentException("a volume holds one level");
            if (format.colorSlotCount() != 1 || format.getColorSlot(0) == null || format.isColorRenderbuffer(0)
                    || format.hasDepth() || format.isMultisampled()) {
                throw new IllegalArgumentException("a volume is one single-sampled colour texture at slot 0, not " + format);
            }
        }
    }

    /** A texture that is not an array: 2D, or a volume of {@code depth} slices. */
    public CgTextureDesc(int width, int height, int depth, CgFrameBufferFormat format, int levels) {
        this(width, height, depth, format, levels, 1);
    }

    /** A 2D texture of one level. */
    public CgTextureDesc(int width, int height, CgFrameBufferFormat format) {
        this(width, height, 1, format, 1);
    }

    /** A 2D texture of {@code levels} levels. */
    public CgTextureDesc(int width, int height, CgFrameBufferFormat format, int levels) {
        this(width, height, 1, format, levels);
    }

    /**
     * A 3D texture of {@code depth} slices: the colour of {@code format}'s slot 0, which must be its only attachment.
     *
     * <pre>{@code
     * CgFrameBufferFormat r8ui = CgFrameBufferFormat.builder("voxels").color(0, CgTextureType.R8UI).build();
     * CgGraphTexture voxels = CgGraphTexture.requested("voxels", CgTextureDesc.volume(128, 96, 128, r8ui));
     * }</pre>
     */
    public static CgTextureDesc volume(int width, int height, int depth, CgFrameBufferFormat format) {
        if (depth < 2) throw new IllegalArgumentException("a volume of " + depth + " slices: describe a 2D texture for one");
        return new CgTextureDesc(width, height, depth, format, 1);
    }

    /** Whether it is a 3D texture. */
    public boolean isVolume() {
        return depth > 1;
    }

    /**
     * A 2D array of {@code layers} layers: the colour of {@code format}'s slot 0, which must be its only attachment.
     * Keep the count fixed from frame to frame, so the pool hands back the same storage.
     *
     * <pre>{@code
     * CgFrameBufferFormat rgba16f = CgFrameBufferFormat.builder("field").color(0, CgTextureType.RGBA16F).build();
     * CgGraphTexture field = CgGraphTexture.transientTexture("field", CgTextureDesc.array(w, h, 5, rgba16f));
     * CgRasterPass slot2 = rec.raster(field, 0, 2, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.SORTED);
     * }</pre>
     */
    public static CgTextureDesc array(int width, int height, int layers, CgFrameBufferFormat format) {
        if (layers < 2) throw new IllegalArgumentException("an array of " + layers + " layers: describe a 2D texture for one");
        return new CgTextureDesc(width, height, 1, format, 1, layers);
    }

    /** Whether it is a 2D array. */
    public boolean isArray() {
        return layers > 1;
    }

    /** This description with every level down to 1x1. */
    public CgTextureDesc withMips() {
        if (isVolume()) throw new IllegalArgumentException("a volume holds one level");
        if (isArray()) throw new IllegalArgumentException("an array holds one level");
        return new CgTextureDesc(width, height, depth, format, CgTexture.fullChain(width, height));
    }
}
