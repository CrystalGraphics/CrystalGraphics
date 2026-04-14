package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.*;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.api.text.CgTextConstraints;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexConsumer;
import io.github.somehussar.crystalgraphics.gl.render.CgDynamicTextureRenderLayer;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.state.CgTextureState;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Batched text renderer for bitmap, MSDF, and MTSDF glyph atlases.
 *
 * <p>The renderer consumes a pre-built {@link CgTextLayout}, resolves glyphs
 * through {@link CgFontRegistry}, sorts them by GL state, then submits quads
 * through the layer-based batching architecture via {@link CgDynamicTextureRenderLayer}.
 * The layer handles automatic flushing on texture state changes. GL state
 * (blend, depth, cull) is managed by the layer's {@code CgRenderState} at
 * flush time, not by this renderer.</p>
 *
 * <h3>Multi-Page Atlas Batching</h3>
 * <p>The renderer supports multi-page atlases by converting glyph atlas regions
 * into {@link CgGlyphPlacement} records that carry page identity (index and GL
 * texture ID), plane bounds, and per-page distance-field configuration ({@code pxRange}).
 * Quads are sorted by {@link CgDrawBatchKey} (atlas mode, page texture, pxRange)
 * so bitmap batches draw before distance-field batches. On batch-key transitions
 * the layer's texture and render state are swapped (triggering auto-flush).</p>
 *
 * <h3>Three-Space Model</h3>
 * <p>The text rendering pipeline enforces a strict three-space separation
 * (analogous to CSS Transforms — layout is unaffected by draw-time transforms):</p>
 * <ol>
 *   <li><strong>Logical layout space</strong> — coordinates used by {@link CgTextLayout}
 *       for width, height, line breaking, glyph advances, kerning, and caret math.
 *       These never change based on draw-time transforms. Owning types: {@code CgShapedRun},
 *       {@code CgTextLayout}, {@code CgFontMetrics}, {@code CgFontKey}.</li>
 *   <li><strong>Physical raster space</strong> — the actual raster size used for glyph
 *       rendering at draw time, derived from {@code baseTargetPx × poseScale} via
 *       {@link CgTextScaleResolver}. Physical bearings and extents live in
 *       {@link CgGlyphPlacement} (multi-page) and are normalized back into logical
 *       space at the quad-placement boundary before combining with pen positions.</li>
 *   <li><strong>Composite space</strong> — PoseStack/model-view/projection transforms
 *       applied by the GPU shaders at render time. The PoseStack in 2D mode represents
 *       UI scale; in 3D mode it represents model-view positioning.</li>
 * </ol>
 *
 * <h3>Metric Normalization</h3>
 * <p>Physical atlas metrics are normalized to logical space at the quad-placement
 * boundary using {@link #logicalMetricScale(int, int)}:
 * {@code scaleFactor = baseTargetPx / (float) effectiveTargetPx}. This is applied
 * to plane bounds from {@link CgGlyphPlacement}. The normalization ensures
 * that UI scale changes affect raster quality without corrupting spacing or
 * kerning.</p>
 *
 * <h3>Projection and Context Model</h3>
 * <p>Rather than requiring callers to pass a raw {@code FloatBuffer projectionMatrix}
 * on every draw call, the renderer consumes a {@link CgTextRenderContext} that holds
 * the projection matrix and scale resolver. The context is set once (or updated on
 * resize) and reused across frames. The {@link PoseStack} — which changes per draw —
 * is passed directly to the draw method.</p>
 *
 * <h3>World-Space Extension</h3>
 * <p>World-space/3D text uses a separate entry point ({@link #drawWorld}) with a
 * {@link CgWorldTextRenderContext} that enforces always-MSDF rendering, enables
 * depth testing, and supports projection-aware quality/LOD policy via
 * {@link ProjectedSizeEstimator}. The PoseStack in world mode represents model-view
 * positioning (entity rotation, billboard transforms), not UI zoom. Layout metrics
 * remain in logical space regardless of camera distance or FOV.</p>
 *
 * <h3>Layer-Based Submission</h3>
 * <p>All draw methods require a caller-provided {@link CgDynamicTextureRenderLayer}.
 * The layer manages texture state transitions (atlas page changes) and batches quads
 * via the new {@code CgVertexWriter}/{@code CgVertexConsumer} infrastructure. GL state
 * (depth, cull, blend) is handled by the layer's {@code CgRenderState} at flush time,
 * not by this renderer.</p>
 *
 * <p>The layer is expected to already be in the "begun" state (managed by the
 * owning {@code CgBufferSource}). This renderer does not call begin/end on the
 * layer. Text layer factories are provided by
 * {@link CgTextLayers}.</p>
 *
 * <h3>Authoritative Hot Path</h3>
 * <p>The current pipeline is centered on the paged-atlas path:</p>
 * <ol>
 *   <li>String-based draw overloads call {@link #layout(String, CgFontFamily, CgTextConstraints)} or its font variant</li>
 *   <li>{@link CgTextLayoutBuilder} produces a {@link CgTextLayout}</li>
 *   <li>{@link #drawInternalLayer} resolves raster tier</li>
 *   <li>{@link #buildPagedGlyphBatch} converts layout output into {@link CgGlyphPlacement} records</li>
 *   <li>{@link #submitSortedQuadsToLayer} sorts quads by GL state and submits them through the layer</li>
 * </ol>
 */
public class CgTextRenderer {
    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  SHADERS SETUP
    // ══════════════════════════════════════════════════════════════════════════════════════════
    private static final String BITMAP_VERT = "/shader/bitmap_text.vert", BITMAP_FRAG = "/shader/bitmap_text.frag";
    public static final CgShader BITMAP_SHADER = CgShaderFactory.load(BITMAP_VERT, BITMAP_FRAG, CgVertexFormat.POS2_UV2_COL4UB);

    private static final String MSDF_VERT = "/shader/msdf_text.vert", MSDF_FRAG = "/shader/msdf_text.frag";
    public static final CgShader MSDF_SHADER = CgShaderFactory.load(MSDF_VERT, MSDF_FRAG, CgVertexFormat.POS2_UV2_COL4UB);

    private static final String MTSDF_VERT = "/shader/mtsdf_text.vert", MTSDF_FRAG = "/shader/mtsdf_text.frag";
    public static final CgShader MTSDF_SHADER = CgShaderFactory.load(MTSDF_VERT, MTSDF_FRAG, CgVertexFormat.POS2_UV2_COL4UB);

    private static final CgRenderState BITMAP_LAYER_STATE = CgRenderState.builder(BITMAP_SHADER)
            .blend(CgBlendState.ALPHA)
            .texture(CgTextureState.dynamic(GL11.GL_TEXTURE_2D, 0, "u_atlas"))
            .build();

    private static final CgRenderState MSDF_LAYER_STATE = CgRenderState.builder(MSDF_SHADER)
            .blend(CgBlendState.ALPHA)
            .texture(CgTextureState.dynamic(GL11.GL_TEXTURE_2D, 0, "u_atlas"))
            .build();

    private static final CgRenderState MTSDF_LAYER_STATE = CgRenderState.builder(MTSDF_SHADER)
            .blend(CgBlendState.ALPHA)
            .texture(CgTextureState.dynamic(GL11.GL_TEXTURE_2D, 0, "u_atlas"))
            .build();
    
    // ══════════════════════════════════════════════════════════════════════════════════════════
    
    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    public static boolean diagnosticLogging = false;

    private static final CgTextLayoutBuilder LAYOUT_BUILDER = new CgTextLayoutBuilder();
    private final CgFontRegistry registry;

    private boolean deleted;

    private CgTextRenderer(CgFontRegistry registry) {
        this.registry = registry;
    }

    /**
     * Creates the renderer façade.
     *
     * <p>The canonical renderer does not create any batch infrastructure.
     * Batch/layer ownership belongs to the caller / buffer source / render context.
     * This factory only validates backend availability and creates the façade.</p>
     */
    public static CgTextRenderer create(CgCapabilities caps, CgFontRegistry registry) {
        if (caps.preferredFboBackend() == CgCapabilities.Backend.NONE || (!caps.isCoreShaders() && !caps.isArbShaders()) || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException("CgTextRenderer requires a framebuffer backend, a shader backend, VAO support, and glMapBufferRange");
        }

        return new CgTextRenderer(registry);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  2D / UI DRAW ENTRY POINTS (layer-based)
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Layer-based 2D draw entry point — the canonical path for UI text rendering.
     *
     * <p>Submits text quads through the caller-provided {@link CgDynamicTextureRenderLayer}.
     * The layer manages texture state transitions (atlas page changes) and batches quads
     * via the new {@code CgVertexWriter}/{@code CgVertexConsumer} infrastructure. GL state
     * (depth, cull, blend) is handled by the layer's {@code CgRenderState} at flush time,
     * not by this method.</p>
     *
     * <p>This method does <strong>not</strong> perform GL state save/restore. That
     * responsibility belongs to the render pass or buffer source that owns the layer
     * lifecycle.</p>
     *
     * @param textLayer the dynamic texture render layer for text submission
     * @param layout    the pre-built text layout
     * @param family    the font family to render with
     * @param x         local logical X origin
     * @param y         local logical Y origin
     * @param rgba      packed RGBA color (0xRRGGBBAA)
     * @param frame     current frame number for atlas LRU
     * @param context   the render context providing projection and scale resolver
     * @param pose      the current PoseStack providing model-view transform
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFontFamily family,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        if (textLayer == null) throw new IllegalArgumentException("textLayer must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;

        drawInternalLayer(textLayer, layout, family, x, y, rgba, frame, context, pose.last(), context.getScaleResolver());
    }

    /**
     * Layer-based 2D draw with single font (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFont font,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        draw(textLayer, layout, CgFontFamily.of(font), x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw from string (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        draw(textLayer, text, family, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw from string with constraints (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family,
                     CgTextConstraints constraints, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        draw(textLayer, layout(text, family, constraints), family, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw from string with single font (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFont font,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        requireSizedFont(font);
        draw(textLayer, layout(text, font, CgTextConstraints.UNBOUNDED), font, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw from string with single font and constraints (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFont font,
                     CgTextConstraints constraints, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        requireSizedFont(font);
        draw(textLayer, layout(text, font, constraints), font, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw with explicit targetPx and single font (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFont font, int targetPx,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(textLayer, layout(text, sizedFont, CgTextConstraints.UNBOUNDED), sizedFont, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw with explicit targetPx, constraints, and single font (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFont font, int targetPx,
                     CgTextConstraints constraints, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(textLayer, layout(text, sizedFont, constraints), sizedFont, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw with layout + single font + explicit targetPx (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFont font, int targetPx,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(textLayer, layout, sizedFont, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw with layout + family + explicit targetPx (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFontFamily family, int targetPx,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        draw(textLayer, layout, sizeFamily(family, targetPx), x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw from string with family + explicit targetPx (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family, int targetPx,
                     float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        draw(textLayer, text, family, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based 2D draw from string with family + explicit targetPx + constraints (convenience).
     */
    public void draw(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family, int targetPx,
                     CgTextConstraints constraints, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        CgFontFamily sizedFamily = sizeFamily(family, targetPx);
        draw(textLayer, layout(text, sizedFamily, constraints), sizedFamily, x, y, rgba, frame, context, pose);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  WORLD-SPACE / 3D DRAW ENTRY POINTS (layer-based)
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Layer-based world-space 3D text draw entry point.
     *
     * <p>Renders text in 3D world space with always-MSDF rendering. Unlike the
     * 2D {@link #draw} method, this entry point uses world-text semantics from the
     * {@link CgWorldTextRenderContext}. Depth testing and GL state are managed
     * by the layer's render state, not by this method.</p>
     *
     * @param textLayer the dynamic texture render layer for text submission
     * @param layout    the pre-built text layout (logical coordinates)
     * @param family    the font family to render with
     * @param x         local logical X origin inside the current pose
     * @param y         local logical Y origin inside the current pose
     * @param rgba      packed RGBA color (0xRRGGBBAA)
     * @param frame     current frame number for atlas LRU
     * @param context   the world-text render context (always-MSDF, projection-aware)
     * @param pose      the current PoseStack providing model-view transform
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFontFamily family,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        if (textLayer == null) throw new IllegalArgumentException("textLayer must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;

        drawInternalLayer(textLayer, layout, family, x, y, rgba, frame, context, pose.last(), context.getScaleResolver());
    }

    /**
     * Layer-based world-space draw with single font (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFont font,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        drawWorld(textLayer, layout, CgFontFamily.of(font), x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw with single font + targetPx (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFont font, int targetPx,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        drawWorld(textLayer, layout, sizedFont, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(textLayer, text, family, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with constraints (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family,
                          CgTextConstraints constraints, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(textLayer, layout(text, family, constraints), family, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with single font (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFont font,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        requireSizedFont(font);
        drawWorld(textLayer, layout(text, font, CgTextConstraints.UNBOUNDED), font, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with single font + constraints (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFont font,
                          CgTextConstraints constraints, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        requireSizedFont(font);
        drawWorld(textLayer, layout(text, font, constraints), font, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with single font + targetPx (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFont font, int targetPx,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(textLayer, text, font, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with single font + targetPx + constraints (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFont font, int targetPx,
                          CgTextConstraints constraints, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        drawWorld(textLayer, layout(text, sizedFont, constraints), sizedFont, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw with family + targetPx (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFontFamily family, int targetPx,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(textLayer, layout, sizeFamily(family, targetPx), x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with family + targetPx (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family, int targetPx,
                          float x, float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(textLayer, text, family, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    /**
     * Layer-based world-space draw from string with family + targetPx + constraints (convenience).
     */
    public void drawWorld(CgDynamicTextureRenderLayer textLayer, String text, CgFontFamily family, int targetPx,
                          CgTextConstraints constraints, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        CgFontFamily sizedFamily = sizeFamily(family, targetPx);
        drawWorld(textLayer, layout(text, sizedFamily, constraints), sizedFamily, x, y, rgba, frame, context, pose);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    public void delete() {
        if (deleted) return;
        deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  INTERNAL LAYER-BASED PIPELINE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Layer-based renderer core.
     *
     * <p>Resolves the effective raster tier, builds the paged glyph batch, and
     * submits sorted quads through the provided {@link CgDynamicTextureRenderLayer}.
     * GL state is managed by the layer's render state, not by this method.</p>
     */
    private void drawInternalLayer(CgDynamicTextureRenderLayer textLayer, CgTextLayout layout, CgFontFamily family,
                                    float x, float y, int rgba, long frame, CgTextRenderContext context,
                                    PoseStack.Pose pose, CgTextScaleResolver scaleResolver) {
        CgFontKey fontKey = family.getPrimarySource().getKey();
        CgFontMetrics metrics = layout.getMetrics();

        int previousEffectiveTargetPx = context.getPreviousEffectiveTargetPx(fontKey);
        int effectiveTargetPx = scaleResolver.resolveEffectiveTargetPx(fontKey.getTargetPx(), pose, previousEffectiveTargetPx);
        context.setPreviousEffectiveTargetPx(fontKey, effectiveTargetPx);

        boolean previousMsdf = previousEffectiveTargetPx > 0 ? context.wasMsdf(fontKey) : effectiveTargetPx >= 32;
        boolean wantMsdf = scaleResolver.shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setWasMsdf(fontKey, wantMsdf);

        PagedGlyphBatch glyphBatch = buildPagedGlyphBatch(layout, family, x, y, frame, context, fontKey, effectiveTargetPx, wantMsdf, metrics);

        submitSortedQuadsToLayer(textLayer, glyphBatch.placements, glyphBatch.glyphX, glyphBatch.glyphY, rgba,
                fontKey.getTargetPx(), effectiveTargetPx, context, pose.pose());
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  INTERNAL TARGET-BASED PIPELINE (V3.1 draw-list support)
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Target-based renderer core for the V3.2 draw-list path.
     *
     * <p>Same glyph resolution and batch building as the layer path, but emits
     * quads through a generic {@link CgTextQuadSink} instead of a layer.
     * This enables the text renderer to record into the UI draw list without
     * knowing about draw-list internals.</p>
     *
     * <p>The existing layer-based public {@code draw()} and {@code drawWorld()}
     * overloads remain unchanged for non-UI text users.</p>
     */
    public void drawInternalTarget(CgTextQuadSink sink, CgTextLayout layout, CgFontFamily family,
                                   float x, float y, int rgba, long frame, CgTextRenderContext context,
                                   PoseStack pose) {
        if (sink == null) throw new IllegalArgumentException("sink must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;

        CgFontKey fontKey = family.getPrimarySource().getKey();
        CgFontMetrics metrics = layout.getMetrics();
        PoseStack.Pose poseTop = pose.last();

        int previousEffectiveTargetPx = context.getPreviousEffectiveTargetPx(fontKey);
        CgTextScaleResolver scaleResolver = context.getScaleResolver();
        int effectiveTargetPx = scaleResolver.resolveEffectiveTargetPx(fontKey.getTargetPx(), poseTop, previousEffectiveTargetPx);
        context.setPreviousEffectiveTargetPx(fontKey, effectiveTargetPx);

        boolean previousMsdf = previousEffectiveTargetPx > 0 ? context.wasMsdf(fontKey) : effectiveTargetPx >= 32;
        boolean wantMsdf = scaleResolver.shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setWasMsdf(fontKey, wantMsdf);

        PagedGlyphBatch glyphBatch = buildPagedGlyphBatch(layout, family, x, y, frame, context, fontKey, effectiveTargetPx, wantMsdf, metrics);

        submitSortedQuadsToTarget(sink, glyphBatch.placements, glyphBatch.glyphX, glyphBatch.glyphY, rgba,
                fontKey.getTargetPx(), effectiveTargetPx, context, poseTop.pose());
    }

    /**
     * Convenience overload using a single font.
     */
    public void drawInternalTarget(CgTextQuadSink sink, CgTextLayout layout, CgFont font,
                                   float x, float y, int rgba, long frame, CgTextRenderContext context,
                                   PoseStack pose) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        drawInternalTarget(sink, layout, CgFontFamily.of(font), x, y, rgba, frame, context, pose);
    }

    /**
     * Convenience overload from string.
     */
    public void drawInternalTarget(CgTextQuadSink sink, String text, CgFontFamily family,
                                   float x, float y, int rgba, long frame, CgTextRenderContext context,
                                   PoseStack pose) {
        drawInternalTarget(sink, layout(text, family, CgTextConstraints.UNBOUNDED), family, x, y, rgba, frame, context, pose);
    }

    /**
     * Sorts glyph placements by GL state and submits them through the provided
     * {@link CgDynamicTextureRenderLayer}.
     *
     * <p>On batch-key transitions (atlas page / atlas mode changes), this method:
     * <ol>
     *   <li>Calls {@link CgDynamicTextureRenderLayer#setTexture(int)} to signal
     *       the texture change (the layer auto-flushes if the texture ID differs)</li>
     *   <li>Applies per-batch shader uniforms ({@code u_modelview}, {@code u_pxRange})
     *       via the shader's ephemeral bindings — these are picked up when the layer
     *       flushes and applies its {@code CgRenderState}</li>
     *   <li>Emits glyph quads through the layer's {@code CgVertexConsumer}</li>
     * </ol>
     *
     * <p>The layer is expected to already be in the "begun" state (managed by the
     * owning {@code CgBufferSource}). This method does not call begin/end on the
     * layer.</p>
     */
    void submitSortedQuadsToLayer(CgDynamicTextureRenderLayer textLayer, CgGlyphPlacement[] placements,
                                  float[] glyphX, float[] glyphY, int rgba,
                                  int baseTargetPx, int effectiveTargetPx,
                                  CgTextRenderContext context, Matrix4f modelView) {
        // Count visible placements
        int visibleCount = 0;
        for (int i = 0; i < placements.length; i++) {
            if (placements[i] != null && placements[i].hasGeometry())
                visibleCount++;
        }
        if (visibleCount == 0) return;

        // Build sortable entries: (batchKey, originalIndex)
        int[] sortedIndices = new int[visibleCount];
        CgDrawBatchKey[] batchKeys = new CgDrawBatchKey[visibleCount];
        int si = 0;
        for (int i = 0; i < placements.length; i++) {
            CgGlyphPlacement p = placements[i];
            if (p != null && p.hasGeometry()) {
                sortedIndices[si] = i;
                batchKeys[si] = new CgDrawBatchKey(
                        p.getAtlasType(), p.getPageTextureId(), p.getPxRange());
                si++;
            }
        }

        // Sort by batch key using insertion sort (stable, good for small N
        // and nearly-sorted data which is common since glyphs from the same
        // atlas page are often consecutive in the layout)
        for (int i = 1; i < visibleCount; i++) {
            CgDrawBatchKey keyI = batchKeys[i];
            int idxI = sortedIndices[i];
            int j = i - 1;
            while (j >= 0 && batchKeys[j].compareTo(keyI) > 0) {
                batchKeys[j + 1] = batchKeys[j];
                sortedIndices[j + 1] = sortedIndices[j];
                j--;
            }
            batchKeys[j + 1] = keyI;
            sortedIndices[j + 1] = idxI;
        }

        // Submit sorted quads through the layer. On batch-key change, update the
        // layer's texture (which triggers an auto-flush) and set shader uniforms.
        CgDrawBatchKey currentKey = null;

        for (int s = 0; s < visibleCount; s++) {
            CgDrawBatchKey thisKey = batchKeys[s];

            // On batch key change, update texture and shader uniforms on the layer
            if (currentKey == null || !thisKey.equals(currentKey)) {
                CgRenderState renderState = thisKey.isMtsdf() ? MTSDF_LAYER_STATE
                        : thisKey.isDistanceField() ? MSDF_LAYER_STATE : BITMAP_LAYER_STATE;
                textLayer.setRenderState(renderState);

                // Set texture on the dynamic layer — triggers flush if texture changed
                textLayer.setTexture(thisKey.getTextureId());

                // Resolve the shader for this batch key's atlas type and apply
                // per-batch uniforms via ephemeral bindings. These bindings are
                // consumed when the layer's CgRenderState applies the shader at
                // the next flush.
                CgShader shader = renderState.getShader();
                shader.applyBindings(bi -> {
                    bi.mat4("u_modelview", modelView);
                    bi.mat4("u_projection", context.getProjection());
                    bi.set1i("u_atlas", 0);
                    if (thisKey.isDistanceField()) bi.set1f("u_pxRange", thisKey.getPxRange());
                });

                currentKey = thisKey;
            }

            int origIdx = sortedIndices[s];
            CgGlyphPlacement p = placements[origIdx];
            int placementTargetPx = p.getKey().getFontKey().getTargetPx();
            float scaleFactor = logicalMetricScale(baseTargetPx, p.isDistanceField() ? placementTargetPx : effectiveTargetPx);
            addQuadFromPlacementToLayer(textLayer, p, glyphX[origIdx], glyphY[origIdx], rgba, scaleFactor);

            if (diagnosticLogging) {
                LOGGER.info("[LayerBatchDiag] atlasType=" + thisKey.getAtlasType()
                        + ", textureId=" + thisKey.getTextureId()
                        + ", pxRange=" + thisKey.getPxRange());
            }
        }
    }

    /**
     * Computes quad geometry from a {@link CgGlyphPlacement} and submits it to the
     * {@link CgDynamicTextureRenderLayer}.
     *
     * <p>Uses plane bounds for geometry placement. The quad is submitted through
     * the layer's {@link CgVertexConsumer} (which is a {@code CgVertexWriter}
     * backed by the layer's staging buffer).</p>
     */
    private void addQuadFromPlacementToLayer(CgDynamicTextureRenderLayer textLayer, CgGlyphPlacement p,
                                             float penX, float penY, int rgba, float scaleFactor) {
        // Plane bounds are in physical raster space; normalize to logical.
        // planeLeft = bearing offset from pen; planeTop = bearing above baseline.
        // The quad origin is (penX + bearingX, penY - bearingY) in the existing
        // convention (Y-down screen space, bearingY positive = above baseline).
        float logicalBearingX = p.getPlaneLeft() * scaleFactor;
        float logicalBearingY = p.getPlaneTop() * scaleFactor;
        float logicalWidth = p.getPlaneWidth() * scaleFactor;
        float logicalHeight = p.getPlaneHeight() * scaleFactor;

        float qx = penX + logicalBearingX;
        float qy = penY - logicalBearingY;

        if (diagnosticLogging) {
            LOGGER.info(String.format(
                    "[LayerQuadDiag] glyphId=%d penX=%.2f penY=%.2f planeL=%.2f planeB=%.2f planeT=%.2f planeW=%.2f planeH=%.2f qx=%.2f qy=%.2f page=%d tex=%d atlasType=%s distanceField=%b pxRange=%.1f",
                    p.getKey().getGlyphId(), penX, penY,
                    logicalBearingX, p.getPlaneBottom() * scaleFactor,
                    logicalBearingY, logicalWidth, logicalHeight,
                    qx, qy,
                    p.getPageIndex(), p.getPageTextureId(),
                    p.getAtlasType(), p.isDistanceField(), p.getPxRange()));
        }
        
        float u0 = p.getU0(), v0 = p.getV0(), u1 = p.getU1(), v1 = p.getV1();

        // Extract RGBA components for the vertex consumer's color(r,g,b,a) contract
        int r = (rgba >>> 24) & 0xFF;
        int g = (rgba >>> 16) & 0xFF;
        int b = (rgba >>> 8)  & 0xFF;
        int a =  rgba         & 0xFF;

        CgVertexConsumer vc = textLayer.vertex();
        vc.vertex(qx, qy).uv(u0, v0).color(r, g, b, a).endVertex();
        vc.vertex(qx + logicalWidth, qy).uv(u1, v0).color(r, g, b, a).endVertex();
        vc.vertex(qx + logicalWidth, qy + logicalHeight).uv(u1, v1).color(r, g, b, a).endVertex();
        vc.vertex(qx, qy + logicalHeight).uv(u0, v1).color(r, g, b, a).endVertex();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  TARGET-BASED QUAD EMISSION (V3.2 — CgTextQuadSink)
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Sorts glyph placements by GL state and submits them through a generic
     * {@link CgTextQuadSink}. Same batch-key sorting as the layer path.
     *
     * <p>On batch-key transitions, calls {@link CgTextQuadSink#beginBatch} which
     * flushes any pending vertices from the previous batch. After all quads are
     * emitted, calls {@link CgTextQuadSink#endText()} to flush remaining vertices.</p>
     */
    void submitSortedQuadsToTarget(CgTextQuadSink sink, CgGlyphPlacement[] placements,
                                   float[] glyphX, float[] glyphY, int rgba,
                                   int baseTargetPx, int effectiveTargetPx,
                                   CgTextRenderContext context, Matrix4f modelView) {
        int visibleCount = 0;
        for (int i = 0; i < placements.length; i++) {
            if (placements[i] != null && placements[i].hasGeometry())
                visibleCount++;
        }
        if (visibleCount == 0) return;

        int[] sortedIndices = new int[visibleCount];
        CgDrawBatchKey[] batchKeys = new CgDrawBatchKey[visibleCount];
        int si = 0;
        for (int i = 0; i < placements.length; i++) {
            CgGlyphPlacement p = placements[i];
            if (p != null && p.hasGeometry()) {
                sortedIndices[si] = i;
                batchKeys[si] = new CgDrawBatchKey(
                        p.getAtlasType(), p.getPageTextureId(), p.getPxRange());
                si++;
            }
        }

        // Insertion sort by batch key (same as layer path)
        for (int i = 1; i < visibleCount; i++) {
            CgDrawBatchKey keyI = batchKeys[i];
            int idxI = sortedIndices[i];
            int j = i - 1;
            while (j >= 0 && batchKeys[j].compareTo(keyI) > 0) {
                batchKeys[j + 1] = batchKeys[j];
                sortedIndices[j + 1] = sortedIndices[j];
                j--;
            }
            batchKeys[j + 1] = keyI;
            sortedIndices[j + 1] = idxI;
        }

        CgDrawBatchKey currentKey = null;

        for (int s = 0; s < visibleCount; s++) {
            CgDrawBatchKey thisKey = batchKeys[s];

            if (currentKey == null || !thisKey.equals(currentKey)) {
                CgRenderState renderState = thisKey.isMtsdf() ? MTSDF_LAYER_STATE
                        : thisKey.isDistanceField() ? MSDF_LAYER_STATE : BITMAP_LAYER_STATE;

                float pxRange = thisKey.isDistanceField() ? thisKey.getPxRange() : Float.NaN;
                sink.beginBatch(renderState, thisKey.getTextureId(), pxRange);
                currentKey = thisKey;
            }

            int origIdx = sortedIndices[s];
            CgGlyphPlacement p = placements[origIdx];
            int placementTargetPx = p.getKey().getFontKey().getTargetPx();
            float scaleFactor = logicalMetricScale(baseTargetPx, p.isDistanceField() ? placementTargetPx : effectiveTargetPx);
            addQuadFromPlacementToSink(sink, p, glyphX[origIdx], glyphY[origIdx], rgba, scaleFactor);
        }

        // Flush any remaining vertices from the last batch
        sink.endText();
    }

    private void addQuadFromPlacementToSink(CgTextQuadSink sink, CgGlyphPlacement p,
                                            float penX, float penY, int rgba, float scaleFactor) {
        // Plane bounds are in physical raster space; normalize to logical.
        float logicalBearingX = p.getPlaneLeft() * scaleFactor;
        float logicalBearingY = p.getPlaneTop() * scaleFactor;
        float logicalWidth = p.getPlaneWidth() * scaleFactor;
        float logicalHeight = p.getPlaneHeight() * scaleFactor;

        float qx = penX + logicalBearingX;
        float qy = penY - logicalBearingY;

        float u0 = p.getU0(), v0 = p.getV0(), u1 = p.getU1(), v1 = p.getV1();

        int r = (rgba >>> 24) & 0xFF;
        int g = (rgba >>> 16) & 0xFF;
        int b = (rgba >>> 8)  & 0xFF;
        int a =  rgba         & 0xFF;

        sink.emitQuad(qx, qy, qx + logicalWidth, qy + logicalHeight, u0, v0, u1, v1, r, g, b, a);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  PAGED GLYPH BATCH CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Builds the authoritative paged-atlas glyph batch for the current draw.
     *
     * <p>If a draw prefers distance fields but any visible glyph has to fall back
     * to bitmap for the current frame, the method reruns the batch in bitmap mode
     * so one draw never mixes bitmap and distance-field quality tiers.</p>
     */
    private PagedGlyphBatch buildPagedGlyphBatch(CgTextLayout layout, CgFontFamily family, float x, float y, long frame,
                                                 CgTextRenderContext context, CgFontKey fontKey, int effectiveTargetPx,
                                                 boolean wantMsdf, CgFontMetrics metrics) {
        PagedGlyphBatch batch = populatePagedGlyphBatch(layout, family, x, y, frame, context,
                fontKey, effectiveTargetPx, wantMsdf, metrics);
        if (!wantMsdf || !batch.usedBitmapFallback) return batch;

        // Do not mix MSDF and bitmap glyphs inside the same draw. If any glyph
        // in an MSDF-targeted draw falls back to bitmap (for example due to the
        // per-frame MSDF generation budget), rerender the whole batch in bitmap
        // for this frame so all glyphs share the same quality tier.
        return populatePagedGlyphBatch(layout, family, x, y, frame, context,
                fontKey, effectiveTargetPx, false, metrics);
    }

    private static CgFont resolveRunFont(CgTextLayout layout, CgFontFamily family, CgFontKey runFontKey) {
        CgFont resolvedFromLayout = layout.getResolvedFontsByKey().get(runFontKey);
        if (resolvedFromLayout != null) return resolvedFromLayout;
        
        return family.resolveLoadedFont(runFontKey);
    }

    /**
     * Converts logical layout output into paged {@link CgGlyphPlacement}
     * records plus per-glyph pen positions.
     *
     * <p>This is the central layout-to-atlas boundary. The method walks shaped
     * runs in logical order, converts each glyph into the correct runtime cache
     * key for the current raster tier, and asks {@link CgFontRegistry} where that
     * glyph lives in the atlas page set.</p>
     */
    private PagedGlyphBatch populatePagedGlyphBatch(CgTextLayout layout, CgFontFamily family, float x, float y, long frame, 
                                                    CgTextRenderContext context, CgFontKey fontKey, int effectiveTargetPx, 
                                                    boolean wantMsdf, CgFontMetrics metrics) {
        List<List<CgShapedRun>> lines = layout.getLines();
        int totalGlyphs = countGlyphs(lines);
        float[] glyphX = new float[totalGlyphs];
        float[] glyphY = new float[totalGlyphs];
        CgGlyphPlacement[] placements = new CgGlyphPlacement[totalGlyphs];

        boolean usedBitmapFallback = false;
        int index = 0;
        float penY = y;
        prequeueVisibleGlyphs(layout, family, effectiveTargetPx, wantMsdf, context, frame);
        for (List<CgShapedRun> line : lines) {
            float penX = x;
            for (CgShapedRun run : line) {
                CgFontKey runFontKey = run.getFontKey();
                CgFont runFont = resolveRunFont(layout, family, runFontKey);
                int[] glyphIds = run.getGlyphIds();
                float[] advancesX = run.getAdvancesX();
                float[] offsetsX = run.getOffsetsX();
                float[] offsetsY = run.getOffsetsY();
                for (int i = 0; i < glyphIds.length; i++) {
                    int subPixelBucket = resolveSubPixelBucket(context, runFontKey, effectiveTargetPx, offsetsX[i]);
                    CgGlyphKey glyphKey = new CgGlyphKey(runFontKey, glyphIds[i], wantMsdf, subPixelBucket);
                    placements[index] = registry.ensureGlyphPaged(
                            runFont, glyphKey, effectiveTargetPx, subPixelBucket, frame);
                    if (wantMsdf && placements[index] != null && !placements[index].isDistanceField()) {
                        usedBitmapFallback = true;
                    }
                    glyphX[index] = penX + offsetsX[i];
                    glyphY[index] = penY + offsetsY[i];
                    penX += advancesX[i];
                    index++;
                }
            }
            penY += metrics.getLineHeight();
        }
        return new PagedGlyphBatch(glyphX, glyphY, placements, usedBitmapFallback);
    }

    /**
     * Prequeues visible glyphs for asynchronous generation before the main ensure
     * pass runs.
     *
     * <p>This is a latency-hiding step, not a correctness step. The later
     * synchronous {@code ensureGlyphPaged(...)} calls still define the frame's
     * authoritative result, but prequeueing gives worker threads a chance to
     * prepare expensive glyphs before the immediate render request reaches them.</p>
     */
    private void prequeueVisibleGlyphs(CgTextLayout layout, CgFontFamily family, int effectiveTargetPx, boolean wantMsdf,
                                       CgTextRenderContext context, long frame) {
        List<List<CgShapedRun>> lines = layout.getLines();
        for (List<CgShapedRun> line : lines) {
            for (CgShapedRun run : line) {
                CgFontKey runFontKey = run.getFontKey();
                CgFont runFont = resolveRunFont(layout, family, runFontKey);
                int[] glyphIds = run.getGlyphIds();
                float[] offsetsX = run.getOffsetsX();
                for (int i = 0; i < glyphIds.length; i++) {
                    int subPixelBucket = resolveSubPixelBucket(context, runFontKey, effectiveTargetPx, offsetsX[i]);
                    CgGlyphKey glyphKey = new CgGlyphKey(runFontKey, glyphIds[i], wantMsdf, subPixelBucket);
                    registry.queueGlyphPaged(runFont, glyphKey, effectiveTargetPx, subPixelBucket, frame);
                }
            }
        }
    }

    private static final class PagedGlyphBatch {
        private final float[] glyphX;
        private final float[] glyphY;
        private final CgGlyphPlacement[] placements;
        private final boolean usedBitmapFallback;

        private PagedGlyphBatch(float[] glyphX, float[] glyphY, CgGlyphPlacement[] placements, boolean usedBitmapFallback) {
            this.glyphX = glyphX;
            this.glyphY = glyphY;
            this.placements = placements;
            this.usedBitmapFallback = usedBitmapFallback;
        }
    }

    private int countGlyphs(List<List<CgShapedRun>> lines) {
        int total = 0;
        for (List<CgShapedRun> line : lines) for (CgShapedRun run : line) total += run.getGlyphIds().length;
        
        return total;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Converts physical atlas metrics back into logical placement units.
     *
     * <p>The renderer shapes and advances text in logical/base units, but glyphs may
     * be rasterized at a larger or smaller effective physical size due to UI scale.
     * Placement must therefore normalize raster-time bearings and extents back into
     * logical space before combining them with pen positions.</p>
     */
    static float logicalMetricScale(int baseTargetPx, int effectiveTargetPx) {
        if (baseTargetPx <= 0) throw new IllegalArgumentException("baseTargetPx must be > 0");
        if (effectiveTargetPx <= 0) throw new IllegalArgumentException("effectiveTargetPx must be > 0");
        
        return (float) baseTargetPx / (float) effectiveTargetPx;
    }
    
    /**
     * Selects the sub-pixel bucket based on the effective target pixel size.
     * Uses the effective size (not base targetPx) because the effective size
     * determines whether sub-pixel positioning is perceptible.
     */
    static int selectSubPixelBucket(int effectiveTargetPx, float xOffset) {
        if (effectiveTargetPx >= CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX) return 0;
        
        float fractional = xOffset - (float) Math.floor(xOffset);
        if (fractional < 0.125f) return 0;
        if (fractional < 0.375f) return 1;
        if (fractional < 0.625f) return 2;
        if (fractional < 0.875f) return 3;
        
        return 0;
    }

    static int resolveSubPixelBucket(CgTextRenderContext context, CgFontKey fontKey, int effectiveTargetPx, float xOffset) {
        if (context.isWorldText()) return 0;
        if (context.isScaledUiRaster(fontKey, effectiveTargetPx)) return 0;
        
        return selectSubPixelBucket(effectiveTargetPx, xOffset);
    }

    /**
     * Shared layout helper used by the string-based draw overloads.
     *
     * <p>This is the string-to-layout boundary for the renderer. The actual
     * shaping, fallback resolution, and line breaking happen inside
     * {@link CgTextLayoutBuilder}; renderer code should treat the returned
     * {@link CgTextLayout} as the stable hand-off format for glyph resolution.</p>
     */
    static CgTextLayout layout(String text, CgFont font, CgTextConstraints constraints) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (constraints == null) throw new IllegalArgumentException("constraints must not be null");
        
        return LAYOUT_BUILDER.layout(text, font, constraints.getMaxWidth(), constraints.getMaxHeight());
    }

    static CgTextLayout layout(String text, CgFontFamily family, CgTextConstraints constraints) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (constraints == null) throw new IllegalArgumentException("constraints must not be null");
        
        return LAYOUT_BUILDER.layout(text, family, constraints.getMaxWidth(), constraints.getMaxHeight());
    }

    static CgFont requireSizedFont(CgFont font) {
        if (font == null) throw new IllegalArgumentException("font must not be null");
        if (!font.isSizeBound()) throw new IllegalArgumentException("font must be size-bound or supplied with targetPx");
        
        return font;
    }

    static CgFont requireSizedFont(CgFont font, int targetPx) {
        if (font == null) throw new IllegalArgumentException("font must not be null");
        if (targetPx <= 0) throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        
        return font.isSizeBound() && font.getTargetPx() == targetPx ? font : font.atSize(targetPx);
    }

    static CgFontFamily sizeFamily(CgFontFamily family, int targetPx) {
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (targetPx <= 0) throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        
        CgFont primary = family.getPrimarySource().requireFont().atSize(targetPx);
        List<CgFontSource> fallbackSources = new ArrayList<CgFontSource>();
        for (CgFontSource fallback : family.getFallbackSources()) {
            fallbackSources.add(new CgFontSource(fallback.requireFont().atSize(targetPx), fallback.getSourceLabel()));
        }
        return new CgFontFamily(family.getFamilyId(), new CgFontSource(primary, family.getPrimarySource().getSourceLabel()), fallbackSources);
    }
}
