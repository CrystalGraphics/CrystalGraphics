package io.github.somehussar.crystalgraphics.api.state;

/**
 * Identifies the GL state domains that {@link io.github.somehussar.crystalgraphics.gl.state.CgGlState}
 * can independently capture and restore. Pass the desired slots to
 * {@code CgGlState.save(CgGlSlot...)} to capture only what you intend to modify.
 *
 * <p>This enum replaces {@code CgStateBoundary.Slot}. Each constant maps 1-to-1 to a
 * static inner class inside {@code CgGlStates} which owns the GL capture and restore
 * logic for that domain.</p>
 *
 * <p>Ordinals are stable and used as array indices inside
 * {@link io.github.somehussar.crystalgraphics.gl.state.CgGlScope}.</p>
 */
public enum CgGlSlot {
    // ── Binding slots ──────────────────────────────────────────────────────────
    /** glBindFramebuffer — draw + read FBO IDs and call family. */
    FBO,
    /** glUseProgram — shader program ID and call family. */
    PROGRAM,
    /** glBindTexture — all 32 2D texture units + active unit. */
    TEXTURES,
    /** VAO, VBO (GL_ARRAY_BUFFER), EBO (GL_ELEMENT_ARRAY_BUFFER). */
    VERTEX_INPUT,
    // ── Render state ───────────────────────────────────────────────────────────
    /** GL_ALPHA_TEST enable, alpha func, alpha ref (compat profile). */
    ALPHA_TEST,
    /** GL_BLEND enable, blend func (separate RGB/alpha), blend equation. */
    BLEND,
    /** GL_DEPTH_TEST enable, depth func, depth write mask. */
    DEPTH,
    /** GL_CULL_FACE enable, cull mode, front face. */
    CULL,
    /** GL_STENCIL_TEST enable, stencil func/ref/mask, stencil op. */
    STENCIL,
    // ── Output / framebuffer state ─────────────────────────────────────────────
    /** glColorMask — per-channel write enables (R, G, B, A). */
    COLOR_MASK,
    /** glViewport — x, y, width, height. */
    VIEWPORT,
    /** GL_SCISSOR_TEST enable + glScissor box (x, y, width, height). */
    SCISSOR,
    /** GL_POLYGON_OFFSET_FILL/LINE/POINT enables + factor + units. */
    POLYGON_OFFSET,
    // ── Raster / fixed-function state ──────────────────────────────────────────
    /** glPolygonMode — front and back fill mode. */
    POLYGON_MODE,
    /** glPointSize. */
    POINT_SIZE,
    /** glLineWidth. */
    LINE_WIDTH,
}
