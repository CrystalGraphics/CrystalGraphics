package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.gl.texture.CgTextureMutable;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.layout.CgTextLayoutCache;
import com.crystalgraphics.text.render.context.*;
import com.crystalgraphics.util.profiling.CgProfiler;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
     * migration ({@code CgGlyphAtlas} owns one {@code CgTexture2DArray} per atlas family; a
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
        // Must run before ANYTHING can toggle a keyword on TEXT_MATERIAL.
        CgQuadRenderer.attachTo(TEXT_MATERIAL);
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
     * {@link #submitBatchedQuads}) and needs no such tracking at all. Compared by value, not
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
     * Batch identity {@link #TEXT_MATERIAL} is currently configured for, or {@link #NO_ACTIVE_BATCH}
     * when nothing is known to be configured.
     *
     * <p>These were locals in {@link #submitBatchedQuads}, which meant every {@code draw()} call
     * began with no knowledge of the material's state and unconditionally transitioned on its first
     * quad — a flush plus keyword toggle plus property re-apply, per draw. For a UI frame issuing a
     * thousand labels that all share one atlas and one shader mode, that was a thousand transitions
     * where one would do, and it dominated the frame.
     *
     * <p><strong>Static on purpose.</strong> {@link #TEXT_MATERIAL} and {@link #ATLAS_TEXTURE_REF}
     * are shared across every live {@code CgTextRenderer}, so this tracks the shared material rather
     * than one renderer's view of it. Per-instance fields would be unsound: two renderers with
     * interleaved batches would each believe the material still held the state <em>they</em> last
     * set, and whichever flushed second would draw its quads with the other's keywords and atlas
     * texture. Because every transition goes through {@link #transitionToMaterial}, keeping the
     * record beside the state it describes means any renderer's change is immediately visible to
     * all the others.
     *
     * <p>Reset at {@link #beginBatch()} rather than trusted across batches. Between batches the GL
     * binding is torn down and an arbitrary {@link #restoreStateWith} hook may have run, so the
     * assumption that the material is still configured as recorded no longer holds. That costs one
     * redundant transition per batch and removes the need to reason about what happens in between;
     * the win is inside the batch, where the thousand draws are.
     */
    private static long activeBatchBits = -1L;

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

    /**
     * Grow-only per-glyph sort-key scratch for {@link #submitBatchedQuads} — see its javadoc
     * for the bit layout. Never needs more than {@code glyphCount} entries, so it's grown
     * directly off that count rather than tracking its own separate capacity field.
     */
    private long[] scratchSortKeys = new long[0];

    /**
     * Last-resort identity pose used by {@link Draw#submit()}/{@link Draw#measure()} when
     * neither {@link Draw#pose(PoseStack)} nor {@link #poseStack(PoseStack)} was set. Built with
     * {@code syncsToGL = false} — it only ever backs a single never-pushed identity {@code Pose}
     * entry, so it must never touch the real GL matrix stack. Shared, never mutated.
     */
    private static final PoseStack IDENTITY_POSE_STACK = new PoseStack(false);
    
    /**
     * Reusable scratch for {@link #pixelSnapDelta} — the inverse of the current draw call's
     * model-view (recomputed once per {@link #submitBatchedQuads} call, not per-glyph) plus two
     * throwaway vectors, kept as fields purely to avoid a small allocation per glyph.
     */
    private final Matrix4f scratchInverseModelView = new Matrix4f();
    private final Vector3f scratchLocalDelta = new Vector3f();

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
        // Leave no keyword state behind: the material is shared static, and the first real
        // transition must not be skipped because the material already happens to match.
        TEXT_MATERIAL.toggleKeyword("MSDF_MODE", false);
        activeBatchBits = NO_ACTIVE_BATCH;
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

        activeProjection = null;
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

        try (CgProfiler.Scope ignored = CgProfiler.scope("glFlush")) {
            CgProfiler.count("glFlush.count");
            quadRenderer.useMaterial(TEXT_MATERIAL);
            TEXT_DATA_UBO.bind();
            (context.isWorldText() ? CgDepthState.TEST_ONLY : CgDepthState.NONE).apply();

            quadRenderer.flush();
        }

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
     * pending under the previous state first. Callers (just {@link #submitBatchedQuads}) are
     * responsible for only calling this when the state actually changed — this method always
     * flushes and applies unconditionally, it does not re-check for a no-op transition itself.
     *
     * <p>Records {@code batchBits} into {@link #activeBatchBits} as the last step, so the record of
     * what the shared material holds is updated in the same place the material is. Callers must not
     * maintain their own copy: see {@link #activeBatchBits} for why tracking this per renderer
     * rather than per material is unsound.
     *
     * <p>Every transition explicitly sets {@code MSDF_MODE} — one keyword covers both
     * distance-field atlas types (MSDF and MTSDF), since the fragment logic is identical for
     * both today (see {@code text.shader}). {@link #TEXT_MATERIAL} is shared static state, so
     * this is always an explicit enable-or-disable, never a bare {@code enableKeyword()} alone
     * — a stale keyword left on by a previous transition (this renderer's or another live
     * instance's) would otherwise silently persist into the next bind's compiled variant.</p>
     */
    private void transitionToMaterial(long batchBits, boolean isDistanceField, int textureId, float pxRange) {
        flush();

        // Counted to expose batch fragmentation: each transition is a flush + keyword toggle +
        // property re-apply. A warmup frame mixing bitmap-fallback and MSDF glyphs across many
        // atlas pages can produce far more of these than a settled frame.
        CgProfiler.count("materialTransition");
        try (CgProfiler.Scope ignored = CgProfiler.scope("materialTransition")) {
            TEXT_MATERIAL.toggleKeyword("MSDF_MODE", isDistanceField);
            ATLAS_TEXTURE_REF.setId(textureId);
            TEXT_MATERIAL.applyProperties(b -> b.set1f("_PxRange", pxRange));
            activeBatchBits = batchBits;
        }
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

        long frame = CgGraphicsLifecycle.getCurrentFrame();
        int glyphCount;
        try (CgProfiler.Scope ignored = CgProfiler.scope("resolveGlyphs")) {
            glyphCount = resolvedGlyphs.resolve(resolvedLayout, draw.x, draw.y, frame, context, effectiveTargetPx, wantMsdf, fontKey, draw.rgba);
        }
        CgProfiler.sample("draw.glyphCount", glyphCount);
        CgTextDecorationRect[] decorations = resolvedLayout.baked().decorations();
        if (glyphCount > 0 || decorations.length > 0) {
            try (CgProfiler.Scope ignored = CgProfiler.scope("submitSortedQuads")) {
                submitBatchedQuads(glyphCount, decorations, fontKey.getTargetPx(), effectiveTargetPx, wantMsdf,
                        draw.x, draw.y, draw.rgba, pose.pose());
            }
        }
    }

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
     *   <li><b>Once per call</b> — sync the projection to {@link #TEXT_DATA_UBO} via
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
     * @param glyphCount        number of entries in {@link CgResolvedGlyphs#placements} to consider;
     *                          null or geometry-less placements are skipped
     * @param decorations       baked decoration rects for this draw, resolved here rather than by
     *                          the caller
     * @param baseTargetPx      the font's declared size, the denominator for metric normalisation
     * @param effectiveTargetPx the size glyphs were actually rasterised at this frame
     * @param wantMsdf          whether this draw prefers distance-field glyphs
     * @param modelView         transform baked into each emitted quad
     */
    private void submitBatchedQuads(int glyphCount, CgTextDecorationRect[] decorations,
                                    int baseTargetPx, int effectiveTargetPx, boolean wantMsdf,
                                    float drawX, float drawY, int drawRgba, Matrix4f modelView) {
        CgGlyphPlacement[] placements = resolvedGlyphs.placements;

        int visibleGlyphCount = 0;
        try (CgProfiler.Scope ignored = CgProfiler.scope("visibilityScan")) {
            for (int i = 0; i < glyphCount; i++)
                if (placements[i] != null && placements[i].hasGeometry()) visibleGlyphCount++;
        }

        List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations;
        try (CgProfiler.Scope ignored = CgProfiler.scope("resolveDecorations")) {
            resolvedDecorations = resolvedGlyphs.resolveDecorations(decorations, drawX, drawY, drawRgba, effectiveTargetPx, wantMsdf);
        }

        int totalCount = visibleGlyphCount + resolvedDecorations.size();
        if (totalCount == 0) return;

        try (CgProfiler.Scope ignored = CgProfiler.scope("sortKeys")) {
            ensureSortScratchCapacity(totalCount);
            int si = 0;
            for (int i = 0; i < glyphCount; i++) {
                CgGlyphPlacement p = placements[i];
                if (p != null && p.hasGeometry()) scratchSortKeys[si++] = CgTextSortKey.forGlyph(p, i);
            }
            for (int i = 0; i < resolvedDecorations.size(); i++) 
                scratchSortKeys[si++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i);
            
            Arrays.sort(scratchSortKeys, 0, totalCount);
        }

        // Projection is constant for this whole draw() call. Flushes first if it differs from
        // what's already queued under a different projection — see syncProjection().
        try (CgProfiler.Scope ignored = CgProfiler.scope("syncProjection")) {
            syncProjection(context.projection());
        }

        // Pixel-snap is an ORTHO-ONLY correction: pixelSnapDelta floors the modelView-transformed
        // position, which is only meaningful when modelView maps into screen-pixel space. For
        // world text it maps into world units (a whole text block often spans < 1 unit), so
        // flooring collapses every glyph onto the same integer world coordinate -- and the
        // inverse-transform back to local space then multiplies that error by 1/worldScale.
        boolean pixelSnap = !context.isWorldText();
        if (pixelSnap) scratchInverseModelView.set(modelView).invert();

        try (CgProfiler.Scope ignored = CgProfiler.scope("quadLoop")) {
        for (int s = 0; s < totalCount; s++) {
            long key = scratchSortKeys[s];
            long batchBits = CgTextSortKey.batchOf(key);
            boolean isDecoration = CgTextSortKey.isDecoration(key);
            int localIndex = CgTextSortKey.localIndexOf(key);

            // Both branches only compute this entry's batch identity + quad geometry — the
            // actual transition check and quad() submission below are shared, so a decoration
            // and a glyph in the same batch (the common case — see CgTextSortKey.forDecoration)
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

                // Bitmap-only, and ortho-only (see pixelSnapDelta's javadoc and the pixelSnap
                // computation above); snaps from the shared line baseline rather than qy so every
                // glyph on a line gets the same correction, since qy already has this glyph's own
                // bearingY baked in.
                if (pixelSnap && !isDistanceField) {
                    pixelSnapDelta(modelView, scratchInverseModelView, qx, resolvedGlyphs.glyphY[localIndex], scratchLocalDelta);
                    qx += scratchLocalDelta.x;
                    qy += scratchLocalDelta.y;
                }

                u0 = p.u0(); v0 = p.v0(); u1 = p.u1(); v1 = p.v1();
                rgba = resolvedGlyphs.argbColor[localIndex];
                atlasLayer = p.atlasPageIndex();
            }

            if (batchBits != activeBatchBits) {
                transitionToMaterial(batchBits, isDistanceField, textureId, pxRange);
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
        float screenDeltaX = (float) Math.floor(outLocalDelta.x) - outLocalDelta.x;
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

        CgFont primary = family.getPrimarySource().requireFont().atSize(targetPx);
        List<CgFontSource> fallbackSources = new ArrayList<>();
        for (CgFontSource fallback : family.getFallbackSources()) 
            fallbackSources.add(new CgFontSource(fallback.requireFont().atSize(targetPx), fallback.getSourceLabel()));
        
        return new CgFontFamily(family.getFamilyId(), new CgFontSource(primary, family.getPrimarySource().getSourceLabel()), fallbackSources);
    }
}
