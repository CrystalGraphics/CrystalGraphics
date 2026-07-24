package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextConstraints;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.gl.texture.CgTextureMutable;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.cache.CgFontRegistry;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Batched text renderer for bitmap, MSDF, and MTSDF glyph atlases.
 *
 * <p>The renderer consumes a pre-built {@link CgTextLayout}, resolves glyphs
 * through {@link CgFontRegistry}, sorts them by GL state, then submits quads
 * through its own owned {@link CgQuadRenderer} — each glyph becomes one
 * instanced-quad record (transform baked per-glyph via {@code Quad.pose()}), not a
 * batch of raw vertices. Material bind/unbind, keyword toggling, and atlas texture
 * swaps on batch-key transitions are handled directly by this class — see
 * {@link #transitionToMaterial}.</p>
 *
 * <h3>Multi-Page Atlas Batching</h3>
 * <p>The renderer supports multi-page atlases by converting glyph atlas regions
 * into {@link CgGlyphPlacement} records that carry page identity (index and GL
 * texture ID), plane bounds, and per-page distance-field configuration ({@code pxRange}).
 * Quads are sorted by {@link CgDrawBatchKey} (atlas mode, page texture, pxRange)
 * so bitmap batches draw before distance-field batches. On batch-key transitions
 * the active material keywords, atlas texture, and pxRange property are swapped
 * (triggering a flush of whatever was pending under the previous state).</p>
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
 * <p>World-space/3D text uses the same {@link #draw()}/{@link Draw#submit()} entry point
 * as 2D UI text — calling {@link #context(CgTextRenderContext)} with one built via
 * {@link CgTextRenderContext#world} instead of {@link CgTextRenderContext#orthographic}
 * is what switches it on. That context's {@link PerspectiveScaleResolver} enforces
 * always-MSDF rendering and projection-aware quality/LOD policy via
 * {@link ProjectedSizeEstimator}; depth-tested render state is applied in this class
 * via {@link CgTextRenderContext#isWorldText()} — see {@link #flush}.
 * The PoseStack in world mode represents
 * model-view positioning (entity rotation, billboard transforms), not UI zoom. Layout
 * metrics remain in logical space regardless of camera distance or FOV.</p>
 *
 * <h3>Owned Batch Lifecycle</h3>
 * <p>{@code CgTextRenderer} owns a private {@link CgQuadRenderer} — no caller-provided
 * layer or buffer source is required. The renderer is frame-agnostic: {@link #beginBatch()}/
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
 *   <li>{@link Draw#submit()} calls {@link #layout(String, CgFontFamily, CgTextConstraints)} or its font
 *       variant when {@link Draw#text(String)} was used instead of a prebuilt {@link Draw#layout(CgTextLayout)}</li>
 *   <li>{@link CgTextLayoutBuilder} produces a {@link CgTextLayout}</li>
 *   <li>{@link #drawInternal} resolves raster tier</li>
 *   <li>{@link #buildPagedGlyphBatch} converts layout output into {@link CgGlyphPlacement} records</li>
 *   <li>{@link #submitSortedQuads} sorts quads by GL state and submits them to the owned batch renderer</li>
 * </ol>
 *
 * <h3>Draw API</h3>
 * <p>{@link #draw()}/{@link #retainedDraw()} return a fluent {@link Draw} request object —
 * see that class's javadoc for the full chain-method surface and field-priority rules.
 * This replaced a fixed-arity {@code draw(...)} overload matrix that grew combinatorially
 * with every new optional parameter.</p>
 */
public class CgTextRenderer {

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  CgMaterial SETUP
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Shared static material — every {@code CgTextRenderer} instance binds/toggles keywords on
     * this same instance (mirrors the already-static raw shaders above). Loaded via
     * {@link CgMaterial#load(String)} (cache-per-path), not {@code newInstance()}, so it
     * participates in {@code CgMaterialRegistry}'s hot-reload (F3+T) and teardown for free.
     */
    public static final CgMaterial TEXT_MATERIAL = CgMaterial.load("crystalgraphics:shaders/text.shader");

    /**
     * Text-only per-renderer uniform data — currently just {@code u_Projection}. Deliberately
     * kept as its own small UBO rather than reusing the engine's shared per-frame
     * {@code CgFrameData}/{@code cg_ProjMatrix}: that block is frame-owner state (set once by
     * whoever actually drives the frame — a 3D scene, the MC render hook, etc.), and a text
     * renderer overwriting it on every flush would clobber the real projection for anything
     * else sharing that frame. {@code CgTextRenderContext}'s projection is renderer-local (often
     * an orthographic UI projection with nothing to do with the scene camera), so it gets its
     * own private slot instead. Also the natural home for any future text-only uniform that
     * isn't a good fit for either {@link #TEXT_MATERIAL}'s Properties block (per-batch-key, not
     * per-draw) or {@code CgQuadRenderer}'s per-instance record (per-glyph, not per-renderer) —
     * {@code u_ModelView} was here before this migration but is gone now: model-view is baked
     * per-glyph-instance via {@code Quad.pose()}, see {@link #addQuadFromPlacement}.
     */
    private static final CgBufferFormat TEXT_DATA_FORMAT = CgBufferFormat
            .builder("TextData", CgBufferFormat.MemoryLayout.STD140)
            .mat4("u_Projection")
            .build();

    /**
     * Created via the registry (not a bare {@code CgUniformBuffer.create()}) so it's covered by
     * {@code CgShaderBufferRegistry.deleteAll()}'s teardown — no individual {@code CgTextRenderer}
     * instance owns or deletes it.
     */
    private static final CgUniformBuffer TEXT_DATA_UBO = CgShaderBufferRegistry.get().getOrCreateUbo(
            TEXT_DATA_FORMAT, "TextData", CgBindingPoints.TEXT_DATA_UBO);

    /**
     * Mutable adapter over whatever raw GL atlas-page texture id is currently active.
     * {@code CgMaterial}'s Properties-block sampler API ({@code applyProperties(b -> b.sampler(...))})
     * requires a real {@link com.crystalgraphics.api.texture.CgTexture}, not a raw int — atlas
     * pages ({@code CgGlyphAtlasPage}) own only a raw GL texture id with no such wrapper. This view
     * (never owns/deletes the real texture) is registered once via {@link #TEXT_MATERIAL}'s
     * Properties block at class-init, then its id is mutated per atlas-page transition — the
     * property system rebinds whatever this wrapper currently points to on every
     * {@code material.bind()}, so no repeated {@code applyProperties} sampler call is needed.
     */
    private static final CgTextureMutable ATLAS_TEXTURE_REF = new CgTextureMutable(CgGL.GL_TEXTURE_2D);

    static {
        TEXT_MATERIAL.attach(TEXT_DATA_UBO);
        TEXT_MATERIAL.applyProperties(b -> b.sampler("_MainTex", 0, ATLAS_TEXTURE_REF));
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    public static boolean diagnosticLogging = false;

    private static final CgTextLayoutBuilder LAYOUT_BUILDER = new CgTextLayoutBuilder();
    private final CgFontRegistry registry = CgFontRegistry.get();

    // ── Owned batch lifecycle ────────────────────────────────────────────────
    private final CgQuadRenderer quadRenderer;
    private boolean batchActive;
    /** Batch-key transition tracking — see {@link #transitionToMaterial}. */
    private CgDrawBatchKey activeBatchKey;
    /**
     * Projection this renderer's queued-but-unflushed quads were computed against —
     * {@code null} at the start of every batch (see {@link #beginBatch()}), since another live
     * {@code CgTextRenderer} may have overwritten the shared {@link #TEXT_DATA_UBO} since this
     * renderer's last upload. Used only to decide whether a {@link #context(CgTextRenderContext)}
     * change mid-batch must flush first (see {@link #syncProjection}) — unlike the model-view
     * transform, which is baked per-glyph-instance via {@code Quad.pose()} (see
     * {@link #addQuadFromPlacement}) and needs no such tracking at all. Compared by value, not
     * reference, since {@link CgTextRenderContext#getProjection()} is a live, mutable matrix
     * owned by the context.
     */
    private Matrix4f activeProjection;
    /**
     * Optional caller-supplied hook invoked at the end of every {@link #endBatch()} (manual
     * or {@link Draw#submit()}'s standalone auto-batch alike) — see {@link #restoreStateWith}.
     */
    private Runnable postBatchRestore;
    
        /**
     * Registers a hook that runs at the end of every {@link #endBatch()} — including the
     * standalone auto-batch that {@link Draw#submit()} opens/closes around a single
     * one-shot draw when no batch is already active, which is the common case for
     * {@code ctx.text().draw()...submit()} call sites.
     *
     * @param restoreAction closure re-establishing the caller's own GL state (e.g.
     *                       re-binding its own material/texture); pass {@code null} to
     *                       clear a previously registered hook
     */
    public CgTextRenderer restoreStateWith(Runnable restoreAction) {
        this.postBatchRestore = restoreAction;
        return this;
    }


    // ── Owned render context ────────────────────────────────────────────────
    /**
     * Defaults to an orthographic context sized to {@link CgGraphicsLifecycle}'s current
     * known window dimensions (0×0 before the engine's first resize/init) — size it via
     * {@link CgTextRenderContext#updateOrtho} before first use if needed, or replace it
     * entirely via {@link #context(CgTextRenderContext)}. Fluent Lombok accessors:
     * {@link #context()} (getter), {@link #context(CgTextRenderContext)} (setter — the way
     * to switch between orthographic/2D and world/3D modes, since the two differ in which
     * {@link CgTextScaleResolver} they hold; build the replacement via
     * {@link CgTextRenderContext#orthographic} or {@link CgTextRenderContext#world}).
     */
    @Getter
    @Setter
    @Accessors(fluent = true)
    @NonNull
    private CgTextRenderContext context = CgTextRenderContext.orthographic(
            CgGraphicsLifecycle.getCurrentWidth(), CgGraphicsLifecycle.getCurrentHeight());

    // ── Optional fallback pose stack ─────────────────────────────────────────
    /**
     * Not instantiated by default — {@code null} until a caller opts in via
     * {@link #poseStack(PoseStack)}. In the common case a caller supplies its own
     * {@link PoseStack} directly to every draw via {@link Draw#pose(PoseStack)}; this
     * field only exists as a niche fallback for {@link Draw#submit()} when that wasn't
     * called.
     */
    @Getter
    @Setter
    @Accessors(fluent = true)
    private PoseStack poseStack;

    /** Tracks the display window's resolution automatically — every {@link CgGraphicsLifecycle#onResize} call resizes
     * it in place, no manual per-frame dimension check needed.
     * Only resizes a 2D orthographic {@link #context}, 3D perspective contexts need to be manually resized.*/
    @Getter
    private boolean screenSized;

    @Getter
    private boolean deleted;

    private CgTextRenderer() {
        this.quadRenderer = CgQuadRenderer.create();        
        quadRenderer.useMaterial(TEXT_MATERIAL);
    }

    /**
     * Creates the renderer façade, including its owned {@link CgQuadRenderer}, and
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
     * {@link #endBatch()} record into the same underlying {@link CgQuadRenderer} pass and
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

        activeBatchKey = null;
        activeProjection = null;
        batchActive = true;
        quadRenderer.begin();
    }

    /**
     * Closes the batching window opened by {@link #beginBatch()}, flushing any pending
     * quads and unbinding whatever shader/texture/state is currently active, then invoking
     * {@link #restoreStateWith}'s hook, if one was registered.
     *
     * <p>Lenient: does nothing if no batch is active.</p>
     */
    public void endBatch() {
        if (!batchActive) return;

        flush();
        quadRenderer.end();
        activeBatchKey = null;
        activeProjection = null;
        batchActive = false;

        if (postBatchRestore != null) postBatchRestore.run();
    }


    /**
     * Flushes whatever is currently staged under {@link #activeBatchKey}, binding
     * {@link #TEXT_MATERIAL} (with whatever keywords/properties/atlas texture were last set by
     * {@link #transitionToMaterial}) via {@link CgQuadRenderer#useMaterial}, plus
     * {@link #TEXT_DATA_UBO} for the duration of the draw, and issuing the instanced draw. No-op
     * if nothing is staged.
     *
     * <p>The real depth state (disabled for UI text, test-only for world text — see
     * {@link CgTextRenderContext#isWorldText()}) is applied <em>after</em>
     * {@code quadRenderer.useMaterial(TEXT_MATERIAL)} binds the material, since a {@code Pass}'s
     * {@code RenderState} is baked at author time and can't itself express this split (see
     * {@code text.shader}'s placeholder {@code DepthTest}/{@code DepthWrite} lines). No explicit
     * {@code .clear()} is needed afterward — {@code CgMaterial.unbind()}'s own {@code CgGlScope}
     * already restores depth (along with blend/cull/etc.) to whatever was active before
     * {@code bind()}, which is more correct than resetting to hard GL defaults.</p>
     */
    private void flush() {
        if (!quadRenderer.isDirty()) return;

        quadRenderer.useMaterial(TEXT_MATERIAL);
        TEXT_DATA_UBO.bind();
        (context.isWorldText() ? CgDepthState.TEST_ONLY : CgDepthState.NONE).apply();

        quadRenderer.flush();

        // No need to unbind as they are wasteful — useMaterial() unbinds-old on the next call.
    }

    /**
     * Ensures {@link #TEXT_DATA_UBO} holds {@code projection} for the glyphs about to be
     * submitted. Two independent things happen here, in order:
     *
     * <ol>
     *   <li><b>Flush first if this renderer's own queued-but-unflushed quads were placed under a
     *       different projection</b> ({@link #activeProjection}). Projection is shared GPU state
     *       at flush time (unlike model-view, which is baked per-glyph-instance) — without this,
     *       a {@link #context(CgTextRenderContext)} switch mid-batch would silently re-project
     *       already-queued glyphs onto the new projection instead of the one they were placed
     *       for.</li>
     *   <li><b>Always (re)upload</b>, even if {@code projection} equals what this renderer last
     *       uploaded — {@link #TEXT_DATA_UBO} is shared across every live {@code CgTextRenderer},
     *       so another instance may have overwritten it since. Cheap: one small UBO write per
     *       {@code draw()} call, not per glyph.</li>
     * </ol>
     */
    private void syncProjection(Matrix4f projection) {
        if(activeProjection != null) {
            if (activeProjection.equals(projection)) return;
            else flush();
            activeProjection.set(projection);
        } else activeProjection = new Matrix4f(projection);
        

        TEXT_DATA_UBO.writer().reset().beginRecord().mat4("u_Projection", projection);
        TEXT_DATA_UBO.endRecord();
        TEXT_DATA_UBO.upload();
    }

    /**
     * Transitions {@link #TEXT_MATERIAL} to the given batch key, flushing whatever was pending
     * under the previous key first.
     *
     * <p>Every transition explicitly sets {@code MSDF_MODE} — one keyword covers both
     * distance-field atlas types (MSDF and MTSDF), since the fragment logic is identical for
     * both today (see {@code text.shader}). {@link #TEXT_MATERIAL} is shared static state, so
     * this is always an explicit enable-or-disable, never a bare {@code enableKeyword()} alone
     * — a stale keyword left on by a previous transition (this renderer's or another live
     * instance's) would otherwise silently persist into the next bind's compiled variant.</p>
     */
    private void transitionToMaterial(CgDrawBatchKey key) {
        if (activeBatchKey != null && activeBatchKey.equals(key)) return;

        flush();

        TEXT_MATERIAL.toggleKeyword("MSDF_MODE", key.isDistanceField());
        ATLAS_TEXTURE_REF.setId(key.getTextureId());
        TEXT_MATERIAL.applyProperties(b -> b.set1f("_PxRange", key.getPxRange()));

        activeBatchKey = key;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  FLUENT DRAW REQUEST
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** Reused scratch {@link Draw} instance returned by {@link #draw()}. */
    private final Draw scratchDraw = new Draw();

    /**
     * Starts a fluent draw request using this renderer's single reused scratch instance —
     * zero allocation. Build it and call {@link Draw#submit()} in the same expression;
     * do not hold the returned reference past that, since any other {@code draw()} call
     * on this renderer (even from an unrelated call site) resets and reuses the same
     * instance. For a draw descriptor you want to hold across frames, use
     * {@link #retainedDraw()} instead.
     *
     * <p>Replaces the fixed-arity {@code draw(...)} overloads above for new call sites —
     * new optional parameters become new chain methods instead of new overloads.</p>
     *
     * <pre>{@code
     * renderer.draw().text("Hello").font(myFont).at(x, y).color(0xFFFFFFFF).pose(pose).submit();
     * }</pre>
     */
    public Draw draw() {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        return scratchDraw.reset();
    }

    /**
     * Allocates a standalone, retained-mode {@link Draw} instance the caller owns and may
     * hold across frames — e.g. build once, then call {@link Draw#submit()} every tick,
     * mutating only whatever field changed. Independent of {@link #draw()}'s shared
     * immediate-mode scratch instance and of any other renderer's or call site's
     * {@code retainedDraw()} result.
     */
    public Draw retainedDraw() {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        return new Draw();
    }

    /**
     * Fluent, mutable draw request — the replacement for {@code CgTextRenderer}'s fixed-arity
     * {@code draw(...)} overload matrix. Obtain one via {@link #draw()} (shared scratch,
     * zero-allocation, immediate-mode: submit right away) or {@link #retainedDraw()}
     * (standalone, retained-mode: holdable across frames).
     *
     * <h3>Field priority, not exclusivity</h3>
     * <p>{@link #layout(CgTextLayout)} and {@link #text(String)} may both be set; if so,
     * the prebuilt {@code layout} wins — it already paid the shaping cost and is presumably
     * what the caller actually wants drawn. Likewise {@link #family(CgFontFamily)} wins over
     * {@link #font(CgFont)} when both are set, since a family is the strictly more capable
     * superset (a single font is just wrapped into one internally via
     * {@link CgFontFamily#of(CgFont, CgFont...)}).</p>
     *
     * <h3>Required fields</h3>
     * <p>{@link #submit()} throws {@link IllegalStateException} unless at least one of
     * {@code layout}/{@code text} and at least one of {@code family}/{@code font} have been
     * set. {@link #pose(PoseStack)} is usually required too, unless the owning
     * {@link CgTextRenderer} has a fallback set via {@link CgTextRenderer#poseStack(PoseStack)}.
     * {@link #at(float, float)} and {@link #color(int)} default to {@code (0, 0)} and opaque
     * white ({@code 0xFFFFFFFF}) respectively if never called.</p>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * // One-shot: build and submit in the same expression, zero allocation
     * // (renderer.draw() reuses a single scratch instance internally).
     * renderer.draw()
     *         .text("Hello world")
     *         .font(myFont)
     *         .at(20.0f, 40.0f)
     *         .color(0xFFFFFFFF)
     *         .pose(poseStack)
     *         .submit();
     *
     * // A prebuilt CgTextLayout wins over text(), and family() wins over font() —
     * // useful when you already have both and want the more specific one to apply.
     * renderer.draw()
     *         .layout(prebuiltLayout)
     *         .family(myFontFamily)
     *         .at(x, y)
     *         .color(argb)
     *         .pose(poseStack)
     *         .submit();
     *
     * // Retained: held across frames, only the text changes each tick.
     * // Independent of renderer.draw()'s shared immediate-mode scratch instance.
     * CgTextRenderer.Draw hudDraw = renderer.retainedDraw()
     *         .font(hudFont).at(8.0f, 8.0f).color(0xFFFFFFFF).pose(poseStack);
     * // ... later, once per frame:
     * hudDraw.text(currentFpsString).submit();
     *
     * // Manually-batched: several draws sharing one upload+draw. submit() returns the
     * // owning CgTextRenderer, so the last call in the batch can chain into endBatch().
     * renderer.beginBatch();
     * renderer.draw().text(line1).font(font).at(20.0f, 20.0f).color(0xFFFFFFFF).pose(poseStack).submit();
     * renderer.draw().text(line2).font(font).at(20.0f, 40.0f).color(0xFFFFFFFF).pose(poseStack)
     *         .submit().endBatch();
     * }</pre>
     */
    public final class Draw {
        private CgTextLayout layout;
        private String text;
        private CgFont font;
        private CgFontFamily family;
        private CgTextConstraints constraints = CgTextConstraints.UNBOUNDED;
        private int targetPx = -1;
        private float x;
        private float y;
        private int rgba = 0xFFFFFFFF;
        private PoseStack pose;

        private Draw() {}

        private Draw reset() {
            layout = null;
            text = null;
            font = null;
            family = null;
            constraints = CgTextConstraints.UNBOUNDED;
            targetPx = -1;
            x = 0f;
            y = 0f;
            rgba = 0xFFFFFFFF;
            pose = null;
            return this;
        }

        /** Sets a prebuilt layout. Wins over {@link #text(String)} if both are set. */
        public Draw layout(CgTextLayout layout) {
            this.layout = layout;
            return this;
        }

        /** Sets raw text to be laid out at {@link #submit()} time. */
        public Draw text(String text) {
            this.text = text;
            return this;
        }

        /** Sets a single font. Loses to {@link #family(CgFontFamily)} if both are set. */
        public Draw font(CgFont font) {
            this.font = font;
            return this;
        }

        /** Sets a font family. Wins over {@link #font(CgFont)} if both are set. */
        public Draw family(CgFontFamily family) {
            this.family = family;
            return this;
        }

        /**
         * Explicit raster target size in pixels. When set, resizes whichever of
         * {@code font}/{@code family} is in effect. When unset (default {@code -1}), the
         * font/family is used as-is — it must already be size-bound if building a layout
         * from {@link #text(String)}.
         */
        public Draw targetPx(int targetPx) {
            this.targetPx = targetPx;
            return this;
        }

        /** Wrap/height constraints used when building a layout from {@link #text(String)}. */
        public Draw constraints(CgTextConstraints constraints) {
            this.constraints = constraints;
            return this;
        }

        /**
         * Wrap/height constraints used when building a layout from {@link #text(String)},
         * given directly as {@code maxWidth}/{@code maxHeight} instead of a pre-built
         * {@link CgTextConstraints} — convenient for callers deriving these every draw
         * (e.g. from a live layout box) who would otherwise have to construct a fresh
         * {@link CgTextConstraints} at the call site just to pass it straight through.
         * {@code <= 0} on either axis means unbounded, matching {@link CgTextConstraints}'s
         * own convention.
         */
        public Draw constraints(float maxWidth, float maxHeight) {
            this.constraints = new CgTextConstraints(maxWidth, maxHeight);
            return this;
        }

        /** Local logical draw origin. Defaults to {@code (0, 0)} if never called. */
        public Draw at(float x, float y) {
            this.x = x;
            this.y = y;
            return this;
        }

        /** Packed RGBA color (0xRRGGBBAA). Defaults to opaque white if never called. */
        public Draw color(int rgba) {
            this.rgba = rgba;
            return this;
        }

        /**
         * The current PoseStack providing model-view transform. Usually required — the
         * only exception is when the owning {@link CgTextRenderer} has a fallback pose
         * stack set via {@link CgTextRenderer#poseStack(PoseStack)}, in which case this
         * call may be omitted and {@link #submit()} falls back to that instead.
         */
        public Draw pose(PoseStack pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Resolves and submits this draw request through the same pipeline the fixed-arity
         * {@code draw(...)} overloads use ({@link #drawInternal}). Returns the owning
         * {@link CgTextRenderer} so a manually-opened batch's final {@code submit()} call
         * can chain straight into {@link CgTextRenderer#endBatch()} — see the class
         * javadoc's example.
         *
         * @return the owning {@link CgTextRenderer}, for chaining into {@link CgTextRenderer#endBatch()}
         * @throws IllegalStateException if neither {@code layout} nor {@code text} was set,
         *                                neither {@code family} nor {@code font} was set, or
         *                                {@code pose} was never set and the renderer has no
         *                                fallback {@link CgTextRenderer#poseStack(PoseStack)}
         */
        public CgTextRenderer submit() {
            if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
            if (layout == null && text == null) throw new IllegalStateException("CgTextRenderer.Draw requires text(...) or layout(...) before submit()");
            if (family == null && font == null) throw new IllegalStateException("CgTextRenderer.Draw requires font(...) or family(...) before submit()");

            CgTextLayout resolvedLayout;
            CgFontFamily resolvedFamily;

            if (family != null) {
                resolvedFamily = targetPx > 0 ? sizeFamily(family, targetPx) : family;
                resolvedLayout = layout != null ? layout : CgTextRenderer.layout(text, resolvedFamily, constraints);
            } else {
                CgFont resolvedFont;
                if (targetPx > 0) {
                    resolvedFont = requireSizedFont(font, targetPx);
                } else if (layout == null) {
                    // Building a layout from text requires an already-sized font.
                    resolvedFont = requireSizedFont(font);
                } else {
                    // Prebuilt layout, no explicit targetPx: trust the caller, matching
                    // draw(CgTextLayout, CgFont, ...)'s existing no-check behavior.
                    resolvedFont = font;
                }
                resolvedFamily = CgFontFamily.of(resolvedFont);
                resolvedLayout = layout != null ? layout : CgTextRenderer.layout(text, resolvedFont, constraints);
            }

            if (resolvedLayout == null || resolvedLayout.getLines().isEmpty()) return CgTextRenderer.this;

            PoseStack effectivePose = pose != null ? pose : CgTextRenderer.this.poseStack;
            if (effectivePose == null) {
                throw new IllegalStateException(
                        "CgTextRenderer.Draw requires pose(...) before submit() (no fallback set via CgTextRenderer.poseStack(...))");
            }

            boolean standalone = !batchActive;
            if (standalone) beginBatch();
            try {
                drawInternal(resolvedLayout, resolvedFamily, x, y, rgba, context, effectivePose.last());
            } finally {
                if (standalone) endBatch();
            }
            return CgTextRenderer.this;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    public void delete() {
        if (deleted) return;
        if (batchActive) endBatch();
        quadRenderer.delete();
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
     * submits sorted quads to the owned {@link CgQuadRenderer}.</p>
     */
    private void drawInternal(CgTextLayout layout, CgFontFamily family,
                              float x, float y, int rgba, CgTextRenderContext context,
                              PoseStack.Pose pose) {
        CgFontKey fontKey = family.getPrimarySource().getKey();
        CgFontMetrics metrics = layout.getMetrics();
        long frame = CgGraphicsLifecycle.getCurrentFrame();

        CgTextRenderContext.RasterHistory previous = context.getHistory(fontKey);
        int previousEffectiveTargetPx = previous != null ? previous.effectiveTargetPx() : -1;
        int effectiveTargetPx = context.getScaleResolver().resolveEffectiveTargetPx(fontKey.getTargetPx(), pose, previousEffectiveTargetPx);

        boolean previousMsdf = previous != null ? previous.wasMsdf() : effectiveTargetPx >= 32;
        boolean wantMsdf =  context.getScaleResolver().shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setHistory(fontKey, effectiveTargetPx, wantMsdf);

        PagedGlyphBatch glyphBatch = buildPagedGlyphBatch(layout, family, x, y, frame, context, fontKey, effectiveTargetPx, wantMsdf, metrics);

        submitSortedQuads(glyphBatch.placements, glyphBatch.glyphX, glyphBatch.glyphY, rgba,
                fontKey.getTargetPx(), effectiveTargetPx, context, pose.pose());
    }


    /**
     * Sorts glyph placements by GL state and submits them to the owned
     * {@link CgQuadRenderer}.
     *
     * <p>On batch-key transitions (atlas page / atlas mode changes), this method:
     * <ol>
     *   <li>Calls {@link #transitionToMaterial} to flush any pending quads under the previous
     *       keyword/property/texture combination, then adopt the new one</li>
     *   <li>The projection is constant for the whole call and is synced to {@link #TEXT_DATA_UBO}
     *       once up-front via {@link #syncProjection} — flushing first if it differs from what
     *       this renderer already has queued under a different projection. The model-view
     *       transform needs no such check anymore — it's baked per-glyph-instance via
     *       {@code Quad.pose()}, see {@link #addQuadFromPlacement}.</li>
     *   <li>Emits one instanced-quad record per glyph via {@link CgQuadRenderer#quad()}</li>
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

        // Fast path (§3.4): if every glyph in this draw already shares one batch key — the
        // dominant case, one font/tier for the whole string — sorting is a guaranteed no-op.
        // Skip it: one linear scan against batchKeys[0] instead of an O(n log n) insertion sort.
        boolean uniformBatch = true;
        for (int i = 1; i < visibleCount; i++) {
            if (!batchKeys[i].equals(batchKeys[0])) {
                uniformBatch = false;
                break;
            }
        }

        if (!uniformBatch) {
            // General path: sort by batch key using insertion sort (stable, good for small N
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
        }

        // Projection is constant for this whole draw() call. Flushes first if it differs from
        // what's already queued under a different projection — see syncProjection().
        syncProjection(context.getProjection());

        // Submit sorted quads. On batch-key change, transition material state (flushing whatever
        // was pending under the previous combination).
        CgDrawBatchKey currentKey = null;

        for (int s = 0; s < visibleCount; s++) {
            CgDrawBatchKey thisKey = batchKeys[s];

            // On batch key change, transition material keywords/properties/texture.
            if (currentKey == null || !thisKey.equals(currentKey)) {
                transitionToMaterial(thisKey);

                currentKey = thisKey;
            }

            int origIdx = sortedIndices[s];
            CgGlyphPlacement p = placements[origIdx];
            int placementTargetPx = p.getKey().getFontKey().getTargetPx();
            float scaleFactor = logicalMetricScale(baseTargetPx, p.isDistanceField() ? placementTargetPx : effectiveTargetPx);
            addQuadFromPlacement(p, glyphX[origIdx], glyphY[origIdx], rgba, scaleFactor, modelView);

            if (diagnosticLogging) {
                LOGGER.info("[BatchDiag] atlasType=" + thisKey.getAtlasType()
                        + ", textureId=" + thisKey.getTextureId()
                        + ", pxRange=" + thisKey.getPxRange());
            }
        }
    }

    /**
     * Computes quad geometry from a {@link CgGlyphPlacement} and submits it to the owned
     * {@link CgQuadRenderer} as one instance record, its model-view transform baked in
     * per-glyph via {@code Quad.pose(modelView)} (see {@code CgQuadRenderer.Quad#pose}).
     *
     * <p>Uses plane bounds for geometry placement.</p>
     */
    private void addQuadFromPlacement(CgGlyphPlacement p,
                                      float penX, float penY, int rgba, float scaleFactor,
                                      Matrix4f modelView) {
        // Plane bounds are in physical raster space; normalize to logical.
        // planeLeft = bearing offset from pen; planeTop = bearing above baseline.
        // The quad origin is (penX + bearingX, penY - bearingY) in the existing
        // convention (Y-down screen space, bearingY positive = above baseline).
        float logicalBearingX = p.getPlaneLeft() * scaleFactor, logicalBearingY = p.getPlaneTop() * scaleFactor;
        float logicalWidth = p.getPlaneWidth() * scaleFactor, logicalHeight = p.getPlaneHeight() * scaleFactor;

        float qx = penX + logicalBearingX, qy = penY - logicalBearingY;
        float u0 = p.getU0(), v0 = p.getV0(), u1 = p.getU1(), v1 = p.getV1();

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
        
        quadRenderer.quad()
                    .at(qx, qy).size(logicalWidth, logicalHeight)
                    .uv(u0, v0, u1, v1).color((rgba >>> 8) | ((rgba & 0xFF) << 24))
                    .pose(modelView)
                    .submit();
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
        float penY = y + metrics.getAscender();
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
