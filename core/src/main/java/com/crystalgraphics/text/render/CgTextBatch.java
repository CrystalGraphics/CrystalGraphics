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
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgQuadRecord;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.layout.CgTextLayoutCache;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextPaint;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A canvas's text: what {@link CgCanvas#text()} draws through. Resolves a draw's glyphs from the font registry's
 * atlases (bitmap, MSDF or MTSDF, by size on screen), plans its shadows, and hands its quads to the canvas in its
 * style's order: each layer, bottom first, the under-lines before the first fill or outline and line-through after the
 * last fill. Neighbouring quads of one atlas, tier and layer material, within a draw or across draws, are one canvas
 * run.
 *
 * <p>Engine: a canvas makes its own. Code that keeps glyphs on the GPU itself (world labels) makes one with no
 * canvas, gives it a {@link #camera}, and uses {@link #capture} and {@link #drawCaptured}:</p>
 *
 * <pre>{@code
 * CgTextBatch labels = new CgTextBatch(null);
 * labels.camera(projection, view, width, height);
 * boolean whole = labels.capture(label, kept);   // false: a glyph or shadow is still building
 * labels.drawCaptured(chunks, kept.batch(b), gpuRecords, kept.count(b), placements, linear);
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
    private static final int MOTION_IDLE_FRAMES = 120, MAX_MOTIONS = 1024;

    /** text.shader's instance kinds, carried as a negative custom0.w. @see text.shader Properties */
    private static final float KIND_FIELD_OUTER = -1f, KIND_FIELD_INSET = -2f, KIND_CELL = -3f,
            KIND_RECT_OUTER = -4f, KIND_RECT_INSET = -5f;

    /** Paint steps besides a style's layers: the decorations under the text, and over it. */
    static final int STEP_UNDER = -1, STEP_OVER = -2;

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
    /** This draw's shadow layers, gathered from its style. */
    private final CgTextShadowList shadowList = new CgTextShadowList();
    /** Per layer of this draw's style: its shadow in {@link #shadowList}, or -1; its gradient's ramp row, or 0. */
    private int[] layerShadow = new int[8];
    private float[] layerRow = new float[8];
    /** Per layer: whether an outline draws its ring alone (a fill is under it), or the whole grown glyph. */
    private boolean[] layerRing = new boolean[8];
    /** This draw's paint steps, bottom first: a layer's index, {@link #STEP_UNDER} or {@link #STEP_OVER}. */
    private int[] steps = new int[16];
    private int stepCount;

    /** Each font's last screen raster size and tier, which {@link CgTextRaster}'s hysteresis damps against. */
    private final Map<CgTextLayout, CgTextRasterMotion> motions = new IdentityHashMap<>();
    /** Whether this draw is under a perspective camera: world text. */
    private boolean world;

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
    private int[] runRank = new int[16], runAtlas = new int[16], runFrom = new int[16], runCount = new int[16],
            runLayer = new int[16];
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
        List<CgTextLayer> layers = draw.style.layers();
        for (int i = 0; i < layers.size(); i++) {
            CgTextLayer l = layers.get(i);
            if (l.blend() != CgTextLayer.Blend.ALPHA || l.paint().kind() == CgTextPaint.Kind.MATERIAL) {
                throw new IllegalStateException("a world label draws colour and gradient layers over the scene alone: "
                        + l + " of " + draw.style + " is additive or a material");
            }
        }
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
     * boolean linear = CgTextBatch.blendsLinearly(stage.target());
     * text.drawCaptured(chunks, batch, glyphs, glyphCount, labels, linear);   // labels: CgInstanceKind.OBJECT records
     * canvas.add(chunks.end());
     * }</pre>
     *
     * <ul>
     *   <li>{@code records} and {@code labels} need {@code STORAGE}; a graph buffer is read after the pass writing it.</li>
     *   <li>A record's {@code node} is an index into {@code labels}, as a float; the placement holds the view.</li>
     *   <li>{@code linearTarget}: the pass draws into a float target ({@link #blendsLinearly}), whose fills take the
     *       linear-blend correction in place of text gamma.</li>
     * </ul>
     */
    public static void drawCaptured(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count,
                                    CgBufferHandle labels, boolean linearTarget) {
        drawCaptured(chunks, batch, records, count, labels, false, linearTarget);
    }

    /**
     * {@link #drawCaptured}'s depth pass: the records' solid core (alpha 0.5 and up) into depth alone. Drawn for every
     * label's text before any label's colour, it makes a nearer label's text hide a farther one's in any order.
     */
    public static void drawCapturedDepth(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count,
                                         CgBufferHandle labels) {
        drawCaptured(chunks, batch, records, count, labels, true, false);
    }

    private static void drawCaptured(CgChunkBuilder chunks, long batch, CgBufferHandle records, int count,
                                     CgBufferHandle labels, boolean depthPass, boolean linearTarget) {
        if (count <= 0) return;
        CgMaterial material = CgTextMaterials.label(CgTextSortKey.isDistanceField(batch), depthPass, CgTextGamma.initial(),
                linearTarget);
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
        } else if (draw.font != null || draw.styleFont() != null) {
            CgFont font = draw.font != null ? draw.font : draw.styleFont();
            if (draw.targetPx > 0) {
                resolvedFont = requireSizedFont(font, draw.targetPx);
            } else if (draw.layout == null && draw.paragraph == null) {
                resolvedFont = requireSizedFont(font);   // a layout built from text needs a sized font
            } else {
                resolvedFont = font;
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

        int basePx = fontKey.getTargetPx();

        CgTextLayout layout;
        if (draw.layout != null) {
            layout = draw.layout;
        } else {
            // Constraints are screen pixels: divided by the pose's own scale, not effective/base px, which is clamped
            // at CgTextRaster.MAX_PX. Never for world text, which must not reflow with the camera.
            float scale = world ? 1f : CgTextRaster.maxScale(viewPose);
            float maxWidth = scaleConstraint(draw.maxWidth, scale), maxHeight = scaleConstraint(draw.maxHeight, scale);
            // Slate's LetterSpacing: thousandths of an em, at the text's own size.
            float spacing = draw.style.letterSpacing() * basePx / 1000f;
            if (draw.paragraph != null) layout = draw.paragraph.layout(maxWidth, maxHeight);
            else if (resolvedFamily != null && draw.family != null) {
                layout = layout(draw.transformedText(), resolvedFamily, maxWidth, maxHeight, spacing);
            } else {
                layout = layout(draw.transformedText(), resolvedFont, maxWidth, maxHeight, spacing);
            }
        }

        int effectiveTargetPx;
        boolean sizeWantsMsdf;
        if (world) {
            effectiveTargetPx = CgTextRaster.worldPx(basePx);
            sizeWantsMsdf = true;
        } else {
            // Per draw (its layout), not per font: two draws of one face are not one animation.
            long frame = CgFrameClock.frameIndex();
            CgTextRasterMotion motion = motion(layout, frame);
            int exact = CgTextRaster.screenPx(basePx, CgTextRaster.maxScale(pixelPose), motion != null ? motion.exactPx() : -1);
            int before = motion != null ? motion.lastPx() : -1;
            effectiveTargetPx = motion != null ? motion.resolve(exact, frame) : exact;
            boolean previousMsdf = motion != null && before > 0 ? motion.wasMsdf : effectiveTargetPx >= 32;
            sizeWantsMsdf = CgTextRaster.msdf(effectiveTargetPx, previousMsdf);
            // The size-derived decision, not the forced ones below: the hysteresis damps size changes only.
            if (motion != null) motion.wasMsdf = sizeWantsMsdf;
            if (before != effectiveTargetPx && CgTrace.isEnabled(CgChannels.TEXT)) {
                CgTrace.marker(CgChannels.TEXT, "text.rasterPx", "base=" + basePx + " px=" + effectiveTargetPx
                        + (sizeWantsMsdf ? " msdf" : " bitmap") + (motion != null && motion.moving(frame) ? " moving" : ""));
            }
        }

        // The style's request over the size's pick; world text is always a field.
        boolean wantMsdf = world || switch (draw.style.raster()) {
            case MSDF -> true;
            case BITMAP -> false;
            case AUTO -> sizeWantsMsdf;
        };
        if (!wantMsdf && !CgTextRaster.isAxisAligned(pixelPose)) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByTransform", 1);
        }
        // An outline or a skew needs a distance field, down to the size the field can antialias.
        if (!wantMsdf && (hasOutline(draw.style) || draw.style.skew() != 0f)
                && effectiveTargetPx >= registry.getResolvedMsdfConfig(fontKey).minAntialiasablePx()) {
            wantMsdf = true;
            CgTrace.add(CgChannels.TEXT, "text.msdfForcedByStroke", 1);
        }

        return new ResolvedDraw(fontKey, effectiveTargetPx, wantMsdf, layout);
    }

    /** {@code layout}'s raster motion, made the first time it draws; null for a layout with no state to keep. */
    private CgTextRasterMotion motion(CgTextLayout layout, long frame) {
        if (layout == null) return null;
        CgTextRasterMotion m = motions.get(layout);
        if (m != null) return m;
        if (motions.size() >= MAX_MOTIONS) motions.values().removeIf(x -> frame - x.lastUsed >= MOTION_IDLE_FRAMES);
        if (motions.size() >= MAX_MOTIONS) return null;
        m = new CgTextRasterMotion();
        motions.put(layout, m);
        return m;
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
        } else if (draw.spatialNode == 0 && !visible(layout, originX, originY, pose, draw.reach())) {
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

    /** Hands this draw's runs to the canvas, each under its layer's material and blend, and its atlas. */
    private void land(CgTextDraw draw, float[] src, @Nullable Replay replay) {
        int n = replay != null ? replay.runs : runs;
        List<CgTextLayer> layers = draw.style.layers();
        boolean linear = canvas != null && blendsLinearly(canvas.target());
        CgTrace.add(CgChannels.TEXT, "draw.runs", n);
        for (int r = 0; r < n; r++) {
            boolean df = replay != null ? replay.distanceField[r] : runDistanceField[r];
            int atlasId = replay != null ? replay.atlas[r] : runAtlas[r];
            int from = replay != null ? replay.from[r] : runFrom[r];
            int count = replay != null ? replay.count[r] : runCount[r];
            int layer = replay != null ? replay.layer[r] : runLayer[r];
            CgTextLayer l = layer >= 0 ? layers.get(layer) : null;
            boolean additive = l != null && l.blend() == CgTextLayer.Blend.ADDITIVE;
            CgMaterial material;
            CgRenderState state = null;
            if (l != null && l.paint().kind() == CgTextPaint.Kind.MATERIAL) {
                if (l.paint().definition() == null) {
                    throw new IllegalStateException(l + " of " + draw.style + " names a material no loader compiled: load the"
                            + " style as an asset, or build it with CgTextPaint.material(definition)");
                }
                material = CgTextMaterials.paint(l.paint().definition(), df);
                state = CgTextMaterials.paintState(material, !world, additive);
                if (state == null) continue;   // its program is still building: held, as any draw is
            } else {
                material = CgTextMaterials.of(df, !world, draw.gamma, additive, linear);
            }
            canvas.quads(material, state, CgGlyphAtlas.texture(atlasId), src, from, count, draw.spatialNode);
        }
    }

    /**
     * Whether {@code target} is a float target (the HDR scene), where a blend mixes light: text gamma there would
     * thicken coverage a second time, so text's fills take the linear-blend correction instead. False for null or a
     * host's own target.
     */
    public static boolean blendsLinearly(@Nullable CgGraphTexture target) {
        CgTextureDesc desc = target != null ? target.desc() : null;
        if (desc == null) return false;
        CgTextureType colour = desc.format().getColorSlot(0);
        return colour != null && colour.isFloat();
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
                // 0: a glyph's colour is its span's, or none, so a fill tells a span's colour from its own paint.
                glyphCount = resolvedGlyphs.resolve(layout, originX, originY, frame, world,
                        effectiveTargetPx, wantMsdf, fontKey, 0, posePhase.x);
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

        // Lengths are Slate units at the style's size: local pixels times its unit, and in the glyph's own texels
        // painted in local space and transformed with the text, the only form an anisotropic transform can carry.
        float unit = draw.unit();
        float texelsPerLocal = registry.getResolvedMsdfConfig(fontKey).atlasScalePx() / Math.max(1, fontKey.getTargetPx());
        CgTextLayer outline = firstOutline(draw.style);
        // An outline the tier cannot draw resolves to none here, so a style may always have one.
        float outlineTexels = outline != null && wantMsdf ? outline.width() * unit * texelsPerLocal : 0f;
        float outlineAlign = outline != null ? alignOf(outline) : CgTextDraw.ALIGN_OUTSET;
        gatherLayers(draw, unit, wantMsdf ? CgTextLayer.MAX_FIELD_WIDTH_EM * fontKey.getTargetPx() : Float.MAX_VALUE);

        if (shadowList.count() > 0 && glyphCount > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.planShadows")) {
                boolean degraded;
                synchronized (registry) {
                    degraded = shadowPlan.plan(shadowList, resolvedGlyphs.placements, resolvedGlyphs.cached,
                            layout.baked(), glyphCount, fontKey, effectiveTargetPx, outlineTexels, outlineAlign, world,
                            frame);
                }
                if (degraded) degradedDraws++;
            }
        }

        CgTextDecorationRect[] decorations = layout.baked().decorations();
        if (glyphCount > 0 || decorations.length > 0) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.submitSortedQuads")) {
                sortAndRecord(glyphCount, decorations, fontKey.getTargetPx(), effectiveTargetPx, wantMsdf, draw, pose,
                        layout, outline, outlineTexels, outlineAlign, unit, texelsPerLocal);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Sort and record
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** Whether {@code style} has an outline layer, which needs a distance field. */
    private static boolean hasOutline(CgTextStyle style) {
        return firstOutline(style) != null;
    }

    /** {@code style}'s lowest outline layer: the shape an outlined shadow casts. Null without one. */
    @Nullable
    private static CgTextLayer firstOutline(CgTextStyle style) {
        List<CgTextLayer> layers = style.layers();
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).kind() == CgTextLayer.Kind.OUTLINE) return layers.get(i);
        }
        return null;
    }

    /** {@code text.shader}'s align code for an outline: on the edge, outside it, inside it. */
    private static float alignOf(CgTextLayer outline) {
        return switch (outline.align()) {
            case CENTRE -> CgTextDraw.ALIGN_CENTER;
            case INSIDE -> CgTextDraw.ALIGN_INSET;
            default -> CgTextDraw.ALIGN_OUTSET;
        };
    }

    /** {@code text.shader}'s align slot: the align, plus 3 for a round join. */
    private static float alignCode(float align, boolean round) {
        return round ? align + 3f : align;
    }

    /**
     * This draw's layers as the quad loop reads them: each shadow layer into {@link #shadowList}, in the style's local
     * pixels; each gradient's ramp row; each outline's ring or grown glyph. Indexed loops: nothing allocated.
     *
     * @param fieldReach the widest outline, in local pixels, every glyph's field carries: a grown-glyph outline wider
     *                   than it joins the shadows, as a spread with no offset or blur, so each glyph draws it from its
     *                   field where it fits and from a true-distance cell where it does not, never clamped
     */
    private void gatherLayers(CgTextDraw draw, float unit, float fieldReach) {
        List<CgTextLayer> layers = draw.style.layers();
        int n = layers.size();
        if (layerShadow.length < n) {
            layerShadow = new int[n * 2];
            layerRow = new float[n * 2];
            layerRing = new boolean[n * 2];
        }
        int shadows = 0;
        for (int i = 0; i < n; i++) {
            layerRing[i] = ring(layers, i);
            if (layers.get(i).kind() == CgTextLayer.Kind.SHADOW || grownPastField(layers.get(i), layerRing[i], unit, fieldReach)) shadows++;
        }
        shadowList.clear();
        shadowList.count(shadows);
        int s = 0;
        for (int i = 0; i < n; i++) {
            CgTextLayer l = layers.get(i);
            layerShadow[i] = -1;
            layerRow[i] = l.paint().kind() == CgTextPaint.Kind.GRADIENT ? CgTextGradients.row(l.paint()) : 0f;
            if (l.kind() == CgTextLayer.Kind.SHADOW) {
                shadowList.set(s, l.offsetX() * unit, l.offsetY() * unit, l.blur() * unit, l.spread() * unit,
                        layerArgb(draw.rgba, l), l.inset(), l.outlined());
            } else if (grownPastField(l, layerRing[i], unit, fieldReach)) {
                shadowList.set(s, 0f, 0f, 0f, l.width() * unit, layerArgb(draw.rgba, l), false, false);
            } else {
                continue;
            }
            int scope = draw.scopeOf(i);
            if (scope >= 0) shadowList.scope(s, scope);
            layerShadow[i] = s++;
        }
        if (draw.glyphScopes != null) shadowList.glyphScopes(draw.glyphScopes);
    }

    /**
     * Whether {@code l} is an outline under a fill wider than every glyph's field carries, which a shadow's spread draws
     * the same: the glyph grown by the true distance, as Slate's round-joined stroke. A ring, a mitre, a gradient or
     * material, or an additive outline has no shadow form and stays at the field's width.
     */
    private static boolean grownPastField(CgTextLayer l, boolean ring, float unit, float fieldReach) {
        return l.kind() == CgTextLayer.Kind.OUTLINE && !ring && l.join() == CgTextLayer.Join.ROUND
                && l.align() == CgTextLayer.Align.OUTSIDE && l.blend() == CgTextLayer.Blend.ALPHA
                && l.paint().kind() == CgTextPaint.Kind.COLOUR && l.width() * unit > fieldReach;
    }

    /**
     * Whether layer {@code i}, an outline, draws as the ring alone: over a fill, or with no fill above it. Under a fill it
     * is Slate's grown glyph, the fill covering its inside; over one, a grown glyph would cover it.
     */
    static boolean ring(List<CgTextLayer> layers, int i) {
        for (int j = 0; j < i; j++) if (layers.get(j).kind() == CgTextLayer.Kind.FILL) return true;
        for (int j = i + 1; j < layers.size(); j++) if (layers.get(j).kind() == CgTextLayer.Kind.FILL) return false;
        return true;
    }

    private void planSteps(CgTextStyle style) {
        if (steps.length < style.layers().size() + 2) steps = new int[style.layers().size() + 2];
        stepCount = steps(style.layers(), steps);
    }

    /**
     * {@code layers}' paint steps into {@code out}, bottom first: each layer's index, with {@link #STEP_UNDER} (the
     * decorations under the text) before the first fill or outline and {@link #STEP_OVER} after the last fill, or the
     * last outline where there is none: CSS's order for text-decoration. Returns how many; {@code out} holds n + 2.
     */
    static int steps(List<CgTextLayer> layers, int[] out) {
        int n = layers.size(), first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            CgTextLayer.Kind k = layers.get(i).kind();
            if (k == CgTextLayer.Kind.SHADOW) continue;
            if (first < 0) first = i;
            if (k == CgTextLayer.Kind.FILL || last < 0 || layers.get(last).kind() != CgTextLayer.Kind.FILL) last = i;
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (i == first) out[count++] = STEP_UNDER;
            out[count++] = i;
            if (i == last) out[count++] = STEP_OVER;
        }
        if (first < 0) {
            out[count++] = STEP_UNDER;
            out[count++] = STEP_OVER;
        }
        return count;
    }

    /**
     * One draw's glyphs, decorations and shadows as quads, sorted by paint step, then atlas and mode
     * ({@link CgTextSortKey}), so its runs are as few as its states. An underline runs with its font's glyphs.
     */
    private void sortAndRecord(int glyphCount, CgTextDecorationRect[] decorations, int baseTargetPx,
                               int effectiveTargetPx, boolean wantMsdf, CgTextDraw draw, Matrix4f pose,
                               CgTextLayout layout, @Nullable CgTextLayer outline, float outlineTexels,
                               float outlineAlign, float unit, float texelsPerLocal) {
        CgGlyphPlacement[] placements = resolvedGlyphs.placements;
        List<CgTextLayer> layers = draw.style.layers();

        List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "glyph.resolveDecorations")) {
            synchronized (registry) {
                resolvedDecorations = resolvedGlyphs.resolveDecorations(decorations, draw.originX(), draw.originY(),
                        decorationArgb(draw), effectiveTargetPx, wantMsdf);
            }
        }

        int perStage = glyphCount + resolvedDecorations.size();
        if (perStage == 0) return;
        planSteps(draw.style);
        int keys = perStage * stepCount;
        if (keys > sortKeys.length) sortKeys = Arrays.copyOf(sortKeys, Math.max(keys, sortKeys.length * 2));

        // Snap to pixels on screen only: in the world a block spans under a unit and every glyph would floor together.
        boolean pixelSnap = !world;
        if (pixelSnap) inverseModelView.set(pixelPose).invert();
        float devicePerLocal = effectiveTargetPx / (float) Math.max(1, baseTargetPx);

        int lastPaint = stepCount - 1;
        long emitted = 0;
        for (int window = 0; window <= lastPaint; window += CgTextSortKey.MAX_STAGE + 1) {
            int windowEnd = Math.min(lastPaint, window + CgTextSortKey.MAX_STAGE);
            int count = 0;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT_DETAIL, "draw.sortKeys")) {
                for (int paint = window; paint <= windowEnd; paint++) {
                    int stage = paint - window, step = steps[paint];
                    if (step < 0) {
                        boolean under = step == STEP_UNDER;
                        for (int i = 0; i < resolvedDecorations.size(); i++) {
                            if (resolvedDecorations.get(i).underText() == under) {
                                sortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                            }
                        }
                        continue;
                    }
                    CgTextLayer l = layers.get(step);
                    if (layerShadow[step] >= 0) {
                        int shadow = layerShadow[step];
                        if (!shadowList.casts(shadow)) continue;
                        for (int i = 0; i < glyphCount; i++) {
                            if (shadowPlan.kind(shadow, i) != CgTextShadowPlan.NONE) {
                                sortKeys[count++] = CgTextSortKey.forGlyph(shadowPlan.placement(shadow, i), i, stage);
                            }
                        }
                        if (shadowList.scoped(shadow)) continue;
                        for (int i = 0; i < resolvedDecorations.size(); i++) {
                            sortKeys[count++] = CgTextSortKey.forDecoration(resolvedDecorations.get(i), i, stage);
                        }
                        continue;
                    }
                    boolean outlineLayer = l.kind() == CgTextLayer.Kind.OUTLINE;
                    if (outlineLayer && !(wantMsdf && l.width() > 0f)) continue;
                    int scope = draw.scopeOf(step);
                    for (int i = 0; i < glyphCount; i++) {
                        CgGlyphPlacement p = placements[i];
                        if (p == null || !p.hasGeometry() || outlineLayer && !p.isDistanceField()) continue;
                        if (scope >= 0 && draw.glyphScope(i) != scope) continue;
                        sortKeys[count++] = CgTextSortKey.forGlyph(p, i, stage);
                    }
                }
                Arrays.sort(sortKeys, 0, count);
            }
            emitted += count;
            recordSorted(count, window, resolvedDecorations, placements, draw, layout, baseTargetPx, effectiveTargetPx,
                    devicePerLocal, pixelSnap, pose, outline, outlineTexels, outlineAlign, unit, texelsPerLocal);
        }
        // A counter over the frame (draw.glyphCount is the last draw's sample). Unculled: off-screen quads count.
        CgTrace.add(CgChannels.TEXT, "draw.quadsEmitted", emitted);
    }

    private void recordSorted(int count, int window, List<CgResolvedGlyphs.ResolvedDecoration> resolvedDecorations,
                              CgGlyphPlacement[] placements, CgTextDraw draw, CgTextLayout layout,
                              int baseTargetPx, int effectiveTargetPx, float devicePerLocal, boolean pixelSnap,
                              Matrix4f pose, @Nullable CgTextLayer outline, float outlineTexels, float outlineAlign,
                              float unit, float texelsPerLocal) {
        List<CgTextLayer> layers = draw.style.layers();
        float skew = draw.style.skew();
        boolean roundOutline = outline != null && outline.join() == CgTextLayer.Join.ROUND;
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
                int step = steps[paint];
                CgTextLayer layer = step >= 0 ? layers.get(step) : null;
                int shadow = layer != null ? layerShadow[step] : -1;

                CgGlyphPlacement p;
                boolean isDistanceField;
                int atlasId;
                float pxRange;
                float u0, v0, u1, v1;
                int rgba, atlasLayer;
                // The instance's custom slots, as env/buffer/text.glsl lays them out.
                float c0x = 0f, c0y = 0f, c0z = 0f, c0w = 0f, c1x = 0f, c1y = 0f, c1z = 0f, c1w = 0f, c2 = 0f;
                // A glyph's quad shears about its baseline with the style's skew; a decoration's never does.
                boolean sheared = false;
                float baseline = 0f;

                if (isDecoration) {
                    CgResolvedGlyphs.ResolvedDecoration d = resolvedDecorations.get(localIndex);
                    isDistanceField = d.isDistanceField();
                    atlasId = d.atlasId();
                    pxRange = d.pxRange();
                    quadX = d.qx(); quadY = d.qy(); quadW = d.w(); quadH = d.h();
                    u0 = d.u0(); v0 = d.v0(); u1 = d.u1(); v1 = d.v1();
                    rgba = d.rgba();
                    atlasLayer = d.atlasPageIndex();
                    c1w = pxRange;
                    if (shadow >= 0) {
                        rgba = shadowList.argb(shadow);
                        float sigma = shadowList.sigma(shadow);
                        boolean blurred = CgTextShadowPlan.blurs(sigma, devicePerLocal);
                        float spread = shadowList.spread(shadow);
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
                    sheared = true;
                    baseline = resolvedGlyphs.originY + resolvedGlyphs.glyphY[localIndex];
                    u0 = p.u0(); v0 = p.v0(); u1 = p.u1(); v1 = p.v1();
                    atlasLayer = p.atlasPageIndex();
                    c1w = pxRange;
                    if (layer.kind() == CgTextLayer.Kind.FILL) {
                        rgba = fillArgb(draw.rgba, resolvedGlyphs.argbColor[localIndex], layer);
                        c2 = p.key().getFontKey().getTargetPx();
                    } else {
                        rgba = layerArgb(draw.rgba, layer);
                        c1x = layer.width() * unit * texelsPerLocal;
                        c1y = alignCode(alignOf(layer),
                                layer.join() == CgTextLayer.Join.ROUND && p.atlasType() == CgGlyphAtlas.Type.MTSDF);
                        c1z = layerRing[step] ? 1f : 0f;
                    }
                    CgTextPaint paintOf = layer.paint();
                    if (paintOf.kind() != CgTextPaint.Kind.COLOUR) {
                        mapPaint(paintOf, layout, localIndex, draw.originX(), draw.originY());
                        c0x = mapA; c0y = mapB; c0z = mapC;
                        c0w = paintOf.kind() == CgTextPaint.Kind.GRADIENT ? layerRow[step] : mapD;
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
                    sheared = true;
                    baseline = resolvedGlyphs.originY + resolvedGlyphs.glyphY[localIndex] + oy;
                    if (kind == CgTextShadowPlan.CELL) {
                        c0w = KIND_CELL;
                    } else {
                        c1w = pxRange;
                        if (kind == CgTextShadowPlan.FIELD_OUTER || kind == CgTextShadowPlan.FIELD_INSET) {
                            c1x = shadowList.outlined(shadow) ? outlineTexels : 0f;
                            c1y = alignCode(outlineAlign, roundOutline && p.atlasType() == CgGlyphAtlas.Type.MTSDF);
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

                int o = reserve(paint, step, batchBits, isDistanceField, atlasId);
                float[] q = records;
                // As Quad.submit bakes a pose, through the same JOML calls, so the floats are the same. Slate's skew:
                // each point moves right by skew times its height above the baseline (SlateTextShaper.cpp:300-309).
                float shear = sheared ? skew : 0f;
                bakeOrigin.set(quadX + shear * (baseline - quadY), quadY, 0f);
                bakeRight.set(quadW, 0f, 0f);
                bakeUp.set(-shear * quadH, quadH, 0f);
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

    /** {@link #mapPaint}'s answer: a gradient's t = a + b*u + c*v over the quad; a material's run uv (a, b) + (u, v) * (c, d). */
    private float mapA, mapB, mapC, mapD;

    /**
     * Maps the current quad ({@link #quadX} and friends, before any skew) onto {@code paint}'s space: a gradient's t
     * along its angle over the glyph's quad, its line's box or the whole text's; a material's run uv over the text.
     */
    private void mapPaint(CgTextPaint paint, CgTextLayout layout, int glyph, float originX, float originY) {
        float bx = originX, by = originY, bw = layout.totalWidth(), bh = layout.totalHeight();
        if (paint.kind() == CgTextPaint.Kind.MATERIAL) {
            bw = Math.max(bw, 1e-6f);
            bh = Math.max(bh, 1e-6f);
            mapA = (quadX - bx) / bw;
            mapB = (quadY - by) / bh;
            mapC = quadW / bw;
            mapD = quadH / bh;
            return;
        }
        if (paint.space() == CgTextPaint.Space.GLYPH) {
            bx = quadX; by = quadY; bw = quadW; bh = quadH;
        } else if (paint.space() == CgTextPaint.Space.LINE) {
            int[] lineStart = layout.baked().lineStart();
            float[] lineHeight = layout.baked().lineHeight();
            float top = originY;
            for (int line = 0; line < lineHeight.length; line++) {
                if (glyph < lineStart[line + 1]) {
                    by = top;
                    bh = lineHeight[line];
                    break;
                }
                top += lineHeight[line];
            }
        }
        double radians = Math.toRadians(paint.angle());
        float dx = (float) Math.cos(radians), dy = (float) Math.sin(radians);
        // The box's corners projected on the direction: t is 0 at the nearest, 1 at the farthest.
        float min = Math.min(0f, bw * dx) + Math.min(0f, bh * dy);
        float range = Math.max(Math.abs(bw * dx) + Math.abs(bh * dy), 1e-6f);
        mapA = ((quadX - bx) * dx + (quadY - by) * dy - min) / range;
        mapB = quadW * dx / range;
        mapC = quadH * dy / range;
        mapD = 0f;
    }

    /** {@code layer}'s colour over {@code tint}: the paint's colour (white under a gradient or a material) times it, and its opacity. */
    private static int layerArgb(int tint, CgTextLayer layer) {
        int paint = layer.paint().kind() == CgTextPaint.Kind.COLOUR ? layer.paint().colour() : 0xFFFFFFFF;
        int argb = multiply(tint, paint);
        int a = Math.round((argb >>> 24) * layer.opacity());
        return a << 24 | argb & 0xFFFFFF;
    }

    /**
     * A fill's colour on one glyph: its span's colour in place of a colour paint (under a gradient or a material, over
     * it), else the paint's; then the draw's tint and the layer's opacity. {@code span} is 0 where no span sets one.
     */
    private static int fillArgb(int tint, int span, CgTextLayer layer) {
        if (span == 0) return layerArgb(tint, layer);
        int argb = multiply(tint, span);
        int a = Math.round((argb >>> 24) * layer.opacity());
        return a << 24 | argb & 0xFFFFFF;
    }

    /** The decorations' colour: the draw's tint over its style's first fill's colour (a gradient's first stop). */
    private static int decorationArgb(CgTextDraw draw) {
        List<CgTextLayer> layers = draw.style.layers();
        for (int i = 0; i < layers.size(); i++) {
            CgTextLayer l = layers.get(i);
            if (l.kind() == CgTextLayer.Kind.FILL) return multiply(draw.rgba, l.paint().colour());
        }
        return draw.rgba;
    }

    private static int multiply(int a, int b) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= (ca * cb + 127) / 255 << shift;
        }
        return out;
    }

    /** Room for one record, in the run of {@code (rank, batchBits)} drawing {@code layer}; returns its float offset. */
    private int reserve(int rank, int layer, long batchBits, boolean distanceField, int atlasId) {
        if (runs == 0 || runRank[runs - 1] != rank || runBatch[runs - 1] != batchBits) {
            if (runs == runRank.length) {
                int n = runs * 2;
                runRank = Arrays.copyOf(runRank, n);
                runAtlas = Arrays.copyOf(runAtlas, n);
                runFrom = Arrays.copyOf(runFrom, n);
                runCount = Arrays.copyOf(runCount, n);
                runBatch = Arrays.copyOf(runBatch, n);
                runDistanceField = Arrays.copyOf(runDistanceField, n);
                runLayer = Arrays.copyOf(runLayer, n);
            }
            runRank[runs] = rank;
            runBatch[runs] = batchBits;
            runDistanceField[runs] = distanceField;
            runAtlas[runs] = atlasId;
            runLayer[runs] = layer;
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
        int[] from = new int[0], count = new int[0], atlas = new int[0], layer = new int[0];
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
            replay.layer = new int[runs];
            replay.distanceField = new boolean[runs];
        }
        System.arraycopy(runFrom, 0, replay.from, 0, runs);
        System.arraycopy(runCount, 0, replay.count, 0, runs);
        System.arraycopy(runAtlas, 0, replay.atlas, 0, runs);
        System.arraycopy(runLayer, 0, replay.layer, 0, runs);
        System.arraycopy(runDistanceField, 0, replay.distanceField, 0, runs);
        replay.runs = runs;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  Layouts and fonts
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** The layout of {@code text}, from {@link CgTextLayoutCache}; shared, never mutate it. */
    static CgTextLayout layout(String text, CgFont font, float maxWidth, float maxHeight, float letterSpacing) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        CgTextLayoutCache.Key key = CgTextLayoutCache.key(text, font, maxWidth, maxHeight, letterSpacing);
        CgTextLayout cached = CgTextLayoutCache.get(key);
        if (cached != null) return cached;
        CgTextLayout built = CgTextLayout.of(text, font).maxWidth(maxWidth).maxHeight(maxHeight)
                .letterSpacing(letterSpacing).build();
        CgTextLayoutCache.put(key, built);
        return built;
    }

    static CgTextLayout layout(String text, CgFontFamily family, float maxWidth, float maxHeight, float letterSpacing) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        CgTextLayoutCache.Key key = CgTextLayoutCache.key(text, family, maxWidth, maxHeight, letterSpacing);
        CgTextLayout cached = CgTextLayoutCache.get(key);
        if (cached != null) return cached;
        CgTextLayout built = CgTextLayout.of(text, family).maxWidth(maxWidth).maxHeight(maxHeight)
                .letterSpacing(letterSpacing).build();
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
