package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgFontSource;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.gl.state.CgStateBoundary;
import io.github.somehussar.crystalgraphics.gl.state.CgStateSnapshot;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.api.text.CgTextConstraints;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Batched text renderer for bitmap, MSDF, and MTSDF glyph atlases.
 *
 * <p>The renderer consumes a pre-built {@link CgTextLayout}, resolves glyphs
 * through {@link CgFontRegistry}, writes quads into {@link CgGlyphVbo}, then
 * draws sorted bitmap and distance-field batches. It uses the modern GL path
 * required by the current font implementation and restores GL state through
 * {@link CgStateBoundary} after each draw.</p>
 *
 * <h3>Multi-Page Atlas Batching</h3>
 * <p>The renderer supports multi-page atlases by converting glyph atlas regions
 * into {@link CgGlyphPlacement} records that carry page identity (index and GL
 * texture ID), plane bounds, and per-page distance-field configuration ({@code pxRange}).
 * Quads are grouped into {@link CgDrawBatch} objects keyed by
 * {@link CgDrawBatchKey} (atlas mode, page texture, pxRange), sorted so bitmap
 * batches draw before distance-field batches. Each batch issues one
 * {@code glDrawElements} call with the appropriate shader, texture, and uniforms
 * bound. This replaces the former two-pass model that assumed one texture per
 * atlas mode.</p>
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
 *       {@link CgAtlasRegion} (legacy) or {@link CgGlyphPlacement} (multi-page)
 *       and are normalized back into logical space at the quad-placement boundary
 *       before combining with pen positions.</li>
 *   <li><strong>Composite space</strong> — PoseStack/model-view/projection transforms
 *       applied by the GPU shaders at render time. The PoseStack in 2D mode represents
 *       UI scale; in 3D mode it represents model-view positioning.</li>
 * </ol>
 *
 * <h3>Metric Normalization</h3>
 * <p>Physical atlas metrics are normalized to logical space at the quad-placement
 * boundary using {@link #logicalMetricScale(int, int)}:
 * {@code scaleFactor = baseTargetPx / (float) effectiveTargetPx}. This is applied
 * to plane bounds from {@link CgGlyphPlacement} (or bearingX, bearingY, width,
 * height from {@link CgAtlasRegion} in legacy mode). The normalization ensures
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
 * <h3>Authoritative Hot Path</h3>
 * <p>The current pipeline is intentionally centered on the paged-atlas path:</p>
 * <ol>
 *   <li>String-based draw overloads call {@link #layout(String, CgFontFamily, CgTextConstraints)} or its font variant</li>
 *   <li>{@link CgTextLayoutBuilder} produces a {@link CgTextLayout}</li>
 *   <li>{@link #drawInternal(CgTextLayout, CgFontFamily, float, float, int, long, CgTextRenderContext, PoseStack.Pose, CgTextScaleResolver)} resolves raster tier</li>
 *   <li>{@link #buildPagedGlyphBatch(CgTextLayout, CgFontFamily, float, float, long, CgTextRenderContext, CgFontKey, int, boolean, CgFontMetrics)} converts layout output into {@link CgGlyphPlacement} records</li>
 *   <li>{@link #buildDrawBatches(CgGlyphPlacement[], float[], float[], int, int, int)} groups quads by GL state</li>
 *   <li>{@link #drawBatches(List, CgTextRenderContext, FloatBuffer)} binds shaders/textures and issues draw calls</li>
 * </ol>
 *
 * <p>Legacy single-page atlas helpers are still retained for compatibility, but
 * they are no longer the authoritative path for new rendering work.</p>
 */
public class CgTextRenderer {

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    private static final int INITIAL_VBO_CAPACITY = 256;
    public static boolean diagnosticLogging = false;

    private static final String BITMAP_VERT = "/assets/crystalgraphics/shader/bitmap_text.vert";
    private static final String BITMAP_FRAG = "/assets/crystalgraphics/shader/bitmap_text.frag";
    private static final String MSDF_VERT = "/assets/crystalgraphics/shader/msdf_text.vert";
    private static final String MSDF_FRAG = "/assets/crystalgraphics/shader/msdf_text.frag";
    private static final String MTSDF_VERT = "/assets/crystalgraphics/shader/mtsdf_text.vert";
    private static final String MTSDF_FRAG = "/assets/crystalgraphics/shader/mtsdf_text.frag";
    private static final CgTextLayoutBuilder LAYOUT_BUILDER = new CgTextLayoutBuilder();

    private final CgShaderProgram bitmapShader;
    private final CgShaderProgram msdfShader;
    private final CgShaderProgram mtsdfShader;
    private final CgGlyphVbo vbo;
    private final CgFontRegistry registry;
    private final int bitmapLocProjection;
    private final int bitmapLocModelview;
    private final int bitmapLocAtlas;
    private final int msdfLocProjection;
    private final int msdfLocModelview;
    private final int msdfLocAtlas;
    private final int msdfLocPxRange;
    private final int mtsdfLocProjection;
    private final int mtsdfLocModelview;
    private final int mtsdfLocAtlas;
    private final int mtsdfLocPxRange;
    private final FloatBuffer matrixBuf;

    private boolean deleted;

    private CgTextRenderer(CgShaderProgram bitmapShader,
                           CgShaderProgram msdfShader,
                           CgShaderProgram mtsdfShader,
                           CgGlyphVbo vbo,
                           CgFontRegistry registry) {
        this.bitmapShader = bitmapShader;
        this.msdfShader = msdfShader;
        this.mtsdfShader = mtsdfShader;
        this.vbo = vbo;
        this.registry = registry;
        this.bitmapLocProjection = bitmapShader.getUniformLocation("u_projection");
        this.bitmapLocModelview = bitmapShader.getUniformLocation("u_modelview");
        this.bitmapLocAtlas = bitmapShader.getUniformLocation("u_atlas");
        this.msdfLocProjection = msdfShader.getUniformLocation("u_projection");
        this.msdfLocModelview = msdfShader.getUniformLocation("u_modelview");
        this.msdfLocAtlas = msdfShader.getUniformLocation("u_atlas");
        this.msdfLocPxRange = msdfShader.getUniformLocation("u_pxRange");
        this.mtsdfLocProjection = mtsdfShader.getUniformLocation("u_projection");
        this.mtsdfLocModelview = mtsdfShader.getUniformLocation("u_modelview");
        this.mtsdfLocAtlas = mtsdfShader.getUniformLocation("u_atlas");
        this.mtsdfLocPxRange = mtsdfShader.getUniformLocation("u_pxRange");
        this.matrixBuf = BufferUtils.createFloatBuffer(16);
    }

    /**
     * Creates the renderer and compiles the text shaders.
     *
     * <p>This renderer requires the modern GL feature set used by the current
     * implementation: core FBO support, core shaders, VAOs, and
     * glMapBufferRange.</p>
     */
    public static CgTextRenderer create(CgCapabilities caps, CgFontRegistry registry) {
        if (!caps.isCoreFbo() || !caps.isCoreShaders() || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException("CgTextRenderer requires modern GL support: core FBO, core shaders, VAO, and glMapBufferRange");
        }

        CgShaderProgram bitmapShader = CgShaderFactory.compile(caps, readShaderSource(BITMAP_VERT), readShaderSource(BITMAP_FRAG));
        CgShaderProgram msdfShader = CgShaderFactory.compile(caps, readShaderSource(MSDF_VERT), readShaderSource(MSDF_FRAG));
        CgShaderProgram mtsdfShader = CgShaderFactory.compile(caps, readShaderSource(MTSDF_VERT), readShaderSource(MTSDF_FRAG));

        CgGlyphVbo vbo = CgGlyphVbo.create(INITIAL_VBO_CAPACITY);
        vbo.setupAttributes(bitmapShader);
        vbo.setupAttributes(msdfShader);
        vbo.setupAttributes(mtsdfShader);

        LOGGER.info("[CrystalGraphics] CgTextRenderer created: bitmap=" + bitmapShader.getId()
                + ", msdf=" + msdfShader.getId()
                + ", mtsdf=" + mtsdfShader.getId());
        return new CgTextRenderer(bitmapShader, msdfShader, mtsdfShader, vbo, registry);
    }

    /**
     * Primary PoseStack-aware draw entry point.
     *
     * <p>Draws a layout at the given local logical origin ({@code x}, {@code y})
     * inside the current top pose. The pose's cumulative transform is used to:
     * <ul>
     *   <li>Derive the effective physical glyph raster size via the context's
     *       {@link CgTextScaleResolver}</li>
     *   <li>Upload the model-view matrix to the text shader as {@code u_modelview}</li>
     * </ul>
     * The {@code x} and {@code y} offsets are applied in local pose space before
     * the shader model-view transformation.</p>
     *
     * @param layout  the pre-built text layout (logical coordinates)
     * @param font    the font to render with
     * @param x       local logical X origin inside the current pose
     * @param y       local logical Y origin inside the current pose
     * @param rgba    packed RGBA color (0xRRGGBBAA)
     * @param frame   current frame number for atlas LRU
     * @param context the render context providing projection and scale resolver
     * @param pose    the current PoseStack providing model-view transform
     */
    public void draw(CgTextLayout layout, CgFont font, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        if (deleted) {
            throw new IllegalStateException("CgTextRenderer has been deleted");
        }
        if (layout == null || layout.getLines().isEmpty()) {
            return;
        }
        draw(layout, CgFontFamily.of(font), x, y, rgba, frame, context, pose);
    }

    public void draw(CgTextLayout layout, CgFont font, int targetPx, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(layout, sizedFont, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFont font, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        draw(text, font, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFont font, CgTextConstraints constraints, float x, float y, int rgba,
                     long frame, CgTextRenderContext context, PoseStack pose) {
        requireSizedFont(font);
        draw(layout(text, font, constraints), font, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFont font, int targetPx, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        draw(text, font, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFont font, int targetPx, CgTextConstraints constraints, float x, float y, int rgba,
                     long frame, CgTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        draw(layout(text, sizedFont, constraints), sizedFont, x, y, rgba, frame, context, pose);
    }


    public void draw(CgTextLayout layout, CgFontFamily family, int targetPx, float x, float y, int rgba, long frame, 
                     CgTextRenderContext context, PoseStack pose) {
        draw(layout, sizeFamily(family, targetPx), x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFontFamily family, float x, float y, int rgba, long frame, CgTextRenderContext context, 
                     PoseStack pose) {
        draw(text, family, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFontFamily family, CgTextConstraints constraints, float x, float y, int rgba,
                     long frame, CgTextRenderContext context, PoseStack pose) {
        draw(layout(text, family, constraints), family, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFontFamily family, int targetPx, float x, float y, int rgba, long frame,
                     CgTextRenderContext context, PoseStack pose) {
        draw(text, family, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void draw(String text, CgFontFamily family, int targetPx, CgTextConstraints constraints, float x, float y,
                     int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        CgFontFamily sizedFamily = sizeFamily(family, targetPx);
        draw(layout(text, sizedFamily, constraints), sizedFamily, x, y, rgba, frame, context, pose);
    }


    public void draw(CgTextLayout layout, CgFontFamily family, float x, float y, int rgba, long frame, CgTextRenderContext context, PoseStack pose) {
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        
        CgStateSnapshot snapshot = CgStateBoundary.save();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            drawInternal(layout, family, x, y, rgba, frame, context, pose.last(), context.getScaleResolver());
        } finally {
            CgStateBoundary.restore(snapshot);
        }
    }


    /**
     * World-space 3D text draw entry point.
     *
     * <p>Renders text in 3D world space with always-MSDF rendering. Unlike the
     * 2D {@link #draw} method, this entry point:</p>
     * <ul>
     *   <li>Enables depth testing so text occludes and is occluded by world geometry</li>
     *   <li>Configures world-text GL state for 3D rendering</li>
     *   <li>Forces MSDF-only rendering — no bitmap fallback path</li>
     *   <li>Optionally updates the projected-size hint on the context for
     *       quality/LOD tier selection</li>
     * </ul>
     *
     * <p>Layout metrics remain in logical space. The PoseStack positions the text
     * in world space (translation, rotation, billboard transforms) but does not
     * drive raster size like the 2D UI path does.</p>
     *
     * @param layout  the pre-built text layout (logical coordinates)
     * @param font    the font to render with
     * @param x       local logical X origin inside the current pose
     * @param y       local logical Y origin inside the current pose
     * @param rgba    packed RGBA color (0xRRGGBBAA)
     * @param frame   current frame number for atlas LRU
     * @param context the world-text render context (always-MSDF, projection-aware)
     * @param pose    the current PoseStack providing model-view transform
     */
    public void drawWorld(CgTextLayout layout, CgFont font, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        
        drawWorld(layout, CgFontFamily.of(font), x, y, rgba, frame, context, pose);
    }

    public void drawWorld(CgTextLayout layout, CgFont font, int targetPx, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        drawWorld(layout, sizedFont, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFont font, float x, float y, int rgba, long frame, 
                          CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(text, font, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFont font, CgTextConstraints constraints, float x, float y, int rgba,
                          long frame, CgWorldTextRenderContext context, PoseStack pose) {
        requireSizedFont(font);
        drawWorld(layout(text, font, constraints), font, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFont font, int targetPx, float x, float y, int rgba, long frame, 
                          CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(text, font, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFont font, int targetPx, CgTextConstraints constraints, float x, float y, int rgba,
                          long frame, CgWorldTextRenderContext context, PoseStack pose) {
        CgFont sizedFont = requireSizedFont(font, targetPx);
        drawWorld(layout(text, sizedFont, constraints), sizedFont, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(CgTextLayout layout, CgFontFamily family, int targetPx, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(layout, sizeFamily(family, targetPx), x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFontFamily family, float x, float y, int rgba, long frame, CgWorldTextRenderContext context,
                          PoseStack pose) {
        drawWorld(text, family, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFontFamily family, CgTextConstraints constraints, float x, float y, int rgba,
                          long frame, CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(layout(text, family, constraints), family, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFontFamily family, int targetPx, float x, float y, int rgba, long frame,
                          CgWorldTextRenderContext context, PoseStack pose) {
        drawWorld(text, family, targetPx, CgTextConstraints.UNBOUNDED, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(String text, CgFontFamily family, int targetPx, CgTextConstraints constraints, float x,
                          float y, int rgba, long frame, CgWorldTextRenderContext context, PoseStack pose) {
        CgFontFamily sizedFamily = sizeFamily(family, targetPx);
        drawWorld(layout(text, sizedFamily, constraints), sizedFamily, x, y, rgba, frame, context, pose);
    }

    public void drawWorld(CgTextLayout layout, CgFontFamily family, float x, float y, int rgba, long frame, 
                          CgWorldTextRenderContext context, PoseStack pose) {
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (layout == null || layout.getLines().isEmpty()) return;
        

        CgStateSnapshot snapshot = CgStateBoundary.save();
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            drawInternal(layout, family, x, y, rgba, frame, context, pose.last(), context.getScaleResolver());
        } finally {
            CgStateBoundary.restore(snapshot);
        }
    }
    public void delete() {
        if (deleted) return;
        
        deleted = true;
        vbo.delete();
        bitmapShader.delete();
        msdfShader.delete();
        mtsdfShader.delete();
    }

    public boolean isDeleted() {
        return deleted;
    }

    // ── Authoritative current paged-atlas pipeline ─────────────────────

    /**
     * Shared renderer core used by both 2D and world-text entrypoints.
     *
     * <p>This method is the main place where the runtime pipeline becomes
     * concrete: resolve the effective raster tier, collect paged glyph
     * placements, group them into draw batches, emit VBO quads, and submit the
     * batches to OpenGL.</p>
     *
     * <p>The bitmap-vs-distance-field choice is made here at the draw level.
     * The later distinction between MSDF and MTSDF comes from the atlas page
     * type carried by the returned placements.</p>
     */
    private void drawInternal(CgTextLayout layout, CgFontFamily family, float x, float y, int rgba, long frame, 
                              CgTextRenderContext context, PoseStack.Pose pose, CgTextScaleResolver scaleResolver) {
        CgFontKey fontKey = family.getPrimarySource().getKey();
        CgFontMetrics metrics = layout.getMetrics();

        // Stage 1: resolve the physical raster size that should back this draw.
        // Layout remains in logical space, but cache keys and atlas lookups depend
        // on this effective physical size.
        int previousEffectiveTargetPx = context.getPreviousEffectiveTargetPx(fontKey);
        int effectiveTargetPx = scaleResolver.resolveEffectiveTargetPx(fontKey.getTargetPx(), pose, previousEffectiveTargetPx);
        context.setPreviousEffectiveTargetPx(fontKey, effectiveTargetPx);

        // Stage 2: choose bitmap vs. distance-field rendering. The previous mode
        // is fed back into the resolver as hysteresis so zooming near the threshold
        // does not flicker between quality tiers frame-to-frame.
        boolean previousMsdf = previousEffectiveTargetPx > 0 ? context.wasMsdf(fontKey) : effectiveTargetPx >= 32;
        boolean wantMsdf = scaleResolver.shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setWasMsdf(fontKey, wantMsdf);

        // Stage 3: convert layout output into concrete paged-atlas placements
        // backed by page textures, UVs, and plane bounds.
        PagedGlyphBatch batch = buildPagedGlyphBatch(layout, family, x, y, frame, context, fontKey, effectiveTargetPx, wantMsdf, metrics);

        // Stage 4: sort placements by GL state and emit quads into the VBO in
        // that order. Each batch becomes one contiguous VBO range that can be
        // drawn with one shader/texture/pxRange configuration.
        List<CgDrawBatch> drawBatches = buildDrawBatches(batch.placements, batch.glyphX, batch.glyphY, rgba, 
                fontKey.getTargetPx(), effectiveTargetPx);
        
        if (drawBatches.isEmpty()) return;
        

        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        vbo.uploadAndBind();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (!context.isWorldText()) GL11.glDisable(GL11.GL_DEPTH_TEST);
        

        // Stage 5: with all logical/layout work finished, upload the current
        // model-view matrix and submit the sorted batches to the GPU.
        Matrix4f mv = pose.pose();
        FloatBuffer mvBuf = prepareMatrixBuffer(mv);

        drawBatches(drawBatches, context, mvBuf);

        vbo.unbind();

        if (!blendWasEnabled) GL11.glDisable(GL11.GL_BLEND);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        
    }

    /**
     * Builds sorted draw batches from glyph placements.
     *
     * <p>This method performs two passes over the placement array:</p>
     * <ol>
     *   <li><strong>Sort pass</strong>: Collects placement indices and sorts them
     *       by batch key (bitmap before MSDF, then by texture ID, then by pxRange).
     *       This ensures quads in the VBO are grouped by GL state.</li>
     *   <li><strong>Emit pass</strong>: Writes quads into the VBO in sorted order
     *       and records batch boundaries wherever the batch key changes.</li>
     * </ol>
     *
     * @return sorted list of draw batches, empty if no visible quads
     */
    List<CgDrawBatch> buildDrawBatches(CgGlyphPlacement[] placements, float[] glyphX, float[] glyphY, int rgba,
                                       int baseTargetPx, int effectiveTargetPx) {
        // Collect indices of visible placements paired with their batch keys
        int visibleCount = 0;
        for (int i = 0; i < placements.length; i++) {
            if (placements[i] != null && placements[i].hasGeometry()) 
                visibleCount++;
        }
        if (visibleCount == 0) return Collections.emptyList();
        

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

        // Emit quads in sorted order and record batch boundaries
        vbo.begin();
        List<CgDrawBatch> batches = new ArrayList<CgDrawBatch>();
        CgDrawBatchKey currentKey = batchKeys[0];
        int batchStartQuad = 0;
        int totalQuads = 0;

        for (int s = 0; s < visibleCount; s++) {
            CgDrawBatchKey thisKey = batchKeys[s];
            // Check if we need to start a new batch
            if (!thisKey.equals(currentKey)) {
                int batchQuadCount = totalQuads - batchStartQuad;
                if (batchQuadCount > 0) {
                    batches.add(new CgDrawBatch(currentKey, batchStartQuad, batchQuadCount));
                }
                currentKey = thisKey;
                batchStartQuad = totalQuads;
            }

            int origIdx = sortedIndices[s];
            CgGlyphPlacement p = placements[origIdx];
            int placementTargetPx = p.getKey().getFontKey().getTargetPx();
            // Bitmap placements use the current effective raster size directly.
            // Distance-field placements may come from a shared atlas family whose
            // own atlas-scale targetPx differs from this draw's effective size, so
            // their plane bounds must be normalized using the placement's atlas scale.
            float scaleFactor = logicalMetricScale(baseTargetPx,
                    p.isDistanceField() ? placementTargetPx : effectiveTargetPx);
            appendQuadFromPlacement(p, glyphX[origIdx], glyphY[origIdx], rgba, scaleFactor);
            totalQuads++;
        }

        // Final batch
        int batchQuadCount = totalQuads - batchStartQuad;
        if (batchQuadCount > 0) batches.add(new CgDrawBatch(currentKey, batchStartQuad, batchQuadCount));

        return batches;
    }

    /**
     * Appends a single glyph quad from a {@link CgGlyphPlacement} into the VBO.
     *
     * <p>Uses <strong>plane bounds</strong> for geometry placement instead of
     * the legacy bearing + metrics model. Plane bounds include SDF range padding
     * for MSDF glyphs, ensuring the distance field extends beyond the visible
     * glyph edge. Physical raster metrics are normalized to logical space using
     * the provided scale factor.</p>
     *
     * @param p           the glyph placement
     * @param penX        logical pen X position
     * @param penY        logical pen Y position
     * @param rgba        packed RGBA color
     * @param scaleFactor logical metric normalization (baseTargetPx / effectiveTargetPx)
     */
    private void appendQuadFromPlacement(CgGlyphPlacement p, float penX, float penY, int rgba, float scaleFactor) {
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
        
        vbo.addGlyph(qx, qy, logicalWidth, logicalHeight, p.getU0(), p.getV0(), p.getU1(), p.getV1(), rgba);
    }

    /**
     * Issues GL draw calls for each batch, binding the appropriate shader,
     * texture, and uniforms per batch.
     *
     * <p>Batches are already sorted by {@link CgDrawBatchKey}: bitmap batches
     * first, then distance-field batches. The method tracks the currently bound shader to avoid
     * redundant binds when consecutive batches share the same mode. Texture and
     * pxRange are always set per batch since they can differ between pages.</p>
     *
     * <p>MTSDF batches use a dedicated shader because the atlas stores an MSDF in
     * RGB plus a true SDF in alpha. That representation still belongs to the
     * distance-field family, but it needs its own reconstruction path.</p>
     */
    private void drawBatches(List<CgDrawBatch> batches, CgTextRenderContext context, FloatBuffer mvBuf) {
        // Track which shader is currently bound to minimize bind/unbind calls
        boolean bitmapShaderBound = false;
        boolean msdfShaderBound = false;
        boolean mtsdfShaderBound = false;

        for (int b = 0; b < batches.size(); b++) {
            CgDrawBatch batch = batches.get(b);
            if (batch.isEmpty()) continue;

            CgDrawBatchKey key = batch.getKey();
            if (diagnosticLogging) {
                LOGGER.info("[BatchDiag] atlasType=" + key.getAtlasType()
                        + ", textureId=" + key.getTextureId()
                        + ", pxRange=" + key.getPxRange()
                        + ", quadCount=" + batch.getQuadCount());
            }

            if (key.isMtsdf()) {
                if (bitmapShaderBound) {
                    bitmapShader.unbind();
                    bitmapShaderBound = false;
                }
                if (msdfShaderBound) {
                    msdfShader.unbind();
                    msdfShaderBound = false;
                }
                if (!mtsdfShaderBound) {
                    mtsdfShader.bind();
                    uploadProjectionMatrix(mtsdfShader, mtsdfLocProjection, context.getProjectionBuffer());
                    uploadMatrix(mtsdfShader, mtsdfLocModelview, mvBuf);
                    mtsdfShader.setUniform1i(mtsdfLocAtlas, 0);
                    mtsdfShaderBound = true;
                }
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, key.getTextureId());
                mtsdfShader.setUniform1f(mtsdfLocPxRange, key.getPxRange());
            } else if (key.isDistanceField()) {
                // Unbind bitmap shader if it was active
                if (bitmapShaderBound) {
                    bitmapShader.unbind();
                    bitmapShaderBound = false;
                }
                if (mtsdfShaderBound) {
                    mtsdfShader.unbind();
                    mtsdfShaderBound = false;
                }
                // Bind MSDF shader if not already bound
                if (!msdfShaderBound) {
                    msdfShader.bind();
                    uploadProjectionMatrix(msdfShader, msdfLocProjection, context.getProjectionBuffer());
                    uploadMatrix(msdfShader, msdfLocModelview, mvBuf);
                    msdfShader.setUniform1i(msdfLocAtlas, 0);
                    msdfShaderBound = true;
                }
                // Per-batch: bind texture and set pxRange (may differ by page)
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, key.getTextureId());
                msdfShader.setUniform1f(msdfLocPxRange, key.getPxRange());
            } else {
                // Unbind MSDF shader if it was active
                if (msdfShaderBound) {
                    msdfShader.unbind();
                    msdfShaderBound = false;
                }
                if (mtsdfShaderBound) {
                    mtsdfShader.unbind();
                    mtsdfShaderBound = false;
                }
                // Bind bitmap shader if not already bound
                if (!bitmapShaderBound) {
                    bitmapShader.bind();
                    uploadProjectionMatrix(bitmapShader, bitmapLocProjection, context.getProjectionBuffer());
                    uploadMatrix(bitmapShader, bitmapLocModelview, mvBuf);
                    bitmapShader.setUniform1i(bitmapLocAtlas, 0);
                    bitmapShaderBound = true;
                }
                // Per-batch: bind texture (may differ by page)
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, key.getTextureId());
            }

            GL11.glDrawElements(GL11.GL_TRIANGLES,
                    batch.getIndexCount(), GL11.GL_UNSIGNED_SHORT,
                    batch.getIboByteOffset());
        }

        // Unbind whichever shader is still active
        if (bitmapShaderBound) bitmapShader.unbind();
        if (msdfShaderBound) msdfShader.unbind();
        if (mtsdfShaderBound) mtsdfShader.unbind();
        
    }

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
                                                    boolean wantMsdf,
                                                      CgFontMetrics metrics) {
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
    private void prequeueVisibleGlyphs(CgTextLayout layout,
                                       CgFontFamily family,
                                       int effectiveTargetPx,
                                       boolean wantMsdf,
                                       CgTextRenderContext context,
                                       long frame) {
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

    private void uploadProjectionMatrix(CgShaderProgram shader, int uniformLocation, FloatBuffer projectionMatrix) {
        if (uniformLocation < 0) return;
        
        int sourcePosition = projectionMatrix.position();
        matrixBuf.clear();
        for (int i = 0; i < 16; i++) matrixBuf.put(projectionMatrix.get(sourcePosition + i));
        
        matrixBuf.flip();
        shader.setUniformMatrix4f(uniformLocation, matrixBuf);
    }

    private FloatBuffer prepareMatrixBuffer(Matrix4f matrix) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        matrix.get(buf);
        buf.rewind();
        return buf;
    }

    private void uploadMatrix(CgShaderProgram shader, int uniformLocation, FloatBuffer matrixData) {
        if (uniformLocation < 0) return;
        
        matrixData.rewind();
        shader.setUniformMatrix4f(uniformLocation, matrixData);
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

    static String readShaderSource(String resourcePath) {
        InputStream in = CgTextRenderer.class.getResourceAsStream(resourcePath);
        if (in == null) throw new RuntimeException("Shader resource not found on classpath: " + resourcePath);
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource: " + resourcePath, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
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
