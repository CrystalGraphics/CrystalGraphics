package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgStrokeAlign;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextStroke;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTextureMutable;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgChunkSink;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.layout.CgTextLayoutCache;
import com.crystalgraphics.text.render.context.*;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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
 * {@link #submitBatchedQuads}) so bitmap batches draw before distance-field batches. On
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
 *   <li>{@link com.crystalgraphics.text.layout.CgTextLayoutEngine} produces a {@link CgTextLayout} when built from raw text
 *       — see {@link CgTextLayoutCache} for how repeated text content skips re-shaping</li>
 *   <li>{@code CgResolvedGlyphs.resolve} is the sole entry into glyph resolution: on a cache
 *       hit (the steady-state common case — see that class's javadoc) it skips straight to a
 *       cached result; on a miss it walks the layout once into per-glyph scratch buffers,
 *       then resolves each glyph's {@link CgGlyphPlacement} via the atlas (itself {@code O(1)}
 *       per glyph — see {@code CgGlyphAtlas}'s javadoc)</li>
 *   <li>{@link #submitBatchedQuads} sorts quads by GL state and submits them to the owned batch renderer</li>
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

    private static final String TEXT_SHADER = "crystalgraphics:shaders/text.shader";

    /**
     * The shader every renderer draws with, cached per path so it takes part in hot reload (F3+T) and teardown.
     * {@link #warmUpMaterial} compiles its variants; each renderer draws through an instance of its own,
     * {@link #textMaterial}, which shares the compiled programs.
     */
    public static final CgMaterial TEXT_MATERIAL = CgMaterial.load(TEXT_SHADER);

    /**
     * Text-only per-renderer uniform data — currently just {@code u_Projection}: a renderer's projection is its own
     * (an orthographic UI one, as often as not), where the pass's {@code cg_ProjMatrix} is whoever records the pass.
     * Also the natural home for any future text-only uniform that
     * isn't a good fit for either {@link #TEXT_MATERIAL}'s Properties block (per-batch-key, not
     * per-draw) or {@code CgQuadRenderer}'s per-instance record (per-glyph, not per-renderer) —
     * {@code u_ModelView} was here before this migration but is gone now: model-view is baked
     * per-glyph-instance via {@code Quad.pose()}, see {@link #addQuadFromPlacement}.
     */
    private static final CgBufferFormat TEXT_DATA_FORMAT = CgBufferFormat
            .builder("TextData", CgBufferFormat.MemoryLayout.STD140)
            .mat4("u_Projection")
            .vec4("u_TextGammaSmall")
            .vec4("u_TextGammaLarge")
            .vec4("u_TextGammaRamp")
            .build();

    /**
     * Declares {@code TextData} on the shared shader. Nothing writes or uploads it: each renderer's {@link #textBlock}
     * overrides it on that renderer's material, and every chunk keeps those bytes by value.
     */
    private static final CgUniformBuffer TEXT_DATA_UBO = CgShaderBufferRegistry.get().getOrCreateUboInternal(
            TEXT_DATA_FORMAT, "TextData", CgBindingPoints.TEXT_DATA_UBO, CgBufferLifetime.FRAME);

    static {
        TEXT_MATERIAL.attach(TEXT_DATA_UBO);
    }

    /**
     * This renderer's own material. Its keywords and atlas are its own, so two renderers — recording on two threads,
     * or interleaving batches on one — never draw with each other's.
     */
    private final CgMaterial textMaterial = CgMaterial.newInstance(TEXT_SHADER);
    /**
     * The atlas the current batch draws from, as {@link #textMaterial}'s {@code _MainTex}. Points at the atlas's texture
     * itself, whose GL id exists only once the render thread has made it. {@code GL_TEXTURE_2D_ARRAY}, as
     * {@code text.shader}'s {@code sampler2DArray} wants.
     */
    private final CgTextureMutable atlasTexture = new CgTextureMutable(CgGL.GL_TEXTURE_2D_ARRAY);
    /** This renderer's {@code TextData}: the projection and the gamma correction its chunks keep. */
    private final CgBufferWriter textBlock =
            new CgBufferWriter(new CgStagingBuffer(TEXT_DATA_FORMAT.getFloatCount()), TEXT_DATA_FORMAT);

    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    public static boolean diagnosticLogging = false;

    /**
     * {@code -Dcrystalgraphics.text.traceQuadLoop=true} times every individual iteration of the
     * quad-submission loop and reports the slowest one.
     *
     * <p>Off by default because it adds a {@code System.nanoTime()} per quad (~25 ns x 8103 quads
     * = ~0.2 ms on a 1.1 ms loop). Turn it on only to distinguish a blocking call inside the loop
     * from the render thread being descheduled — see the comment at the loop head.</p>
     */
    private static final boolean traceQuadLoop =
            Boolean.getBoolean("crystalgraphics.text.traceQuadLoop");


    private final CgFontRegistry registry = CgFontRegistry.get();

    // ── Owned batch lifecycle ────────────────────────────────────────────────
    private final CgQuadRenderer quadRenderer;
    private boolean batchActive;
    /**
     * Projection this renderer's queued-but-unflushed quads were computed against; invalid at the start of every
     * batch (see {@link #beginBatch()}). Used only to decide whether a {@link #context(CgTextRenderContext)}
     * change mid-batch must flush first (see {@link #syncProjection}) — unlike the model-view
     * transform, which is baked per-glyph-instance via {@code Quad.pose()} (see
     * {@link #submitBatchedQuads}) and needs no such tracking at all. Compared by value, not
     * reference, since {@link CgTextRenderContext#getProjection()} is a live, mutable matrix
     * owned by the context.
     */
    private final Matrix4f activeProjection = new Matrix4f();

    /** Whether {@link #activeProjection} is what {@link #textBlock} holds; false after anything that may have moved it. */
    private boolean projectionValid;

    /** Written beside the projection, so two renderers can draw with different corrections in one frame. */
    private CgTextGamma gamma = CgTextGamma.initial();
    /** The {@link CgClipTable} entry every quad is stamped with; 0 for none. */
    private int clip;
    /** The spatial and effect nodes every quad is drawn in; 0 and 0 for the pass's own space. */
    private int spatialNode, effectNode;
    /**
     * Optional caller-supplied hook invoked at the end of every {@link #endBatch()} (manual
     * or {@link Draw#submit()}'s standalone auto-batch alike) — see {@link #restoreStateWith}.
     */
    private Runnable postBatchRestore;

    /**
     * Batch identity {@link #textMaterial} is currently configured for, or {@link #NO_ACTIVE_BATCH}
     * when nothing is known to be configured.
     *
     * <p>These were locals in {@link #submitBatchedQuads}, which meant every {@code draw()} call
     * began with no knowledge of the material's state and unconditionally transitioned on its first
     * quad — a flush plus keyword toggle plus property re-apply, per draw. For a UI frame issuing a
     * thousand labels that all share one atlas and one shader mode, that was a thousand transitions
     * where one would do, and it dominated the frame.
     *
     * <p>Reset at {@link #beginBatch()} rather than trusted across batches. Between batches the GL
     * binding is torn down and an arbitrary {@link #restoreStateWith} hook may have run, so the
     * assumption that the material is still configured as recorded no longer holds. That costs one
     * redundant transition per batch and removes the need to reason about what happens in between;
     * the win is inside the batch, where the thousand draws are.
     */
    private long activeBatchBits = -1L;

    /**
     * Sentinel for "no batch state is known". Not a valid batch identity: {@link CgTextSortKey}
     * reserves bit 63 clear so keys sort correctly under Java's signed {@code Arrays.sort(long[])},
     * so a negative value can never collide with a real batch and be mistaken for a match.
     */
    private static final long NO_ACTIVE_BATCH = -1L;

    /**
     * Owns the layout→atlas-placement resolution pipeline for this renderer — a distinct
     * concern from everything else in this class (batch lifecycle, material/projection
     * transitions, GPU quad submission). See {@link CgResolvedGlyphs}'s class javadoc.
     */
    private final CgResolvedGlyphs resolvedGlyphs = new CgResolvedGlyphs(registry);

    /** Per-renderer, since two renderers can be mid-draw under different projections. */
    private final CgTextCuller culler = new CgTextCuller();
    private final CgTextShadowPlan shadowPlan = new CgTextShadowPlan(registry);

    /**
     * Grow-only per-glyph sort-key scratch for {@link #submitBatchedQuads} — see its javadoc
     * for the bit layout. Never needs more than {@code glyphCount} entries, so it's grown
     * directly off that count rather than tracking its own separate capacity field.
     */
    private long[] scratchSortKeys = new long[0];

    /**
     * Last-resort identity pose used by {@link Draw#submit()}/{@link Draw#measure()} when
     * neither {@link Draw#pose(PoseStack)} nor {@link #poseStack(PoseStack)} was set. Shared, never
     * mutated.
     */
    private static final PoseStack IDENTITY_POSE_STACK = new PoseStack();
    
    /**
     * Reusable scratch for {@link #pixelSnapDelta} — the inverse of the current draw call's
     * model-view (recomputed once per {@link #submitBatchedQuads} call, not per-glyph) plus two
     * throwaway vectors, kept as fields purely to avoid a small allocation per glyph.
     */
    private final Matrix4f scratchInverseModelView = new Matrix4f();
    private final Vector3f scratchLocalDelta = new Vector3f();
    /** Reused by the pose-phase probe in drawInternal; never escapes the frame thread. */
    private final Vector3f scratchPosePhase = new Vector3f();

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


    public CgTextGamma gamma() {
        return gamma;
    }

    /**
     * The coverage correction this renderer's text is drawn with; {@link CgTextGamma#DEFAULT} unless the JVM says
     * otherwise. Safe mid-batch: glyphs already queued keep the correction they were queued under.
     */
    public CgTextRenderer gamma(@NonNull CgTextGamma gamma) {
        if (gamma.equals(this.gamma)) return this;
        flush();
        this.gamma = gamma;
        // Forces the next draw to upload, since the projection alone may not have changed.
        projectionValid = false;
        return this;
    }

    /**
     * The rounded clip every glyph, decoration and shadow drawn from now on is drawn inside: a
     * {@link CgClipTable} entry of this frame, or 0 for none. Per quad, so it needs no flush and a change
     * mid-batch keeps the batch.
     *
     * <pre>{@code
     * renderer.clip(recording.clips().add(...));
     * renderer.draw().text(label).at(x, y).submit();
     * renderer.clip(0);
     * }</pre>
     */
    public CgTextRenderer clip(int entry) {
        this.clip = entry;
        return this;
    }

    /**
     * The spatial and effect nodes every glyph, decoration and shadow drawn from now on is drawn in: a draw's pose is
     * then in the spatial node's space, and the palette places it. 0 and 0 for the pass's own space. Per quad, like
     * {@link #clip}.
     *
     * <pre>{@code
     * renderer.node(scrollContent, 0);
     * renderer.draw().text(row).at(x, yInContent).submit();
     * renderer.node(0, 0);
     * }</pre>
     */
    public CgTextRenderer node(int spatial, int effect) {
        this.spatialNode = spatial;
        this.effectNode = effect;
        return this;
    }

    /**
     * Hands every later flush's glyphs to {@code sink} as a chunk — a {@code CgPassRecorder} recording a frame —
     * instead of drawing them; null draws at once again.
     */
    public CgTextRenderer sink(@Nullable CgChunkSink sink) {
        quadRenderer.sink(sink);
        projectionValid = false;
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
        textMaterial.applyProperties(b -> b.sampler("_MainTex", 0, atlasTexture));
        textMaterial.overrideBlock(TEXT_DATA_UBO, textBlock);
        quadRenderer.useMaterial(textMaterial);
    }

    /**
     * Creates the renderer façade, including its owned {@link CgQuadRenderer}, and
     * registers it with {@link CgTextRendererRegistry} — the registry doesn't own release
     * timing (callers must still call {@link #delete()} promptly when done), but sweeps
     * any renderer still alive at GL context teardown as a backstop, matching every other
     * GPU-resource registry in this codebase.
     *
     * <p>Flags the renderer as screen-sized, so its owned {@link CgTextRenderContext} tracks
     * the display window's resolution automatically — every {@link CgGraphicsLifecycle#onResize}
     * call resizes it in place, no manual per-frame dimension check needed. This is the default
     * and the right choice for the common case (UI overlays, HUDs) — use {@link #createManualSized()}
     * only when the context should NOT follow the real display window.</p>
     */
    /**
     * Compiles {@link #TEXT_MATERIAL}'s shader variants ahead of the first draw.
     *
     * <p>Material variants compile lazily on first {@code bind()} with a given keyword set, so
     * without this the first string drawn pays for it — measured at ~134 ms on a frame that had
     * already started rendering. Called from {@code CgGraphicsLifecycle.initContext}, where a stall
     * costs nothing.
     *
     * <p>Compiles <strong>both</strong> keyword states. {@code MSDF_MODE} on and off are separate
     * cached programs, and text routinely uses both in one frame — bitmap fallback while an MSDF
     * glyph is still generating. Warming only one would leave the other to compile mid-frame and
     * defeat the point.
     *
     * <p>GL thread only, and only meaningful with a live context.
     */
    public static void warmUpMaterial() {
        for (boolean msdf : new boolean[]{false, true}) {
            TEXT_MATERIAL.toggleKeyword("MSDF_MODE", msdf);
            TEXT_MATERIAL.bind();
            TEXT_MATERIAL.unbind();
        }
        TEXT_MATERIAL.toggleKeyword("MSDF_MODE", false);
    }

    public static CgTextRenderer create() {
        CgTextRenderer renderer = new CgTextRenderer();
        CgTextRendererRegistry.get().register(renderer);
        renderer.screenSized = true;
        return renderer;
    }

    /**
     * Creates the renderer façade like {@link #create()}, but does NOT flag it as screen-sized —
     * its owned {@link CgTextRenderContext} is never auto-resized by {@link CgGraphicsLifecycle#onResize}
     * and must be sized manually by the caller.
     *
     * <p>Use this for renderers sized to something other than the real display window — an
     * offscreen FBO capture, an atlas dump, a fixed test viewport — where auto-tracking window
     * resize would silently desync the projection from the actual target size.</p>
     */
    public static CgTextRenderer createManualSized() {
        CgTextRenderer renderer = create();
        renderer.screenSized = false;
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

        projectionValid = false;
        // Between batches the material binding was torn down and a restore hook may have run, so
        // whatever was recorded about the shared material can no longer be trusted.
        activeBatchBits = NO_ACTIVE_BATCH;
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
        projectionValid = false;
        batchActive = false;

        if (postBatchRestore != null) postBatchRestore.run();
    }


    /**
     * Flushes whatever is currently staged under {@link #textMaterial}, as {@link #transitionToMaterial} last set it,
     * with {@link #textBlock} as it is now. No-op if nothing is staged.
     *
     * <p>Text draws under {@code text.shader}'s declared depth (test and write). A per-context override applied
     * after the bind never reached a draw — measured on {@code text-3d}, render-graph G2 — and is gone; a pass that
     * owns its depth decides it from G3 on.</p>
     */
    private void flush() {
        if (!quadRenderer.isDirty()) return;

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "gl.flush")) {
            CgTrace.add(CgChannels.GL, "gl.flush.count", 1);
            quadRenderer.useMaterial(textMaterial);

            quadRenderer.flush();
        }

        // No need to unbind as they are wasteful — useMaterial() unbinds-old on the next call.
    }

    /**
     * Writes {@code projection} into {@link #textBlock} for the glyphs about to be submitted, first flushing quads this
     * renderer queued under another one: a chunk keeps the block as it is when the chunk ends, so a context switch
     * mid-batch would otherwise re-project glyphs already queued. One small write per {@code draw()}, not per glyph.
     */
    private void syncProjection(Matrix4f projection) {
        if (projectionValid) {
            if (activeProjection.equals(projection)) return;
            flush();
        }
        // KEPT, and marked invalid rather than dropped: the resets run per batch, and a fresh matrix each time
        // was a steady allocation per text draw.
        activeProjection.set(projection);
        projectionValid = true;

        CgTextGamma.Level small = gamma.small(), large = gamma.large();
        textBlock.reset().beginRecord().mat4("u_Projection", projection)
                .vec4("u_TextGammaSmall", small.exponent(), small.contrast(), 1f / small.exponent(), 0f)
                .vec4("u_TextGammaLarge", large.exponent(), large.contrast(), 1f / large.exponent(), 0f)
                .vec4("u_TextGammaRamp", gamma.smallPx(), gamma.largePx(), gamma.isIdentity() ? 0f : 1f, 0f);
    }

    /**
     * Transitions {@link #textMaterial} to the given batch state, flushing whatever was
     * pending under the previous state first. Callers (just {@link #submitBatchedQuads}) are
     * responsible for only calling this when the state actually changed — this method always
     * flushes and applies unconditionally, it does not re-check for a no-op transition itself.
     *
     * <p>Records {@code batchBits} into {@link #activeBatchBits} as the last step, so the record of
     * what the material holds is updated in the same place the material is.
     *
     * <p>Every transition explicitly sets {@code MSDF_MODE} — one keyword covers both
     * distance-field atlas types (MSDF and MTSDF), since the fragment logic is identical for
     * both today (see {@code text.shader}). It is always an explicit enable-or-disable, never a bare
     * {@code enableKeyword()}: a keyword a previous transition left on would otherwise persist into the next
     * batch's variant.</p>
     */
    private void transitionToMaterial(long batchBits, boolean isDistanceField, int atlasId) {
        flush();

        // Counted to expose batch fragmentation: each transition is a flush + keyword toggle +
        // property re-apply. A warmup frame mixing bitmap-fallback and MSDF glyphs across many
        // atlas pages can produce far more of these than a settled frame.
        CgTrace.add(CgChannels.TEXT, "draw.materialTransition", 1);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "draw.materialTransition")) {
            textMaterial.toggleKeyword("MSDF_MODE", isDistanceField);
            // The atlas's texture itself, not its id: the texture is made on the render thread before this draws.
            atlasTexture.pointAt(CgGlyphAtlas.texture(atlasId));
            activeBatchBits = batchBits;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  FLUENT DRAW REQUEST
    // ══════════════════════════════════════════════════════════════════════════════════════════
    
    /** What {@code quad.glsl} reads for a {@link CgStrokeAlign}: on the contour, outside it, inside it. */
    static final float ALIGN_CENTER = 0f, ALIGN_OUTSET = 1f, ALIGN_INSET = 2f;
    
    /**
     * Whether the most recent draw resolved any glyph below the tier it requested.
     *
     * <p>True when a distance-field draw had to take bitmap glyphs because generation was refused by
     * the per-frame budget. The picture is then provisional, and a caller that only paints on damage
     * must ask for another frame or keep the degraded one indefinitely — which for a stroked draw
     * means losing the stroke entirely, since a bitmap glyph has no field for the shader to read.</p>
     *
     * <pre>{@code
     * long before = renderer.degradedDrawCount();
     * ...submit();
     * if (renderer.degradedDrawCount() != before) repaint();   // ask again next frame
     * }</pre>
     *
     * <p><b>A COUNT rather than a flag about the last draw</b>, so a caller brackets whatever it
     * likes — one draw, a widget's whole paint, a frame — instead of having to read it between a
     * submit and the next one. It only ever rises.</p>
     */
    @Getter
    private long degradedDrawCount;

    private final Draw scratchDraw = new Draw(null);

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
        return new Draw(null);
    }

    /**
     * A retained draw whose {@link Draw#submit()} hands it to {@code queue} instead of drawing: for an owner that
     * draws later, under a pose it only knows then, with {@link #drawQueued}. {@code CgWorldRenderer}'s labels are
     * these: the caller fills every field a draw has, and the world draws it under each firing's camera.
     *
     * <pre>{@code
     * Draw label = renderer.queuedDraw(pending::add);
     * label.text("Spawn").font(font).stroke(0.1f, 0xFF000000).submit();   // into pending
     * // later, at record time:
     * for (Draw d : pending) renderer.drawQueued(d.pose(pose));
     * }</pre>
     */
    public Draw queuedDraw(Consumer<Draw> queue) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        return new Draw(queue);
    }

    /** Draws a {@link #queuedDraw} now, as {@link Draw#submit()} would have; inside a batch if one is open. */
    public CgTextRenderer drawQueued(Draw draw) {
        return draw.drawNow();
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
     * {@code layout}/{@code text} has been set. {@code family}/{@code font} are also required
     * <em>except</em> when {@code layout} was set to a non-empty {@link CgTextLayout} — its own
     * {@link CgBakedGlyphs} already carries a {@code CgFontKey} per glyph, so a representative
     * one is derived straight from that instead of forcing a redundant {@code font(...)}/
     * {@code family(...)} call. {@link #pose(PoseStack)} is optional: an unset pose falls back to the owning
     * {@link CgTextRenderer}'s {@link CgTextRenderer#poseStack(PoseStack)}, and finally to a
     * shared identity pose if neither was ever set — so callers with no real transform (plain
     * screen-space text) can skip {@code pose(...)} entirely.
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
        // The stroke, already in the shape the quad wants: em (the pose decides the pixels) and the
        // align/over codes quad.glsl reads, mapped once in the setter that takes the enum.
        private float strokeWidthEm;
        private int strokeArgb;
        private float strokeAlign = ALIGN_OUTSET;
        private float strokeOver;
        private final CgTextShadowList shadows = new CgTextShadowList();
        @Nullable
        private final Consumer<Draw> queue;

        private Draw(@Nullable Consumer<Draw> queue) {
            this.queue = queue;
        }

        /** Every field back to its default: for a retained draw reused for something else. */
        public Draw reset() {
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
            strokeWidthEm = 0f;
            strokeArgb = 0;
            strokeAlign = ALIGN_OUTSET;
            strokeOver = 0f;
            shadows.clear();
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

        /** The text's colour, {@code 0xAARRGGBB}; a styled span keeps its own. Defaults to opaque white. */
        public Draw color(int argb) {
            this.rgba = argb;
            return this;
        }

        /**
         * The current PoseStack providing model-view transform. Optional — if omitted,
         * {@link #submit()}/{@link #measure()} fall back to the owning {@link CgTextRenderer}'s
         * {@link CgTextRenderer#poseStack(PoseStack)} if one is set, and finally to a shared
         * identity pose (no transform) if neither was ever set.
         */
        public Draw pose(PoseStack pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Outlines this draw's glyphs, {@code widthEm} wide in {@code argb}. A width of 0 or a
         * transparent colour — the default — draws none.
         *
         * <pre>{@code
         * .stroke(0.06f, 0xFF101418)                                // 6% of em, outside the contour
         * .stroke(0.06f, argb).strokeAlign(CgStrokeAlign.CENTER)    // webkit's behaviour
         * .stroke(0.06f, argb).strokeOverFill(true)                 // painted on top of the fill
         * }</pre>
         *
         * <p><b>Width is in em</b>: the same draw is rasterised at whatever size the pose resolves
         * to, so pixels would mean a different fraction of the letterform each frame. The stored
         * distance field bounds it at {@link CgTextStroke#MAX_FIELD_WIDTH_EM}, and asking for more
         * clamps rather than throwing — that type carries the full account of the bound.</p>
         *
         * <p><b>A stroke pulls the draw onto the distance-field tier.</b> A bitmap glyph carries
         * coverage rather than distance, so there is nothing to offset a second threshold from and an
         * outline is not degraded there but absent. The size thresholds decide which tier draws a
         * FILL better and at 20px they answer bitmap, which stays the right answer for the fill and
         * the wrong one for the label.</p>
         *
         * <p>It stops at {@code CgMsdfAtlasConfig.minAntialiasablePx()}, which is per BAND — 7px for
         * a face banded wide, 15px for one carrying a dense script — because below there the field
         * cannot resolve its own edge, which is msdfgen's
         * rule rather than a preference. Under that size the stroke is dropped and the glyph keeps the
         * bitmap tier: there is no outline worth the fill it would cost.</p>
         */
        public Draw stroke(float widthEm, int argb) {
            this.strokeWidthEm = widthEm;
            this.strokeArgb = argb;
            return this;
        }

        /** The outline's width alone, in em. @see #stroke(float, int) */
        public Draw strokeWidth(float widthEm) {
            this.strokeWidthEm = widthEm;
            return this;
        }

        /** The outline's colour alone; alpha 0 draws nothing. @see #stroke(float, int) */
        public Draw strokeColor(int argb) {
            this.strokeArgb = argb;
            return this;
        }

        /** Where the outline sits relative to the contour. {@link CgStrokeAlign#OUTSET} by default. */
        public Draw strokeAlign(CgStrokeAlign align) {
            this.strokeAlign = align == CgStrokeAlign.CENTER ? ALIGN_CENTER
                    : align == CgStrokeAlign.INSET ? ALIGN_INSET : ALIGN_OUTSET;
            return this;
        }

        /** {@code true} paints the outline over the fill; {@code false}, the default, behind it. */
        public Draw strokeOverFill(boolean strokeOverFill) {
            this.strokeOver = strokeOverFill ? 1f : 0f;
            return this;
        }

        /**
         * The same four values from a {@link CgTextStroke}, for a caller that already holds one.
         *
         * <p><b>Not the main path</b> — the setters above take the values themselves, so a painter
         * resolving a stroke every frame builds no record to carry them.</p>
         */
        public Draw stroke(CgTextStroke stroke) {
            CgTextStroke s = stroke == null ? CgTextStroke.NONE : stroke;
            return stroke(s.widthEm(), s.argb())
                    .strokeAlign(s.align())
                    .strokeOverFill(s.strokeOverFill());
        }

        /**
         * How many text shadows this draw casts; set each with {@link #shadow}. {@code 0}, the default,
         * casts none.
         *
         * <pre>{@code
         * draw.shadowCount(2)
         *     .shadow(0, 0f, 0f, 4f, 0f, 0xFF44CCFF, false)   // a glow, painted on top
         *     .shadow(1, 1f, 1f, 0f, 0f, 0x80000000, false);  // a sharp drop shadow beneath it
         * }</pre>
         *
         * <p>Painted as Chrome paints {@code text-shadow}: every glyph casts its own shadow, blurred in
         * local space so it scales and skews with the pose. The first shadow is painted on top and every
         * shadow beneath the text, whichever atlas each lives in.</p>
         *
         * <p>Growing the count keeps the shadows already set, so a second source of shadows can append:
         * {@code draw.shadowCount(draw.shadowCount() + extra)}.</p>
         */
        public Draw shadowCount(int count) {
            shadows.count(count);
            return this;
        }

        /** How many shadows this draw casts. */
        public int shadowCount() {
            return shadows.count();
        }

        /**
         * Scopes glyphs for {@link #shadowScope}: {@code scopeByGlyph[i]} is the scope of the layout's glyph
         * {@code i}, in {@code CgBakedGlyphs} order. The array is read at submit, not copied.
         *
         * <pre>{@code
         * // The highlighted word's glyphs are scope 0, and its ::highlight shadow applies to them alone.
         * draw.shadowScopes(scopes).shadowCount(2)
         *     .shadow(0, 1f, 1f, 1f, 0f, black, false)                         // the element's, every glyph
         *     .shadow(1, 0f, 0f, 3f, 0f, gold, false).shadowScope(1, 0);        // the highlight's
         * }</pre>
         *
         * <p>A scoped shadow casts nothing for decorations, which carry no glyph index.</p>
         */
        public Draw shadowScopes(int[] scopeByGlyph) {
            shadows.glyphScopes(scopeByGlyph);
            return this;
        }

        /** Restricts shadow {@code index} to the glyphs whose scope is {@code scope}; -1 is every glyph. */
        public Draw shadowScope(int index, int scope) {
            shadows.scope(index, scope);
            return this;
        }

        /**
         * Shadow {@code index} of this draw.
         *
         * <ul>
         *   <li>{@code offsetX}, {@code offsetY}: local pixels, positive right and down.</li>
         *   <li>{@code sigma}: the Gaussian's standard deviation in local pixels. A CSS blur radius is twice
         *       it. Negative reads as 0.</li>
         *   <li>{@code spread}: grows the glyph outline by a true distance before blurring, so corners
         *       round. Negative reads as 0, as CSS Text Decoration 4 forbids it for text.</li>
         *   <li>{@code argb}: straight ARGB, already resolved; its alpha is the shadow's whole opacity,
         *       whatever the text's own colour is. Fully transparent casts nothing.</li>
         *   <li>{@code inset}: shadows the canvas into the glyph, over the text, inside the stroke.</li>
         * </ul>
         *
         * <p>A stroke set on this draw is part of the shadow's shape. A shadow a worker has not built yet
         * is left out of this frame and counted in {@link CgTextRenderer#getDegradedDrawCount()}.</p>
         */
        public Draw shadow(int index, float offsetX, float offsetY, float sigma, float spread, int argb,
                           boolean inset) {
            shadows.set(index, offsetX, offsetY, sigma, spread, argb, inset);
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
         * @throws IllegalStateException if neither {@code layout} nor {@code text} was set, or
         *                                neither {@code family} nor {@code font} was set and
         *                                {@code layout} (if set) has no glyphs to derive one from
         */
        public CgTextRenderer submit() {
            if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
            if (layout == null && paragraph == null && text == null) throw new IllegalStateException("CgTextRenderer.Draw requires text(...), paragraph(...), or layout(...) before submit()");
            if (layout == null && family == null && font == null) throw new IllegalStateException(
                    "CgTextRenderer.Draw requires font(...) or family(...) before submit()");
            if (queue != null) {
                queue.accept(this);
                return CgTextRenderer.this;
            }
            return drawNow();
        }

        private CgTextRenderer drawNow() {
            if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
            boolean standalone = !batchActive;
            if (standalone) beginBatch();
            try {
                drawInternal(this, effectivePose().last());
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
            if (layout == null && family == null && font == null) throw new IllegalStateException(
                    "CgTextRenderer.Draw requires font(...) or family(...) before measure()");

            return resolveDraw(this, effectivePose().last()).layout();
        }

        /**
         * The size its text is laid out at, in the layout's units: {@code targetPx} where set, else its family's or
         * font's, else its layout's first glyph's; 0 with none of them. What a pose scales to place the text.
         */
        public int basePx() {
            if (targetPx > 0 && (family != null || font != null)) return targetPx;
            if (family != null) return family.getPrimarySource().getKey().getTargetPx();
            if (font != null) return font.getTargetPx();
            if (layout != null && layout.baked().fontKeys().length > 0) return layout.baked().fontKeys()[0].getTargetPx();
            return 0;
        }

        /**
         * Resolves the {@link PoseStack} this draw should use: an explicit {@link #pose(PoseStack)}
         * wins, then the owning renderer's fallback {@link CgTextRenderer#poseStack(PoseStack)},
         * then a shared identity {@link PoseStack} — so callers that genuinely don't need a
         * transform (screen-space HUD text with no camera/zoom involved) can omit {@code pose(...)}
         * entirely instead of being forced to construct a throwaway identity stack themselves.
         */
        private PoseStack effectivePose() {
            if (pose != null) return pose;
            if (CgTextRenderer.this.poseStack != null) return CgTextRenderer.this.poseStack;
            return IDENTITY_POSE_STACK;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    public void delete() {
        if (deleted) return;
        // AN OPEN BATCH IS ABANDONED, NOT ENDED. endBatch() flushes and then runs postBatchRestore, and
        // both BIND A MATERIAL -- this renderer's for the flush, the caller's own for the restore. During
        // CgGraphicsLifecycle.destroyContext both are already gone, because materials are swept before
        // this registry is. Deleting a renderer is not a request to draw with it, and a caller being
        // torn down has no state worth restoring; doing either threw "CgMaterial has been deleted" out
        // of teardown on every harness run that had text on screen.
        if (batchActive) {
            quadRenderer.end();
            projectionValid = false;
            batchActive = false;
        }
        quadRenderer.delete();
        textMaterial.delete();
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
     * ({@link #submitBatchedQuads}).</p>
     */
    /**
     * Holds everything {@link #drawInternal} needs beyond {@code CgTextLayout} resolution
     * itself (font key, resolved raster tier) alongside the resolved layout — shared by
     * {@link #drawInternal} and {@link Draw#measure()} so measuring a paragraph's on-screen
     * size never drifts from what actually gets drawn.
     */
    private record ResolvedDraw(CgFontKey fontKey, int effectiveTargetPx, boolean wantMsdf, CgTextLayout layout) {
    }

    /** A width and a colour that would draw something. The tier decision needs it before the stroke is resolved. */
    private static boolean strokeRequested(Draw draw) {
        return draw.strokeWidthEm > 0f && (draw.strokeArgb >>> 24) != 0;
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
        CgFontFamily resolvedFamily = null; // stays null on the layout-derived branch below --
        // draw.layout is honored verbatim, so nothing ever
        // needs a CgFontFamily to (re)build it
        CgFont resolvedFont = null; // only populated (and only needed) on the family==null branch
        CgFontKey fontKey;

        if (draw.family != null) {
            resolvedFamily = draw.targetPx > 0 ? sizeFamily(draw.family, draw.targetPx) : draw.family;
            fontKey = resolvedFamily.getPrimarySource().getKey();
        } else if (draw.font != null) {
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
            fontKey = resolvedFamily.getPrimarySource().getKey();
        } else if (draw.layout != null) {
            // No font(...)/family(...) given -- a prebuilt CgTextLayout already carries its own
            // per-glyph CgFontKey (CgBakedGlyphs.fontKeys()), so redundantly requiring the caller
            // to pass a font again just to identify "the" font for raster-tier history is
            // unnecessary. Use the first glyph's font key as the representative one.
            CgFontKey[] bakedKeys = draw.layout.baked().fontKeys();
            if (bakedKeys.length == 0) {
                throw new IllegalStateException(
                        "CgTextRenderer.Draw requires font(...) or family(...) when layout(...) has no glyphs to derive a font from");
            }
            fontKey = bakedKeys[0];
        } else {
            throw new IllegalStateException(
                    "CgTextRenderer.Draw requires font(...) or family(...) before submit()/measure()");
        }

        CgTextRenderContext.RasterHistory previous = context.getHistory(fontKey);
        int previousEffectiveTargetPx = previous != null ? previous.effectiveTargetPx() : -1;
        int effectiveTargetPx = context.getScaleResolver().resolveEffectiveTargetPx(fontKey.getTargetPx(), pose, previousEffectiveTargetPx);

        boolean previousMsdf = previous != null ? previous.wasMsdf() : effectiveTargetPx >= 32;
        boolean sizeWantsMsdf = context.getScaleResolver().shouldUseMsdf(effectiveTargetPx, previousMsdf);

        // Record the *size-derived* decision, not the transform-forced one below. shouldUseMsdf
        // applies hysteresis against this history, and that hysteresis is meant to damp size
        // changes only -- feeding a forced value back into it would let one rotated draw pin a
        // later unrotated draw of the same font to MSDF while it sits in the band.
        context.setHistory(fontKey, effectiveTargetPx, sizeWantsMsdf);

        // A rotated or sheared transform forces the distance-field tier at any size. Bitmap
        // glyphs are pre-rasterized at one orientation and only survive resampling while the
        // sampling grid stays parallel to the raster grid; off-axis, they go soft and
        // stair-stepped. Quarter-turns and flips stay on the bitmap tier -- they are texel-exact.
        // See OrthographicScaleResolver#isAxisAligned.
        //
        // Short-circuited on sizeWantsMsdf so the matrix inspection is skipped entirely for the
        // draws that were already going to be MSDF anyway -- which includes all world-space text,
        // since PerspectiveScaleResolver#shouldUseMsdf is unconditionally true.
        boolean wantMsdf = sizeWantsMsdf;
        if (!wantMsdf && !OrthographicScaleResolver.isAxisAligned(pose.pose())) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByTransform", 1);
        }

        // A VISIBLE STROKE FORCES IT TOO, for the same kind of reason: a bitmap glyph is coverage with
        // no distance to offset a second threshold from, so on that tier an outline is not degraded --
        // it is absent. The size thresholds are about which tier draws a FILL better, and at 20px they
        // answer bitmap; that answer is still right for the fill and wrong for the label as a whole,
        // because the alternative on offer is no outline at all.
        //
        // It stops where the field stops being able to resolve its own edge, which is msdfgen's own
        // rule rather than a taste: @see CgMsdfAtlasConfig#minAntialiasablePx. Below that a promoted
        // draw would trade a crisp unstroked label for a soft stroked one, which is the trade this
        // deliberately refused when the tier was first written -- the correction is that the refusal
        // was applied at 33px, where the field antialiases perfectly well, rather than at 15.
        if (!wantMsdf && strokeRequested(draw)
                && effectiveTargetPx >= registry.getResolvedMsdfConfig(fontKey).minAntialiasablePx()) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByStroke", 1);
        }

        CgTextLayout resolvedLayout;
        if (draw.layout != null) {
            // Prebuilt, immutable layout -- honored verbatim, no reflow. See this class's
            // "Three-Space Model" javadoc: logical layout space never changes based on
            // draw-time transforms, by design, for callers who explicitly opted into a
            // frozen CgTextLayout.
            resolvedLayout = draw.layout;
        } else {
            // draw.maxWidth/maxHeight are expressed in the same design-space pixels as
            // fontKey's base size. Divide by the PoseStack's true scale so maxWidth keeps
            // meaning "this many on-screen pixels" regardless of the live UI zoom -- see
            // Draw's class javadoc. Never applied to world-space text: world paragraphs must
            // not reflow as the camera moves (see PerspectiveScaleResolver's "Layout
            // Invariance").
            //
            // Deliberately NOT effectiveTargetPx/baseTargetPx here (what raster-tier crispness
            // uses) -- effectiveTargetPx is clamped to MAX_EFFECTIVE_PX (256) to cap atlas cell
            // size at extreme zoom, so that ratio silently under-reports the true scale once a
            // glyph's raw target size exceeds the clamp (e.g. a 22px font at 20x zoom wants a
            // 440px raster, clamped to 256 -- the ratio then implies only ~11.6x, not 20x). The
            // wrap width would then divide by the wrong, smaller scale and let more text fit
            // per line than the PoseStack's real (unclamped) transform actually displays,
            // overflowing past maxWidth on screen. extractMaxScale reads the PoseStack directly
            // and is never clamped, so it stays correct past the raster clamp.
            float scale = context.isWorldText() ? 1f : OrthographicScaleResolver.extractMaxScale(pose.pose());
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
        boolean wantMsdf =  resolved.wantMsdf();
        CgTextLayout resolvedLayout = resolved.layout();

        if (resolvedLayout == null || resolvedLayout.lines().isEmpty()) return;

        // Before resolveGlyphs, not inside the quad loop: an off-screen layout otherwise pays for
        // full glyph resolution and quad building before anything notices. See CgTextCuller.
        if (culler.isCulled(resolvedLayout, draw.x, draw.y, context.projection(), pose.pose(), draw.shadows.reach())) {
            CgTrace.add(CgChannels.TEXT, "text.drawsCulled", 1);
            return;
        }

        long frame = CgGraphicsLifecycle.getCurrentFrame();
        int glyphCount;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "glyph.resolveGlyphs")) {
            // The pose's own sub-pixel phase. A CSS transform moves text through the POSE, not
            // through draw.x/draw.y, so without this the placement cache returns the same glyphs
            // for every sub-pixel position of a translated element and the offset never lands.
            scratchPosePhase.set(draw.x, draw.y, 0f);
            pose.pose().transformPosition(scratchPosePhase);
            // Under the registry's lock, so a glyph a worker commits cannot land halfway through one resolve.
            synchronized (registry) {
                glyphCount = resolvedGlyphs.resolve(resolvedLayout, draw.x, draw.y, frame, context,
                        effectiveTargetPx, wantMsdf, fontKey, draw.rgba, scratchPosePhase.x);
            }
        }
        CgTrace.counter(CgChannels.TEXT, "draw.glyphCount", glyphCount);

        // DID THIS DRAW GET THE TIER IT ASKED FOR? Glyph generation is budgeted per frame, and a
        // glyph refused by the budget falls back to bitmap FOR THE FRAME -- deliberately, so a large
        // paragraph costs a few frames at lower fidelity instead of a hitch. That trade assumes
        // somebody paints again. A retained, damage-driven UI does not: nothing about the tree
        // changed, so the degraded picture is kept forever, and since a bitmap glyph carries no
        // distance field, any stroke on it silently disappears with it.
        //
        // So the caller is told, and can ask for another frame. Self-limiting by construction: the
        // count stops rising the moment draws get what they want. @see #degradedDrawCount
        for (int i = 0; i < glyphCount; i++) {
            CgGlyphPlacement placement = resolvedGlyphs.placements[i];
            // NO PLACEMENT AT ALL is the most degraded answer there is, not a neutral one: the glyph
            // is not in the atlas yet and nothing was drawn for it. Counting only the LOWER-TIER case
            // left the window right after a font change -- where nothing has resolved at any tier --
            // reporting a healthy draw, so nobody asked again and the label stayed blank.
            if (placement == null) {
                degradedDrawCount++;
                break;
            }
            if (wantMsdf && placement.hasGeometry() && !placement.isDistanceField()) {
                degradedDrawCount++;
                break;
            }
        }

        // A stroke the tier cannot express resolves to none here rather than at the call site, so
        // a caller may set one unconditionally and let the glyph tier decide -- which is what a CSS
        // cascade does, having no idea which tier it will land on.
        //
        // It rides on the QUADS from here, not on the material: it used to be uploaded as material
        // properties, which every draw with a different stroke then had to flush and re-apply for.
        boolean stroked = wantMsdf && strokeRequested(draw);
        // TEXELS, not screen pixels: a fraction of the em in the GLYPH's own space, which the
        // transform then stretches like everything else. That is what CSS does -- a stroke is painted
        // in local space and transformed with the element -- and it is the only form an anisotropic
        // transform can carry, since converting a screen-pixel width needs ONE scale factor and
        // scale(2,1) does not have one.
        //
        // Algebraically identical to the screen-space form this replaces wherever that one worked:
        // it sent width * targetPx * scale, and the shader divided by a screenPxRange of
        // pxRange * targetPx * scale / atlasScalePx, so the scale cancelled and left exactly this.
        // Zoom and world text are unaffected for the same reason, which is why neither needs a case
        // here any more.
        float strokeWidthTexels = stroked
                ? draw.strokeWidthEm * registry.getResolvedMsdfConfig(fontKey).atlasScalePx()
                : 0f;

        if (draw.shadows.count() > 0 && glyphCount > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.planShadows")) {
                boolean degraded;
                synchronized (registry) {
                    degraded = shadowPlan.plan(draw.shadows, resolvedGlyphs.placements, resolvedGlyphs.cached,
                            resolvedLayout.baked(), glyphCount, fontKey, effectiveTargetPx, strokeWidthTexels,
                            draw.strokeAlign, context.isWorldText(), frame);
                }
                if (degraded) degradedDrawCount++;
            }
        }

        CgTextDecorationRect[] decorations = resolvedLayout.baked().decorations();
        if (glyphCount > 0 || decorations.length > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.submitSortedQuads")) {
                submitBatchedQuads(glyphCount, decorations, fontKey.getTargetPx(), effectiveTargetPx, wantMsdf,
                        draw, pose.pose(), stroked ? draw.strokeArgb : 0,
                        strokeWidthTexels, draw.strokeAlign, draw.strokeOver);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  TEXT SHADOWS: planned by CgTextShadowPlan, emitted by submitSorted
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** text.shader's instance kinds, carried as a negative custom0.w. @see text.shader Properties */
    private static final float KIND_FIELD_OUTER = -1f, KIND_FIELD_INSET = -2f, KIND_CELL = -3f,
            KIND_RECT_OUTER = -4f, KIND_RECT_INSET = -5f;

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  BATCH + SUBMIT
    // ══════════════════════════════════════════════════════════════════════════════════════════

    // Sort-key packing lives in CgTextSortKey: the bit layout, the width arithmetic, and the
    // batch-identity mask are one cohesive concern with invariants worth stating once and
    // enforcing (the widths are checked to sum to 64, and out-of-range indices throw rather than
    // aliasing two entries onto one key). See that class for why bit 63 stays clear and why
    // `kind` sorts below the batch fields rather than above them.


    /**
     * Turns one draw call's glyphs and decorations into the <strong>fewest possible GL batches</strong>,
     * then submits them in that order.
     *
     * <p>Every change of atlas texture or shader mode costs a flush plus a material rebind, so the
     * cost of a draw is set by how many times that state changes, not by how many quads it emits.
     * This method's whole job is to make that number as small as it can be: it groups everything
     * that can share a state into contiguous runs, so a draw performs exactly as many transitions
     * as it has genuinely distinct states — and never one more because two entries that could have
     * shared a batch happened to arrive in the wrong order.
     *
     * <p>Since the glyph atlases were merged into one per texture format, "distinct state" no
     * longer means "distinct font": two fonts now report the same texture id and batch together.
     * A mixed-font run costs the same as a single-font one.
     *
     * <h4>What happens, and how often</h4>
     * <ol>
     *   <li><b>Once per call</b> — count visible glyphs, resolve decoration rects, build a
     *       {@link CgTextSortKey} per entry, and sort. The key is laid out so a single numeric
     *       sort produces batch order directly; see that class for the bit layout.</li>
     *   <li><b>Once per call</b> — write the projection into {@link #textBlock} via
     *       {@link #syncProjection}, flushing first if this renderer has quads queued under a
     *       different one. The model-view transform needs no equivalent check: it is baked
     *       per-instance rather than held as shared state.</li>
     *   <li><b>Once per batch boundary</b> — {@link #transitionToMaterial} flushes the quads
     *       accumulated under the previous state and adopts the new one.</li>
     *   <li><b>Once per entry</b> — emit one instanced-quad record via
     *       {@link CgQuadRenderer#quad()}, with the transform baked in through
     *       {@code Quad.pose(modelView)}.</li>
     * </ol>
     *
     * <h4>Glyphs and decorations share the ordering, not the data</h4>
     * <p>They remain two separate sources for their entire lifetime — a {@code CgGlyphPlacement[]}
     * and a {@code List<CgResolvedGlyphs.ResolvedDecoration>}, resolved by two separate methods on
     * {@link CgResolvedGlyphs}. The only thing they share is the sort key's layout and this one
     * pass.
     *
     * <p>That sharing is what makes decorations free: a decoration's batch bits come from the same
     * atlas its font's glyphs use (see {@link CgResolvedGlyphs.ResolvedDecoration}), so an underline
     * drawn alongside text lands in the same sorted run as that text and adds
     * <strong>zero</strong> transitions. A transition happens only where the state genuinely
     * changes — a different atlas, or bitmap versus distance field — never because an entry
     * happened to be a decoration rather than a glyph.
     *
     * <h4>Sorting</h4>
     * <p>{@link Arrays#sort(long[], int, int)} — the JDK's primitive dual-pivot quicksort, which
     * already detects nearly-sorted runs and drops to insertion sort for small ranges — does the
     * whole sort with no per-entry allocation and no hand-rolled fast path. Note it sorts
     * <em>signed</em>, which is why {@link CgTextSortKey} keeps bit 63 clear.
     *
     * <h4>Shadows sort with the text, one paint step each</h4>
     * <p>Every shadow's instances are keyed at their own stage, ahead of the batch fields, so painter's
     * order holds across atlases while a shadow and its text in one atlas stay one run. The kinds each
     * glyph paints in each shadow were decided by {@link CgTextShadowPlan}.</p>
     *
     * @param glyphCount        number of entries in {@link CgResolvedGlyphs#placements} to consider;
     *                          null or geometry-less placements are skipped
     * @param decorations       baked decoration rects for this draw, resolved here rather than by
     *                          the caller
     * @param baseTargetPx      the font's declared size, the denominator for metric normalisation
     * @param effectiveTargetPx the size glyphs were actually rasterised at this frame
     * @param wantMsdf          whether this draw prefers distance-field glyphs
     * @param draw              the request, read for its position, colour and shadows
     * @param modelView         transform baked into each emitted quad
     */
    private void submitBatchedQuads(int glyphCount, CgTextDecorationRect[] decorations,
                                    int baseTargetPx, int effectiveTargetPx, boolean wantMsdf,
                                    Draw draw, Matrix4f modelView, int strokeArgb, float strokeWidthTexels,
                                    float strokeAlign, float strokeOver) {
        CgGlyphPlacement[] placements = resolvedGlyphs.placements;
        CgTextShadowList shadowList = draw.shadows;
        int shadows = shadowList.count();

        List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "glyph.resolveDecorations")) {
            synchronized (registry) {
                resolvedDecorations = resolvedGlyphs.resolveDecorations(decorations, draw.x, draw.y, draw.rgba,
                        effectiveTargetPx, wantMsdf);
            }
        }

        // An upper bound: the text, then every shadow's glyphs and decorations again.
        int perStage = glyphCount + resolvedDecorations.size();
        if (perStage == 0) return;
        ensureSortScratchCapacity(perStage * (1 + shadows));

        // Projection is constant for this whole draw() call. Flushes first if it differs from
        // what's already queued under a different projection — see syncProjection().
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.syncProjection")) {
            syncProjection(context.projection());
        }

        // Pixel-snap is an ORTHO-ONLY correction: pixelSnapDelta floors the modelView-transformed
        // position, which is only meaningful when modelView maps into screen-pixel space. For
        // world text it maps into world units (a whole text block often spans < 1 unit), so
        // flooring collapses every glyph onto the same integer world coordinate -- and the
        // inverse-transform back to local space then multiplies that error by 1/worldScale.
        boolean pixelSnap = !context.isWorldText();
        if (pixelSnap) scratchInverseModelView.set(modelView).invert();
        float devicePerLocal = effectiveTargetPx / (float) Math.max(1, baseTargetPx);

        // PAINT ORDER: outer shadows at 0..n-1 with the LAST shadow first, the underline and overline at n, the
        // text at n+1, its line-through at n+2, inset shadows at n+3..2n+2. It is the key's outermost field, so
        // one sort gives painter's order across batches, and a list longer than the field holds is submitted as
        // successive sorts in the same order. The lines split around the text as CSS Text Decoration 3 paints
        // them: under the glyphs and their stroke, and a line-through over both.
        //
        // EACH ITS OWN STEP, never "a decoration after a glyph in the same step": the key sorts by atlas before
        // kind, and a decoration's white texel is often in the bitmap atlas while the glyphs are distance
        // fields, so within one step every line painted under the text.
        int textPaint = shadows + 1;
        int lastPaint = 2 * shadows + 2;
        long emitted = 0;
        for (int window = 0; window <= lastPaint; window += CgTextSortKey.MAX_STAGE + 1) {
            int windowEnd = Math.min(lastPaint, window + CgTextSortKey.MAX_STAGE);
            int count = 0;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.sortKeys")) {
                for (int paint = window; paint <= windowEnd; paint++) {
                    int stage = paint - window;
                    if (paint == shadows) {
                        for (int i = 0; i < resolvedDecorations.size(); i++) {
                            if (resolvedDecorations.get(i).underText()) {
                                scratchSortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                            }
                        }
                        continue;
                    }
                    if (paint == textPaint) {
                        for (int i = 0; i < glyphCount; i++) {
                            CgGlyphPlacement p = placements[i];
                            if (p != null && p.hasGeometry()) scratchSortKeys[count++] = CgTextSortKey.forGlyph(p, i, stage);
                        }
                        continue;
                    }
                    if (paint == textPaint + 1) {
                        for (int i = 0; i < resolvedDecorations.size(); i++) {
                            if (!resolvedDecorations.get(i).underText()) {
                                scratchSortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                            }
                        }
                        continue;
                    }
                    int shadow = shadowAt(paint, shadows);
                    if (shadowList.inset(shadow) != (paint > textPaint + 1) || !shadowList.casts(shadow)) {
                        continue;
                    }
                    for (int i = 0; i < glyphCount; i++) {
                        if (shadowPlan.kind(shadow, i) != CgTextShadowPlan.NONE) {
                            scratchSortKeys[count++] = CgTextSortKey.forGlyph(shadowPlan.placement(shadow, i), i, stage);
                        }
                    }
                    if (shadowList.scoped(shadow)) continue;
                    for (int i = 0; i < resolvedDecorations.size(); i++) {
                        scratchSortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                    }
                }
                Arrays.sort(scratchSortKeys, 0, count);
            }
            emitted += count;
            submitSorted(count, window, shadowList, glyphCount, resolvedDecorations, placements, draw,
                    baseTargetPx, effectiveTargetPx, devicePerLocal, pixelSnap, modelView,
                    strokeArgb, strokeWidthTexels, strokeAlign, strokeOver);
        }

        // A COUNTER, summed over every draw in the frame -- unlike draw.glyphCount, which is a
        // SAMPLE and therefore reports only the last draw's count. Mistaking the sample for a
        // per-frame total makes quadLoop look ~150x more expensive per glyph than it is (45 vs
        // ~6771 in text-3d), which is exactly how a perfectly healthy loop gets "optimised".
        //
        // Note "visible" here means hasGeometry(), NOT on-screen: there is no viewport cull at
        // this level, so a layout positioned off-screen still emits every one of its quads.
        CgTrace.add(CgChannels.TEXT, "draw.quadsEmitted", emitted);
    }

    /** Which shadow a paint step belongs to; see the paint order in {@link #submitBatchedQuads}. */
    /** The shadow a paint step belongs to: outer ones before the text's three steps, inset ones after them. */
    private static int shadowAt(int paint, int shadows) {
        return paint < shadows ? shadows - 1 - paint : 2 * shadows + 2 - paint;
    }

    // Per-entry scratch for submitSorted, so a quad's geometry is written once and read by the submit.
    private float quadX, quadY, quadW, quadH;

    private void submitSorted(int count, int window, CgTextShadowList shadowList, int glyphCount,
                              List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations,
                              CgGlyphPlacement[] placements, Draw draw,
                              int baseTargetPx, int effectiveTargetPx, float devicePerLocal,
                              boolean pixelSnap, Matrix4f modelView,
                              int strokeArgb, float strokeWidthTexels, float strokeAlign, float strokeOver) {
        int shadows = shadowList.count();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.quadLoop")) {
        // Per-iteration timing, off unless -Dcrystalgraphics.text.traceQuadLoop=true.
        //
        // Exists to answer one question that the scope total cannot: when quadLoop occasionally
        // costs 133 ms instead of 1.1 ms for the *same* 8103 quads, 5 flushes and 5 material
        // transitions, is that ONE blocking call inside the loop, or the whole loop running slowly
        // because the thread lost the CPU? Those have opposite fixes, and the aggregate time is
        // identical either way. maxIter answers it directly: ~130 ms in one iteration means a
        // blocking call (a buffer flush waiting on the GPU); ~16 us spread over every iteration
        // means preemption and there is nothing here to fix.
        long traceMaxNanos = 0, traceMaxIndex = -1, traceSlowIters = 0, traceIterStart = 0;
        if (traceQuadLoop) traceIterStart = System.nanoTime();
        for (int s = 0; s < count; s++) {
            long key = scratchSortKeys[s];
            long batchBits = CgTextSortKey.batchOf(key);
            boolean isDecoration = CgTextSortKey.isDecoration(key);
            int localIndex = CgTextSortKey.localIndexOf(key);
            int paint = window + CgTextSortKey.stageOf(key);
            int shadow = paint >= shadows && paint <= shadows + 2 ? -1 : shadowAt(paint, shadows);

            CgGlyphPlacement p = null;
            boolean isDistanceField;
            int atlasId;
            float pxRange;
            float u0, v0, u1, v1;
            int rgba, atlasLayer;
            // The instance's custom slots, as text.shader's Properties comment lays them out.
            float c0x = 0f, c0y = 0f, c0z = 0f, c0w = 0f, c1x = 0f, c1y = 0f, c1z = 0f, c1w = 0f, c2 = 0f;

            if (isDecoration) {
                CgResolvedGlyphs.ResolvedDecoration d = resolvedDecorations.get(localIndex);
                isDistanceField = d.isDistanceField();
                atlasId = d.atlasId();
                pxRange = d.pxRange();
                quadX = d.qx(); quadY = d.qy(); quadW = d.w(); quadH = d.h();
                u0 = d.u0(); v0 = d.v0(); u1 = d.u1(); v1 = d.v1();
                rgba = d.rgba();
                atlasLayer = d.atlasPageIndex();
                if (shadow < 0) {
                    c0x = ((strokeArgb >> 16) & 0xFF) / 255f; c0y = ((strokeArgb >> 8) & 0xFF) / 255f;
                    c0z = (strokeArgb & 0xFF) / 255f; c0w = ((strokeArgb >>> 24) & 0xFF) / 255f;
                    c1x = strokeWidthTexels; c1y = strokeAlign; c1z = strokeOver; c1w = pxRange;
                } else {
                    rgba = shadowList.argb(shadow);
                    float sigma = shadowList.sigma(shadow);
                    boolean blurred = CgTextShadowPlan.blurs(sigma, devicePerLocal);
                    float spread = shadowList.spread(shadow);
                    c1w = pxRange;
                    if (!shadowList.inset(shadow)) {
                        quadX += shadowList.x(shadow) - spread;
                        quadY += shadowList.y(shadow) - spread;
                        quadW += 2f * spread;
                        quadH += 2f * spread;
                        if (blurred) {
                            // Graphite's AnalyticBlurMask::MakeRect, in the quad's unit parameter: the quad
                            // reaches three sigma past the rect, and the shader's rect is the rect inset by
                            // three sigma, so its edge is where the integral's t is 0.
                            float threeSigma = 3f * sigma;
                            float outW = quadW + 2f * threeSigma;
                            float outH = quadH + 2f * threeSigma;
                            c0x = outW / (6f * sigma);
                            c0y = outH / (6f * sigma);
                            c0w = KIND_RECT_OUTER;
                            c1x = 2f * threeSigma / outW;
                            c1y = 2f * threeSigma / outH;
                            c1z = quadW / outW;
                            c1w = quadH / outH;
                            quadX -= threeSigma;
                            quadY -= threeSigma;
                            quadW = outW;
                            quadH = outH;
                        }
                    } else {
                        c0x = shadowList.x(shadow) / quadW;
                        c0y = shadowList.y(shadow) / quadH;
                        c0z = blurred ? quadW / (6f * sigma) : 0f;
                        c0w = KIND_RECT_INSET;
                        c1x = blurred ? quadH / (6f * sigma) : 0f;
                        c1y = spread / quadW;
                        c1z = spread / quadH;
                        c1w = 0f;
                    }
                }
            } else if (shadow < 0) {
                p = placements[localIndex];
                isDistanceField = p.isDistanceField();
                atlasId = p.atlasId();
                pxRange = p.pxRange();
                placeGlyph(p, localIndex, baseTargetPx, effectiveTargetPx, pixelSnap && !p.isDistanceField(),
                        modelView, 0f, 0f);
                u0 = p.u0(); v0 = p.v0(); u1 = p.u1(); v1 = p.v1();
                rgba = resolvedGlyphs.argbColor[localIndex];
                atlasLayer = p.atlasPageIndex();
                // text.shader's own header states the layout: custom0 the colour, custom1
                // (widthPx, align, over, pxRange). The STROKE fields are glyphs only -- a
                // decoration is a solid rect with no distance field to offset a second threshold
                // from, and they default to zero, so underlines never ask rather than needing to
                // opt out. pxRange is not: a decoration's white texel is reserved in the same
                // banded atlas and carries that band's range, so it batches with the glyphs
                // around it instead of splitting them.
                c0x = ((strokeArgb >> 16) & 0xFF) / 255f; c0y = ((strokeArgb >> 8) & 0xFF) / 255f;
                c0z = (strokeArgb & 0xFF) / 255f; c0w = ((strokeArgb >>> 24) & 0xFF) / 255f;
                c1x = strokeWidthTexels; c1y = strokeAlign; c1z = strokeOver; c1w = pxRange;
                c2 = p.key().getFontKey().getTargetPx();
            } else {
                byte kind = shadowPlan.kind(shadow, localIndex);
                p = shadowPlan.placement(shadow, localIndex);
                isDistanceField = p.isDistanceField();
                atlasId = p.atlasId();
                pxRange = p.pxRange();
                u0 = p.u0(); v0 = p.v0(); u1 = p.u1(); v1 = p.v1();
                rgba = shadowList.argb(shadow);
                atlasLayer = p.atlasPageIndex();
                boolean inset = shadowList.inset(shadow);
                float ox = inset ? 0f : shadowList.x(shadow);
                float oy = inset ? 0f : shadowList.y(shadow);
                // A shadow snaps exactly when its text does, so the two stay on one grid: a cell is a bitmap
                // placement, and snapping it under distance-field text would part it from its glyph.
                // A cell's plane bounds are device pixels of the glyph's raster, so the bitmap metric scale
                // places it, and a downsampled cell stretches back over its true extent.
                placeGlyph(p, localIndex, baseTargetPx, effectiveTargetPx,
                        pixelSnap && !placements[localIndex].isDistanceField(), modelView, ox, oy);
                if (kind == CgTextShadowPlan.CELL) {
                    c0w = KIND_CELL;
                } else {
                    c1w = pxRange;
                    if (kind == CgTextShadowPlan.FIELD_OUTER || kind == CgTextShadowPlan.FIELD_INSET) {
                        c1x = strokeWidthTexels;
                        c1y = strokeAlign;
                        c1z = shadowPlan.spreadTexels(shadow);
                        c0w = kind == CgTextShadowPlan.FIELD_OUTER ? KIND_FIELD_OUTER : KIND_FIELD_INSET;
                        if (kind == CgTextShadowPlan.FIELD_INSET) {
                            // The hole is sampled at uv minus the offset, in the atlas's own uv units.
                            c0x = shadowList.x(shadow) * (u1 - u0) / quadW;
                            c0y = shadowList.y(shadow) * (v1 - v0) / quadH;
                            c0z = shadowPlan.insetSigmaTexels(shadow, localIndex);
                        }
                    }
                }
            }

            if (batchBits != activeBatchBits) {
                transitionToMaterial(batchBits, isDistanceField, atlasId);
            }

            quadRenderer.quad()
                    .at(quadX, quadY).size(quadW, quadH)
                    .uv(u0, v0, u1, v1)
                    .color(rgba)
                    .atlasLayer(atlasLayer)
                    .custom0(c0x, c0y, c0z, c0w)
                    .custom1(c1x, c1y, c1z, c1w)
                    .custom2(c2)
                    .clip(clip)
                    .node(spatialNode, effectNode)
                    .pose(modelView)
                    .submit();

            if (traceQuadLoop) {
                long now = System.nanoTime();
                long iterNanos = now - traceIterStart;
                traceIterStart = now;
                if (iterNanos > traceMaxNanos) { traceMaxNanos = iterNanos; traceMaxIndex = s; }
                if (iterNanos > 1_000_000L) traceSlowIters++;
            }

            if (diagnosticLogging && p != null) {
                LOGGER.info("[BatchDiag] glyphId=" + p.key().getGlyphId()
                        + ", atlasType=" + p.atlasType()
                        + ", atlasId=" + p.atlasId()
                        + ", atlasPageIndex/layer=" + p.atlasPageIndex()
                        + ", pxRange=" + p.pxRange()
                        + ", uv=[" + p.u0() + "," + p.v0() + "," + p.u1() + "," + p.v1() + "]"
                        + ", pos=[" + quadX + "," + quadY + "], size=[" + quadW + "," + quadH + "]"
                        + ", paint=" + paint);
            }
        }
        if (traceQuadLoop) {
            CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.maxIterUs", traceMaxNanos / 1000.0);
            CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.maxIterIndex", traceMaxIndex);
            CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.slowIters", traceSlowIters);
        }
        }
    }

    /**
     * A glyph's quad from its placement, moved by {@code (dx, dy)} local pixels, into {@link #quadX} and
     * friends, and snapped to whole device pixels after the move when {@code snap} is set.
     */
    private void placeGlyph(CgGlyphPlacement p, int localIndex, int baseTargetPx, int effectiveTargetPx,
                            boolean snap, Matrix4f modelView, float dx, float dy) {
        boolean isDistanceField = p.isDistanceField();
        int placementTargetPx = p.key().getFontKey().getTargetPx();
        // A shadow cell keeps the raster size it was built at, which a cell standing in for one still being
        // built at a new size does not share with this draw.
        boolean ownRaster = isDistanceField || p.key().getShadowCell() != null;
        float scaleFactor = CgResolvedGlyphs.logicalMetricScale(baseTargetPx, ownRaster ? placementTargetPx : effectiveTargetPx);

        // Plane bounds are in physical raster space; normalize to logical. planeLeft/planeTop
        // are bearing offsets from the pen (Y-down screen space, bearingY positive = above baseline).
        float logicalBearingX = p.planeLeft() * scaleFactor, logicalBearingY = p.planeTop() * scaleFactor;
        quadW = p.getPlaneWidth() * scaleFactor;
        quadH = p.getPlaneHeight() * scaleFactor;
        float baseline = resolvedGlyphs.originY + resolvedGlyphs.glyphY[localIndex] + dy;
        quadX = resolvedGlyphs.originX + resolvedGlyphs.glyphX[localIndex] + logicalBearingX + dx;
        quadY = baseline - logicalBearingY;

        // Bitmap text only, and ortho-only (see pixelSnapDelta's javadoc and the pixelSnap
        // computation in submitBatchedQuads); snaps from the shared line baseline rather than quadY so
        // every glyph on a line gets the same correction, since quadY already has this glyph's own
        // bearingY baked in.
        if (snap) {
            pixelSnapDelta(modelView, scratchInverseModelView, quadX, baseline, scratchLocalDelta);
            quadX += scratchLocalDelta.x;
            quadY += scratchLocalDelta.y;
        }
    }

    /**
     * Local-space correction that, added to {@code (qx, qy)}, makes this glyph's on-screen
     * position (after {@code modelView}) land on a whole pixel — {@code GL_NEAREST} sampling
     * isn't invariant under sub-pixel translation, so an unsnapped position can drop/duplicate
     * a texel row at an edge. Floors (not rounds) to match the sub-pixel bucket convention.
     *
     * <p><strong>Orthographic/UI text only.</strong> This is only meaningful when
     * {@code modelView} maps into screen-pixel space. Under a world-space {@code modelView}
     * it transforms into world units instead, where an entire text block routinely spans less
     * than one unit — {@link Math#floor} then snaps every glyph to the same integer coordinate,
     * and the inverse-transform back to local space scales that error up by {@code 1/worldScale}.
     * Callers must gate on {@link CgTextRenderContext#isWorldText()}; do not rely on
     * {@code isDistanceField} as a proxy for "not world text" (see {@code submitSortedQuads}).</p>
     */
    private static void pixelSnapDelta(Matrix4f modelView, Matrix4f invModelView,
                                        float qx, float qy, Vector3f outLocalDelta) {
        outLocalDelta.set(qx, qy, 0f);
        modelView.transformPosition(outLocalDelta);
        // X ROUNDS TO THE SUB-PIXEL GRID FIRST, then takes the whole-pixel part; the quarter left
        // over is what the glyph's own raster variant already carries (CgGlyphKey.SUB_PIXEL_BUCKETS,
        // and CgResolvedGlyphs.selectSubPixelBucket which takes the remainder of this same
        // rounding). Flooring the raw value instead decides the two halves independently, and a
        // position a hair below an integer then floors down while the bucket rounds up -- 0.75
        // device px of error. Y has no buckets, so it stays a whole-pixel floor.
        int quartersX = Math.round(outLocalDelta.x * CgGlyphKey.SUB_PIXEL_BUCKETS);
        float snappedX = (float) Math.floor(quartersX / (double) CgGlyphKey.SUB_PIXEL_BUCKETS);
        float screenDeltaX = snappedX - outLocalDelta.x;
        float screenDeltaY = (float) Math.floor(outLocalDelta.y) - outLocalDelta.y;
        outLocalDelta.set(screenDeltaX, screenDeltaY, 0f);
        invModelView.transformDirection(outLocalDelta);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Shared layout helper used by the string-based draw overloads.
     *
     * <p>This is the string-to-layout boundary for the renderer. Checks {@link CgTextLayoutCache}
     * first — see that class's javadoc; on a miss, builds a default-knobs {@link CgTextLayout.Request}.
     * Renderer code should treat the returned {@link CgTextLayout} as the stable hand-off
     * format for glyph resolution, and must not mutate it — a cache hit may hand the same
     * instance to multiple unrelated callers.</p>
     */
    static CgTextLayout layout(String text, CgFont font, float maxWidth, float maxHeight) {
        if (text == null) throw new IllegalArgumentException("text must not be null");

        CgTextLayoutCache.Key key = CgTextLayoutCache.key(text, font, maxWidth, maxHeight);
        CgTextLayout cached = CgTextLayoutCache.get(key);
        if (cached != null) return cached;

        CgTextLayout built = CgTextLayout.of(text, font).maxWidth(maxWidth).maxHeight(maxHeight).build();
        CgTextLayoutCache.put(key, built);
        return built;
    }

    static CgTextLayout layout(String text, CgFontFamily family, float maxWidth, float maxHeight) {
        if (text == null) throw new IllegalArgumentException("text must not be null");

        CgTextLayoutCache.Key key = CgTextLayoutCache.key(text, family, maxWidth, maxHeight);
        CgTextLayout cached = CgTextLayoutCache.get(key);
        if (cached != null) return cached;

        CgTextLayout built = CgTextLayout.of(text, family).maxWidth(maxWidth).maxHeight(maxHeight).build();
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

        return family.atSize(targetPx);
    }
}
