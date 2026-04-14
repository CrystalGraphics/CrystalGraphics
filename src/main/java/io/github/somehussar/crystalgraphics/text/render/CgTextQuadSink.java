package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;

/**
 * Quad-oriented text emission sink that decouples glyph quad emission from
 * any specific submission model (layers, draw lists, etc.).
 *
 * <p>CrystalGUI's draw-list path implements this to record text quads in painter's
 * order. The existing layer-based path remains unchanged. This interface enables
 * a unified text emission core without forcing a UI-specific API onto the renderer.</p>
 *
 * <p>Unlike the previous {@code CgTextEmissionTarget}, this interface does not
 * expose vertex consumers or staging cursors to the text renderer. The renderer
 * calls {@link #emitQuad} to submit glyph geometry — the sink owns all vertex
 * writing. This keeps adapter implementations trivially simple.</p>
 *
 * <p>Implementations must ensure that one element's text emission remains contiguous.
 * Internal page/material grouping is allowed only within a single text emission call.</p>
 */
public interface CgTextQuadSink {

    /**
     * Begins a text batch with the given render state, texture, and pxRange.
     * The sink records that all subsequent quads belong to this batch until
     * the next {@code beginBatch()} or {@link #endText()} call.
     *
     * <p>Implementations should flush any pending vertices from a previous batch
     * before recording the new batch parameters.</p>
     *
     * @param renderState the render state to apply for the next batch
     * @param textureId   GL texture ID to bind, or -1 for no texture override
     * @param pxRange     the MSDF pxRange value, or {@link Float#NaN} if unused (bitmap text)
     */
    void beginBatch(CgRenderState renderState, int textureId, float pxRange);

    /**
     * Emits a single glyph quad. The sink owns vertex writing — no vertex-consumer
     * or staging-cursor bookkeeping leaks to the caller.
     *
     * @param x0 left X
     * @param y0 top Y
     * @param x1 right X
     * @param y1 bottom Y
     * @param u0 left UV
     * @param v0 top UV
     * @param u1 right UV
     * @param v1 bottom UV
     * @param r  red component (0–255)
     * @param g  green component (0–255)
     * @param b  blue component (0–255)
     * @param a  alpha component (0–255)
     */
    void emitQuad(float x0, float y0, float x1, float y1,
                  float u0, float v0, float u1, float v1,
                  int r, int g, int b, int a);

    /**
     * Ends the current text emission, flushing any pending quads as a draw command.
     * Must be called after all quads for one text element have been emitted.
     */
    void endText();
}
