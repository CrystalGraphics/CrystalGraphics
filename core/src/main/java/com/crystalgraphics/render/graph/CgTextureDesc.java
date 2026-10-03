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
 * }</pre>
 *
 * <p>A pass draws into level 0; a kernel writes any level ({@code CgDispatch.image(name, texture, level, -1)}), and
 * {@code CgGpuOps.downsample} fills each from the one above. A texture of more than one level samples trilinearly.</p>
 */
public record CgTextureDesc(int width, int height, CgFrameBufferFormat format, int levels) {

    /** One RGBA8 colour attachment: a UI layer, a backdrop. */
    public static final CgFrameBufferFormat RGBA8 = CgFrameBufferFormat.builder("cg_graph_rgba8")
            .color(0, CgTextureType.RGBA8)
            .build();

    public CgTextureDesc {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("a texture of " + width + "x" + height);
        if (levels < 1 || levels > CgTexture.fullChain(width, height)) {
            throw new IllegalArgumentException(levels + " levels for " + width + "x" + height + ": it holds 1 to "
                    + CgTexture.fullChain(width, height));
        }
        if (levels > 1 && format.isMultisampled()) throw new IllegalArgumentException("a multisampled texture has no mip levels");
    }

    /** One level. */
    public CgTextureDesc(int width, int height, CgFrameBufferFormat format) {
        this(width, height, format, 1);
    }

    /** This description with every level down to 1x1. */
    public CgTextureDesc withMips() {
        return new CgTextureDesc(width, height, format, CgTexture.fullChain(width, height));
    }
}
