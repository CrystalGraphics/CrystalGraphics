package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexConsumer;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;

/**
 * Generic internal text emission target that decouples glyph quad emission from
 * any specific submission model (layers, draw lists, etc.).
 *
 * <p>CrystalGUI's draw-list path implements this to record text quads in painter's
 * order. The existing layer-based path remains unchanged. This interface enables
 * a unified text emission core without forcing a UI-specific API onto the renderer.</p>
 *
 * <p>Implementations must ensure that one element's text emission remains contiguous.
 * Internal page/material grouping is allowed only within a single text emission call.</p>
 */
public interface CgTextEmissionTarget {

    /**
     * Signals a render-state / texture / pxRange transition for subsequent quads.
     *
     * @param renderState the render state to apply for the next batch
     * @param textureId   GL texture ID to bind, or -1 for no texture override
     * @param pxRange     the MSDF pxRange value, or {@link Float#NaN} if unused
     */
    void switchBatch(CgRenderState renderState, int textureId, float pxRange);

    CgVertexConsumer vertexConsumer();

    void reserveQuads(int count);

    /**
     * Records a completed quad (4 vertices) into the target.
     *
     * @param vtxStart first vertex index
     * @param vtxCount number of vertices (must be multiple of 4)
     */
    void recordQuad(int vtxStart, int vtxCount);

    /**
     * Returns the current vertex count in the backing staging buffer.
     * Used by the text renderer to compute vtxStart for recordQuad.
     */
    int currentVertexCount();
}
