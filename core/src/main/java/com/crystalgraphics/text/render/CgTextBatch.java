package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgQuadRecord;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.layout.CgTextLayoutCache;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A canvas's text: what {@link CgCanvas#text()} draws through. Resolves a draw's glyphs from the font registry's
 * atlases (bitmap, MSDF or MTSDF, by size on screen), plans its shadows, and hands its quads to the canvas in paint
 * order: outer shadows, under-lines, the outline pass, the fill, line-through, inset shadows. Neighbouring quads of
 * one atlas and tier, within a draw or across draws, are one canvas run.
 *
 * <p>Engine: a canvas makes its own. Code that keeps glyphs on the GPU itself (world labels) makes one with no
 * canvas, gives it a {@link #camera}, and uses {@link #capture} and {@link #drawCaptured}:</p>
 *
 * <pre>{@code
 * CgTextBatch labels = new CgTextBatch(null);
 * labels.camera(projection, view, width, height);
 * boolean whole = labels.capture(label, kept);   // false: a glyph or shadow is still building
 * labels.drawCaptured(chunks, kept.batch(b), gpuRecords, kept.count(b), placements);
 * }</pre>
 *
 * <ul>
 *   <li>One thread at a time, as its canvas.</li>
 *   <li>Under a perspective camera a draw is world text: always distance-field, its tier from its size on
 *       screen, never re-wrapped, never pixel-snapped.</li>
 * </ul>
 */
public final class CgTextBatch {

    /** Its own class: the statics need a platform, and a variable is declared without one. */
    private static final class Trace {
        // A System.nanoTime() per quad: about 0.2 ms on a 1.1 ms loop.
        static final boolean QUAD_LOOP = CgVar.bool("crystalgraphics.text.traceQuadLoop", false,
                CgVar.DEV | CgVar.RESTART, "Time every iteration of text's quad-submission loop and report the slowest: tells"
                        + " a blocking call inside the loop from the render thread being descheduled. Costs a fifth of the loop.").get();
    }

    /** {@code -Dcrystalgraphics.text.replay=false} records every draw, for comparison. */
    private static final boolean REPLAYS = !"false".equals(System.getProperty("crystalgraphics.text.replay"));
    private static final int REPLAY_FRAMES = 30, MAX_REPLAYS = 1024;

    /** text.shader's instance kinds, carried as a negative custom0.w. @see text.shader Properties */
    private static final float KIND_FIELD_OUTER = -1f, KIND_FIELD_INSET = -2f, KIND_CELL = -3f,
            KIND_RECT_OUTER = -4f, KIND_RECT_INSET = -5f;

    /**
     * What a stroked glyph draws in each of the text's two passes, so no glyph's ring covers another's fill. A ring
     * under the fill (or an outset one, which never overlaps it) is all of pass 1, the fill all of pass 2. Pass 1 is
     * Slate's: the whole shape in the stroke's colour, which an opaque fill covers with no seam at its edge; under a
     * fill that is not opaque, the ring alone, or it would show through. A centred or inset ring over the fill is
     * pass 2, alone, over pass 1's fill: its inner edge lies inside the fill, so it opens no seam either.
     */
    private static final int PASS_FILL = 0, PASS_SHAPE = 1, PASS_RING = 2;

    private static final int RECORD = CgQuadRecord.FLOATS;
    private static final int R_ORIGIN = CgQuadRecord.ORIGIN, R_RIGHT = CgQuadRecord.RIGHT,
            R_UP = CgQuadRecord.UP, R_UV0 = CgQuadRecord.UV0, R_UV1 = CgQuadRecord.UV1,
            R_COLOR = CgQuadRecord.COLOR, R_ATLAS_LAYER = CgQuadRecord.ATLAS_LAYER,
            R_CUSTOM0 = CgQuadRecord.CUSTOM0, R_CUSTOM1 = CgQuadRecord.CUSTOM1,
            R_CUSTOM2 = CgQuadRecord.CUSTOM2, R_CLIP = CgQuadRecord.CLIP, R_NODE = CgQuadRecord.NODE;

    @Nullable
    private final CgCanvas canvas;
    private final CgFontRegistry registry = CgFontRegistry.get();
    private final CgTextDraw scratch;
    private final CgResolvedGlyphs resolvedGlyphs = new CgResolvedGlyphs(registry);
    private final CgTextShadowPlan shadowPlan = new CgTextShadowPlan(registry);

    /** Each font's last screen raster size and tier, which {@link CgTextRaster}'s hysteresis damps against. */
    private final Map<CgFontKey, RasterHistory> history = new HashMap<>();
    /** Whether this draw is under a perspective camera: world text. */
    private boolean world;

    private record RasterHistory(int effectiveTargetPx, boolean wasMsdf) {
    }

    /** A canvas-less batch's camera, from {@link #camera}. */
    private final Matrix4f ownProjection = new Matrix4f(), ownView = new Matrix4f();
    private float ownWidth = 1f, ownHeight = 1f;

    /**
     * This draw's projection times view; view times pose; and that in the target's pixels, which picks the raster
     * size and snaps: glyphs rasterise in device pixels whatever units the camera is in.
     */
    private final Matrix4f camera = new Matrix4f(), viewPose = new Matrix4f(), pixelPose = new Matrix4f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    private long degradedDraws;

    /** Sort keys, grown to the glyph count. */
    private long[] sortKeys = new long[0];
    private final Matrix4f inverseModelView = new Matrix4f();
    private final Vector3f localDelta = new Vector3f(), posePhase = new Vector3f();
    private final Vector3f bakeOrigin = new Vector3f(), bakeRight = new Vector3f(), bakeUp = new Vector3f();
    private float quadX, quadY, quadW, quadH;

    // ── This draw's output: records, cut into runs of one paint rank and batch ──
    private float[] records = new float[RECORD * 256];
    private int recordCount;
    private int[] runRank = new int[16], runAtlas = new int[16], runFrom = new int[16], runCount = new int[16];
    private long[] runBatch = new long[16];
    private boolean[] runDistanceField = new boolean[16];
    private int runs;

    @Nullable
    private CgTextCapture capturing;
    private final Map<CgTextLayout, Replay> replays = new IdentityHashMap<>();

    public CgTextBatch(@Nullable CgCanvas canvas) {
        this.canvas = canvas;
        this.scratch = new CgTextDraw(this, null);
    }

    /** The canvas's one scratch draw, reset. */
    public CgTextDraw draw() {
        return scratch.reset();
    }

    /** A draw of the caller's own on this batch, held across frames. */
    public CgTextDraw retained() {
        return new CgTextDraw(this, null);
    }

    /** Draws {@code draw}, whoever made it. @return whether everything drew as asked */
    public boolean draw(CgTextDraw draw) {
        draw.validate("submit");
        if (canvas == null) throw new IllegalStateException("a text batch with no canvas draws nothing: capture instead");
        long before = degradedDraws;
        drawInternal(draw, poseOf(draw));
        return degradedDraws == before;
    }

    /** How many draws resolved a glyph or shadow below what they asked for. Rises only. */
    public long degradedDraws() {
        return degradedDraws;
    }

    /** The camera of a batch with no canvas: what its captures are culled and tiered under. */
    public CgTextBatch camera(Matrix4fc projection, Matrix4fc view, float width, float height) {
        ownProjection.set(projection);
        ownView.set(view);
        ownWidth = Math.max(1f, width);
        ownHeight = Math.max(1f, height);
        return this;
    }

    CgTextLayout measure(CgTextDraw draw) {
        Matrix4f pose = poseOf(draw);
        useCamera(pose);
        return resolveDraw(draw).layout();
    }

    private static Matrix4f poseOf(CgTextDraw draw) {
        Matrix4f pose = draw.poseNow();
        return pose != null ? pose : IDENTITY;
    }

    /** Takes the camera this draw is under, and picks its space. */
    private void useCamera(Matrix4f pose) {
        Matrix4fc projection, view;
        float w, h;
        if (canvas != null) {
            projection = canvas.cameraProjection();
            view = canvas.cameraView();
            w = canvas.cameraWidth();
            h = canvas.cameraHeight();
        } else {
            projection = ownProjection;
            view = ownView;
            w = ownWidth;
            h = ownHeight;
        }
        // A perspective projection divides by depth: its w row reads z.
        world = projection.m23() != 0f;
        camera.set(projection).mul(view);
        viewPose.set(view).mul(pose);
        if (!world && w > 1f && h > 1f) {
            // An orthographic projection's units to the target's pixels, per axis.
            pixelPose.scaling(Math.abs(projection.m00()) * w * 0.5f, Math.abs(projection.m11()) * h * 0.5f, 1f).mul(viewPose);
        } else {
            pixelPose.set(viewPose);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Capture: world labels' glyphs kept on the GPU
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Records {@code draw}'s quads into {@code into} instead of drawing them, in the draw's own space: its pose is not
     * applied and nothing is culled. Draw them, frame after frame, with {@link #drawCaptured}.
     *
     * <p>The tier is the camera's for an identity pose, so capture only where the tier does not follow the pose:
     * under a perspective camera, whose tier is fixed.</p>
     *
     * @return whether everything drew as asked; false while a glyph or shadow is still building, the capture then
     *         holding what was ready
     */
    public boolean capture(CgTextDraw draw, CgTextCapture into) {
        draw.validate("capture");
        long before = degradedDraws;
        into.begin(CgGlyphAtlas.evictions());
        capturing = into;
        try {
            drawInternal(draw, IDENTITY);
        } finally {
            capturing = null;
        }
        for (int r = 0; r < runs; r++) {
            into.add(runRank[r], runBatch[r], records, runFrom[r], runCount[r]);
        }
        runs = 0;
        recordCount = 0;
        return degradedDraws == before;
    }

    /**
     * Draws {@code count} captured records of {@code batch} ({@link CgTextCapture#batch}) from {@code records} into
     * {@code chunks}' open chunk, each placed by the model matrix of the object record its {@code node} names in
     * {@code labels}: one draw of {@code text.shader}'s {@code WORLD_LABEL} variant, under the pass's projection.
     *
     * <pre>{@code
     * chunks.bindings(recording.bindings()).begin(0, 0, 0);
     * text.drawCaptured(chunks, batch, glyphs, glyphCount, labels);   // labels: CgInstanceKind.OBJECT records
     * canvas.add(chunks.end());
     * }</pre>
     *
     * <ul>
     *   <li>{@code records} and {@code labels} need {@code STORAGE}; a graph buffer is read after the pass writing it.</li>
     *   <li>A record's {@code node} is an index into {@code labels}, as a float; the placement holds the view.</li>
     * </ul>
     */
    public static void drawCaptured(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count,
                                    CgBufferHandle labels) {
        drawCaptured(chunks, batch, records, count, labels, false);
    }

    /**
     * {@link #drawCaptured}'s depth pass: the records' solid core (alpha 0.5 and up) into depth alone. Drawn for every
     * label's text before any label's colour, it makes a nearer label's text hide a farther one's in any order.
     */
    public static void drawCapturedDepth(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count,
                                         CgBufferHandle labels) {
        drawCaptured(chunks, batch, records, count, labels, true);
    }

    private static void drawCaptured(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count,
                                     CgBufferHandle labels, boolean depthPass) {
        if (count <= 0) return;
        CgMaterial material = CgTextMaterials.label(CgTextSortKey.isDistanceField(batch), depthPass, CgTextGamma.initial());
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgBindingTable table = chunks.bindings();
        int bindings = table.withTexture(material.captureBindings(table), 0,
                CgGlyphAtlas.texture(CgTextSortKey.atlasIdOf(batch)));
        pipeline = pipeline.withState(labelState(pipeline.state(), depthPass));
        chunks.draw(pipeline, bindings, CgMesh.quads(1)).objects(labels, 0, count)
                .buffer(CgBindingPoints.QUAD_RENDERER, records);
    }

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

    private static CgRenderState labelStateFrom, labelDepthState, labelColorState;

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Resolve
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** What a draw and its measure share, so a measure never drifts from the draw. */
    private record ResolvedDraw(CgFontKey fontKey, int effectiveTargetPx, boolean wantMsdf, CgTextLayout layout) {
    }

    /** Font, raster tier and layout for {@code draw}, wrapped for the pose; {@link #useCamera} first. */
    private ResolvedDraw resolveDraw(CgTextDraw draw) {
        CgFontFamily resolvedFamily = null;
        CgFont resolvedFont = null;
        CgFontKey fontKey;
        if (draw.family != null) {
            resolvedFamily = draw.targetPx > 0 ? sizeFamily(draw.family, draw.targetPx) : draw.family;
            fontKey = resolvedFamily.getPrimarySource().getKey();
        } else if (draw.font != null) {
            if (draw.targetPx > 0) {
                resolvedFont = requireSizedFont(draw.font, draw.targetPx);
            } else if (draw.layout == null && draw.paragraph == null) {
                resolvedFont = requireSizedFont(draw.font);   // a layout built from text needs a sized font
            } else {
                resolvedFont = draw.font;
            }
            resolvedFamily = CgFontFamily.of(resolvedFont);
            fontKey = resolvedFamily.getPrimarySource().getKey();
        } else {
            // A laid-out layout names its fonts: its first glyph's stands for the raster history.
            CgFontKey[] bakedKeys = draw.layout.baked().fontKeys();
            if (bakedKeys.length == 0) {
                throw new IllegalStateException("a text draw needs font(...) or family(...) when its layout has no glyphs");
            }
            fontKey = bakedKeys[0];
        }

        int basePx = fontKey.getTargetPx(), effectiveTargetPx;
        boolean sizeWantsMsdf;
        if (world) {
            effectiveTargetPx = CgTextRaster.worldPx(basePx);
            sizeWantsMsdf = true;
        } else {
            RasterHistory previous = history.get(fontKey);
            int previousPx = previous != null ? previous.effectiveTargetPx() : -1;
            effectiveTargetPx = CgTextRaster.screenPx(basePx, CgTextRaster.maxScale(pixelPose), previousPx);
            boolean previousMsdf = previous != null ? previous.wasMsdf() : effectiveTargetPx >= 32;
            sizeWantsMsdf = CgTextRaster.msdf(effectiveTargetPx, previousMsdf);
            // The size-derived decision, not the forced ones below: the hysteresis damps size changes only.
            if (previous == null || previous.effectiveTargetPx() != effectiveTargetPx || previous.wasMsdf() != sizeWantsMsdf) {
                history.put(fontKey, new RasterHistory(effectiveTargetPx, sizeWantsMsdf));
            }
        }

        boolean wantMsdf = sizeWantsMsdf;
        if (!wantMsdf && !CgTextRaster.isAxisAligned(pixelPose)) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByTransform", 1);
        }
        // A stroke needs a distance field, down to the size the field can antialias.
        if (!wantMsdf && draw.strokeRequested()
                && effectiveTargetPx >= registry.getResolvedMsdfConfig(fontKey).minAntialiasablePx()) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByStroke", 1);
        }

        CgTextLayout layout;
        if (draw.layout != null) {
            layout = draw.layout;
        } else {
            // Constraints are screen pixels: divided by the pose's own scale, not effective/base px, which is clamped
            // at CgTextRaster.MAX_PX. Never for world text, which must not reflow with the camera.
            float scale = world ? 1f : CgTextRaster.maxScale(viewPose);
            float maxWidth = scaleConstraint(draw.maxWidth, scale), maxHeight = scaleConstraint(draw.maxHeight, scale);
            if (draw.paragraph != null) layout = draw.paragraph.layout(maxWidth, maxHeight);
            else if (resolvedFamily != null && draw.family != null) layout = layout(draw.text, resolvedFamily, maxWidth, maxHeight);
            else layout = layout(draw.text, resolvedFont, maxWidth, maxHeight);
        }
        return new ResolvedDraw(fontKey, effectiveTargetPx, wantMsdf, layout);
    }

    /** {@code value / scale}, unbounded staying unbounded and a scale of about 1 skipped. */
    private static float scaleConstraint(float value, float scale) {
        if (value <= 0f || scale <= 0f || Math.abs(scale - 1f) < 0.0001f) return value;
        return value / scale;
    }

    /** Resolves {@code draw}, then replays it or records and lands its quads. */
    private void drawInternal(CgTextDraw draw, Matrix4f pose) {
        useCamera(pose);
        ResolvedDraw resolved = resolveDraw(draw);
        CgTextLayout layout = resolved.layout();
        runs = 0;
        recordCount = 0;
        if (layout == null || layout.lines().isEmpty()) return;
        float originX = draw.originX(), originY = draw.originY();

        // Before resolving: an off-screen layout otherwise pays for every glyph first. See CgTextCuller.
        if (capturing != null) {
            capturing.layout(layout);
        } else if (draw.spatialNode == 0 && !visible(layout, originX, originY, pose, draw.shadows.reach())) {
            CgTrace.add(CgChannels.TEXT, "text.drawsCulled", 1);
            return;
        }

        long frame = CgFrameClock.frameIndex();
        if (REPLAYS && capturing == null) {
            Replay replay = replays.get(layout);
            if (replay != null && replay.matches(draw, pose, camera, resolved.effectiveTargetPx(), resolved.wantMsdf(), frame)) {
                land(draw, replay.records, replay);
                CgTrace.add(CgChannels.TEXT, "draw.replayed", 1);
                return;
            }
        }
        long degradedBefore = degradedDraws;
        recordDraw(draw, pose, resolved, originX, originY, frame);
        if (capturing != null) return;
        if (REPLAYS && degradedDraws == degradedBefore) keepReplay(layout, draw, pose, resolved, frame);
        land(draw, records, null);
    }

    /**
     * Whether the layout can reach the canvas's target. Glyphs overhang their box (bearings, descenders, italic shear,
     * underlines), so the box grows by a layout height and the shadows' reach on every side: a rare, position-dependent
     * clip at the view's edge otherwise. Per draw, never per glyph, which costs more than the quad it saves.
     */
    private boolean visible(CgTextLayout layout, float x, float y, Matrix4f pose, float reach) {
        float w = layout.totalWidth(), h = layout.totalHeight();
        if (!(w > 0f) || !(h > 0f)) return true;
        float slack = h + Math.max(0f, reach);
        return canvas.visible(pose, x - slack, y - slack, x + w + slack, y + h + slack);
    }

    /** Hands this draw's runs to the canvas, each under its tier's material and its atlas. */
    private void land(CgTextDraw draw, float[] src, @Nullable Replay replay) {
        int n = replay != null ? replay.runs : runs;
        CgTrace.add(CgChannels.TEXT, "draw.runs", n);
        for (int r = 0; r < n; r++) {
            boolean df = replay != null ? replay.distanceField[r] : runDistanceField[r];
            int atlasId = replay != null ? replay.atlas[r] : runAtlas[r];
            int from = replay != null ? replay.from[r] : runFrom[r];
            int count = replay != null ? replay.count[r] : runCount[r];
            canvas.quads(CgTextMaterials.of(df, !world, draw.gamma), null, CgGlyphAtlas.texture(atlasId), src, from, count,
                    draw.spatialNode);
        }
    }

    /** The draw's glyphs resolved and its quads recorded into {@link #records}, past the cull and a replay. */
    private void recordDraw(CgTextDraw draw, Matrix4f pose, ResolvedDraw resolved, float originX, float originY,
                            long frame) {
        CgFontKey fontKey = resolved.fontKey();
        int effectiveTargetPx = resolved.effectiveTargetPx();
        boolean wantMsdf = resolved.wantMsdf();
        CgTextLayout layout = resolved.layout();
        int glyphCount;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "glyph.resolveGlyphs")) {
            // The pose's own sub-pixel phase: a transform moves text through the pose, not through at().
            posePhase.set(originX, originY, 0f);
            pixelPose.transformPosition(posePhase);
            // Under the registry's lock, so a glyph a worker commits cannot land halfway through one resolve.
            synchronized (registry) {
                glyphCount = resolvedGlyphs.resolve(layout, originX, originY, frame, world,
                        effectiveTargetPx, wantMsdf, fontKey, draw.rgba, posePhase.x);
            }
        }
        CgTrace.counter(CgChannels.TEXT, "draw.glyphCount", glyphCount);

        // A glyph not yet in the atlas, or refused by the budget at its tier, makes the draw degraded: paint again.
        for (int i = 0; i < glyphCount; i++) {
            CgGlyphPlacement placement = resolvedGlyphs.placements[i];
            if (placement == null || (wantMsdf && placement.hasGeometry() && !placement.isDistanceField())) {
                degradedDraws++;
                break;
            }
        }

        // A stroke the tier cannot draw resolves to none here, so a caller may always set one.
        boolean stroked = wantMsdf && draw.strokeRequested();
        // In the glyph's own texels: painted in local space and transformed with the text, the only form an
        // anisotropic transform can carry.
        float strokeTexels = stroked ? draw.strokeWidthEm * registry.getResolvedMsdfConfig(fontKey).atlasScalePx() : 0f;

        if (draw.shadows.count() > 0 && glyphCount > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.planShadows")) {
                boolean degraded;
                synchronized (registry) {
                    degraded = shadowPlan.plan(draw.shadows, resolvedGlyphs.placements, resolvedGlyphs.cached,
                            layout.baked(), glyphCount, fontKey, effectiveTargetPx,
                            draw.shadowsStroked ? strokeTexels : 0f, draw.strokeAlign, world, frame);
                }
                if (degraded) degradedDraws++;
            }
        }

        CgTextDecorationRect[] decorations = layout.baked().decorations();
        if (glyphCount > 0 || decorations.length > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.submitSortedQuads")) {
                sortAndRecord(glyphCount, decorations, fontKey.getTargetPx(), effectiveTargetPx, wantMsdf, draw, pose,
                        stroked ? draw.strokeArgb : 0, strokeTexels);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Sort and record
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * One draw's glyphs, decorations and shadows as quads, sorted by paint step, then atlas and mode
     * ({@link CgTextSortKey}), so its runs are as few as its states. An underline runs with its font's glyphs.
     */
    private void sortAndRecord(int glyphCount, CgTextDecorationRect[] decorations, int baseTargetPx,
                               int effectiveTargetPx, boolean wantMsdf, CgTextDraw draw, Matrix4f pose,
                               int strokeArgb, float strokeTexels) {
        CgGlyphPlacement[] placements = resolvedGlyphs.placements;
        CgTextShadowList shadowList = draw.shadows;
        int shadows = shadowList.count();

        List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "glyph.resolveDecorations")) {
            synchronized (registry) {
                resolvedDecorations = resolvedGlyphs.resolveDecorations(decorations, draw.originX(), draw.originY(),
                        draw.rgba, effectiveTargetPx, wantMsdf);
            }
        }

        // An upper bound: the text's two passes, then every shadow's glyphs and decorations again.
        int perStage = glyphCount + resolvedDecorations.size();
        if (perStage == 0) return;
        int keys = perStage * (2 + shadows);
        if (keys > sortKeys.length) sortKeys = Arrays.copyOf(sortKeys, Math.max(keys, sortKeys.length * 2));
        boolean stroked = strokeArgb != 0 && strokeTexels > 0f;

        // Snap to pixels on screen only: in the world a block spans under a unit and every glyph would floor together.
        boolean pixelSnap = !world;
        if (pixelSnap) inverseModelView.set(pixelPose).invert();
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
                                sortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                            }
                        }
                        continue;
                    }
                    if (paint == textPaint || paint == textPaint + 1) {
                        if (paint == textPaint && !stroked) continue;
                        for (int i = 0; i < glyphCount; i++) {
                            CgGlyphPlacement p = placements[i];
                            if (p != null && p.hasGeometry()) sortKeys[count++] = CgTextSortKey.forGlyph(p, i, stage);
                        }
                        continue;
                    }
                    if (paint == textPaint + 2) {
                        for (int i = 0; i < resolvedDecorations.size(); i++) {
                            if (!resolvedDecorations.get(i).underText()) {
                                sortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                            }
                        }
                        continue;
                    }
                    int shadow = shadowAt(paint, shadows);
                    if (shadowList.inset(shadow) != (paint > textPaint + 2) || !shadowList.casts(shadow)) continue;
                    for (int i = 0; i < glyphCount; i++) {
                        if (shadowPlan.kind(shadow, i) != CgTextShadowPlan.NONE) {
                            sortKeys[count++] = CgTextSortKey.forGlyph(shadowPlan.placement(shadow, i), i, stage);
                        }
                    }
                    if (shadowList.scoped(shadow)) continue;
                    for (int i = 0; i < resolvedDecorations.size(); i++) {
                        sortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                    }
                }
                Arrays.sort(sortKeys, 0, count);
            }
            emitted += count;
            recordSorted(count, window, shadowList, resolvedDecorations, placements, draw, baseTargetPx,
                    effectiveTargetPx, devicePerLocal, pixelSnap, pose, strokeArgb, strokeTexels);
        }
        // A counter over the frame (draw.glyphCount is the last draw's sample). Unculled: off-screen quads count.
        CgTrace.add(CgChannels.TEXT, "draw.quadsEmitted", emitted);
    }

    /** The shadow a paint step belongs to: outer ones before the text's four steps, inset ones after them. */
    private static int shadowAt(int paint, int shadows) {
        return paint < shadows ? shadows - 1 - paint : 2 * shadows + 3 - paint;
    }

    private static int glyphPass(int pass, boolean stroked, float strokeAlign, float strokeOver, int fillArgb) {
        if (!stroked) return PASS_FILL;
        boolean ringOver = strokeOver > 0f && strokeAlign != CgTextDraw.ALIGN_OUTSET;
        if (pass == 1) return ringOver ? PASS_FILL : (fillArgb >>> 24) == 0xFF ? PASS_SHAPE : PASS_RING;
        return ringOver ? PASS_RING : PASS_FILL;
    }

    /** {@code text.shader}'s align slot: the align, plus 3 for a round join. */
    private static float alignCode(float align, boolean round) {
        return round ? align + 3f : align;
    }

    private void recordSorted(int count, int window, CgTextShadowList shadowList,
                              List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations,
                              CgGlyphPlacement[] placements, CgTextDraw draw,
                              int baseTargetPx, int effectiveTargetPx, float devicePerLocal,
                              boolean pixelSnap, Matrix4f pose, int strokeArgb, float strokeTexels) {
        int shadows = shadowList.count();
        float strokeAlign = draw.strokeAlign, strokeOver = draw.strokeOver;
        float node = CgPalette.pack(draw.spatialNode, draw.effectNode);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.quadLoop")) {
            // Per-iteration timing (-Dcrystalgraphics.text.traceQuadLoop=true): one blocking call inside the loop or
            // the thread preempted.
            long traceMaxNanos = 0, traceMaxIndex = -1, traceSlowIters = 0, traceIterStart = 0;
            if (Trace.QUAD_LOOP) traceIterStart = System.nanoTime();
            for (int s = 0; s < count; s++) {
                long key = sortKeys[s];
                long batchBits = CgTextSortKey.batchOf(key);
                boolean isDecoration = CgTextSortKey.isDecoration(key);
                int localIndex = CgTextSortKey.localIndexOf(key);
                int paint = window + CgTextSortKey.stageOf(key);
                int shadow = paint >= shadows && paint <= shadows + 3 ? -1 : shadowAt(paint, shadows);

                CgGlyphPlacement p;
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
                        c1x = strokeTexels; c1y = strokeAlign; c1z = strokeOver; c1w = pxRange;
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
                    placeGlyph(p, localIndex, baseTargetPx, effectiveTargetPx, pixelSnap && !p.isDistanceField(), 0f, 0f);
                    u0 = p.u0(); v0 = p.v0(); u1 = p.u1(); v1 = p.v1();
                    rgba = resolvedGlyphs.argbColor[localIndex];
                    atlasLayer = p.atlasPageIndex();
                    // custom0 the outline's colour, custom1 (width, align, over, pxRange); pxRange on every pass, so a
                    // decoration's white texel in the same banded atlas runs with the glyphs around it.
                    int pass = glyphPass(paint - shadows, strokeArgb != 0 && strokeTexels > 0f, strokeAlign, strokeOver, rgba);
                    c1w = pxRange;
                    if (pass == PASS_FILL) {
                        c2 = p.key().getFontKey().getTargetPx();
                    } else {
                        // The stroked composite: the shape in the stroke's colour, or the ring alone with the fill clear.
                        rgba = pass == PASS_SHAPE ? strokeArgb : rgba & 0x00FFFFFF;
                        c0x = ((strokeArgb >> 16) & 0xFF) / 255f; c0y = ((strokeArgb >> 8) & 0xFF) / 255f;
                        c0z = (strokeArgb & 0xFF) / 255f; c0w = ((strokeArgb >>> 24) & 0xFF) / 255f;
                        c1x = strokeTexels;
                        c1y = alignCode(strokeAlign, draw.strokeRound && p.atlasType() == CgGlyphAtlas.Type.MTSDF);
                        c1z = strokeOver;
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
                    // A shadow snaps exactly when its text does, so the two stay on one grid; a cell's plane bounds are
                    // device pixels of the glyph's raster, so a downsampled cell stretches back over its extent.
                    placeGlyph(p, localIndex, baseTargetPx, effectiveTargetPx,
                            pixelSnap && !placements[localIndex].isDistanceField(), ox, oy);
                    if (kind == CgTextShadowPlan.CELL) {
                        c0w = KIND_CELL;
                    } else {
                        c1w = pxRange;
                        if (kind == CgTextShadowPlan.FIELD_OUTER || kind == CgTextShadowPlan.FIELD_INSET) {
                            c1x = draw.shadowsStroked ? strokeTexels : 0f;
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

                int o = reserve(paint - shadows, batchBits, isDistanceField, atlasId);
                float[] q = records;
                // As Quad.submit bakes a pose, through the same JOML calls, so the floats are the same.
                bakeOrigin.set(quadX, quadY, 0f);
                bakeRight.set(quadW, 0f, 0f);
                bakeUp.set(0f, quadH, 0f);
                pose.transformPosition(bakeOrigin);
                pose.transformDirection(bakeRight);
                pose.transformDirection(bakeUp);
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
                q[o + R_CLIP] = draw.clip;
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
            }
            if (Trace.QUAD_LOOP) {
                CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.maxIterUs", traceMaxNanos / 1000.0);
                CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.maxIterIndex", traceMaxIndex);
                CgTrace.counter(CgChannels.TEXT, "draw.quadLoop.slowIters", traceSlowIters);
            }
        }
    }

    /** Room for one record, in the run of {@code (rank, batchBits)}; returns its float offset. */
    private int reserve(int rank, long batchBits, boolean distanceField, int atlasId) {
        if (runs == 0 || runRank[runs - 1] != rank || runBatch[runs - 1] != batchBits) {
            if (runs == runRank.length) {
                int n = runs * 2;
                runRank = Arrays.copyOf(runRank, n);
                runAtlas = Arrays.copyOf(runAtlas, n);
                runFrom = Arrays.copyOf(runFrom, n);
                runCount = Arrays.copyOf(runCount, n);
                runBatch = Arrays.copyOf(runBatch, n);
                runDistanceField = Arrays.copyOf(runDistanceField, n);
            }
            runRank[runs] = rank;
            runBatch[runs] = batchBits;
            runDistanceField[runs] = distanceField;
            runAtlas[runs] = atlasId;
            runFrom[runs] = recordCount;
            runCount[runs] = 0;
            runs++;
        }
        if ((recordCount + 1) * RECORD > records.length) records = Arrays.copyOf(records, records.length * 2);
        runCount[runs - 1]++;
        return recordCount++ * RECORD;
    }

    /**
     * A glyph's quad from its placement, moved by {@code (dx, dy)} local pixels, into {@link #quadX} and friends,
     * snapped to whole device pixels after the move when {@code snap} is set.
     */
    private void placeGlyph(CgGlyphPlacement p, int localIndex, int baseTargetPx, int effectiveTargetPx,
                            boolean snap, float dx, float dy) {
        int placementTargetPx = p.key().getFontKey().getTargetPx();
        // A shadow cell keeps the raster size it was built at, which one standing in for a cell still building at a
        // new size does not share with this draw.
        boolean ownRaster = p.isDistanceField() || p.key().getShadowCell() != null;
        float scale = CgResolvedGlyphs.logicalMetricScale(baseTargetPx, ownRaster ? placementTargetPx : effectiveTargetPx);
        // Plane bounds are in raster space: normalised to the layout's. Bearings are from the pen, Y down.
        float bearingX = p.planeLeft() * scale, bearingY = p.planeTop() * scale;
        quadW = p.getPlaneWidth() * scale;
        quadH = p.getPlaneHeight() * scale;
        float baseline = resolvedGlyphs.originY + resolvedGlyphs.glyphY[localIndex] + dy;
        quadX = resolvedGlyphs.originX + resolvedGlyphs.glyphX[localIndex] + bearingX + dx;
        quadY = baseline - bearingY;
        // From the line's baseline, so every glyph on a line takes the same correction.
        if (snap) {
            pixelSnapDelta(pixelPose, inverseModelView, quadX, baseline, localDelta);
            quadX += localDelta.x;
            quadY += localDelta.y;
        }
    }

    /**
     * The local-space offset that puts the glyph on a whole pixel on screen after {@code modelView}, X on the
     * sub-pixel grid. Screen text only.
     */
    private static void pixelSnapDelta(Matrix4f modelView, Matrix4f invModelView, float qx, float qy, Vector3f out) {
        out.set(qx, qy, 0f);
        modelView.transformPosition(out);
        // X rounds to the sub-pixel grid first, the bucket (CgGlyphKey.SUB_PIXEL_BUCKETS) taking the rest; flooring
        // separately is off by 0.75 px. Y floors.
        int quartersX = Math.round(out.x * CgGlyphKey.SUB_PIXEL_BUCKETS);
        float snappedX = (float) Math.floor(quartersX / (double) CgGlyphKey.SUB_PIXEL_BUCKETS);
        out.set(snappedX - out.x, (float) Math.floor(out.y) - out.y, 0f);
        invModelView.transformDirection(out);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Replays
    // ══════════════════════════════════════════════════════════════════════════════════════════
    // A draw the same as one before it (its layout object, every field, pose, camera, the tier it resolved to, no
    // atlas eviction since) lands the records that one recorded, skipping glyph resolve, the shadow plan, the sort and
    // the quad loop. Kept REPLAY_FRAMES at most: resolving stamps each glyph's use, which the atlas evicts by.

    private static final class Replay {
        final CgTextDraw draw = CgTextDraw.detached();
        final Matrix4f pose = new Matrix4f(), camera = new Matrix4f();
        int targetPx, runs;
        boolean msdf;
        long evictions, frame;
        float[] records = new float[0];
        int[] from = new int[0], count = new int[0], atlas = new int[0];
        boolean[] distanceField = new boolean[0];

        boolean matches(CgTextDraw d, Matrix4f p, Matrix4f cam, int px, boolean df, long now) {
            return now - frame < REPLAY_FRAMES && evictions == CgGlyphAtlas.evictions() && targetPx == px
                    && msdf == df && pose.equals(p) && camera.equals(cam) && draw.sameAs(d);
        }
    }

    private void keepReplay(CgTextLayout layout, CgTextDraw draw, Matrix4f pose, ResolvedDraw resolved, long frame) {
        Replay replay = replays.get(layout);
        if (replay == null) {
            // Full: drop those past their frames, and keep nothing new while every one is live, rather than thrash.
            if (replays.size() >= MAX_REPLAYS) replays.values().removeIf(r -> frame - r.frame >= REPLAY_FRAMES);
            if (replays.size() >= MAX_REPLAYS) return;
            replay = new Replay();
            replays.put(layout, replay);
        }
        replay.draw.set(draw);
        replay.pose.set(pose);
        replay.camera.set(camera);
        replay.targetPx = resolved.effectiveTargetPx();
        replay.msdf = resolved.wantMsdf();
        replay.evictions = CgGlyphAtlas.evictions();
        replay.frame = frame;
        int floats = recordCount * RECORD;
        if (replay.records.length < floats) replay.records = new float[floats];
        System.arraycopy(records, 0, replay.records, 0, floats);
        if (replay.from.length < runs) {
            replay.from = new int[runs];
            replay.count = new int[runs];
            replay.atlas = new int[runs];
            replay.distanceField = new boolean[runs];
        }
        System.arraycopy(runFrom, 0, replay.from, 0, runs);
        System.arraycopy(runCount, 0, replay.count, 0, runs);
        System.arraycopy(runAtlas, 0, replay.atlas, 0, runs);
        System.arraycopy(runDistanceField, 0, replay.distanceField, 0, runs);
        replay.runs = runs;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Layouts and fonts
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
