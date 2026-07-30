package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlStateManager;

/**
 * The GL state domains {@link CgGlStateManager} tracks, and the granularity at which
 * {@code CgGlState.save(...)} captures and restores.
 *
 * <p>Moved here from {@code core/api/state} in the V2 move: deduplication happens where the GL call is
 * made ({@link CgGL}), the shadow has to live where the deduplication happens, and both are in
 * {@code platform}. {@code core} depends on {@code platform}, never the reverse.</p>
 *
 * <p><strong>Ordinals are load-bearing.</strong> They index the trust bitmask and the scope masks, so the
 * count must stay at or below 32 — asserted at class-init in {@link CgGlStateManager}.</p>
 */
public enum CgGlSlot {

    // ── Binding domains ───────────────────────────────────────────────────────
    /** Draw and read framebuffer bindings, plus the call family that bound them. */
    FBO,
    /** Bound shader program. */
    PROGRAM,
    /** Active texture unit and the {@code GL_TEXTURE_2D} binding per unit. */
    TEXTURES,
    /** Vertex array object, array buffer and element array buffer. */
    VERTEX_INPUT,

    // ── Pure state domains ────────────────────────────────────────────────────
    /** Fixed-function alpha test. Compatibility profile only; a no-op on core profiles. */
    ALPHA_TEST,
    /** Blend enable, separate factors and separate equations. */
    BLEND,
    /** Depth test enable, write mask and compare function. */
    DEPTH,
    /** Cull enable, culled face and front-face winding. */
    CULL,
    /** Stencil test enable, function, reference, masks and the three operations. */
    STENCIL,
    /** Colour write mask, per render target. */
    COLOR_MASK,
    /** Viewport rectangle. */
    VIEWPORT,
    /** Scissor test enable and the scissor box. */
    SCISSOR,
    /** Polygon offset enables for fill, line and point, plus factor and units. */
    POLYGON_OFFSET,
    /** Polygon rasterisation mode, per face orientation. */
    POLYGON_MODE,
    /** Rasterised line width. */
    LINE_WIDTH,
    /** Rasterised point size. */
    POINT_SIZE
}
