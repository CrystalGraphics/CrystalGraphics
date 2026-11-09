package com.crystalgraphics.text.render;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgStrokeAlign;
import com.crystalgraphics.api.text.CgStrokeJoin;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextStroke;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgQuadRecord;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.render.world.CgPipelineList;
import com.crystalgraphics.render.world.CgWorldRenderer;
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
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Draws text: a layout's glyphs from the font registry's atlases (bitmap, MSDF or MTSDF, chosen by size and
 * pose), outlined and shadowed, as quads on the canvas recording on the calling thread
 * ({@link CgCanvas#current()}), else on one of its own drawn when the batch ends.
 *
 * <pre>{@code
 * CgTextRenderer text = CgTextRenderer.create();
 * text.draw().text("Hello").font(font).at(x, y).color(0xFFFFFFFF).submit();       // one draw, its own batch
 *
 * text.beginBatch();                                                                // many draws, batched together
 * text.draw().text(name).font(font).at(x, y).stroke(0.08f, 0xFF000000).submit();
 * text.draw().layout(prebuilt).family(family).at(x, y + 30).pose(poseStack).submit();
 * text.endBatch();
 *
 * text.delete();                                                                    // when done with it
 * }</pre>
 *
 * <ul>
 *   <li>Layout is in logical pixels and never moves with the pose; the pose only picks the raster size
 *       ({@link CgTextScaleResolver}), so a UI scale changes sharpness, never spacing.</li>
 *   <li>{@link #context(CgTextRenderContext)} switches the space: {@link CgTextRenderContext#orthographic} for the
 *       screen, {@link CgTextRenderContext#world} for always-MSDF, depth-tested text in the world.</li>
 *   <li>Labels in the world need no renderer of their own: {@link CgWorldRenderer#text} queues a {@link Draw}.</li>
 *   <li>One thread at a time.</li>
 * </ul>
 */
public class CgTextRenderer {

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  CgMaterial SETUP
    // ══════════════════════════════════════════════════════════════════════════════════════════

    public static final String TEXT_SHADER = "crystalgraphics:shaders/text.shader";

    /** {@code text.shader}; each renderer draws an instance of it, {@link #textMaterial}, sharing its programs. */
    public static final CgMaterial TEXT_MATERIAL = CgMaterial.load(TEXT_SHADER);

    /**
     * Names {@code text.shader} to a precache list in every form a draw takes. Through this class, so its
     * {@code TextData} block is attached first.
     *
     * <pre>{@code
     * CgPipelinePrecache.register("crystalgraphics:world", CgTextRenderer::precache);
     * }</pre>
     */
    public static void precache(CgPipelineList list) {
        list.add(TEXT_SHADER);
        list.addPass(TEXT_SHADER, CgInstanceKind.QUAD);
    }

    /** {@code TextData}: the renderer's own projection, since the pass's is whoever records it. */
    private static final CgBufferFormat TEXT_DATA_FORMAT = CgBufferFormat
            .builder("TextData", CgBufferFormat.MemoryLayout.STD140)
            .mat4("u_Projection")
            .vec4("u_TextGammaSmall")
            .vec4("u_TextGammaLarge")
            .vec4("u_TextGammaRamp")
            // x: the alpha below which a fragment is discarded; 0 but in a label depth pass. @see #drawCapturedDepth
            .vec4("u_TextCut")
            .build();

    /** {@code TextData}'s declaration; each renderer overrides it with {@link #textBlock}, kept by value per chunk. */
    private static final CgUniformBuffer TEXT_DATA_UBO = CgShaderBufferRegistry.get().getOrCreateUboInternal(
            TEXT_DATA_FORMAT, "TextData", CgBindingPoints.TEXT_DATA_UBO, CgBufferLifetime.FRAME);

    /** The {@code text} engine token's block ({@code CgEngineBufferRegistry}). */
    public static CgUniformBuffer textData() {
        return TEXT_DATA_UBO;
    }

    /**
     * This renderer's own material. Its keywords and atlas are its own, so two renderers — recording on two threads,
     * or interleaving batches on one — never draw with each other's.
     */
    private final CgMaterial textMaterial = CgMaterial.newInstance(TEXT_SHADER);
    /**
     * The atlas the current batch draws from, bound as {@link #textMaterial}'s {@code _MainTex} by {@link #bindAtlas}: the
     * texture itself, whose storage the render thread makes before the draw executes.
     */
    @Nullable
    private CgTexture atlas;
    private final Consumer<CgMaterialValues> bindAtlas = b -> b.sampler("_MainTex", 0, atlas);
    /** This renderer's {@code TextData}: the projection and the gamma correction its chunks keep. */
    private final CgBufferWriter textBlock =
            new CgBufferWriter(new CgStagingBuffer(TEXT_DATA_FORMAT.getFloatCount()), TEXT_DATA_FORMAT);

    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    public static boolean diagnosticLogging = false;

    /** Its own class: the renderer's statics need a platform, and a variable is declared without one. */
    private static final class Trace {
        // A System.nanoTime() per quad: about 0.2 ms on a 1.1 ms loop.
        static final boolean QUAD_LOOP = CgVar.bool("crystalgraphics.text.traceQuadLoop", false,
                CgVar.DEV | CgVar.RESTART, "Time every iteration of text's quad-submission loop and report the slowest: tells"
                        + " a blocking call inside the loop from the render thread being descheduled. Costs a fifth of the loop.").get();
    }


    private final CgFontRegistry registry = CgFontRegistry.get();

    // ── Owned batch lifecycle ────────────────────────────────────────────────
    /** Where glyphs land when no canvas records on the calling thread: drawn at each flush. */
    private final CgCanvas immediate = new CgCanvas();
    private boolean batchActive;
    /** The projection the queued quads were computed against; a different one mid-batch flushes first. */
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

    /** The batch state {@link #textMaterial} holds, or {@link #NO_ACTIVE_BATCH}; reset at each batch. */
    private long activeBatchBits = -1L;

    /** No known state. Never a key: {@link CgTextSortKey} keeps bit 63 clear. */
    private static final long NO_ACTIVE_BATCH = -1L;

    /** Layout to atlas placements. @see CgResolvedGlyphs */
    private final CgResolvedGlyphs resolvedGlyphs = new CgResolvedGlyphs(registry);

    /** Per-renderer, since two renderers can be mid-draw under different projections. */
    private final CgTextCuller culler = new CgTextCuller();
    private final CgTextShadowPlan shadowPlan = new CgTextShadowPlan(registry);

    /** Sort keys for {@link #submitBatchedQuads}, grown to the glyph count. */
    private long[] scratchSortKeys = new long[0];

    /** The pose of a draw that set none, with no renderer fallback. */
    private static final PoseStack IDENTITY_POSE_STACK = new PoseStack();
    
    /** Scratch for {@link #pixelSnapDelta}. */
    private final Matrix4f scratchInverseModelView = new Matrix4f();
    private final Vector3f scratchLocalDelta = new Vector3f();
    /** Reused by the pose-phase probe in drawInternal; never escapes the frame thread. */
    private final Vector3f scratchPosePhase = new Vector3f();

    private void ensureSortScratchCapacity(int count) {
        if (count <= scratchSortKeys.length) return;
        scratchSortKeys = Arrays.copyOf(scratchSortKeys, Math.max(count, scratchSortKeys.length * 2));
    }
    
    /**
     * A hook run at the end of every batch, the one a lone {@code submit()} opens included: for a caller
     * putting its own state back. Null clears it.
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
     * The {@link CgClipTable} entry later glyphs, decorations and shadows are clipped by; 0 is none. Per quad,
     * so it keeps the batch.
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
     * The spatial and effect nodes later draws are in; a draw's pose is then in the spatial node's space.
     * 0 and 0 for the pass's own. Per quad, as {@link #clip}.
     */
    public CgTextRenderer node(int spatial, int effect) {
        this.spatialNode = spatial;
        this.effectNode = effect;
        return this;
    }

    // ── Owned render context ────────────────────────────────────────────────
    /**
     * The space draws are in: orthographic over the window by default ({@link #create()} keeps it sized to it).
     * Replace it to switch to world text.
     */
    @Getter
    @Setter
    @Accessors(fluent = true)
    @NonNull
    private CgTextRenderContext context = CgTextRenderContext.orthographic(
            CgGraphicsLifecycle.getCurrentWidth(), CgGraphicsLifecycle.getCurrentHeight());

    // ── Optional fallback pose stack ─────────────────────────────────────────
    /** The pose a draw takes when it sets none; null by default. */
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
        textMaterial.overrideBlock(TEXT_DATA_UBO, textBlock);
    }

    /**
     * Compiles {@code text.shader} with {@code MSDF_MODE} on and off before the first draw, which would
     * otherwise pay for it mid-frame. Render thread, with a live context.
     */
    public static void warmUpMaterial() {
        for (boolean msdf : new boolean[]{false, true}) {
            TEXT_MATERIAL.toggleKeyword("MSDF_MODE", msdf);
            TEXT_MATERIAL.bind();
            TEXT_MATERIAL.unbind();
        }
        TEXT_MATERIAL.toggleKeyword("MSDF_MODE", false);
    }

    /**
     * A renderer whose context follows the window's size. Registered so a renderer still alive at teardown is
     * swept; {@link #delete()} it when done.
     */
    public static CgTextRenderer create() {
        CgTextRenderer renderer = new CgTextRenderer();
        CgTextRendererRegistry.get().register(renderer);
        renderer.screenSized = true;
        return renderer;
    }

    /**
     * A renderer whose context the caller sizes: for a target other than the window (an offscreen capture, a
     * fixed viewport).
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
     * Opens a batch: draws until {@link #endBatch()} share quads wherever their state allows. Optional; a
     * draw outside one is its own.
     *
     * @throws IllegalStateException if a batch is open, or the renderer is deleted
     */
    public void beginBatch() {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (batchActive) throw new IllegalStateException("CgTextRenderer.beginBatch() called without a matching endBatch()");

        projectionValid = false;
        // Between batches the material binding was torn down and a restore hook may have run, so
        // whatever was recorded about the shared material can no longer be trusted.
        activeBatchBits = NO_ACTIVE_BATCH;
        batchActive = true;
    }

    /** Lands the batch's quads and runs {@link #restoreStateWith}'s hook. Does nothing with no batch open. */
    public void endBatch() {
        if (!batchActive) return;

        flush();
        projectionValid = false;
        batchActive = false;

        if (postBatchRestore != null) postBatchRestore.run();
    }


    /** Lands what is staged under {@link #textMaterial} as it now is. Nothing staged, nothing done. */
    private void flush() {
        flushes++;
        drainBuckets();
        if (!immediate.pending()) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "gl.flush")) {
            CgTrace.add(CgChannels.GL, "gl.flush.count", 1);
            immediate.flush();
        }
    }

    /** Writes {@code projection} into {@link #textBlock}, flushing first if queued glyphs used another. */
    private void syncProjection(Matrix4f projection) {
        if (projectionValid) {
            if (activeProjection.equals(projection)) return;
            flush();
        }
        // KEPT, and marked invalid rather than dropped: the resets run per batch, and a fresh matrix each time
        // was a steady allocation per text draw.
        activeProjection.set(projection);
        projectionValid = true;
        writeTextBlock(projection, 0f);
    }

    private void writeTextBlock(Matrix4f projection, float cut) {
        CgTextGamma.Level small = gamma.small(), large = gamma.large();
        textBlock.reset().beginRecord().mat4("u_Projection", projection)
                .vec4("u_TextGammaSmall", small.exponent(), small.contrast(), 1f / small.exponent(), 0f)
                .vec4("u_TextGammaLarge", large.exponent(), large.contrast(), 1f / large.exponent(), 0f)
                .vec4("u_TextGammaRamp", gamma.smallPx(), gamma.largePx(), gamma.isIdentity() ? 0f : 1f, 0f)
                .vec4("u_TextCut", cut, 0f, 0f, 0f);
        // Written past the material: re-given so its change starts a new draw, glyphs before keeping the old block.
        textMaterial.overrideBlock(TEXT_DATA_UBO, textBlock);
    }

    /**
     * Puts {@link #textMaterial} in {@code batchBits}'s state: its atlas and {@code MSDF_MODE} set
     * explicitly either way. Callers call it only on a change.
     */
    private void transitionToMaterial(long batchBits, boolean isDistanceField, int atlasId) {
        flush();

        // Counted to expose batch fragmentation: each transition is a flush + keyword toggle +
        // property re-apply. A warmup frame mixing bitmap-fallback and MSDF glyphs across many
        // atlas pages can produce far more of these than a settled frame.
        CgTrace.add(CgChannels.TEXT, "draw.materialTransition", 1);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "draw.materialTransition")) {
            textMaterial.toggleKeyword("MSDF_MODE", isDistanceField);
            atlas = CgGlyphAtlas.texture(atlasId);
            textMaterial.applyProperties(bindAtlas);
            activeBatchBits = batchBits;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  FLUENT DRAW REQUEST
    // ══════════════════════════════════════════════════════════════════════════════════════════
    
    /** {@code text.shader}'s align slot: the align, plus 3 for a round join. */
    private static float alignCode(float align, boolean round) {
        return round ? align + 3f : align;
    }

    /** What {@code quad.glsl} reads for a {@link CgStrokeAlign}: on the contour, outside it, inside it. */
    static final float ALIGN_CENTER = 0f, ALIGN_OUTSET = 1f, ALIGN_INSET = 2f;
    
    /**
     * How many draws resolved a glyph or shadow below what they asked for: still generating, or refused by
     * the frame's budget. Rises only; a damage-driven caller compares it around its paint and asks for another frame.
     *
     * <pre>{@code
     * long before = renderer.degradedDrawCount();
     * ...submit();
     * if (renderer.degradedDrawCount() != before) repaint();
     * }</pre>
     */
    @Getter
    private long degradedDrawCount;

    private final Draw scratchDraw = new Draw(null);

    /** A draw: this renderer's one scratch, to build and {@link Draw#submit()} in one expression. */
    public Draw draw() {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        return scratchDraw.reset();
    }

    /** A draw of the caller's own, held across frames; change what changed and submit each frame. */
    public Draw retainedDraw() {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        return new Draw(null);
    }

    /**
     * A retained draw whose {@link Draw#submit()} hands it to {@code queue}, for an owner that draws it later
     * under a pose it knows then ({@link #drawQueued}). {@code CgWorldRenderer}'s labels are these.
     *
     * <pre>{@code
     * Draw label = renderer.queuedDraw(pending::add);
     * label.text("Spawn").font(font).stroke(0.1f, 0xFF000000).submit();   // into pending
     * for (Draw d : pending) renderer.drawQueued(d.pose(pose));           // at record time
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
     * Records {@code draw}'s quads into {@code into} instead of drawing them, in the draw's own space: its pose is not
     * applied and nothing is culled. Draw them, frame after frame, with {@link #drawCaptured}.
     *
     * <pre>{@code
     * if (!renderer.capture(label, kept)) recaptureNextFrame = true;   // a glyph or a shadow is still building
     * }</pre>
     *
     * <ul>
     *   <li>Outside a batch, or between draws of none: quads queued and not yet flushed throw.</li>
     *   <li>The raster tier is the context's for an identity pose, so capture only where the tier does not follow
     *       the pose: world text ({@code CgTextRenderContext.world}), whose tier is fixed.</li>
     * </ul>
     *
     * @return whether everything drew as asked; false while a glyph or a shadow cell is still building, and the
     *         capture then holds what was ready
     */
    public boolean capture(Draw draw, CgTextCapture into) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (bucketCount != 0 || immediate.pending()) {
            throw new IllegalStateException("capture() with quads queued: end the batch first");
        }
        if (draw.layout == null && draw.paragraph == null && draw.text == null) {
            throw new IllegalStateException("CgTextRenderer.Draw requires text(...), paragraph(...), or layout(...) before capture()");
        }
        if (draw.layout == null && draw.family == null && draw.font == null) {
            throw new IllegalStateException("CgTextRenderer.Draw requires font(...) or family(...) before capture()");
        }
        long degraded = degradedDrawCount;
        into.begin(CgGlyphAtlas.evictions());
        capturing = into;
        try {
            drawInternal(draw, IDENTITY_POSE_STACK.last());
        } finally {
            capturing = null;
        }
        int n = bucketCount;
        bucketCount = 0;
        lastBucket = null;
        sortBuckets(n);
        for (int i = 0; i < n; i++) {
            Bucket b = buckets.get(i);
            into.add(b.rank, b.batchBits, b.records, b.count);
            b.count = 0;
        }
        return degradedDrawCount == degraded;
    }

    /** The capture {@link #capture} is filling: drawInternal culls nothing and notes its layout there. */
    @Nullable
    private CgTextCapture capturing;

    /**
     * Draws {@code count} captured records of {@code batch} ({@link CgTextCapture#batch}) from {@code records} into
     * {@code chunks}' open chunk, each placed by the model matrix of the object record its {@code node} names in
     * {@code labels}: one draw of {@code text.shader}'s {@code WORLD_LABEL} variant, under this renderer's projection.
     *
     * <pre>{@code
     * chunks.bindings(recording.bindings()).begin(0, 0, 0);
     * renderer.drawCaptured(chunks, batch, glyphs, glyphCount, labels);   // labels: CgInstanceKind.OBJECT records
     * recorder.add(chunks.end());
     * }</pre>
     *
     * <ul>
     *   <li>{@code records} and {@code labels} need {@code STORAGE}; a graph buffer is read after the pass writing it.</li>
     *   <li>A record's {@code node} is an index into {@code labels}, as a float.</li>
     * </ul>
     */
    public void drawCaptured(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count, CgBufferHandle labels) {
        drawCaptured(chunks, batch, records, count, labels, false);
    }

    /**
     * {@link #drawCaptured}'s depth pass: the records' solid core (alpha 0.5 and up) into depth alone. Drawn for every
     * label's text and lines before any label's colour, it makes a nearer label's text hide a farther one's whatever
     * order they draw in; a label's own layers lie in its plane and pass.
     *
     * <pre>{@code
     * for (int b : textBatches) renderer.drawCapturedDepth(chunks, batch(b), glyphs(b), count(b), labels);
     * for (int b : allBatches)  renderer.drawCaptured(chunks, batch(b), glyphs(b), count(b), labels);
     * }</pre>
     */
    public void drawCapturedDepth(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count, CgBufferHandle labels) {
        drawCaptured(chunks, batch, records, count, labels, true);
    }

    private void drawCaptured(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count, CgBufferHandle labels,
                              boolean depthPass) {
        if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
        if (count <= 0) return;
        syncProjection(context.projection());
        if (depthPass) writeTextBlock(context.projection(), DEPTH_PASS_CUT);
        textMaterial.toggleKeyword("MSDF_MODE", CgTextSortKey.isDistanceField(batch));
        textMaterial.toggleKeyword("WORLD_LABEL", true);
        atlas = CgGlyphAtlas.texture(CgTextSortKey.atlasIdOf(batch));
        textMaterial.applyProperties(bindAtlas);
        CgPipeline pipeline = textMaterial.pipeline(CgInstanceKind.OBJECT);
        int bindings = textMaterial.captureBindings(chunks.bindings());
        textMaterial.toggleKeyword("WORLD_LABEL", false);
        if (depthPass) writeTextBlock(context.projection(), 0f);
        // What the material holds is no batch's any more.
        activeBatchBits = NO_ACTIVE_BATCH;
        if (pipeline == null) return;
        pipeline = pipeline.withState(labelState(pipeline.state(), depthPass));
        chunks.draw(pipeline, bindings, CgMesh.quads(1)).objects(labels, 0, count)
                .buffer(CgBindingPoints.QUAD_RENDERER, records);
    }

    /** The alpha a label's depth pass writes from: half covered, so an antialiased edge stays see-through. */
    private static final float DEPTH_PASS_CUT = 0.5f;

    /**
     * A label's state from the text pass's: the depth pass with colour off and depth written, the colour passes with
     * colour declared on, since the text pass leaves the mask undeclared and the depth pass's would stand. Kept per
     * state: a pipeline is keyed by its identity.
     */
    private static synchronized CgRenderState labelState(CgRenderState text, boolean depthPass) {
        if (labelStateFrom != text) {
            labelDepthState = text.withColorMask(CgColorMask.NONE).withDepth(CgDepthState.TEST_WRITE);
            labelColorState = text.withColorMask(CgColorMask.ALL);
            labelStateFrom = text;
        }
        return depthPass ? labelDepthState : labelColorState;
    }

    /** {@link #labelState}: the text state they were made from, and the two. */
    private static CgRenderState labelStateFrom, labelDepthState, labelColorState;

    /**
     * One draw: what to draw, where and how. {@link #submit()} needs text ({@link #text}, {@link #paragraph}
     * or {@link #layout}, the last winning) and a font ({@link #family} over {@link #font}), which a non-empty layout
     * supplies itself. Position defaults to (0, 0), colour to opaque white, the pose to the renderer's or identity.
     *
     * <pre>{@code
     * renderer.draw().text("Hello world").font(font).at(20f, 40f).color(0xFFFFFFFF).submit();
     *
     * CgTextRenderer.Draw fps = renderer.retainedDraw().font(hudFont).at(8f, 8f);   // held across frames
     * fps.text(currentFps).submit();                                                // each frame
     *
     * renderer.draw().text(title).family(family).targetPx(24).stroke(0.08f, 0xFF000000)
     *         .shadowCount(1).shadow(0, 1f, 1f, 2f, 0f, 0x80000000, false).submit();
     * }</pre>
     *
     * <ul>
     *   <li>A {@link #layout} is drawn as built; a {@link #paragraph} or {@link #text} wraps at {@link #constraints} in
     *       screen pixels, re-wrapped as the UI scale changes (never for world text).</li>
     *   <li>{@link #draw()}'s scratch is reset by the next {@code draw()}: never hold it.</li>
     * </ul>
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
        private boolean strokeRound = true;
        private boolean shadowsStroked;
        private boolean contained = true;
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
            strokeRound = true;
            shadowsStroked = false;
            contained = true;
            shadows.clear();
            return this;
        }

        /**
         * Whether {@code other} draws the same text the same way: every field but the pose. Text by content; layout,
         * paragraph, font and family by identity.
         *
         * <pre>{@code
         * if (!kept.sameAs(label)) { renderer.capture(label, capture); kept.set(label); }
         * }</pre>
         *
         * <p>A field added to {@code Draw} joins this, {@link #set} and {@link #reset}, or a retained copy keeps a
         * stale picture.</p>
         */
        public boolean sameAs(Draw other) {
            return layout == other.layout && paragraph == other.paragraph && Objects.equals(text, other.text)
                    && font == other.font && family == other.family && maxWidth == other.maxWidth
                    && maxHeight == other.maxHeight && targetPx == other.targetPx && x == other.x && y == other.y
                    && rgba == other.rgba && strokeWidthEm == other.strokeWidthEm && strokeArgb == other.strokeArgb
                    && strokeAlign == other.strokeAlign && strokeOver == other.strokeOver
                    && strokeRound == other.strokeRound && shadowsStroked == other.shadowsStroked
                    && contained == other.contained && shadows.sameAs(other.shadows);
        }

        /** Takes every field of {@code other} but its pose: a copy to compare later draws against with {@link #sameAs}. */
        public Draw set(Draw other) {
            layout = other.layout;
            paragraph = other.paragraph;
            text = other.text;
            font = other.font;
            family = other.family;
            maxWidth = other.maxWidth;
            maxHeight = other.maxHeight;
            targetPx = other.targetPx;
            x = other.x;
            y = other.y;
            rgba = other.rgba;
            strokeWidthEm = other.strokeWidthEm;
            strokeArgb = other.strokeArgb;
            strokeAlign = other.strokeAlign;
            strokeOver = other.strokeOver;
            strokeRound = other.strokeRound;
            shadowsStroked = other.shadowsStroked;
            contained = other.contained;
            shadows.set(other.shadows);
            return this;
        }

        /** Sets a prebuilt layout. Wins over {@link #paragraph(CgShapedParagraph)}/{@link #text(String)} if set. */
        public Draw layout(CgTextLayout layout) {
            this.layout = layout;
            return this;
        }

        /**
         * A shaped paragraph, wrapped at {@link #constraints} on each submit. Over {@link #text}, under {@link #layout}.
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

        /** The raster size in pixels the font or family is resized to; unset, it must already be sized. */
        public Draw targetPx(int targetPx) {
            this.targetPx = targetPx;
            return this;
        }

        /** Where {@link #text} and {@link #paragraph} wrap and stop; 0 or less is unbounded. */
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

        /** The model-view; unset, the renderer's {@link CgTextRenderer#poseStack}, then identity. */
        public Draw pose(PoseStack pose) {
            this.pose = pose;
            return this;
        }

        /**
         * An outline {@code widthEm} of the em wide, outside the contour; 0 or a transparent colour draws none.
         *
         * <pre>{@code
         * .stroke(0.06f, 0xFF101418)
         * .stroke(0.06f, argb).strokeAlign(CgStrokeAlign.CENTER)
         * .stroke(0.06f, argb).strokeOverFill(true)
         * }</pre>
         *
         * <ul>
         *   <li>In em, since the raster size follows the pose. Clamped at {@link CgTextStroke#MAX_FIELD_WIDTH_EM}.</li>
         *   <li>Moves the draw to the distance-field tier, down to {@code CgMsdfAtlasConfig.minAntialiasablePx()}; smaller,
         *       the stroke is dropped.</li>
         * </ul>
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

        /** How the outline turns the glyph's corners. {@link CgStrokeJoin#ROUND} by default, as Unreal's. */
        public Draw strokeJoin(CgStrokeJoin join) {
            this.strokeRound = join != CgStrokeJoin.MITRE;
            return this;
        }

        /**
         * Whether shadows take the outline's shape: false, the default, as Unreal's
         * {@code bApplyOutlineToDropShadows}; true as CSS {@code text-shadow}.
         */
        public Draw shadowsStroked(boolean stroked) {
            this.shadowsStroked = stroked;
            return this;
        }

        /**
         * Whether the outline and the shadows' offsets stay inside the text's box, as Slate's text runs keep them:
         * {@code true}, the default, moves the text right and down by the outline's reach and by any shadow offset
         * up or left, and {@link #measureSize} counts them. {@code false} draws the text at {@link #at} and lets
         * both overflow, as CSS does.
         *
         * <pre>{@code
         * renderer.draw().text("Ready").font(font).at(4, 4).stroke(0.1f, black).submit();     // the outline's edge at (4, 4)
         * renderer.draw().text("Ready").font(font).at(4, 4).stroke(0.1f, black).contained(false).submit(); // the glyphs'
         * }</pre>
         */
        public Draw contained(boolean contained) {
            this.contained = contained;
            return this;
        }

        /** The four stroke values from a {@link CgTextStroke} already held. */
        public Draw stroke(CgTextStroke stroke) {
            CgTextStroke s = stroke == null ? CgTextStroke.NONE : stroke;
            return stroke(s.widthEm(), s.argb())
                    .strokeAlign(s.align())
                    .strokeOverFill(s.strokeOverFill());
        }

        /**
         * How many shadows it casts, each set with {@link #shadow}; 0 by default. Growing it keeps those set.
         *
         * <pre>{@code
         * draw.shadowCount(2)
         *     .shadow(0, 0f, 0f, 4f, 0f, 0xFF44CCFF, false)   // a glow, on top
         *     .shadow(1, 1f, 1f, 0f, 0f, 0x80000000, false);  // a drop shadow beneath it
         * }</pre>
         *
         * <p>As Chrome paints {@code text-shadow}: per glyph, blurred in local space, the first on top and all beneath
         * the text.</p>
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
         * Shadow {@code index}: an offset in local pixels (right and down), {@code sigma} the blur's standard
         * deviation (half a CSS blur radius), {@code spread} grown before blurring, {@code argb} its whole colour and
         * opacity, {@code inset} inside the glyph. Negative sigma or spread reads as 0; transparent casts nothing. One a
         * worker has not built yet is left out and counted in {@link CgTextRenderer#getDegradedDrawCount()}.
         */
        public Draw shadow(int index, float offsetX, float offsetY, float sigma, float spread, int argb,
                           boolean inset) {
            shadows.set(index, offsetX, offsetY, sigma, spread, argb, inset);
            return this;
        }

        /**
         * Draws it, in the open batch or one of its own.
         *
         * @return the renderer, so a batch's last submit can chain into {@link CgTextRenderer#endBatch()}
         * @throws IllegalStateException without text, or without a font where the layout gives none
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
         * The layout {@link #submit()} would draw now, wrapped for the current pose, without drawing: to place
         * what follows it. Cheap per frame; the wrap is memoised.
         *
         * @throws IllegalStateException as {@link #submit()}
         */
        public CgTextLayout measure() {
            if (deleted) throw new IllegalStateException("CgTextRenderer has been deleted");
            if (layout == null && paragraph == null && text == null) throw new IllegalStateException("CgTextRenderer.Draw requires text(...), paragraph(...), or layout(...) before measure()");
            if (layout == null && family == null && font == null) throw new IllegalStateException(
                    "CgTextRenderer.Draw requires font(...) or family(...) before measure()");

            return resolveDraw(this, effectivePose().last()).layout();
        }

        /**
         * The box the text takes in the layout's pixels: {@link #measure()}'s size, and under {@link #contained} the
         * outline's reach on every side and the shadows' offsets, as Slate measures a run.
         *
         * <pre>{@code
         * Vector2f size = draw.measureSize(new Vector2f());
         * nextY += size.y;
         * }</pre>
         */
        public Vector2f measureSize(Vector2f out) {
            CgTextLayout measured = measure();
            out.set(measured.totalWidth(), measured.totalHeight());
            if (!contained) return out;
            float outline = outlineReach();
            return out.add(2f * outline + shadowReach(true, false) + shadowReach(true, true),
                    2f * outline + shadowReach(false, false) + shadowReach(false, true));
        }

        /** Where the glyphs' origin lands: {@link #at}, moved in under {@link #contained}. */
        private float originX() {
            return contained ? x + outlineReach() + shadowReach(true, true) : x;
        }

        private float originY() {
            return contained ? y + outlineReach() + shadowReach(false, true) : y;
        }

        /** How far the outline reaches past the glyphs, in the layout's pixels; its tier is not asked. */
        private float outlineReach() {
            if (!strokeRequested(this)) return 0f;
            float share = strokeAlign == ALIGN_INSET ? 0f : strokeAlign == ALIGN_CENTER ? 0.5f : 1f;
            return strokeWidthEm * share * basePx();
        }

        /** The outer shadows' furthest offset along one axis, before the text ({@code before}) or after it. */
        private float shadowReach(boolean horizontal, boolean before) {
            float reach = 0f;
            for (int i = 0; i < shadows.count(); i++) {
                if (shadows.inset(i)) continue;
                float offset = horizontal ? shadows.x(i) : shadows.y(i);
                reach = Math.max(reach, before ? -offset : offset);
            }
            return reach;
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
        // An open batch is dropped, not ended: ending draws and restores, and at teardown the materials are
        // already gone.
        if (batchActive) {
            projectionValid = false;
            batchActive = false;
        }
        replays.clear();
        textMaterial.delete();
        CgTextRendererRegistry.get().unregister(this);
        deleted = true;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  INTERNAL PIPELINE
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** What {@link #drawInternal} and {@link Draw#measure()} share, so a measure never drifts from the draw. */
    private record ResolvedDraw(CgFontKey fontKey, int effectiveTargetPx, boolean wantMsdf, CgTextLayout layout) {
    }

    /** A width and a colour that would draw something. The tier decision needs it before the stroke is resolved. */
    private static boolean strokeRequested(Draw draw) {
        return draw.strokeWidthEm > 0f && (draw.strokeArgb >>> 24) != 0;
    }


    /**
     * Font, raster tier and layout for {@code draw}, wrapped for the pose. No placement and no drawing, so
     * {@link Draw#measure()} calls it too.
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

        // Off-axis, bitmap glyphs resample soft: a rotation or shear takes the distance-field tier. Quarter
        // turns and flips stay bitmap.
        boolean wantMsdf = sizeWantsMsdf;
        if (!wantMsdf && !OrthographicScaleResolver.isAxisAligned(pose.pose())) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByTransform", 1);
        }

        // A stroke needs a distance field (bitmap has none to outline), down to the size the field can
        // antialiase (CgMsdfAtlasConfig#minAntialiasablePx).
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
            // Constraints are screen pixels: divided by the pose's own scale, not effective/base px, which is clamped
            // at MAX_EFFECTIVE_PX. Never for world text, which must not reflow with the camera.
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

    /** {@code value / scale}, unbounded staying unbounded and a scale of about 1 skipped. */
    private static float scaleConstraint(float value, float scale) {
        if (value <= 0f || scale <= 0f || Math.abs(scale - 1f) < 0.0001f) {
            return value;
        }
        return value / scale;
    }

    /** Resolves {@code draw}, places its glyphs and submits them. */
    private void drawInternal(Draw draw, PoseStack.Pose pose) {
        ResolvedDraw resolved = resolveDraw(draw, pose);
        CgFontKey fontKey = resolved.fontKey();
        int effectiveTargetPx = resolved.effectiveTargetPx();
        boolean wantMsdf =  resolved.wantMsdf();
        CgTextLayout resolvedLayout = resolved.layout();

        if (resolvedLayout == null || resolvedLayout.lines().isEmpty()) return;
        float originX = draw.originX(), originY = draw.originY();

        // Before resolveGlyphs, not inside the quad loop: an off-screen layout otherwise pays for
        // full glyph resolution and quad building before anything notices. See CgTextCuller.
        if (capturing != null) {
            capturing.layout(resolvedLayout);
        } else if (spatialNode == 0 && culler.isCulled(resolvedLayout, originX, originY, context.projection(), pose.pose(), draw.shadows.reach())) {
            // Not under a spatial node: the pose is the node's space and the projection the target's.
            CgTrace.add(CgChannels.TEXT, "text.drawsCulled", 1);
            return;
        }

        long frame = CgFrameClock.frameIndex();
        Replay replay = REPLAYS && capturing == null ? replays.get(resolvedLayout) : null;
        if (replay != null && replay.matches(draw, pose.pose(), context.projection(), effectiveTargetPx, wantMsdf,
                clip, spatialNode, effectNode, frame)) {
            syncProjection(context.projection());
            replay(replay);
            CgTrace.add(CgChannels.TEXT, "draw.replayed", 1);
            return;
        }
        long degradedBefore = degradedDrawCount;
        long flushesBefore = flushes;
        markBuckets();
        recordDraw(draw, pose, resolved, originX, originY, frame);
        if (REPLAYS && capturing == null && degradedDrawCount == degradedBefore && flushes == flushesBefore) {
            keepReplay(resolvedLayout, draw, pose.pose(), effectiveTargetPx, wantMsdf, frame);
        }
    }

    /** {@link #drawInternal}'s glyphs resolved and recorded into the buckets, past the cull and a replay. */
    private void recordDraw(Draw draw, PoseStack.Pose pose, ResolvedDraw resolved, float originX, float originY,
                            long frame) {
        CgFontKey fontKey = resolved.fontKey();
        int effectiveTargetPx = resolved.effectiveTargetPx();
        boolean wantMsdf = resolved.wantMsdf();
        CgTextLayout resolvedLayout = resolved.layout();
        int glyphCount;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "glyph.resolveGlyphs")) {
            // The pose's own sub-pixel phase. A CSS transform moves text through the POSE, not
            // through draw.x/draw.y, so without this the placement cache returns the same glyphs
            // for every sub-pixel position of a translated element and the offset never lands.
            scratchPosePhase.set(originX, originY, 0f);
            pose.pose().transformPosition(scratchPosePhase);
            // Under the registry's lock, so a glyph a worker commits cannot land halfway through one resolve.
            synchronized (registry) {
                glyphCount = resolvedGlyphs.resolve(resolvedLayout, originX, originY, frame, context,
                        effectiveTargetPx, wantMsdf, fontKey, draw.rgba, scratchPosePhase.x);
            }
        }
        CgTrace.counter(CgChannels.TEXT, "draw.glyphCount", glyphCount);

        // A glyph refused by the budget draws bitmap this frame; count it so a damage-driven caller paints again.
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

        // A stroke the tier cannot draw resolves to none here, so a caller may always set one. It rides on the quads.
        boolean stroked = wantMsdf && strokeRequested(draw);
        // In the glyph's own texels: a stroke is painted in local space and transformed with the text, the only
        // form an anisotropic transform can carry.
        float strokeWidthTexels = stroked
                ? draw.strokeWidthEm * registry.getResolvedMsdfConfig(fontKey).atlasScalePx()
                : 0f;

        if (draw.shadows.count() > 0 && glyphCount > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.planShadows")) {
                boolean degraded;
                synchronized (registry) {
                    degraded = shadowPlan.plan(draw.shadows, resolvedGlyphs.placements, resolvedGlyphs.cached,
                            resolvedLayout.baked(), glyphCount, fontKey, effectiveTargetPx,
                            draw.shadowsStroked ? strokeWidthTexels : 0f,
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



    /**
     * One draw's glyphs, decorations and shadows as quads, sorted into the fewest material transitions: by
     * paint step, then atlas and mode ({@link CgTextSortKey}). An underline batches with its font's glyphs.
     *
     * @param glyphCount        entries of {@link CgResolvedGlyphs#placements}; empty ones are skipped
     * @param decorations       the draw's decoration rects
     * @param baseTargetPx      the font's declared size, for metric normalisation
     * @param effectiveTargetPx the size glyphs were rasterised at
     * @param wantMsdf          whether the draw prefers distance-field glyphs
     * @param draw              the request
     * @param modelView         baked into each quad
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
                resolvedDecorations = resolvedGlyphs.resolveDecorations(decorations, draw.originX(), draw.originY(), draw.rgba,
                        effectiveTargetPx, wantMsdf);
            }
        }

        // An upper bound: the text's two passes, then every shadow's glyphs and decorations again.
        int perStage = glyphCount + resolvedDecorations.size();
        if (perStage == 0) return;
        ensureSortScratchCapacity(perStage * (2 + shadows));
        boolean stroked = strokeArgb != 0 && strokeWidthTexels > 0f;

        // Projection is constant for this whole draw() call. Flushes first if it differs from
        // what's already queued under a different projection — see syncProjection().
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.syncProjection")) {
            syncProjection(context.projection());
        }

        // Snap to pixels on screen only: in the world a block spans under a unit and every glyph would floor together.
        boolean pixelSnap = !context.isWorldText();
        if (pixelSnap) scratchInverseModelView.set(modelView).invert();
        float devicePerLocal = effectiveTargetPx / (float) Math.max(1, baseTargetPx);

        // Paint steps, the key's outermost field: outer shadows (last first), under-lines, the two text passes (Slate's
        // outline then fill), line-through, inset shadows. Each its own step: within one, the atlas sorts first.
        int textPaint = shadows + 1;
        int lastPaint = 2 * shadows + 3;
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
                    if (paint == textPaint || paint == textPaint + 1) {
                        if (paint == textPaint && !stroked) continue;
                        for (int i = 0; i < glyphCount; i++) {
                            CgGlyphPlacement p = placements[i];
                            if (p != null && p.hasGeometry()) scratchSortKeys[count++] = CgTextSortKey.forGlyph(p, i, stage);
                        }
                        continue;
                    }
                    if (paint == textPaint + 2) {
                        for (int i = 0; i < resolvedDecorations.size(); i++) {
                            if (!resolvedDecorations.get(i).underText()) {
                                scratchSortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                            }
                        }
                        continue;
                    }
                    int shadow = shadowAt(paint, shadows);
                    if (shadowList.inset(shadow) != (paint > textPaint + 2) || !shadowList.casts(shadow)) {
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

        // A counter over the frame (draw.glyphCount is the last draw's sample). Unculled: off-screen quads count.
        CgTrace.add(CgChannels.TEXT, "draw.quadsEmitted", emitted);
    }

    /** The shadow a paint step belongs to: outer ones before the text's four steps, inset ones after them. */
    private static int shadowAt(int paint, int shadows) {
        return paint < shadows ? shadows - 1 - paint : 2 * shadows + 3 - paint;
    }

    /**
     * What a stroked glyph draws in each of the text's two passes, so no glyph's ring covers another's fill. A ring
     * under the fill (or an outset one, which never overlaps it) is all of pass 1, the fill all of pass 2. Pass 1 is
     * Slate's: the whole shape in the stroke's colour, which an opaque fill covers with no seam at its edge; under a
     * fill that is not opaque, the ring alone, or it would show through. A centred or inset ring over the fill is
     * pass 2, alone, over pass 1's fill: its inner edge lies inside the fill, so it opens no seam either.
     */
    private static final int PASS_FILL = 0, PASS_SHAPE = 1, PASS_RING = 2;

    private static int glyphPass(int pass, boolean stroked, float strokeAlign, float strokeOver, int fillArgb) {
        if (!stroked) return PASS_FILL;
        boolean ringOver = strokeOver > 0f && strokeAlign != ALIGN_OUTSET;
        if (pass == 1) return ringOver ? PASS_FILL : (fillArgb >>> 24) == 0xFF ? PASS_SHAPE : PASS_RING;
        return ringOver ? PASS_RING : PASS_FILL;
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
        float node = CgPalette.pack(spatialNode, effectNode);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.quadLoop")) {
        // Per-iteration timing (-Dcrystalgraphics.text.traceQuadLoop=true): one blocking call inside the loop or
        // the thread preempted.
        long traceMaxNanos = 0, traceMaxIndex = -1, traceSlowIters = 0, traceIterStart = 0;
        if (Trace.QUAD_LOOP) traceIterStart = System.nanoTime();
        for (int s = 0; s < count; s++) {
            long key = scratchSortKeys[s];
            long batchBits = CgTextSortKey.batchOf(key);
            boolean isDecoration = CgTextSortKey.isDecoration(key);
            int localIndex = CgTextSortKey.localIndexOf(key);
            int paint = window + CgTextSortKey.stageOf(key);
            int shadow = paint >= shadows && paint <= shadows + 3 ? -1 : shadowAt(paint, shadows);

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
                // (widthPx, align, over, pxRange). pxRange is on every pass: a decoration's white
                // texel is reserved in the same banded atlas and carries that band's range, so it
                // batches with the glyphs around it instead of splitting them.
                int pass = glyphPass(paint - shadows, strokeArgb != 0 && strokeWidthTexels > 0f,
                        strokeAlign, strokeOver, rgba);
                c1w = pxRange;
                if (pass == PASS_FILL) {
                    c2 = p.key().getFontKey().getTargetPx();
                } else {
                    // The shader's stroked composite: the shape in the stroke's colour is that composite with
                    // the fill coloured as the stroke; the ring alone is it with the fill transparent.
                    rgba = pass == PASS_SHAPE ? strokeArgb : rgba & 0x00FFFFFF;
                    c0x = ((strokeArgb >> 16) & 0xFF) / 255f; c0y = ((strokeArgb >> 8) & 0xFF) / 255f;
                    c0z = (strokeArgb & 0xFF) / 255f; c0w = ((strokeArgb >>> 24) & 0xFF) / 255f;
                    c1x = strokeWidthTexels; c1y = alignCode(strokeAlign, draw.strokeRound && p.atlasType() == CgGlyphAtlas.Type.MTSDF); c1z = strokeOver;
                }
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
                        c1x = draw.shadowsStroked ? strokeWidthTexels : 0f;
                        c1y = alignCode(strokeAlign, draw.strokeRound && p.atlasType() == CgGlyphAtlas.Type.MTSDF);
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

            Bucket b = bucket(paint - shadows, batchBits, isDistanceField, atlasId);
            float[] q = b.reserve(spatialNode);
            int o = (b.count - 1) * RECORD;
            // As Quad.submit bakes a pose, through the same JOML calls, so the floats are the same.
            bakeOrigin.set(quadX, quadY, 0f);
            bakeRight.set(quadW, 0f, 0f);
            bakeUp.set(0f, quadH, 0f);
            modelView.transformPosition(bakeOrigin);
            modelView.transformDirection(bakeRight);
            modelView.transformDirection(bakeUp);
            q[o + R_ORIGIN] = bakeOrigin.x; q[o + R_ORIGIN + 1] = bakeOrigin.y; q[o + R_ORIGIN + 2] = bakeOrigin.z;
            q[o + R_RIGHT] = bakeRight.x; q[o + R_RIGHT + 1] = bakeRight.y; q[o + R_RIGHT + 2] = bakeRight.z;
            q[o + R_UP] = bakeUp.x; q[o + R_UP + 1] = bakeUp.y; q[o + R_UP + 2] = bakeUp.z;
            q[o + R_UV0] = u0; q[o + R_UV0 + 1] = v0;
            q[o + R_UV1] = u1; q[o + R_UV1 + 1] = v1;
            // Division, not a reciprocal multiply: CgBufferWriter.colorAt's exact floats.
            q[o + R_COLOR] = ((rgba >>> 16) & 0xFF) / 255f;
            q[o + R_COLOR + 1] = ((rgba >>> 8) & 0xFF) / 255f;
            q[o + R_COLOR + 2] = (rgba & 0xFF) / 255f;
            q[o + R_COLOR + 3] = ((rgba >>> 24) & 0xFF) / 255f;
            q[o + R_ATLAS_LAYER] = atlasLayer;
            q[o + R_CUSTOM2] = c2;
            q[o + R_CLIP] = clip;
            q[o + R_NODE] = node;
            q[o + R_CUSTOM0] = c0x; q[o + R_CUSTOM0 + 1] = c0y; q[o + R_CUSTOM0 + 2] = c0z; q[o + R_CUSTOM0 + 3] = c0w;
            q[o + R_CUSTOM1] = c1x; q[o + R_CUSTOM1 + 1] = c1y; q[o + R_CUSTOM1 + 2] = c1z; q[o + R_CUSTOM1 + 3] = c1w;

            if (Trace.QUAD_LOOP) {
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
        if (Trace.QUAD_LOOP) {
            CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.maxIterUs", traceMaxNanos / 1000.0);
            CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.maxIterIndex", traceMaxIndex);
            CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.slowIters", traceSlowIters);
        }
        }
    }

    // ── Buckets ─────────────────────────────────────────────────────────────
    // A batch's quads wait in a bucket per (paint rank, batch) and are drawn bucket by bucket when it flushes, so a
    // run of draws alternating atlases or shadow cells costs a transition per bucket rather than per draw. The rank
    // (a draw's paint step less its shadow count) means the same layer in every draw, which keeps each draw's own
    // layering; across draws in one batch every shadow lies beneath every glyph. A bucket holds final instance
    // records, so draining one is a copy per run of spatial node.

    private static final int RECORD = CgQuadRecord.FLOATS;
    private static final int R_ORIGIN = CgQuadRecord.ORIGIN, R_RIGHT = CgQuadRecord.RIGHT,
            R_UP = CgQuadRecord.UP, R_UV0 = CgQuadRecord.UV0, R_UV1 = CgQuadRecord.UV1,
            R_COLOR = CgQuadRecord.COLOR, R_ATLAS_LAYER = CgQuadRecord.ATLAS_LAYER,
            R_CUSTOM0 = CgQuadRecord.CUSTOM0, R_CUSTOM1 = CgQuadRecord.CUSTOM1,
            R_CUSTOM2 = CgQuadRecord.CUSTOM2, R_CLIP = CgQuadRecord.CLIP,
            R_NODE = CgQuadRecord.NODE;

    private static final class Bucket {
        int rank;
        long batchBits;
        boolean distanceField;
        int atlasId;
        /** Records in {@code CgQuadRecord}'s layout; padding is never written, so stays 0. */
        float[] records = new float[RECORD * 64];
        /** Per record, its spatial node: a run of one node is one copy. */
        int[] spatial = new int[64];
        int count;

        /** Room for one more record in {@code node}, counted; it is written at {@code (count - 1) * RECORD}. */
        float[] reserve(int node) {
            if (count == spatial.length) {
                spatial = Arrays.copyOf(spatial, count * 2);
                records = Arrays.copyOf(records, count * 2 * RECORD);
            }
            spatial[count++] = node;
            return records;
        }
    }

    private final List<Bucket> buckets = new ArrayList<>();
    private int bucketCount;
    @Nullable
    private Bucket lastBucket;

    // ── Replays ─────────────────────────────────────────────────────────────
    // A draw the same as one before it (its layout object, every Draw field, pose, projection, clip and nodes, the
    // tier it resolved to, no atlas eviction since) appends the records that one emitted, skipping glyph resolve,
    // the shadow plan, the sort and the quad loop. A HUD whose text changes a few times a second is these on most
    // frames. Kept REPLAY_FRAMES at most: resolving stamps each glyph's use, which the atlas evicts by.

    private static final int REPLAY_FRAMES = 30, MAX_REPLAYS = 1024;
    /** {@code -Dcrystalgraphics.text.replay=false} records every draw, for comparison. */
    private static final boolean REPLAYS = !"false".equals(System.getProperty("crystalgraphics.text.replay"));
    private final Map<CgTextLayout, Replay> replays = new IdentityHashMap<>();
    /** Each open bucket's count, and how many were open, when the draw being recorded began. */
    private int[] markCounts = new int[16];
    private int markBuckets;
    /** Flushes so far: one inside a draw resets the buckets, so that draw is not kept. */
    private long flushes;

    private static final class Replay {
        final Draw draw;
        final Matrix4f pose = new Matrix4f(), projection = new Matrix4f();
        int targetPx, clip, spatial, effect;
        boolean msdf;
        long evictions, frame;
        final List<Slice> slices = new ArrayList<>();

        Replay(Draw draw) {
            this.draw = draw;
        }

        boolean matches(Draw d, Matrix4f p, Matrix4f proj, int px, boolean df, int c, int s, int e, long now) {
            return now - frame < REPLAY_FRAMES && evictions == CgGlyphAtlas.evictions() && targetPx == px
                    && msdf == df && clip == c && spatial == s && effect == e
                    && pose.equals(p) && projection.equals(proj) && draw.sameAs(d);
        }
    }

    /** One bucket's share of a kept draw: its key and the records the draw put there. */
    private record Slice(int rank, long batchBits, boolean distanceField, int atlasId, float[] records, int[] spatial) {
    }

    private void markBuckets() {
        if (markCounts.length < bucketCount) markCounts = Arrays.copyOf(markCounts, bucketCount * 2);
        for (int i = 0; i < bucketCount; i++) markCounts[i] = buckets.get(i).count;
        markBuckets = bucketCount;
    }

    /** Keeps what the draw just recorded since {@link #markBuckets}, under its layout. */
    private void keepReplay(CgTextLayout layout, Draw draw, Matrix4f pose, int px, boolean msdf, long frame) {
        Replay replay = replays.get(layout);
        if (replay == null) {
            // Full: drop those past their frames, and keep nothing new while every one is live, rather than thrash
            if (replays.size() >= MAX_REPLAYS) replays.values().removeIf(r -> frame - r.frame >= REPLAY_FRAMES);
            if (replays.size() >= MAX_REPLAYS) return;
            replay = new Replay(new Draw(null));
            replays.put(layout, replay);
        }
        replay.draw.set(draw);
        replay.pose.set(pose);
        replay.projection.set(context.projection());
        replay.targetPx = px;
        replay.msdf = msdf;
        replay.clip = clip;
        replay.spatial = spatialNode;
        replay.effect = effectNode;
        replay.evictions = CgGlyphAtlas.evictions();
        replay.frame = frame;
        replay.slices.clear();
        for (int i = 0; i < bucketCount; i++) {
            Bucket b = buckets.get(i);
            int from = i < markBuckets ? markCounts[i] : 0;
            if (b.count <= from) continue;
            replay.slices.add(new Slice(b.rank, b.batchBits, b.distanceField, b.atlasId,
                    Arrays.copyOfRange(b.records, from * RECORD, b.count * RECORD),
                    Arrays.copyOfRange(b.spatial, from, b.count)));
        }
    }

    private void replay(Replay replay) {
        for (Slice slice : replay.slices) {
            Bucket b = bucket(slice.rank, slice.batchBits, slice.distanceField, slice.atlasId);
            int n = slice.spatial.length, need = b.count + n;
            if (need > b.spatial.length) {
                int grown = Math.max(need, b.spatial.length * 2);
                b.spatial = Arrays.copyOf(b.spatial, grown);
                b.records = Arrays.copyOf(b.records, grown * RECORD);
            }
            System.arraycopy(slice.spatial, 0, b.spatial, b.count, n);
            System.arraycopy(slice.records, 0, b.records, b.count * RECORD, n * RECORD);
            b.count = need;
        }
    }
    private final Vector3f bakeOrigin = new Vector3f(), bakeRight = new Vector3f(), bakeUp = new Vector3f();

    private Bucket bucket(int rank, long batchBits, boolean distanceField, int atlasId) {
        Bucket last = lastBucket;
        if (last != null && last.rank == rank && last.batchBits == batchBits) return last;
        for (int i = 0; i < bucketCount; i++) {
            Bucket b = buckets.get(i);
            if (b.rank == rank && b.batchBits == batchBits) return lastBucket = b;
        }
        if (bucketCount == buckets.size()) buckets.add(new Bucket());
        Bucket b = buckets.get(bucketCount++);
        b.rank = rank;
        b.batchBits = batchBits;
        b.distanceField = distanceField;
        b.atlasId = atlasId;
        b.count = 0;
        return lastBucket = b;
    }

    /** Insertion sort of the first {@code n} buckets by rank alone, stable, so equal ranks keep their first-seen order. */
    private void sortBuckets(int n) {
        for (int i = 1; i < n; i++) {
            Bucket b = buckets.get(i);
            int j = i - 1;
            while (j >= 0 && buckets.get(j).rank > b.rank) {
                buckets.set(j + 1, buckets.get(j));
                j--;
            }
            buckets.set(j + 1, b);
        }
    }

    /** Hands every bucket to the quad renderer, by rank then first seen, a transition between them. */
    private void drainBuckets() {
        int n = bucketCount;
        if (n == 0) return;
        // Cleared first: a transition flushes, and that flush must not drain again.
        bucketCount = 0;
        lastBucket = null;
        sortBuckets(n);
        CgTrace.add(CgChannels.TEXT, "draw.buckets", n);
        // The canvas recording on this thread -- a stage's turn, a paint context's frame -- else drawn at the flush.
        CgCanvas current = CgCanvas.current();
        CgCanvas into = current != null ? current : immediate;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.drainBuckets")) {
            for (int i = 0; i < n; i++) {
                Bucket b = buckets.get(i);
                if (b.batchBits != activeBatchBits) {
                    transitionToMaterial(b.batchBits, b.distanceField, b.atlasId);
                }
                int[] spatial = b.spatial;
                for (int from = 0, to; from < b.count; from = to) {
                    int node = spatial[from];
                    to = from + 1;
                    while (to < b.count && spatial[to] == node) to++;
                    into.quads(textMaterial, null, b.records, from, to - from, node);
                }
                b.count = 0;
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
     * The local-space offset that puts the glyph on a whole pixel on screen after {@code modelView}, X on the
     * sub-pixel grid. Screen text only: callers gate on {@link CgTextRenderContext#isWorldText()}.
     */
    private static void pixelSnapDelta(Matrix4f modelView, Matrix4f invModelView,
                                        float qx, float qy, Vector3f outLocalDelta) {
        outLocalDelta.set(qx, qy, 0f);
        modelView.transformPosition(outLocalDelta);
        // X rounds to the sub-pixel grid first, the bucket (CgGlyphKey.SUB_PIXEL_BUCKETS) taking the rest; flooring
        // separately is off by 0.75 px. Y floors.
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

    /** The layout of {@code text}, from {@link CgTextLayoutCache}; shared, never mutate it. */
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
