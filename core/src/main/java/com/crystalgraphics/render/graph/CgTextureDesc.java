package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;

/**
 * What a graph texture the graph allocates is: its size and its attachments. Equal descriptions share a pool bucket,
 * so two transients that never live at once may be one texture.
 *
 * <pre>{@code
 * CgTextureDesc layer = new CgTextureDesc(w, h, CgTextureDesc.RGBA8);
 * }</pre>
 */
public record CgTextureDesc(int width, int height, CgFrameBufferFormat format) {

    /** One RGBA8 colour attachment: a UI layer, a backdrop. */
    public static final CgFrameBufferFormat RGBA8 = CgFrameBufferFormat.builder("cg_graph_rgba8")
            .color(0, CgTextureType.RGBA8)
            .build();

    public CgTextureDesc {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("a texture of " + width + "x" + height);
    }
}
