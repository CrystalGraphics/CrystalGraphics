package com.crystalgraphics.text.render;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextConstraints;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.vertex.CgVertexConsumer;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.cache.CgFontRegistry;
import lombok.Getter;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Batched text renderer for bitmap, MSDF, and MTSDF glyph atlases.
 *
 * <p>The renderer consumes a pre-built {@link CgTextLayout}, resolves glyphs
 * through {@link CgFontRegistry}, sorts them by GL state, then submits quads
 * through its own owned {@link CgBatchRenderer}. Shader bind/unbind, texture
 * bind/unbind, and {@code CgRenderState} apply/clear on batch-key transitions
 * are handled directly by this class — see {@link #transitionTo}.</p>
 *
 * <h3>Multi-Page Atlas Batching</h3>
 * <p>The renderer supports multi-page atlases by converting glyph atlas regions
 * into {@link CgGlyphPlacement} records that carry page identity (index and GL
 * texture ID), plane bounds, and per-page distance-field configuration ({@code pxRange}).
 * Quads are sorted by {@link CgDrawBatchKey} (atlas mode, page texture, pxRange)
 * so bitmap batches draw before distance-field batches. On batch-key transitions
 * the active shader, texture, and render state are swapped (triggering a flush of
 * whatever was pending under the previous state).</p>
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
 * (or even a {@link CgTextRenderContext}) on every draw call, the renderer owns a
 * single {@link CgTextRenderContext} internally (see {@link #context()}). Callers
 * reach it via {@link #context()} for resize/projection updates or history resets,
 * and replace it wholesale via {@link #context(CgTextRenderContext)} to switch modes.
 * The {@link PoseStack} — which changes per draw — is still passed directly to the
 * draw method.</p>
 *
 * <h3>World-Space Extension</h3>
 * <p>World-space/3D text uses the same {@link #draw} entry point as 2D UI text —
 * calling {@link #context(CgTextRenderContext)} with one built via
 * {@link CgTextRenderContext#world} instead of {@link CgTextRenderContext#orthographic}
 * is what switches it on. That context's {@link PerspectiveScaleResolver} enforces
 * always-MSDF rendering and projection-aware quality/LOD policy via
 * {@link ProjectedSizeEstimator}; depth-tested render state (see
 * {@link #BITMAP_RENDER_STATE_WORLD} and siblings) is selected in this class via
 * {@link CgTextRenderContext#isWorldText()}. The PoseStack in world mode represents
 * model-view positioning (entity rotation, billboard transforms), not UI zoom. Layout
 * metrics remain in logical space regardless of camera distance or FOV.</p>
 *
 * <h3>Owned Batch Lifecycle</h3>
 * <p>{@code CgTextRenderer} owns a private {@link CgBatchRenderer} (format
 * {@link CgVertexFormat#POS2_UV2_COL4UB}) — no caller-provided layer or buffer
 * source is required. The renderer is frame-agnostic: {@link #beginBatch()}/
 * {@link #endBatch()} mark a batching window, not a render frame. The atlas LRU
 * clock used internally for glyph bookkeeping is read directly from
 * {@link CgGraphicsLifecycle#getCurrentFrame()} — callers no longer supply a
 * {@code frame} argument. Callers that issue several
 * {@code draw()} calls that should share one upload+draw wrap them in
 * {@link #beginBatch()}/{@link #endBatch()}. {@code draw()} also tolerates being called
 * with no active batch: each such call transparently wraps itself in its own
 * begin/flush/end, exactly as if a single-call {@code beginBatch()}/{@code endBatch()}
 * pair had been used. This makes {@code CgTextRenderer} usable as a standalone,
 * directly-instantiated object with no owning render pass — unlike UI's
 * {@code CgUiRenderer}, which is always driven by a larger owning context.</p>
 *
 * <h3>Authoritative Hot Path</h3>
 * <p>The current pipeline is centered on the paged-atlas path:</p>
 * <ol>
 *   <li>String-based draw overloads call {@link #layout(String, CgFontFamily, CgTextConstraints)} or its font variant</li>
 *   <li>{@link CgTextLayoutBuilder} produces a {@link CgTextLayout}</li>
 *   <li>{@link #drawInternal} resolves raster tier</li>
 *   <li>{@link #buildPagedGlyphBatch} converts layout output into {@link CgGlyphPlacement} records</li>
 *   <li>{@link #submitSortedQuads} sorts quads by GL state and submits them to the owned batch renderer</li>
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

    private static final CgRenderState BITMAP_RENDER_STATE = CgRenderState.builder()
            .blend(CgBlendState.ALPHA)
            .cull(CgCullState.NONE)
            .depth(CgDepthState.NONE)
            .build();

    private static final CgRenderState MSDF_RENDER_STATE = CgRenderState.builder()
            .blend(CgBlendState.ALPHA)
            .cull(CgCullState.NONE)
            .depth(CgDepthState.NONE)
            .build();

    private static final CgRenderState MTSDF_RENDER_STATE = CgRenderState.builder()
            .blend(CgBlendState.ALPHA)
            .cull(CgCullState.NONE)
            .depth(CgDepthState.NONE)
            .build();

    /**
     * World-space counterparts of {@link #BITMAP_RENDER_STATE}/{@link #MSDF_RENDER_STATE}/
     * {@link #MTSDF_RENDER_STATE} — depth-tested against opaque scene geometry (so world text
     * can be occluded by objects in front of it) but not depth-writing (so overlapping/blended
     * glyph edges within the same text block don't z-fight each other), per
     * world text's documented depth-test contract. Selected via
     * {@link CgTextRenderContext#isWorldText()} in {@link #submitSortedQuads}.
     */
    private static final CgRenderState BITMAP_RENDER_STATE_WORLD = CgRenderState.builder()
            .blend(CgBlendState.ALPHA)
            .cull(CgCullState.NONE)
            .depth(CgDepthState.TEST_ONLY)
            .build();

    private static final CgRenderState MSDF_RENDER_STATE_WORLD = CgRenderState.builder()
            .blend(CgBlendState.ALPHA)
            .cull(CgCullState.NONE)
            .depth(CgDepthState.TEST_ONLY)
            .build();

    private static final CgRenderState MTSDF_RENDER_STATE_WORLD = CgRenderState.builder()
            .blend(CgBlendState.ALPHA)
            .cull(CgCullState.NONE)
            .depth(CgDepthState.TEST_ONLY)
            .build();

    /** Initial CPU staging capacity of the owned {@link CgBatchRenderer}, in quads. */
    private static final int INITIAL_MAX_QUADS = 1024;

    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    public static boolean diagnosticLogging = false;

    private static final CgTextLayoutBuilder LAYOUT_BUILDER = new CgTextLayoutBuilder();
    private final CgFontRegistry registry = CgFontRegistry.get();

    // ── Owned batch lifecycle ────────────────────────────────────────────────
    private final CgBatchRenderer batchRenderer;
    private boolean batchActive;
    private CgShader activeShader;
    private CgRenderState activeRenderState;
    private int activeTextureId = -1;

    // ── Owned render context ────────────────────────────────────────────────
    private CgTextRenderContext context = CgTextRenderContext.orthographic(
            CgGraphicsLifecycle.getCurrentWidth(), CgGraphicsLifecycle.getCurrentHeight());

    /** Tracks the display window's resolution automatically — every {@link CgGraphicsLifecycle#onResize} call resizes
     * it in place, no manual per-frame dimension check needed.
     * Only resizes a 2D orthographic {@link #context}, 3D perspective contexts need to be manually resized.*/
    @Getter
    private boolean screenSized;

    @Getter
    private boolean deleted;

    private CgTextRenderer() {
        this.batchRenderer = CgBatchRenderer.create(CgVertexFormat.POS2_UV2_COL4UB, INITIAL_MAX_QUADS);
    }

    /**
     * Returns this renderer's owned {@link CgTextRenderContext}.
     *
     * <p>The returned context is fully mutable — callers use it directly for
     * resize/projection updates ({@link CgTextRenderContext#updateOrtho},
     * {@link CgTextRenderContext#updateProjection}), raster-history resets
     * ({@link CgTextRenderContext#clearHistory()}), and world-text projected-size
     * hints ({@link CgTextRenderContext#updateProjectedSize}). Defaults to an
     * orthographic context sized to {@link CgGraphicsLifecycle}'s current known window
     * dimensions (0×0 before the engine's first resize/init) — size it via
     * {@link CgTextRenderContext#updateOrtho} before first use if needed, or replace it
     * entirely via {@link #context(CgTextRenderContext)}.</p>
     */
    public CgTextRenderContext context() {
        return context;
    }

    /**
     * Replaces this renderer's owned {@link CgTextRenderContext} — the way to switch
     * between orthographic (2D UI) and world-space (3D) modes, since the two differ
     * in which {@link CgTextScaleResolver} they hold. Build the replacement via
     * {@link CgTextRenderContext#orthographic} or {@link CgTextRenderContext#world}.
     */
    public void context(CgTextRenderContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context;
    }

    /**
     * Creates the renderer façade, including its owned {@link CgBatchRenderer}, and
     * registers it with {@link CgTextRendererRegistry} — the registry doesn't own release
     * timing (callers must still call {@link #delete()} promptly when done), but sweeps
     * any renderer still alive at GL context teardown as a backstop, matching every other
     * GPU-resource registry in this codebase.
     */
    public static CgTextRenderer create() {
        CgTextRenderer renderer = new CgTextRenderer();
        CgTextRendererRegistry.get().register(renderer);
        return renderer;
    }

    /**
     * Creates the renderer façade like {@link #create()}, but additionally flags it as
     * screen-sized so its owned {@link CgTextRenderContext} tracks the display window's
     * resolution automatically — every {@link CgGraphicsLifecycle#onResize} call resizes
     * it in place, no manual per-frame dimension check needed.
     *
     * <p>Only for renderers whose context should follow the real display window
     * (UI overlays, HUDs). Renderers sized to something else — an offscreen FBO capture,
     * an atlas dump, a fixed test viewport — must use {@link #create()} and size their
     * context manually instead; auto-tracking window resize for those would silently
     * desync their projection from their actual target size.</p>
     */
    public static CgTextRenderer createScreenSized() {
        CgTextRenderer renderer = create();
        renderer.screenSized = true;
        return renderer;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  BATCH LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Opens a batching window: {@code draw()} calls made until the matching
     * {@link #endBatch()} record into the same underlying {@link CgBatchRenderer} pass and
     * are flushed together wherever the GL state permits (same shader/texture/render state).
     * This has no relation to a render frame — it is purely a batching scope, hence the name;
     * nothing here reads or depends on frame boundaries.
     *
     * <p>Not required — {@code draw()} tolerates being called with no active batch,
     * auto-wrapping itself. Call this only when issuing multiple draws that should batch
     * together.</p>
     *
     * @throws IllegalStateException if a batch is already active, or the renderer is deleted
     */
    public void beginBatch() {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (batchActive) throw new IllegalStateException("CgTextRenderer.beginBatch() called without a matching endBatch()");

        activeShader = null;
        activeRenderState = null;
        activeTextureId = -1;
        batchActive = true;
        batchRenderer.begin();
    }

    /**
     * Closes the batching window opened by {@link #beginBatch()}, flushing any pending
     * quads and unbinding whatever shader/texture/state is currently active.
     *
     * <p>Lenient: does nothing if no batch is active.</p>
     */
    public void endBatch() {
        if (!batchActive) return;

        flushPending();
        batchRenderer.end();
        activeShader = null;
        activeRenderState = null;
        activeTextureId = -1;
        batchActive = false;
    }

    /**
     * Flushes whatever is currently staged under {@link #activeShader}/
     * {@link #activeRenderState}/{@link #activeTextureId}, binding/applying them for the
     * duration of the draw call and restoring afterward. No-op if nothing is staged.
     */
    private void flushPending() {
        if (!batchRenderer.isDirty()) return;

        if (activeShader != null) activeShader.bind();
        if (activeTextureId >= 0) {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, activeTextureId);
        }
        if (activeRenderState != null) activeRenderState.apply();

        batchRenderer.flush();

        if (activeRenderState != null) activeRenderState.clear();
        if (activeTextureId >= 0) {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        }
        if (activeShader != null) activeShader.unbind();
    }

    /**
     * Transitions to the given shader/render-state/texture, flushing whatever was pending
     * under the previous combination first (mirrors the old
     * {@code CgDynamicTextureRenderLayer.setShader/setRenderState/setTexture} flush-on-change
     * behavior, collapsed into a single flush per actual transition).
     */
    private void transitionTo(CgShader shader, CgRenderState state, int textureId) {
        if (shader != activeShader || state != activeRenderState || textureId != activeTextureId) {
            flushPending();
            activeShader = shader;
            activeRenderState = state;
            activeTextureId = textureId;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  DRAW ENTRY POINTS
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Canonical draw entry point — 2D UI text and 3D world-space text alike.
     *
     * <p>There is no separate world-space entry point, and no separate world-space
     * context class. {@link CgTextRenderContext} is a single concrete class; which
     * {@link CgTextScaleResolver} strategy it holds (built via
     * {@link CgTextRenderContext#orthographic} vs {@link CgTextRenderContext#world})
     * fully determines 2D-vs-world behavior. Both flow through the exact same code
     * path ({@link #drawInternal}, {@link #submitSortedQuads}), which reads
     * {@link CgTextRenderContext#isWorldText()}/{@link CgTextRenderContext#getScaleResolver()}
     * — themselves just delegating to the resolver — to decide behavior rather than
     * branching on a separate method or class.</p>
     *
     * <p>Submits text quads to the renderer's own {@link CgBatchRenderer}. If no
     * {@link #beginBatch()} batch is active, this call transparently wraps itself in its
     * own begin/flush/end.</p>
     *
     * @param layout    the pre-built text layout
     * @param family    the font family to render with
     * @param x         local logical X origin
     * @param y         local logical Y origin
     * @param rgba      packed RGBA color (0xRRGGBBAA)
     * @param pose      the current PoseStack providing model-view transform
     */
    public void draw(CgTextLayout layout, CgFontFamily family,
                     float x, float y, int rgba, PoseStack pose) {

        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;

        boolean standalone = !batchActive;
        if (standalone) beginBatch();
        try {
            long frame = CgGraphicsLifecycle.getCurrentFrame();
            drawInternal(layout, family, x, y, rgba, frame, context, pose.last(), context.getScaleResolver());
        } finally {
            if (standalone) endBatch();
        }
    }

    /**
     * 2D draw with single font (convenience).
     */
    public void draw(CgTextLayout layout, CgFont font,
                     float x, float y, int rgba, PoseStack pose) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        draw(layout, CgFontFamily.of(font), x, y, rgba, pose);
    }

    /**
     * 2D draw from string (convenience).
     */
    public void draw(String text, CgFontFamily family,
                     float x, float y, int rgba, PoseStack pose) {
        draw(text, family, CgTextConstraints.UNBOUNDED, x, y, rgba, pose);
    }

    /**
     * 2D draw from string with constraints (convenience).
     */
    public void draw(String text, CgFontFamily family,
                     CgTextConstraints constraints, float x, float y, int rgba,
                     PoseStack pose) {
        draw(layout(text, family, constraints), family, x, y, rgba, pose);
    }

    /**
     * 2D draw from string with single font (convenience).
     */
    public void draw(String text, CgFont font,
                     float x, float y, int rgba, PoseStack pose) {
        requireSizedFont(font);
        draw(layout(text, font, CgTextConstraints.UNBOUNDED), font, x, y, rgba, pose);
    }

    /**
     * 2D draw from string with single font and constraints (convenience).
     */
    public void draw(String text, CgFont font,
                     CgTextConstraints constraints, float x, float y, int rgba,
                     PoseStack pose) {
        requireSizedFont(font);
        draw(layout(text, font, constraints), font, x, y, rgba, pose);
    }

    /**
     * 2D draw with explicit targetPx and single font (convenience).
     */
    public void draw(String text, CgFont font, int targetPx,
                     float x, float y, int rgba, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(layout(text, sizedFont, CgTextConstraints.UNBOUNDED), sizedFont, x, y, rgba, pose);
    }

    /**
     * 2D draw with explicit targetPx, constraints, and single font (convenience).
     */
    public void draw(String text, CgFont font, int targetPx,
                     CgTextConstraints constraints, float x, float y, int rgba,
                     PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(layout(text, sizedFont, constraints), sizedFont, x, y, rgba, pose);
    }

    /**
     * 2D draw with layout + single font + explicit targetPx (convenience).
     */
    public void draw(CgTextLayout layout, CgFont font, int targetPx,
                     float x, float y, int rgba, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(layout, sizedFont, x, y, rgba, pose);
    }

    /**
     * 2D draw with layout + family + explicit targetPx (convenience).
     */
    public void draw(CgTextLayout layout, CgFontFamily family, int targetPx,
                     float x, float y, int rgba, PoseStack pose) {
        draw(layout, sizeFamily(family, targetPx), x, y, rgba, pose);
    }

    /**
     * 2D draw from string with family + explicit targetPx (convenience).
     */
    public void draw(String text, CgFontFamily family, int targetPx,
                     float x, float y, int rgba, PoseStack pose) {
        draw(text, family, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, pose);
    }

    /**
     * 2D draw from string with family + explicit targetPx + constraints (convenience).
     */
    public void draw(String text, CgFontFamily family, int targetPx,
                     CgTextConstraints constraints, float x, float y, int rgba,
                     PoseStack pose) {
        CgFontFamily sizedFamily = sizeFamily(family, targetPx);
        draw(layout(text, sizedFamily, constraints), sizedFamily, x, y, rgba, pose);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    public void delete() {
        if (deleted) return;
        if (batchActive) endBatch();
        batchRenderer.delete();
        CgTextRendererRegistry.get().unregister(this);
        deleted = true;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  INTERNAL PIPELINE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Renderer core shared by the 2D and world-space entry points.
     *
     * <p>Resolves the effective raster tier, builds the paged glyph batch, and
     * submits sorted quads to the owned {@link CgBatchRenderer}.</p>
     */
    private void drawInternal(CgTextLayout layout, CgFontFamily family,
                               float x, float y, int rgba, long frame, CgTextRenderContext context,
                               PoseStack.Pose pose, CgTextScaleResolver scaleResolver) {
        CgFontKey fontKey = family.getPrimarySource().getKey();
        CgFontMetrics metrics = layout.getMetrics();

        CgTextRenderContext.RasterHistory previous = context.getHistory(fontKey);
        int previousEffectiveTargetPx = previous != null ? previous.effectiveTargetPx() : -1;
        int effectiveTargetPx = scaleResolver.resolveEffectiveTargetPx(fontKey.getTargetPx(), pose, previousEffectiveTargetPx);

        boolean previousMsdf = previous != null ? previous.wasMsdf() : effectiveTargetPx >= 32;
        boolean wantMsdf = scaleResolver.shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setHistory(fontKey, effectiveTargetPx, wantMsdf);

        PagedGlyphBatch glyphBatch = buildPagedGlyphBatch(layout, family, x, y, frame, context, fontKey, effectiveTargetPx, wantMsdf, metrics);

        submitSortedQuads(glyphBatch.placements, glyphBatch.glyphX, glyphBatch.glyphY, rgba,
                fontKey.getTargetPx(), effectiveTargetPx, context, pose.pose());
    }


    /**
     * Sorts glyph placements by GL state and submits them to the owned
     * {@link CgBatchRenderer}.
     *
     * <p>On batch-key transitions (atlas page / atlas mode changes), this method:
     * <ol>
     *   <li>Calls {@link #transitionTo} to flush any pending quads under the previous
     *       shader/render-state/texture combination, then adopt the new one</li>
     *   <li>Applies per-batch shader uniforms ({@code u_modelview}, {@code u_pxRange})
     *       via the shader's ephemeral bindings — these are picked up the next time the
     *       shader is bound (at the next {@link #flushPending()})</li>
     *   <li>Emits glyph quads through the batch renderer's {@code CgVertexConsumer}</li>
     * </ol>
     */
    void submitSortedQuads(CgGlyphPlacement[] placements,
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

        // Submit sorted quads. On batch-key change, transition shader/render-state/texture
        // (flushing whatever was pending under the previous combination) and set shader uniforms.
        CgDrawBatchKey currentKey = null;
        boolean worldText = context.isWorldText();

        for (int s = 0; s < visibleCount; s++) {
            CgDrawBatchKey thisKey = batchKeys[s];

            // On batch key change, transition shader/texture/render-state.
            if (currentKey == null || !thisKey.equals(currentKey)) {
                CgRenderState renderState = thisKey.isMtsdf() ? (worldText ? MTSDF_RENDER_STATE_WORLD : MTSDF_RENDER_STATE)
                        : thisKey.isDistanceField() ? (worldText ? MSDF_RENDER_STATE_WORLD : MSDF_RENDER_STATE)
                        : (worldText ? BITMAP_RENDER_STATE_WORLD : BITMAP_RENDER_STATE);

                CgShader shader = thisKey.isMtsdf() ? MTSDF_SHADER
                        : thisKey.isDistanceField() ? MSDF_SHADER : BITMAP_SHADER;

                transitionTo(shader, renderState, thisKey.getTextureId());

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
            addQuadFromPlacement(p, glyphX[origIdx], glyphY[origIdx], rgba, scaleFactor);

            if (diagnosticLogging) {
                LOGGER.info("[BatchDiag] atlasType=" + thisKey.getAtlasType()
                        + ", textureId=" + thisKey.getTextureId()
                        + ", pxRange=" + thisKey.getPxRange());
            }
        }
    }

    /**
     * Computes quad geometry from a {@link CgGlyphPlacement} and submits it to the owned
     * {@link CgBatchRenderer}.
     *
     * <p>Uses plane bounds for geometry placement. The quad is submitted through
     * the batch renderer's {@link CgVertexConsumer} (a {@code CgVertexWriter}
     * backed by the batch renderer's staging buffer).</p>
     */
    private void addQuadFromPlacement(CgGlyphPlacement p,
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
                    "[QuadDiag] glyphId=%d penX=%.2f penY=%.2f planeL=%.2f planeB=%.2f planeT=%.2f planeW=%.2f planeH=%.2f qx=%.2f qy=%.2f page=%d tex=%d atlasType=%s distanceField=%b pxRange=%.1f",
                    p.getKey().getGlyphId(), penX, penY,
                    logicalBearingX, p.getPlaneBottom() * scaleFactor,
                    logicalBearingY, logicalWidth, logicalHeight,
                    qx, qy,
                    p.getPageIndex(), p.getPageTextureId(),
                    p.getAtlasType(), p.isDistanceField(), p.getPxRange()));
        }

        float u0 = p.getU0(), v0 = p.getV0(), u1 = p.getU1(), v1 = p.getV1();

        CgVertexConsumer vc = batchRenderer.vertex();
        vc.vertex(qx, qy).uv(u0, v0).colorRgba(rgba).endVertex();
        vc.vertex(qx + logicalWidth, qy).uv(u1, v0).colorRgba(rgba).endVertex();
        vc.vertex(qx + logicalWidth, qy + logicalHeight).uv(u1, v1).colorRgba(rgba).endVertex();
        vc.vertex(qx, qy + logicalHeight).uv(u0, v1).colorRgba(rgba).endVertex();
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
