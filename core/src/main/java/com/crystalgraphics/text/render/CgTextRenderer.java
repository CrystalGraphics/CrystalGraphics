package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
import com.crystalgraphics.api.texture.CgTexture;
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
import java.util.Arrays;
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
 * Quads are sorted by a packed {@code long} key (atlas mode, page texture, pxRange — see
 * {@link #submitSortedQuads}) so bitmap batches draw before distance-field batches. On
 * batch-state transitions the active material keywords, atlas texture, and pxRange property
 * are swapped (triggering a flush of whatever was pending under the previous state).</p>
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
 *   <li>{@link Draw#submit()} hands the whole {@link Draw} request to {@link #drawInternal},
 *       which resolves layout/family precedence and raster tier</li>
 *   <li>{@link CgTextLayoutBuilder} produces a {@link CgTextLayout} when built from raw text
 *       — see {@link CgTextLayoutCache} for how repeated text content skips re-shaping</li>
 *   <li>{@code CgResolvedGlyphs.resolve} is the sole entry into glyph resolution: on a cache
 *       hit (the steady-state common case — see that class's javadoc) it skips straight to a
 *       cached result; on a miss it walks the layout once into per-glyph scratch buffers,
 *       then resolves each glyph's {@link CgGlyphPlacement} via the atlas (itself {@code O(1)}
 *       per glyph — see {@code CgPagedGlyphAtlas}'s javadoc)</li>
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
     * Mutable adapter over whatever raw GL atlas-array texture id is currently active.
     * {@code CgMaterial}'s Properties-block sampler API ({@code applyProperties(b -> b.sampler(...))})
     * requires a real {@link CgTexture}, not a raw int — atlas
     * pages ({@code CgGlyphAtlasPage}) don't own a texture at all since the atlas texture-array
     * migration ({@code CgPagedGlyphAtlas} owns one {@code CgTexture2DArray} per atlas family; a
     * page is just a layer index into it). This view (never owns/deletes the real texture) is
     * registered once via {@link #TEXT_MATERIAL}'s Properties block at class-init, then its id is
     * mutated per atlas-family transition — the property system rebinds whatever this wrapper
     * currently points to on every {@code material.bind()}, so no repeated {@code applyProperties}
     * sampler call is needed.
     *
     * <p><strong>Target is {@code GL_TEXTURE_2D_ARRAY}</strong>, matching {@code text.shader}'s
     * {@code _MainTex} being a {@code sampler2DArray} — a texture object's target is fixed at
     * first bind, so this must match what {@code CgTexture2DArray} actually allocated with, not
     * the pre-array-migration {@code GL_TEXTURE_2D}.</p>
     */
    private static final CgTextureMutable ATLAS_TEXTURE_REF = new CgTextureMutable(CgGL.GL_TEXTURE_2D_ARRAY);

    static {
        TEXT_MATERIAL.attach(TEXT_DATA_UBO);
        TEXT_MATERIAL.applyProperties(b -> b.sampler("_MainTex", 0, ATLAS_TEXTURE_REF));
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    public static boolean diagnosticLogging = false;


    private final CgFontRegistry registry = CgFontRegistry.get();

    // ── Owned batch lifecycle ────────────────────────────────────────────────
    private final CgQuadRenderer quadRenderer;
    private boolean batchActive;
    /**
     * Projection this renderer's queued-but-unflushed quads were computed against —
     * {@code null} at the start of every batch (see {@link #beginBatch()}), since another live
     * {@code CgTextRenderer} may have overwritten the shared {@link #TEXT_DATA_UBO} since this
     * renderer's last upload. Used only to decide whether a {@link #context(CgTextRenderContext)}
     * change mid-batch must flush first (see {@link #syncProjection}) — unlike the model-view
     * transform, which is baked per-glyph-instance via {@code Quad.pose()} (see
     * {@link #submitSortedQuads}) and needs no such tracking at all. Compared by value, not
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
     * Owns the layout→atlas-placement resolution pipeline for this renderer — a distinct
     * concern from everything else in this class (batch lifecycle, material/projection
     * transitions, GPU quad submission). See {@link CgResolvedGlyphs}'s class javadoc.
     */
    private final CgResolvedGlyphs resolvedGlyphs = new CgResolvedGlyphs(registry);

    /**
     * Grow-only per-glyph sort-key scratch for {@link #submitSortedQuads} — see its javadoc
     * for the bit layout. Never needs more than {@code glyphCount} entries, so it's grown
     * directly off that count rather than tracking its own separate capacity field.
     */
    private long[] scratchSortKeys = new long[0];

    private void ensureSortScratchCapacity(int count) {
        if (count <= scratchSortKeys.length) return;
        scratchSortKeys = Arrays.copyOf(scratchSortKeys, Math.max(count, scratchSortKeys.length * 2));
    }
    
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
        activeProjection = null;
        batchActive = false;

        if (postBatchRestore != null) postBatchRestore.run();
    }


    /**
     * Flushes whatever is currently staged, binding {@link #TEXT_MATERIAL} (with whatever
     * keywords/properties/atlas texture were last set by
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
     * Transitions {@link #TEXT_MATERIAL} to the given batch state, flushing whatever was
     * pending under the previous state first. Callers (just {@link #submitSortedQuads}) are
     * responsible for only calling this when the state actually changed — this method always
     * flushes and applies unconditionally, it does not re-check for a no-op transition itself.
     *
     * <p>Every transition explicitly sets {@code MSDF_MODE} — one keyword covers both
     * distance-field atlas types (MSDF and MTSDF), since the fragment logic is identical for
     * both today (see {@code text.shader}). {@link #TEXT_MATERIAL} is shared static state, so
     * this is always an explicit enable-or-disable, never a bare {@code enableKeyword()} alone
     * — a stale keyword left on by a previous transition (this renderer's or another live
     * instance's) would otherwise silently persist into the next bind's compiled variant.</p>
     */
    private void transitionToMaterial(boolean isDistanceField, int textureId, float pxRange) {
        flush();

        TEXT_MATERIAL.toggleKeyword("MSDF_MODE", isDistanceField);
        ATLAS_TEXTURE_REF.setId(textureId);
        TEXT_MATERIAL.applyProperties(b -> b.set1f("_PxRange", pxRange));
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
     * <p>{@link #layout(CgTextLayout)}, {@link #paragraph(CgShapedParagraph)}, and
     * {@link #text(String)} may all be set; if so, {@code layout} wins over {@code paragraph},
     * which wins over {@code text} — each is strictly "more already-done" than the next.
     * Likewise {@link #family(CgFontFamily)} wins over {@link #font(CgFont)} when both are set,
     * since a family is the strictly more capable superset (a single font is just wrapped into
     * one internally via {@link CgFontFamily#of(CgFont, CgFont...)}).</p>
     *
     * <h3>{@code layout} vs. {@code paragraph}: scale-reactive wrap</h3>
     * <p>A prebuilt {@link #layout(CgTextLayout)} is honored verbatim — its wrap points never
     * change, matching this class's documented "logical layout space never changes based on
     * draw-time transforms" model. {@link #paragraph(CgShapedParagraph)} is different: on every
     * {@link #submit()}, its {@link #constraints(float, float)} are divided by the current
     * orthographic PoseStack scale (the same scale already resolved for raster crispness — see
     * {@link CgTextScaleResolver}) before re-wrapping via {@link CgShapedParagraph#layout}, so a
     * caller's {@code maxWidth} keeps meaning "this many on-screen pixels" regardless of the
     * live transform, instead of silently doubling in screen space the way a prebuilt
     * {@code CgTextLayout} does under a 2x PoseStack scale. The re-wrap is cheap and memoized
     * (see that method's javadoc) — only the frame where the effective scale actually steps
     * pays for line-breaking again. Not applied for world-space text (see
     * {@link PerspectiveScaleResolver}'s "Layout Invariance" — camera distance must never
     * reflow a billboard/world paragraph).</p>
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
        private CgShapedParagraph paragraph;
        private String text;
        private CgFont font;
        private CgFontFamily family;
        private float maxWidth;
        private float maxHeight;
        private int targetPx = -1;
        private float x;
        private float y;
        private int rgba = 0xFFFFFFFF;
        private PoseStack pose;

        private Draw() {}

        private Draw reset() {
            layout = null;
            paragraph = null;
            text = null;
            font = null;
            family = null;
            maxWidth = 0f;
            maxHeight = 0f;
            targetPx = -1;
            x = 0f;
            y = 0f;
            rgba = 0xFFFFFFFF;
            pose = null;
            return this;
        }

        /** Sets a prebuilt layout. Wins over {@link #paragraph(CgShapedParagraph)}/{@link #text(String)} if set. */
        public Draw layout(CgTextLayout layout) {
            this.layout = layout;
            return this;
        }

        /**
         * Sets a retained, shaped-but-not-wrapped paragraph — re-wrapped at
         * {@link #constraints(float, float)} on every {@link #submit()}, scale-adjusted for
         * orthographic/UI draws (see this class's javadoc, "{@code layout} vs. {@code paragraph}").
         * Wins over {@link #text(String)}; loses to {@link #layout(CgTextLayout)} if both are set.
         */
        public Draw paragraph(CgShapedParagraph paragraph) {
            this.paragraph = paragraph;
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

        /**
         * Wrap/height constraints used when building a layout from {@link #text(String)}/
         * {@link #paragraph(CgShapedParagraph)}. {@code <= 0} on either axis means unbounded
         * (the default).
         */
        public Draw constraints(float maxWidth, float maxHeight) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
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
         * Validates required fields and hands this request off to {@link #drawInternal},
         * which reads whatever raw fields it needs directly off {@code this} — resolution
         * (family/font sizing, layout-from-text, prebuilt-layout precedence) lives there,
         * not here, so a new optional {@link Draw} field never requires touching this
         * method's signature. Returns the owning {@link CgTextRenderer} so a manually-opened
         * batch's final {@code submit()} call can chain straight into
         * {@link CgTextRenderer#endBatch()} — see the class javadoc's example.
         *
         * @return the owning {@link CgTextRenderer}, for chaining into {@link CgTextRenderer#endBatch()}
         * @throws IllegalStateException if neither {@code layout} nor {@code text} was set,
         *                                neither {@code family} nor {@code font} was set, or
         *                                {@code pose} was never set and the renderer has no
         *                                fallback {@link CgTextRenderer#poseStack(PoseStack)}
         */
        public CgTextRenderer submit() {
            if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
            if (layout == null && paragraph == null && text == null) throw new IllegalStateException("CgTextRenderer.Draw requires text(...), paragraph(...), or layout(...) before submit()");
            if (family == null && font == null) throw new IllegalStateException("CgTextRenderer.Draw requires font(...) or family(...) before submit()");

            PoseStack effectivePose = pose != null ? pose : CgTextRenderer.this.poseStack;
            if (effectivePose == null) {
                throw new IllegalStateException(
                        "CgTextRenderer.Draw requires pose(...) before submit() (no fallback set via CgTextRenderer.poseStack(...))");
            }

            boolean standalone = !batchActive;
            if (standalone) beginBatch();
            try {
                drawInternal(this, effectivePose.last());
            } finally {
                if (standalone) endBatch();
            }
            return CgTextRenderer.this;
        }

        /**
         * Resolves (without drawing) the exact {@link CgTextLayout} {@link #submit()} would
         * draw right now — same font/scale/paragraph-reflow resolution, including the
         * pose-scale-aware constraint division for {@link #paragraph}/{@link #text}. Useful
         * for measuring a section's on-screen size (e.g. {@code totalHeight()}) to position
         * whatever comes after it, without the caller ever computing scale itself: this asks
         * the renderer the same question it's about to answer for real. Cheap even when
         * called every frame just for measurement — {@link CgShapedParagraph#layout} memoizes
         * identical {@code (maxWidth, maxHeight)} pairs, and {@link #submit()} immediately
         * after re-resolves to the same cached result.
         *
         * @throws IllegalStateException under the same conditions as {@link #submit()}
         */
        public CgTextLayout measure() {
            if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
            if (layout == null && paragraph == null && text == null) throw new IllegalStateException("CgTextRenderer.Draw requires text(...), paragraph(...), or layout(...) before measure()");
            if (family == null && font == null) throw new IllegalStateException("CgTextRenderer.Draw requires font(...) or family(...) before measure()");

            PoseStack effectivePose = pose != null ? pose : CgTextRenderer.this.poseStack;
            if (effectivePose == null) {
                throw new IllegalStateException(
                        "CgTextRenderer.Draw requires pose(...) before measure() (no fallback set via CgTextRenderer.poseStack(...))");
            }
            return resolveDraw(this, effectivePose.last()).layout();
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
     * Renderer core shared by the 2D and world-space entry points. Reads whatever raw
     * fields it needs directly off {@code draw} — new optional {@link Draw} fields never
     * require touching this signature — and resolves the family/font/layout precedence
     * rules documented on {@link Draw}'s class javadoc.
     *
     * <p>Resolves the effective raster tier, delegates layout flattening/prequeueing and
     * atlas placement resolution to {@link #resolvedGlyphs}, then sorts and submits
     * ({@link #submitSortedQuads}).</p>
     */
    /**
     * Holds everything {@link #drawInternal} needs beyond {@code CgTextLayout} resolution
     * itself (font key, resolved raster tier) alongside the resolved layout — shared by
     * {@link #drawInternal} and {@link Draw#measure()} so measuring a paragraph's on-screen
     * size never drifts from what actually gets drawn.
     */
    private record ResolvedDraw(CgFontKey fontKey, int effectiveTargetPx, boolean wantMsdf, CgTextLayout layout) {
    }

    /**
     * Resolves font/family, raster tier (via {@link CgTextScaleResolver}), and the final
     * {@link CgTextLayout} for {@code draw} — the family/font/layout precedence rules
     * documented on {@link Draw}'s class javadoc, plus the pose-scale-aware constraint
     * division for {@link Draw#paragraph}/{@link Draw#text} (see {@link Draw}'s "{@code layout}
     * vs. {@code paragraph}" section). Pure resolution — no glyph placement, no GPU submission,
     * so it's safe to call from {@link Draw#measure()} without side effects beyond the
     * {@link CgTextRenderContext} raster-history bookkeeping every draw already does.
     */
    private ResolvedDraw resolveDraw(Draw draw, PoseStack.Pose pose) {
        CgFontFamily resolvedFamily;
        CgFont resolvedFont = null; // only populated (and only needed) on the family==null branch

        if (draw.family != null) {
            resolvedFamily = draw.targetPx > 0 ? sizeFamily(draw.family, draw.targetPx) : draw.family;
        } else {
            if (draw.targetPx > 0) {
                resolvedFont = requireSizedFont(draw.font, draw.targetPx);
            } else if (draw.layout == null && draw.paragraph == null) {
                // Building a layout from text requires an already-sized font.
                resolvedFont = requireSizedFont(draw.font);
            } else {
                // Prebuilt layout/paragraph, no explicit targetPx: trust the caller, matching
                // draw(CgTextLayout, CgFont, ...)'s existing no-check behavior.
                resolvedFont = draw.font;
            }
            resolvedFamily = CgFontFamily.of(resolvedFont);
        }

        CgFontKey fontKey = resolvedFamily.getPrimarySource().getKey();

        CgTextRenderContext.RasterHistory previous = context.getHistory(fontKey);
        int previousEffectiveTargetPx = previous != null ? previous.effectiveTargetPx() : -1;
        int effectiveTargetPx = context.getScaleResolver().resolveEffectiveTargetPx(fontKey.getTargetPx(), pose, previousEffectiveTargetPx);

        boolean previousMsdf = previous != null ? previous.wasMsdf() : effectiveTargetPx >= 32;
        boolean wantMsdf = context.getScaleResolver().shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setHistory(fontKey, effectiveTargetPx, wantMsdf);

        CgTextLayout resolvedLayout;
        if (draw.layout != null) {
            // Prebuilt, immutable layout -- honored verbatim, no reflow. See this class's
            // "Three-Space Model" javadoc: logical layout space never changes based on
            // draw-time transforms, by design, for callers who explicitly opted into a
            // frozen CgTextLayout.
            resolvedLayout = draw.layout;
        } else {
            // draw.constraintMaxWidth/Height are expressed in the same design-space pixels as
            // fontKey's base size. Divide by the same scale already resolved above for raster
            // crispness (effectiveTargetPx / baseTargetPx) so maxWidth keeps meaning "this many
            // on-screen pixels" regardless of the live PoseStack scale -- see Draw's class
            // javadoc. Never applied to world-space text: PerspectiveScaleResolver's
            // effectiveTargetPx reflects raster tier / view distance, not UI zoom, and world
            // paragraphs must not reflow as the camera moves (see PerspectiveScaleResolver's
            // "Layout Invariance").
            float scale = context.isWorldText() ? 1f : effectiveTargetPx / (float) fontKey.getTargetPx();
            float effectiveMaxWidth = scaleConstraint(draw.maxWidth, scale);
            float effectiveMaxHeight = scaleConstraint(draw.maxHeight, scale);

            if (draw.paragraph != null) {
                resolvedLayout = draw.paragraph.layout(effectiveMaxWidth, effectiveMaxHeight);
            } else if (draw.family != null) {
                resolvedLayout = layout(draw.text, resolvedFamily, effectiveMaxWidth, effectiveMaxHeight);
            } else {
                resolvedLayout = layout(draw.text, resolvedFont, effectiveMaxWidth, effectiveMaxHeight);
            }
        }

        return new ResolvedDraw(fontKey, effectiveTargetPx, wantMsdf, resolvedLayout);
    }

    /**
     * Divides {@code value} by {@code scale}, leaving an unbounded ({@code <= 0}) value
     * unbounded, and skipping the division entirely when {@code scale} is (effectively) 1 or
     * non-positive -- the common case, and avoids float noise on an exact no-op.
     */
    private static float scaleConstraint(float value, float scale) {
        if (value <= 0f || scale <= 0f || Math.abs(scale - 1f) < 0.0001f) {
            return value;
        }
        return value / scale;
    }

    private void drawInternal(Draw draw, PoseStack.Pose pose) {
        ResolvedDraw resolved = resolveDraw(draw, pose);
        CgFontKey fontKey = resolved.fontKey();
        int effectiveTargetPx = resolved.effectiveTargetPx();
        boolean wantMsdf = resolved.wantMsdf();
        CgTextLayout resolvedLayout = resolved.layout();

        if (resolvedLayout == null || resolvedLayout.lines().isEmpty()) return;

        long frame = CgGraphicsLifecycle.getCurrentFrame();
        int glyphCount = resolvedGlyphs.resolve(resolvedLayout, draw.x, draw.y, frame, context, effectiveTargetPx, wantMsdf, fontKey, draw.rgba);
        CgTextDecorationRect[] decorations = resolvedLayout.baked().decorations();
        if (glyphCount > 0 || decorations.length > 0) {
            submitSortedQuads(glyphCount, decorations, fontKey.getTargetPx(), effectiveTargetPx, wantMsdf,
                    draw.x, draw.y, draw.rgba, pose.pose());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  SORT + SUBMIT
    // ══════════════════════════════════════════════════════════════════════════════════════════

    // Bit layout of each entry in scratchSortKeys, MSB → LSB. Ordered coarsest-to-finest so an
    // unsigned numeric sort of the whole long already sorts by (mode, textureId, pxRange), with
    // the original glyph index riding along in the low bits purely so the sorted array alone
    // tells you which glyph each entry came from — no parallel index array needed.
    //   [63:62] mode      (0 = bitmap, 1 = distance-field — MSDF/MTSDF share one shader keyword,
    //                       see text.shader, so they don't need distinct mode values today)
    //   [61:38] textureId (GL atlas-ARRAY texture id — since the atlas texture-array migration,
    //                       CgGlyphPlacement.getPageTextureId() returns the same id for every
    //                       page/layer of one atlas family, not a per-page id, so this already
    //                       batches every page of a family together with no further code change
    //                       needed here — masked to 24 bits, real ids never come close to that
    //                       range)
    //   [37:16] pxRange   (top 22 bits of the SDF pixel range's raw float bits; valid only
    //                       because pxRange is always >= 0, so its raw bits are already
    //                       monotonic with its value — dropping the low 10 bits just coarsens
    //                       ties between distances closer than ~0.01%, far finer than any two
    //                       real atlas configs differ by. In practice this is always constant
    //                       within one textureId already — one CgPagedGlyphAtlas is one config,
    //                       one pxRange — so it adds no further batch-break granularity beyond
    //                       textureId today; kept rather than removed since promoting pxRange to
    //                       a genuinely per-instance field is a separate, optional decision — see
    //                       docs_research/CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md's "adjacent win.")
    //   [63]    kind      (0 = glyph, localIndex into CgResolvedGlyphs#placements; 1 = decoration,
    //                       localIndex into the resolveDecorations() list built for this draw
    //                       call — see submitSortedQuads. A decoration's own (mode, textureId,
    //                       pxRange) is packed identically to a glyph's, so the two interleave
    //                       into one GL-state-ordered sequence and share the same
    //                       transitionToMaterial calls; only the two data SOURCES stay distinct,
    //                       never stitched into one cache or one placement type. Lives in the
    //                       mode field's own top bit rather than carving into localIndex below:
    //                       mode only ever holds 0 or 1 despite its 2-bit-wide field, so this
    //                       bit was already unconditionally 0 — using it costs nothing.)
    //   [62]    mode      (0 = bitmap, 1 = distance-field — see below)
    //   [15:0]  localIndex (index within whichever array [kind] selects — the FULL 16 bits,
    //                       independently for glyphs and decorations, since kind no longer
    //                       shares this field. Silently wrapping this would alias two different
    //                       entries onto the same key and corrupt rendering, not just degrade
    //                       performance, so keep this at the full width it already was before
    //                       decorations were folded in here.)
    private static final int SORT_INDEX_BITS = 16;
    private static final int SORT_PXRANGE_SHIFT = SORT_INDEX_BITS;
    private static final int SORT_TEXTURE_SHIFT = SORT_PXRANGE_SHIFT + 22;
    private static final int SORT_MODE_SHIFT = SORT_TEXTURE_SHIFT + 24;
    private static final long SORT_INDEX_MASK = (1L << SORT_INDEX_BITS) - 1;
    private static final long KIND_DECORATION_BIT = 1L << 63;
    private static final long LOCAL_INDEX_MASK = SORT_INDEX_MASK;
    /**
     * Mask isolating mode+textureId+pxRange only — deliberately excludes BOTH localIndex and
     * the kind bit, so a glyph and a decoration sharing the same atlas/mode/pxRange compare
     * equal here and never trigger a transition purely because one came from the placements
     * array and the other from resolveDecorations(). Kind still participates in the raw sort
     * (glyphs sort before decorations within an otherwise-tied group), just not in this
     * batch-state comparison.
     */
    private static final long SORT_BATCH_MASK = (~SORT_INDEX_MASK) & ~KIND_DECORATION_BIT;

    private static long packBatchBits(boolean isDistanceField, int textureId, float pxRange) {
        long mode = isDistanceField ? 1L : 0L;
        long textureIdBits = textureId & 0xFFFFFFL;
        long pxRangeBits = (Float.floatToRawIntBits(pxRange) & 0xFFFFFFFFL) >>> 10;
        return (mode << SORT_MODE_SHIFT) | (textureIdBits << SORT_TEXTURE_SHIFT) | (pxRangeBits << SORT_PXRANGE_SHIFT);
    }

    private static long packGlyphSortKey(CgGlyphPlacement p, int glyphIndex) {
        return packBatchBits(p.isDistanceField(), p.atlasTextureId(), p.pxRange()) | glyphIndex;
    }

    private static long packDecorationSortKey(CgResolvedGlyphs.ResolvedDecoration d, int decorationIndex) {
        // isDistanceField/pxRange come straight off the same atlas a real glyph of this font
        // would report (see CgResolvedGlyphs.ResolvedDecoration's javadoc) — that equality is
        // what lets a decoration land in the exact same sorted batch as its font's glyphs,
        // needing no material transition beyond what those glyphs already required.
        return packBatchBits(d.isDistanceField(), d.atlasTextureId(), d.pxRange()) | KIND_DECORATION_BIT | decorationIndex;
    }

    /**
     * Sorts this draw call's glyph placements ({@link CgResolvedGlyphs#placements}) AND baked
     * decoration rects (pre-resolved via {@link CgResolvedGlyphs#resolveDecorations}) together
     * by GL batch state, then submits both to the owned {@link CgQuadRenderer} in that single
     * order — one instanced-quad record per glyph or decoration, transform baked in via
     * {@code Quad.pose(modelView)}.
     *
     * <p>Glyphs and decorations are never merged into one data type or one cache — they stay
     * two entirely separate sources (a {@code CgGlyphPlacement[]} and a
     * {@code List<CgResolvedGlyphs.ResolvedDecoration>}) for their entire lifetime, resolved by
     * two entirely separate methods on {@link CgResolvedGlyphs}. What's shared is only the
     * {@code long} sort key's bit layout (see above) and this one sort+submit pass. A
     * decoration's batch bits are built from the exact same atlas its font's glyphs use (see
     * {@link CgResolvedGlyphs.ResolvedDecoration}'s javadoc), so in the common case — a
     * decoration interleaved with glyphs of the same font/mode — it lands in the very same
     * sorted run as those glyphs and triggers zero extra {@link #transitionToMaterial} calls;
     * a transition only ever happens where the batch state genuinely changes (a different
     * font's atlas, bitmap vs. MSDF, etc.), never merely because an entry happens to be a
     * decoration.</p>
     *
     * <p>{@link Arrays#sort(long[], int, int)} — the JDK's primitive dual-pivot quicksort, which
     * already detects nearly-sorted runs and falls back to insertion sort for small ranges —
     * does the entire sort with no per-entry allocation and no hand-rolled fast path.</p>
     *
     * <p>On batch-state transitions, this method:
     * <ol>
     *   <li>Calls {@link #transitionToMaterial} to flush any pending quads under the previous
     *       keyword/property/texture combination, then adopt the new one</li>
     *   <li>The projection is constant for the whole call and is synced to {@link #TEXT_DATA_UBO}
     *       once up-front via {@link #syncProjection} — flushing first if it differs from what
     *       this renderer already has queued under a different projection. The model-view
     *       transform needs no such check anymore — it's baked per-instance.</li>
     *   <li>Emits one instanced-quad record via {@link CgQuadRenderer#quad()}</li>
     * </ol>
     */
    private void submitSortedQuads(int glyphCount, CgTextDecorationRect[] decorations,
                                    int baseTargetPx, int effectiveTargetPx, boolean wantMsdf,
                                    float drawX, float drawY, int drawRgba, Matrix4f modelView) {
        CgGlyphPlacement[] placements = resolvedGlyphs.placements;

        int visibleGlyphCount = 0;
        for (int i = 0; i < glyphCount; i++)
            if (placements[i] != null && placements[i].hasGeometry()) visibleGlyphCount++;

        List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations =
                resolvedGlyphs.resolveDecorations(decorations, drawX, drawY, drawRgba, effectiveTargetPx, wantMsdf);

        int totalCount = visibleGlyphCount + resolvedDecorations.size();
        if (totalCount == 0) return;

        ensureSortScratchCapacity(totalCount);
        int si = 0;
        for (int i = 0; i < glyphCount; i++) {
            CgGlyphPlacement p = placements[i];
            if (p != null && p.hasGeometry()) {
                scratchSortKeys[si++] = packGlyphSortKey(p, i);
            }
        }
        for (int i = 0; i < resolvedDecorations.size(); i++) {
            scratchSortKeys[si++] = packDecorationSortKey(resolvedDecorations.get(i), i);
        }
        Arrays.sort(scratchSortKeys, 0, totalCount);

        // Projection is constant for this whole draw() call. Flushes first if it differs from
        // what's already queued under a different projection — see syncProjection().
        syncProjection(context.getProjection());

        long currentBatchBits = 0;
        boolean batchStarted = false;
        for (int s = 0; s < totalCount; s++) {
            long key = scratchSortKeys[s];
            long batchBits = key & SORT_BATCH_MASK;
            boolean isDecoration = (key & KIND_DECORATION_BIT) != 0;
            int localIndex = (int) (key & LOCAL_INDEX_MASK);

            // Both branches only compute this entry's batch identity + quad geometry — the
            // actual transition check and quad() submission below are shared, so a decoration
            // and a glyph in the same batch (the common case — see packDecorationSortKey)
            // never duplicate either.
            CgGlyphPlacement p = null;
            boolean isDistanceField;
            int textureId;
            float pxRange;
            float qx, qy, w, h, u0, v0, u1, v1;
            int rgba, atlasLayer;

            if (isDecoration) {
                CgResolvedGlyphs.ResolvedDecoration d = resolvedDecorations.get(localIndex);
                isDistanceField = d.isDistanceField();
                textureId = d.atlasTextureId();
                pxRange = d.pxRange();
                qx = d.qx(); qy = d.qy(); w = d.w(); h = d.h();
                u0 = d.u0(); v0 = d.v0(); u1 = d.u1(); v1 = d.v1();
                rgba = d.rgba();
                atlasLayer = d.atlasPageIndex();
            } else {
                p = placements[localIndex];
                isDistanceField = p.isDistanceField();
                textureId = p.atlasTextureId();
                pxRange = p.pxRange();

                int placementTargetPx = p.key().getFontKey().getTargetPx();
                float scaleFactor = CgResolvedGlyphs.logicalMetricScale(baseTargetPx, isDistanceField ? placementTargetPx : effectiveTargetPx);

                // Plane bounds are in physical raster space; normalize to logical. planeLeft/planeTop
                // are bearing offsets from the pen (Y-down screen space, bearingY positive = above baseline).
                float logicalBearingX = p.planeLeft() * scaleFactor, logicalBearingY = p.planeTop() * scaleFactor;
                w = p.getPlaneWidth() * scaleFactor; h = p.getPlaneHeight() * scaleFactor;
                qx = resolvedGlyphs.glyphX[localIndex] + logicalBearingX;
                qy = resolvedGlyphs.glyphY[localIndex] - logicalBearingY;
                u0 = p.u0(); v0 = p.v0(); u1 = p.u1(); v1 = p.v1();
                rgba = resolvedGlyphs.argbColor[localIndex];
                atlasLayer = p.atlasPageIndex();
            }

            if (!batchStarted || batchBits != currentBatchBits) {
                transitionToMaterial(isDistanceField, textureId, pxRange);
                currentBatchBits = batchBits;
                batchStarted = true;
            }

            quadRenderer.quad()
                    .at(qx, qy).size(w, h)
                    .uv(u0, v0, u1, v1)
                    .color(rgba)
                    .atlasLayer(atlasLayer)
                    .pose(modelView)
                    .submit();

            if (diagnosticLogging && p != null) {
                LOGGER.info("[BatchDiag] glyphId=" + p.key().getGlyphId()
                        + ", atlasType=" + p.atlasType()
                        + ", textureId=" + p.atlasTextureId()
                        + ", atlasPageIndex/layer=" + p.atlasPageIndex()
                        + ", pxRange=" + p.pxRange()
                        + ", uv=[" + p.u0() + "," + p.v0() + "," + p.u1() + "," + p.v1() + "]"
                        + ", pos=[" + qx + "," + qy + "], size=[" + w + "," + h + "]");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Shared layout helper used by the string-based draw overloads.
     *
     * <p>This is the string-to-layout boundary for the renderer. Checks {@link CgTextLayoutCache}
     * first — see that class's javadoc; on a miss, builds a default-knobs {@link CgTextLayoutRequest}.
     * Renderer code should treat the returned {@link CgTextLayout} as the stable hand-off
     * format for glyph resolution, and must not mutate it — a cache hit may hand the same
     * instance to multiple unrelated callers.</p>
     */
    static CgTextLayout layout(String text, CgFont font, float maxWidth, float maxHeight) {
        if (text == null) throw new IllegalArgumentException("text must not be null");

        CgTextLayoutCache.Key key = CgTextLayoutCache.key(text, font, maxWidth, maxHeight);
        CgTextLayout cached = CgTextLayoutCache.get(key);
        if (cached != null) return cached;

        CgTextLayout built = CgTextLayoutRequest.of(text, font)
                .maxWidth(maxWidth).maxHeight(maxHeight)
                .build();
        CgTextLayoutCache.put(key, built);
        return built;
    }

    static CgTextLayout layout(String text, CgFontFamily family, float maxWidth, float maxHeight) {
        if (text == null) throw new IllegalArgumentException("text must not be null");

        CgTextLayoutCache.Key key = CgTextLayoutCache.key(text, family, maxWidth, maxHeight);
        CgTextLayout cached = CgTextLayoutCache.get(key);
        if (cached != null) return cached;

        CgTextLayout built = CgTextLayoutRequest.of(text, family)
                .maxWidth(maxWidth).maxHeight(maxHeight)
                .build();
        CgTextLayoutCache.put(key, built);
        return built;
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
