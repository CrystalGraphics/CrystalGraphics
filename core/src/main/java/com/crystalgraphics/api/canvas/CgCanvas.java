package com.crystalgraphics.api.canvas;

import com.crystalgraphics.render.graph.CgRasterPass;

import com.crystalgraphics.gpu.resource.CgFrameRing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgFit;
import com.crystalgraphics.api.paint.brush.CgTiling;
import com.crystalgraphics.api.material.engine.CgPlacement;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.paint.effect.CgShadow;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.pattern.CgGrid;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.api.vector.CgFillRule;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgStrokeJoin;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.canvas.CgCanvasPrimitives;
import com.crystalgraphics.render.canvas.CgCanvasGlass;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.canvas.CgQuadRecord;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.ui.CgUiRecorder;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.ui.backdrop.CgBackdrop;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.path.CgPathCache;
import com.crystalgraphics.render.ui.path.CgPathCells;
import com.crystalgraphics.render.ui.path.CgPathFlattener;
import com.crystalgraphics.render.ui.path.CgPathStroker;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.ui.svg.CgSvgCells;
import com.crystalgraphics.render.ui.svg.CgSvgOps;
import com.crystalgraphics.render.ui.svg.CgSvgScene;
import com.crystalgraphics.text.render.CgTextBatch;
import com.crystalgraphics.text.render.CgTextGamma;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * A 2D canvas for screens, HUDs and textures: rectangles filled by any paint, rounded, bordered and shadowed; images
 * and nine-slices; under a transform, opacity and clip stack. What it draws lands in a recording as batched element
 * draws, in paint order. Coordinates are logical pixels from the top-left corner, scaled to the target by its
 * {@link #scale}.
 *
 * <pre>{@code
 * CgCanvas canvas = CgCanvas.on(frame);                              // a HUD or screen stage's, in window pixels
 * canvas.fill(8, 8, 200, 40, 0xC0101418);                            // a flat rect
 * canvas.box(8, 56, 200, 40).fill(CgGradient.linear(90).stop(0, 0xFF3060FF).stop(1, 0xFF60C0FF))
 *       .radius(8).border(1, 0x40FFFFFF).shadow(CgShadow.of(0, 4, 12, 0x80000000)).draw();
 * canvas.image(CgBrush.sprite(sheet, "button"), 8, 104, 120, 24);    // a nine-slice from a sprite sheet
 * canvas.svg(CgSvg.of("mymod:ui/icons/save.svg"), 8, 136, 16, 16);  // an icon, sharp at any size
 * try (CgCanvasLayer l = canvas.layer().opacity(0.5f)) { ... }        // a group faded as one picture
 * }</pre>
 *
 * <pre>{@code
 * canvas.save();
 * canvas.translate(cx, cy).rotate(angle).translate(-16, -16);
 * canvas.opacity(0.5f).clip(0, 0, 32, 32, CgCornerRadii.of(6));
 * canvas.image(icon, 0, 0, 32, 32);
 * canvas.restore();                                                  // transform, opacity and clip as before
 * }</pre>
 *
 * <ul>
 *   <li>Items keep paint order: a later item draws over an earlier one it overlaps. Items that overlap nothing between
 *       them draw in one call whatever their order.</li>
 *   <li>Axis-aligned edges snap to device pixels unless {@link #pixelSnap pixelSnap(false)}; rotated items antialias
 *       their edges instead.</li>
 *   <li>Colours are {@code 0xAARRGGBB}, straight alpha; {@link #intensity} raises them past 1 on an HDR target.</li>
 *   <li>One thread draws a canvas at a time; separate canvases record concurrently.</li>
 *   <li>Every {@link #save} needs its {@link #restore}; {@link #end} drops what is left on the stack.</li>
 * </ul>
 */
public final class CgCanvas implements CgStageFrame.Drawer {

    private static final String ELEMENT_SHADER = "crystalgraphics:shaders/canvas/element.shader";
    private static final int MAX_TILES = 4096;
    /** A save's fields: the affine, opacity, intensity, clip entry, clip rect and whether it is one, clip node. */
    private static final int SAVED = 18;

    private static final Logger LOGGER = Logger.getLogger(CgCanvas.class.getName());

    private final CgUiRecorder recorder = new CgUiRecorder();
    private final float[] record = new float[CgUiElements.FLOATS];
    private final CgCanvasBox box = new CgCanvasBox(this);
    private final Matrix4f pose = new Matrix4f();
    /** The transform as text's pose: its glyph quads bake it, rotation included. */
    private final Matrix4f textPose = new Matrix4f();
    @Nullable private CgTextBatch text;
    private final TextTarget textTarget = new TextTarget();
    private CgTextGamma textGamma = CgTextGamma.initial();
    /** Text items drawn so far: a chunk holding one keeps the glyph epoch it was taken in. */
    private long textDraws;
    /** Draws from the path atlas so far: a chunk holding one keeps the atlas's resets it was taken at. */
    private long pathDraws;
    /** Records a path's coverage raster: its own pass, before the element that paints it. */
    private final CgUiRecorder coverage = new CgUiRecorder();
    private final CgPathCells cells = new CgPathCells();
    private final CgSvgCells svgCells = new CgSvgCells();
    // Layers: the open ones, innermost last, kept for reuse; the filter passes' recorder and lens; a mask path's material
    private CgCanvasLayer[] layerStack = new CgCanvasLayer[4];
    private int layers;
    private final CgUiRecorder filters = new CgUiRecorder();
    private final CgCamera filterLens = new CgCamera();
    private final float[] filterRecord = new float[CgUiElements.FLOATS];
    @Nullable private CgMaterialInterface pathMaterial;
    private float maskX0, maskY0, maskX1, maskY1;
    /** Material filters' instances this turn, reused turn to turn: one a layer, since each holds its picture. */
    private final List<CgMaterialInstance> filterInstances = new ArrayList<>();
    private int filterInstancesUsed;
    private final CgCamera atlasCamera = new CgCamera().screenLens(CgPathCache.SIZE, CgPathCache.SIZE);
    private final CgCamera rasterCamera = new CgCamera();
    private final float[] bounds = new float[4];
    private final float[] noRadii = new float[4], clipRx = new float[4], clipRy = new float[4];

    @Nullable private CgRecording recording;
    @Nullable private CgGraphTexture target;
    private CgLoad load;
    private CgCamera camera;
    @Nullable private CgStageFrame frame;
    private float targetHeight;
    private float scale = 1f;
    private boolean snap = true;

    // The transform from logical units to the target's pixels: x' = a x + c y + tx, y' = b x + d y + ty
    private float a = 1f, b, c, d = 1f, tx, ty;
    private float opacity = 1f, intensity = 1f;
    // The clip: its entry, and for culling and intersecting, its device rect where it is one at node 0
    private int clip;
    private boolean clipIsRect = true;
    private float clipX0 = -Float.MAX_VALUE, clipY0 = -Float.MAX_VALUE, clipX1 = Float.MAX_VALUE, clipY1 = Float.MAX_VALUE;
    private int clipNode;
    // The spatial node of the transform now, made at the first item that needs one after it changed
    private int node;
    private boolean nodeStale;
    // The movable node items bake into ({@link #movable}), 0 for the target, and the clip it was opened under
    private int base, clipBase;

    // A paint chunk being taken: what the tables held at its start, and the union of what it drew
    private boolean chunking;
    private int chunkClips, chunkNodes, chunkClip, chunkNode;
    private long chunkTextDraws, chunkDegraded, chunkPathDraws;
    private long chunkPasses;
    private float chunkX0, chunkY0, chunkX1, chunkY1;

    private float[] stack = new float[SAVED * 16];
    private int depth;

    @Nullable private static CgMaterial element, elementAdditive, elementMask;

    /** The material every item draws with unless it names its own: the element primitive's paint, premultiplied. */
    public static synchronized CgMaterial elementMaterial() {
        if (element == null) element = elementUnder(CgBlendMode.PREMULTIPLIED);
        return element;
    }

    /** The element primitive added on: a glow layer's composite. */
    private static synchronized CgMaterial elementAdditive() {
        if (elementAdditive == null) elementAdditive = elementUnder(CgBlendMode.ADDITIVE);
        return elementAdditive;
    }

    /** The element primitive as a mask: what is under it kept by its alpha. */
    private static synchronized CgMaterial elementMask() {
        if (elementMask == null) elementMask = elementUnder(CgBlendMode.MASK);
        return elementMask;
    }

    /**
     * What this canvas draws ahead of its first frame: the element under each blend it composites with, and its layers'
     * blur and colour-matrix kernels. {@code CgCanvasPrimitives.precache} calls it.
     */
    public static void precache(CgPipelineList list) {
        list.addPass(elementMaterial(), CgInstanceKind.ELEMENT);
        list.addPass(elementAdditive(), CgInstanceKind.ELEMENT);
        list.addPass(elementMask(), CgInstanceKind.ELEMENT);
        list.kernels(() -> {
            CgGpuOps.precacheBlur(CgFormat.RGBA16_FLOAT);
            CgGpuOps.precacheColorMatrix();
            CgGpuOps.precacheShrink(CgFormat.RGBA16_FLOAT);
        });
    }

    private static CgMaterial elementUnder(CgBlendMode blend) {
        CgMaterial m = CgMaterial.load(ELEMENT_SHADER,
                CgMaterialState.builder().blend(blend).twoSided().disableDepthTest().build());
        m.precache();
        return m;
    }

    /**
     * The canvas of a HUD or screen stage's turn, in window pixels: what it draws lands in the stage's firing, in
     * paint order with the frame's other drawing. The same canvas all turn; the stack starts empty each turn.
     */
    public static CgCanvas on(CgStageFrame frame) {
        return frame.drawer(CgCanvas.class, CgCanvas::new).turn(frame, 1f);
    }

    /** As {@link #on}, in the host GUI's pixels: scaled by {@code frame.scale()}, Minecraft's GUI scale. */
    public static CgCanvas onGui(CgStageFrame frame) {
        return frame.drawer(CgCanvas.class, CgCanvas::new).turn(frame, frame.scale());
    }

    private CgCanvas turn(CgStageFrame frame, float scale) {
        if (this.frame != frame || recording != frame.recording() || this.scale != scale) {
            begin(frame.recording(), frame.target(), CgLoad.load(), frame.camera(), scale);
            targetHeight = frame.height();
            this.frame = frame;
        }
        return this;
    }

    /**
     * Records later items into {@code target} in {@code recording}, seen through {@code camera} ({@code CgCamera.screen}
     * for a screen), at {@code scale} target pixels per logical pixel. The stack starts empty.
     */
    public CgCanvas begin(CgRecording recording, CgGraphTexture target, CgLoad load, CgCamera camera, float scale) {
        end();
        this.recording = Objects.requireNonNull(recording, "recording");
        this.target = Objects.requireNonNull(target, "target");
        this.load = Objects.requireNonNull(load, "load");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.targetHeight = camera.height();
        this.scale = scale;
        a = scale;
        d = scale;
        b = c = tx = ty = 0f;
        opacity = intensity = 1f;
        snap = true;
        resetClip();
        node = 0;
        nodeStale = false;
        dropLayers();
        filterInstancesUsed = 0;
        panesUsed = 0;
        sweepKept();
        return this;
    }

    /** Lands everything drawn and records nowhere until the next {@link #begin}. */
    public void end() {
        if (layers > 0) {
            // Not thrown: a turn ends in a finally, and an exception thrown inside the layer is the one to see
            LOGGER.warning("a canvas ended with " + layers + " layer(s) open: their drawing is dropped (close every"
                    + " layer; try-with-resources does)");
            dropLayers();
            recorder.abandon();
        }
        recorder.end();
        recording = null;
        target = null;
        frame = null;
        depth = 0;
    }

    /** Drops what is drawn and not landed, and records nowhere: its recording is being discarded. */
    public void abandon() {
        dropLayers();
        recorder.abandon();
        coverage.abandon();
        filters.abandon();
        // Cells this canvas claimed will never be built: others must not wait on them
        CgPathCache.SHARED.abandon(this);
        recording = null;
        target = null;
        frame = null;
        chunking = false;
        depth = 0;
    }

    /** Lands what is drawn; a later item opens a new pass after whatever is recorded between. */
    @Override
    public void endPass() {
        recorder.end();
    }

    @Override
    public void endTurn() {
        end();
    }

    /** A pass open on the target: after the stage frame's own canvas lands what it drew. */
    private void pass() {
        if (recorder.recording()) return;
        if (layers > 0) {
            CgCanvasLayer top = layerStack[layers - 1];
            if (!top.opened) place(top);
            recorder.begin(recording, top.texture, top.load, top.lens);
            recorder.view(top.outerBase, top.ownerX + top.x0, top.ownerY + top.y0);
            recorder.layered(true);
            top.load = CgLoad.load();
            return;
        }
        if (frame != null) frame.canvas().endPass();
        recorder.begin(recording, target, load, camera);
        load = CgLoad.load();
    }

    /** Whether it records anywhere. */
    public boolean recording() {
        return recording != null;
    }

    /** Target pixels per logical pixel. */
    public float scale() {
        return scale;
    }

    // ── State ──────────────────────────────────────────────────────────

    /** Pushes the transform, opacity, intensity, clip and pixel snapping; {@link #restore} pops them. */
    public CgCanvas save() {
        if ((depth + 1) * SAVED > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
        int o = depth++ * SAVED;
        stack[o] = a;
        stack[o + 1] = b;
        stack[o + 2] = c;
        stack[o + 3] = d;
        stack[o + 4] = tx;
        stack[o + 5] = ty;
        stack[o + 6] = opacity;
        stack[o + 7] = intensity;
        stack[o + 8] = Float.intBitsToFloat(clip);
        stack[o + 9] = clipX0;
        stack[o + 10] = clipY0;
        stack[o + 11] = clipX1;
        stack[o + 12] = clipY1;
        stack[o + 13] = clipIsRect ? 1f : 0f;
        stack[o + 14] = Float.intBitsToFloat(clipNode);
        stack[o + 15] = Float.intBitsToFloat(base);
        stack[o + 16] = Float.intBitsToFloat(clipBase);
        stack[o + 17] = snap ? 1f : 0f;
        return this;
    }

    /** Pops what the matching {@link #save} pushed. */
    public CgCanvas restore() {
        if (depth == 0) throw new IllegalStateException("restore() without a save()");
        while (layers > 0 && layerStack[layers - 1].clips && depth == layerStack[layers - 1].saveDepth && !closingLayer) {
            closeLayer(layerStack[layers - 1]);
        }
        if (depth == 0) throw new IllegalStateException("restore() without a save()");
        if (layers > 0 && depth == layerStack[layers - 1].saveDepth && layerStack[layers - 1].live
                && !closingLayer) {
            throw new IllegalStateException("restore() past an open layer's opening: close the layer first");
        }
        int o = --depth * SAVED;
        boolean moved = a != stack[o] || b != stack[o + 1] || c != stack[o + 2] || d != stack[o + 3]
                || tx != stack[o + 4] || ty != stack[o + 5];
        a = stack[o];
        b = stack[o + 1];
        c = stack[o + 2];
        d = stack[o + 3];
        tx = stack[o + 4];
        ty = stack[o + 5];
        if (moved) nodeStale = true;
        opacity = stack[o + 6];
        intensity = stack[o + 7];
        clip = Float.floatToRawIntBits(stack[o + 8]);
        clipX0 = stack[o + 9];
        clipY0 = stack[o + 10];
        clipX1 = stack[o + 11];
        clipY1 = stack[o + 12];
        clipIsRect = stack[o + 13] != 0f;
        clipNode = Float.floatToRawIntBits(stack[o + 14]);
        int savedBase = Float.floatToRawIntBits(stack[o + 15]);
        if (savedBase != base) nodeStale = true;
        base = savedBase;
        clipBase = Float.floatToRawIntBits(stack[o + 16]);
        snap = stack[o + 17] != 0f;
        return this;
    }

    /** Moves later items by {@code (x, y)}. */
    public CgCanvas translate(float x, float y) {
        if (x == 0f && y == 0f) return this;
        tx += a * x + c * y;
        ty += b * x + d * y;
        nodeStale = true;
        return this;
    }

    /** Scales later items about the origin. */
    public CgCanvas scale(float sx, float sy) {
        a *= sx;
        b *= sx;
        c *= sy;
        d *= sy;
        nodeStale = true;
        return this;
    }

    /** Rotates later items about the origin, clockwise on the screen, by {@code degrees}. */
    public CgCanvas rotate(float degrees) {
        double r = Math.toRadians(degrees);
        float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
        return concat(cos, sin, -sin, cos, 0f, 0f);
    }

    /** Applies the affine {@code x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12} before the transform now. */
    public CgCanvas concat(float m00, float m10, float m01, float m11, float m02, float m12) {
        float na = a * m00 + c * m10, nb = b * m00 + d * m10;
        float nc = a * m01 + c * m11, nd = b * m01 + d * m11;
        tx += a * m02 + c * m12;
        ty += b * m02 + d * m12;
        a = na;
        b = nb;
        c = nc;
        d = nd;
        nodeStale = true;
        return this;
    }

    /** Multiplies later items' opacity by {@code f}, 0..1. */
    public CgCanvas opacity(float f) {
        opacity *= Math.max(0f, Math.min(1f, f));
        return this;
    }

    /** Multiplies later items' colour by {@code f}: past 1 on an HDR target, as an emissive UI. */
    public CgCanvas intensity(float f) {
        intensity *= Math.max(0f, f);
        return this;
    }

    /** Whether axis-aligned edges snap to device pixels; on by default. */
    public CgCanvas pixelSnap(boolean on) {
        snap = on;
        return this;
    }

    /** Clips later items to a rect, intersected with the clip now. */
    public CgCanvas clip(float x, float y, float w, float h) {
        return clip(x, y, w, h, null);
    }

    /** Clips later items to a rounded rect, intersected with the clip now. */
    public CgCanvas clip(float x, float y, float w, float h, @Nullable CgCornerRadii radii) {
        boolean rounded = radii != null && !radii.isNone();
        if (!rounded && axisAligned() && clipNode == base && clipIsRect) {
            // Square clips at node 0 intersect on the CPU rather than nest: deep scroll panes cost no depth
            float x0 = Math.max(clipX0, deviceX(x)), y0 = Math.max(clipY0, deviceY(y));
            float x1 = Math.min(clipX1, deviceX(x + w)), y1 = Math.min(clipY1, deviceY(y + h));
            clipX0 = x0;
            clipY0 = y0;
            clipX1 = Math.max(x0, x1);
            clipY1 = Math.max(y0, y1);
            pose.identity();
            int entry = recording.clips().add(clipBase, base, pose, targetHeight, clipX0, clipY0, clipX1, clipY1, noRadii,
                    noRadii, null);
            if (entry > 0) clip = entry;
            return this;
        }
        int at = spatial();
        float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        if (at == base) {
            x0 = deviceX(x);
            y0 = deviceY(y);
            x1 = deviceX(x + w);
            y1 = deviceY(y + h);
        }
        float k = at == base ? Math.abs(a) : 1f, kv = at == base ? Math.abs(d) : 1f;
        if (rounded) {
            CgCornerRadii fit = radii.fitted(w, h);
            clipRx[0] = fit.topLeftX() * k;
            clipRx[1] = fit.topRightX() * k;
            clipRx[2] = fit.bottomRightX() * k;
            clipRx[3] = fit.bottomLeftX() * k;
            clipRy[0] = fit.topLeftY() * kv;
            clipRy[1] = fit.topRightY() * kv;
            clipRy[2] = fit.bottomRightY() * kv;
            clipRy[3] = fit.bottomLeftY() * kv;
        }
        pose.identity();
        int entry = recording.clips().add(clip, at, pose, targetHeight, x0, y0, x1, y1, rounded ? clipRx : noRadii,
                rounded ? clipRy : noRadii, null);
        if (entry < 0) {
            throw new IllegalStateException("clips nest at most " + CgClipTable.MAX_DEPTH
                    + " deep past their square intersections");
        }
        clip = entry;
        clipIsRect = false;
        clipNode = at;
        if (at == base) {
            clipX0 = Math.max(clipX0, x0);
            clipY0 = Math.max(clipY0, y0);
            clipX1 = Math.min(clipX1, x1);
            clipY1 = Math.min(clipY1, y1);
        }
        return this;
    }

    /**
     * Clips later items to {@code path}'s inside, antialiased at any transform, until the {@link #restore} that pops the
     * level it is made at: a mask layer over the path's bounds, so call it inside a {@link #save}.
     *
     * <pre>{@code
     * canvas.save().clip(CgVectorPath.builder().addCircle(32, 32, 32).build());
     * canvas.image(avatar, 0, 0, 64, 64);
     * canvas.restore();                                  // the clip's layer composites here
     * }</pre>
     *
     * @throws IllegalStateException outside a {@code save()}: nothing would close it
     */
    public CgCanvas clip(CgVectorPath path) {
        Objects.requireNonNull(path, "path");
        if (depth == 0) throw new IllegalStateException("clip(path) closes at a restore(): call it inside a save()");
        CgCanvasLayer l = openLayer(path.minX(), path.minY(), path.maxX() - path.minX(), path.maxY() - path.minY());
        l.mask(path);
        l.clips = true;
        return this;
    }

    /**
     * Opens a movable spatial node at the transform now and answers its id: what later items are positioned in until
     * the matching {@link #restore}. A sealed frame moves it by its values, with nothing recorded again
     * ({@code frame.values().translate(node, dx, dy)}); batching treats what is inside apart from what is outside.
     *
     * <pre>{@code
     * canvas.save();
     * int scroller = canvas.translate(0, -scrollY).movable();   // a scroll the compositor moves
     * paintRows(canvas);
     * canvas.restore();
     * }</pre>
     */
    public int movable() {
        if (recording == null) throw new IllegalStateException("a movable node on a canvas that records nowhere");
        int n;
        if (axisAligned()) {
            // The scale stays baked into the items, so their edges snap; the node carries the translation
            n = recording.spatial().add(base, tx, ty, true);
            tx = ty = 0f;
        } else {
            n = recording.spatial().add(base, a, b, c, d, tx, ty, true);
            a = d = 1f;
            b = c = tx = ty = 0f;
        }
        base = n;
        nodeStale = true;
        clipBase = clip;
        clipIsRect = true;
        clipNode = n;
        clipX0 = clipY0 = -Float.MAX_VALUE;
        clipX1 = clipY1 = Float.MAX_VALUE;
        return n;
    }

    // ── Layers ─────────────────────────────────────────────────────────

    /**
     * Opens a layer over the live clip: what is drawn until it closes lands in a picture of its own, composited as one.
     * Set it up (opacity, filter, mask) before drawing in it. @see CgCanvasLayer
     */
    public CgCanvasLayer layer() {
        return openLayer(Float.NaN, 0f, 0f, 0f);
    }

    /** As {@link #layer()}, its picture bounded by a rect in the units now, grown by its filter's reach. */
    public CgCanvasLayer layer(float x, float y, float w, float h) {
        if (!(w >= 0f && h >= 0f)) throw new IllegalArgumentException("a layer of " + w + " x " + h);
        return openLayer(x, y, w, h);
    }

    private boolean closingLayer;

    private CgCanvasLayer openLayer(float x, float y, float w, float h) {
        if (recording == null) throw new IllegalStateException("a layer on a canvas that records nowhere: begin() first");
        recorder.end();
        if (layers == layerStack.length) layerStack = Arrays.copyOf(layerStack, layers * 2);
        CgCanvasLayer l = layerStack[layers];
        if (l == null) layerStack[layers] = l = new CgCanvasLayer(this);
        l.reset();
        if (!Float.isNaN(x)) {
            affineBounds(x, y, x + w, y + h, a, b, c, d, tx, ty);
            l.boundX0 = bounds[0];
            l.boundY0 = bounds[1];
            l.boundX1 = bounds[2];
            l.boundY1 = bounds[3];
        }
        l.clipX0 = clipX0;
        l.clipY0 = clipY0;
        l.clipX1 = clipX1;
        l.clipY1 = clipY1;
        l.scale = linearScale();
        l.outerBase = base;
        owner(l);
        save();
        l.saveDepth = depth;
        // Its items under a node of their own: clips made inside evaluate through the palette, which the pass's view
        // origin moves to the picture's corner. The clip outside applies when it composites.
        l.node = recording.spatial().add(base, 1f, 0f, 0f, 1f, 0f, 0f, false);
        base = l.node;
        nodeStale = true;
        clip = 0;
        clipBase = 0;
        clipIsRect = true;
        clipNode = base;
        opacity = 1f;
        intensity = 1f;
        layers++;
        return l;
    }

    /** Where the outer base sits in the root as recorded: a layer's picture is placed there. */
    private void owner(CgCanvasLayer l) {
        float ox = 0f, oy = 0f;
        for (int n = l.outerBase; n != 0; n = recording.spatial().parent(n)) {
            float la = recording.spatial().local(n, 0), lb = recording.spatial().local(n, 1);
            float lc = recording.spatial().local(n, 2), ld = recording.spatial().local(n, 3);
            if (la != 1f || lb != 0f || lc != 0f || ld != 1f) {
                throw new IllegalStateException("a layer inside a turned or scaled movable node: open the layer outside it,"
                        + " or make the node by translation (movable() under an axis-aligned transform)");
            }
            ox += recording.spatial().local(n, 4);
            oy += recording.spatial().local(n, 5);
        }
        l.ownerX = ox;
        l.ownerY = oy;
    }

    /** A layer's picture placed at its first draw: its bounds or the clip, grown by the filter's reach, cut to the target. */
    private void place(CgCanvasLayer l) {
        float reach = l.filter.reach() * l.scale;
        float x0, y0, x1, y1;
        if (Float.isNaN(l.boundX0)) {
            x0 = l.clipX0;
            y0 = l.clipY0;
            x1 = l.clipX1;
            y1 = l.clipY1;
        } else {
            x0 = Math.max(l.boundX0 - reach, l.clipX0 - reach);
            y0 = Math.max(l.boundY0 - reach, l.clipY0 - reach);
            x1 = Math.min(l.boundX1 + reach, l.clipX1 + reach);
            y1 = Math.min(l.boundY1 + reach, l.clipY1 + reach);
        }
        // The target, in the outer base's space
        x0 = Math.max(x0, -l.ownerX);
        y0 = Math.max(y0, -l.ownerY);
        x1 = Math.min(x1, camera.width() - l.ownerX);
        y1 = Math.min(y1, camera.height() - l.ownerY);
        int ix0 = (int) Math.floor(x0), iy0 = (int) Math.floor(y0);
        int w = Math.max(1, (int) Math.ceil(x1) - ix0), h = Math.max(1, (int) Math.ceil(y1) - iy0);
        if (blurs(l.filter)) {
            // A blur works at a half or a quarter: the picture a whole number of its texels
            w = (w + 3) & ~3;
            h = (h + 3) & ~3;
        }
        l.x0 = ix0;
        l.y0 = iy0;
        l.w = w;
        l.h = h;
        l.texture = CgGraphTexture.transientTexture("canvas_layer", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT));
        l.lens.screenLens(w, h);
        l.load = CgLoad.CLEAR;
        l.opened = true;
    }

    private static boolean blurs(CgFilter filter) {
        for (CgFilter.Op op : filter.ops()) {
            if (op.kind() == CgFilter.Kind.BLUR || op.kind() == CgFilter.Kind.DROP_SHADOW) return true;
        }
        return false;
    }

    /** Masks, filters and composites {@code l}, restoring what its opening saved. */
    void closeLayer(CgCanvasLayer l) {
        if (!l.live) return;
        if (layers == 0 || layerStack[layers - 1] != l) {
            throw new IllegalStateException("layers close in the reverse of their opening");
        }
        while (depth > l.saveDepth) restore();
        if (l.opened && (l.maskPath != null || l.maskPaint != null)) mask(l);
        recorder.end();
        layers--;
        l.live = false;
        closingLayer = true;
        try {
            restore();
        } finally {
            closingLayer = false;
        }
        if (l.kept != null) {
            finishKept(l);
            return;
        }
        if (!l.opened || l.opacity <= 0f || opacity <= 0f) return;
        CgGraphTexture picture = filtered(l);
        composite(l, picture);
    }

    /** The mask drawn into the layer, keeping what it covers: a path's coverage, or a paint over the picture. */
    private void mask(CgCanvasLayer l) {
        if (l.maskPath != null) {
            pathMaterial = elementMask();
            try {
                if (l.maskStroke != null) {
                    stroke(l.maskPath, l.maskStroke, 0xFFFFFFFF);
                } else {
                    fill(l.maskPath, 0xFFFFFFFF);
                }
            } finally {
                pathMaterial = null;
            }
            // Outside the path's bounds nothing was masked: the composite stops there
            float r = l.maskStroke == null ? 0f : l.maskStroke.width() * 0.5f
                    * Math.max(1.4142135f, l.maskStroke.join() == CgStrokeJoin.MITER ? l.maskStroke.miterLimit() : 1f);
            affineBounds(l.maskPath.minX() - r, l.maskPath.minY() - r, l.maskPath.maxX() + r, l.maskPath.maxY() + r,
                    a, b, c, d, tx, ty);
            maskX0 = bounds[0] - 1f;
            maskY0 = bounds[1] - 1f;
            maskX1 = bounds[2] + 1f;
            maskY1 = bounds[3] + 1f;
        } else {
            save();
            a = d = 1f;
            b = c = tx = ty = 0f;
            nodeStale = true;
            box(l.x0, l.y0, l.w, l.h).fill(l.maskPaint).material(elementMask()).draw();
            restore();
            maskX0 = -Float.MAX_VALUE;
            maskY0 = -Float.MAX_VALUE;
            maskX1 = Float.MAX_VALUE;
            maskY1 = Float.MAX_VALUE;
        }
    }

    /** The picture through the layer's filter chain, each step on the last one's result. */
    private CgGraphTexture filtered(CgCanvasLayer l) {
        CgGraphTexture picture = l.texture;
        for (CgFilter.Op op : l.filter.ops()) {
            // Lengths are the canvas's units: device pixels by the transform, then the picture's texels
            float k = l.scale * picture.getWidth() / (float) l.w;
            float[] v = op.values();
            switch (op.kind()) {
                case BLUR -> picture = blurred(picture, v[0] * k, v[1] * k);
                case COLOR_MATRIX -> {
                    int pw = picture.getWidth(), ph = picture.getHeight();
                    CgGraphTexture out = layerTexture(pw, ph);
                    CgComputePass pass = recording.compute("canvas.layer.matrix");
                    CgGpuOps.colorMatrix(pass, picture, 0, 0, pw, ph, out, v);
                    pass.end();
                    picture = out;
                }
                case DROP_SHADOW -> {
                    CgGraphTexture shadow = blurred(picture, v[2] * k, v[3] * k);
                    picture = composed(picture, shadow, v[0] * k, v[1] * k, Float.floatToRawIntBits(v[4]), null);
                }
                case MATERIAL -> picture = composed(picture, null, 0f, 0f, 0, op);
            }
        }
        return picture;
    }

    /** {@code picture} blurred by sigmas in its texels, at a half or a quarter where the blur is wide. */
    private CgGraphTexture blurred(CgGraphTexture picture, float sigmaX, float sigmaY) {
        if (Math.max(sigmaX, sigmaY) < 0.25f) return picture;
        int pw = picture.getWidth(), ph = picture.getHeight();
        int scale = CgGpuOps.blurScale(Math.max(sigmaX, sigmaY));
        CgGraphTexture out = layerTexture((pw + scale - 1) / scale, (ph + scale - 1) / scale);
        CgComputePass pass = recording.compute("canvas.layer.blur");
        CgGpuOps.blurScaled(pass, picture, 0, 0, pw, ph, out, Math.max(0f, sigmaX), Math.max(0f, sigmaY), CgGpuOps.Edge.CLAMP);
        pass.end();
        return out;
    }

    /**
     * A new picture the size of {@code picture}: a shadow's alpha under it, offset and tinted; or the picture through a
     * material filter.
     */
    private CgGraphTexture composed(CgGraphTexture picture, @Nullable CgGraphTexture shadow, float dx, float dy,
                                    int shadowArgb, @Nullable CgFilter.Op through) {
        int pw = picture.getWidth(), ph = picture.getHeight();
        CgGraphTexture out = layerTexture(pw, ph);
        filters.begin(recording, out, CgLoad.CLEAR, filterLens.screenLens(pw, ph));
        if (shadow != null) {
            filterElement(CgUiElements.FILL_MASK, shadowArgb, false, dx, dy, pw, ph);
            filters.draw(CgInstanceKind.ELEMENT, elementMaterial(), null, shadow, null, 0, filterRecord, 0, 1,
                    dx, dy, dx + pw, dy + ph);
        }
        filterElement(CgUiElements.FILL_TEXTURE, 0xFFFFFFFF, true, 0f, 0f, pw, ph);
        CgMaterialInterface material = elementMaterial();
        if (through != null) {
            CgMaterialInstance instance = filterInstance(through.material());
            instance.sampler(through.textureParameter(), 0, picture);
            material = instance;
        }
        filters.draw(CgInstanceKind.ELEMENT, material, null, picture, null, 0, filterRecord, 0, 1, 0f, 0f, pw, ph);
        filters.end();
        return out;
    }

    private void filterElement(int fill, int argb, boolean premultiplied, float x, float y, int w, int h) {
        Arrays.fill(filterRecord, 0f);
        CgUiElements.fill(filterRecord, 0, fill, argb, 0f);
        CgUiElements.flag(filterRecord, 0, CgUiElements.FLAG_PREMULTIPLIED, premultiplied);
        // A render target's row 0 is its bottom
        CgUiElements.uv(filterRecord, 0, 0f, 1f, 1f, 0f);
        CgUiElements.rect(filterRecord, 0, x, y, x + w, y + h);
        CgUiElements.nodes(filterRecord, 0, CgPalette.pack(0, 0), 0);
        CgUiElements.opacity(filterRecord, 0, 1f, 1f);
    }

    /** A material filter's instance for this turn: the caller's own, or one of the canvas's over a material. */
    private CgMaterialInstance filterInstance(CgMaterialInterface material) {
        if (material instanceof CgMaterialInstance own) return own;
        CgMaterial asset = material.material();
        for (int i = filterInstancesUsed; i < filterInstances.size(); i++) {
            if (filterInstances.get(i).material() == asset) {
                Collections.swap(filterInstances, i, filterInstancesUsed);
                return filterInstances.get(filterInstancesUsed++);
            }
        }
        CgMaterialInstance made = asset.instance();
        filterInstances.add(filterInstancesUsed, made);
        filterInstancesUsed++;
        return made;
    }

    private CgGraphTexture layerTexture(int w, int h) {
        return CgGraphTexture.transientTexture("canvas_layer", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT));
    }

    /** The picture drawn as one element at the layer's region, under the clip and opacity outside it. */
    private void composite(CgCanvasLayer l, CgGraphTexture picture) {
        float ex0 = l.x0, ey0 = l.y0, ex1 = l.x0 + l.w, ey1 = l.y0 + l.h;
        if (l.maskPath != null) {
            ex0 = Math.max(ex0, maskX0);
            ey0 = Math.max(ey0, maskY0);
            ex1 = Math.min(ex1, maskX1);
            ey1 = Math.min(ey1, maskY1);
        }
        if (ex1 <= ex0 || ey1 <= ey0) return;
        if (clipNode == base && (ex1 <= clipX0 || ey1 <= clipY0 || ex0 >= clipX1 || ey0 >= clipY1)) return;
        pass();
        clear();
        CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, (Math.round(255f * l.opacity) << 24) | 0x00FFFFFF, 0f);
        CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        // A render target's row 0 is its bottom
        CgUiElements.uv(record, 0, (ex0 - l.x0) / l.w, 1f - (ey0 - l.y0) / l.h, (ex1 - l.x0) / l.w, 1f - (ey1 - l.y0) / l.h);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        finish(base, 1f);
        element(l.blend == CgBlendMode.ADDITIVE ? elementAdditive() : elementMaterial(), picture, null, base, record, 0, 1,
                ex0, ey0, ex1, ey1);
    }

    /** Forgets open layers: a canvas begun again or abandoned with them open. */
    private void dropLayers() {
        for (int i = 0; i < layers; i++) {
            layerStack[i].live = false;
            if (layerStack[i].kept != null) layerStack[i].kept.layer().invalidate();
        }
        layers = 0;
        // A held walk left open has nothing to land
        for (int i = 0; i < keptLayers.size(); i++) keptLayers.get(i).walk.live = false;
    }

    // ── Kept layers ────────────────────────────────────────────────────

    /** Past this many device pixels a side a kept layer is refused. */
    private static final int KEPT_MAX_SIDE = 4096;
    /** Frames a kept layer may go unasked for before its canvas frees it. */
    private static final long KEPT_IDLE_FRAMES = 300L;
    /** How often a thumbnail is filtered again while its picture changes: a preview needs no more. */
    private static final long THUMBNAIL_REFRESH_NANOS = 100L * 1_000_000L;

    /** The kept layers this canvas walked, which it frees when idle. */
    private final List<CgKeptLayer> keptLayers = new ArrayList<>();
    private final float[] keptScratch = new float[4];
    /** Glass drawn flat inside a kept walk. */
    private final CgCanvasBox flatBox = new CgCanvasBox(this);

    /**
     * Walks {@code kept} over a rect in the units now, composited when the walk closes: drawn when {@code revision} or
     * its device size changed since the last walk, else held and nothing recorded for it. @see CgKeptLayer
     */
    public CgKeptLayer.Walk kept(CgKeptLayer kept, float x, float y, float w, float h, long revision) {
        if (recording == null) throw new IllegalStateException("a kept layer on a canvas that records nowhere: begin() first");
        if (!(w > 0f && h > 0f)) throw new IllegalArgumentException("a kept layer of " + w + " x " + h);
        CgKeptLayer.Walk walk = kept.walk;
        if (walk.live) throw new IllegalStateException("a kept layer walked inside its own walk");
        affineBounds(x, y, x + w, y + h, a, b, c, d, tx, ty);
        // Whole device pixels: a move by translation composites the same picture
        int x0 = Math.round(bounds[0]), y0 = Math.round(bounds[1]);
        int pw = Math.max(1, (int) Math.ceil(bounds[2] - bounds[0] - 1e-3f));
        int ph = Math.max(1, (int) Math.ceil(bounds[3] - bounds[1] - 1e-3f));
        if (pw > KEPT_MAX_SIDE || ph > KEPT_MAX_SIDE) {
            throw new IllegalArgumentException("a kept layer " + pw + " x " + ph + " device pixels: at most "
                    + KEPT_MAX_SIDE + " a side");
        }
        long now = CgFrameRing.frame();
        kept.lastFrame = now;
        if (kept.sweeper != this) {
            if (kept.sweeper != null) kept.sweeper.keptLayers.remove(kept);
            keptLayers.add(kept);
            kept.sweeper = this;
        }
        walk.canvas = this;
        walk.live = true;
        walk.x0 = x0;
        walk.y0 = y0;
        walk.opacity = 1f;
        walk.revision = revision;
        walk.draws = !kept.holds(revision, pw, ph);
        if (!walk.draws) return walk;

        boolean full = !kept.hasPicture() || kept.pictureWidth != pw || kept.pictureHeight != ph;
        if (kept.texture != null) {
            // Remade when outgrown, or holding more than twice what it needs
            int tw = kept.texture.getWidth(), th = kept.texture.getHeight();
            int rw = reserved(kept.reserveWidth), rh = reserved(kept.reserveHeight);
            if (tw < pw || th < ph || tw < rw || th < rh || tw > Math.max(Math.max(64, pw * 2), rw)
                    || th > Math.max(Math.max(64, ph * 2), rh)) {
                recording.release(kept.texture);
                kept.texture = null;
            }
        }
        if (kept.texture == null) {
            kept.texture = CgGraphTexture.requested("canvas_kept", CgTextureDesc.of(
                    Math.max(bucket(pw), reserved(kept.reserveWidth)), Math.max(bucket(ph), reserved(kept.reserveHeight)),
                    CgFormat.RGBA16_FLOAT));
            full = true;
        }
        kept.invalidate();
        kept.beginWalk(now, full);
        CgCanvasLayer l = openLayer(Float.NaN, 0f, 0f, 0f);
        l.kept = walk;
        l.x0 = x0;
        l.y0 = y0;
        l.w = pw;
        l.h = ph;
        l.texture = kept.texture;
        l.lens.screenLens(kept.texture.getWidth(), kept.texture.getHeight());
        l.load = CgLoad.CLEAR;
        l.opened = true;
        l.passMark = recording.passCount();
        // Its clear lands even when nothing is drawn: what vacated is cleared
        pass();
        recorder.openPass();
        kept.pictureWidth = pw;
        kept.pictureHeight = ph;
        return walk;
    }

    /** A rect in the units now as {@code walk}'s texels, top-down, into a scratch array. */
    float[] keptRect(CgKeptLayer.Walk walk, float x, float y, float w, float h) {
        affineBounds(x, y, x + w, y + h, a, b, c, d, tx, ty);
        keptScratch[0] = bounds[0] - walk.x0;
        keptScratch[1] = bounds[1] - walk.y0;
        keptScratch[2] = bounds[2] - walk.x0;
        keptScratch[3] = bounds[3] - walk.y0;
        return keptScratch;
    }

    /** Ends {@code walk}: a drawn one's damage cut into its passes; then its picture composited. */
    void closeKept(CgKeptLayer.Walk walk) {
        if (walk.draws) {
            if (layers == 0 || layerStack[layers - 1].kept != walk) {
                throw new IllegalStateException("layers close in the reverse of their opening");
            }
            closeLayer(layerStack[layers - 1]);
        } else {
            walk.live = false;
            compositeKept(walk);
        }
    }

    /** A drawn walk's end, its layer closed: tenants vacate, the damage is cut into its passes, the picture is held. */
    private void finishKept(CgCanvasLayer l) {
        CgKeptLayer.Walk walk = l.kept;
        CgKeptLayer kept = walk.layer();
        walk.live = false;
        kept.endWalk();
        // Nothing damaged on a drawn walk is the whole picture: the safe answer for a caller that damages nothing
        if (!kept.full && kept.damaged()) {
            CgGraphTexture t = kept.texture;
            int th = t.getHeight();
            // A pixel either side for edge coverage, then the bottom-left rect a pass takes
            int x0 = Math.max(0, (int) Math.floor(kept.damageX0) - 1), y0 = Math.max(0, (int) Math.floor(kept.damageY0) - 1);
            int x1 = Math.min(kept.pictureWidth, (int) Math.ceil(kept.damageX1) + 1);
            int y1 = Math.min(kept.pictureHeight, (int) Math.ceil(kept.damageY1) + 1);
            int dx = 0, dy = 0, dw = 0, dh = 0;
            if (x1 > x0 && y1 > y0) {
                dx = x0;
                dy = th - y1;
                dw = x1 - x0;
                dh = y1 - y0;
            }
            for (int i = l.passMark, n = recording.passCount(); i < n; i++) {
                if (recording.pass(i) instanceof CgRasterPass raster && raster.target() == t) raster.damage(dx, dy, dw, dh);
            }
        }
        kept.revision = walk.revision;
        kept.drawCount++;
        compositeKept(walk);
    }

    /** {@code walk}'s picture as one element at its place, under the clip and opacity outside it. */
    private void compositeKept(CgKeptLayer.Walk walk) {
        CgKeptLayer kept = walk.layer();
        if (!kept.hasPicture()) return;
        int x0 = walk.x0, y0 = walk.y0, w = kept.pictureWidth, h = kept.pictureHeight;
        // Composited into a kept layer being walked: it damages where it lands there, and where it was
        for (int i = layers - 1; i >= 0; i--) {
            CgCanvasLayer outer = layerStack[i];
            if (outer.kept == null) continue;
            kept.placement.place(outer.kept.layer(), x0 - outer.x0, y0 - outer.y0, x0 - outer.x0 + w, y0 - outer.y0 + h,
                    outer.kept.layer().walkFrame);
            break;
        }
        if (walk.opacity <= 0f || opacity <= 0f) return;
        if (clipNode == base && (x0 + w <= clipX0 || y0 + h <= clipY0 || x0 >= clipX1 || y0 >= clipY1)) return;
        float tw = kept.texture.getWidth(), th = kept.texture.getHeight();
        pass();
        clear();
        CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, (Math.round(255f * walk.opacity) << 24) | 0x00FFFFFF, 0f);
        CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        // A render target's row 0 is its bottom; the picture is the texture's top-left
        CgUiElements.uv(record, 0, 0f, 1f, w / tw, 1f - h / th);
        CgUiElements.rect(record, 0, x0, y0, x0 + w, y0 + h);
        finish(base, 1f);
        element(elementMaterial(), kept.texture, null, base, record, 0, 1, x0, y0, x0 + w, y0 + h);
    }

    /**
     * {@code kept}'s picture over a rect in the units now, as last drawn: a preview. Nothing when it holds none.
     *
     * <pre>{@code
     * canvas.picture(WINDOW, x, y, 320, 200);
     * }</pre>
     */
    public CgCanvas picture(CgKeptLayer kept, float x, float y, float w, float h) {
        if (!kept.hasPicture()) return this;
        float tw = kept.texture.getWidth(), th = kept.texture.getHeight();
        return box(x, y, w, h).fill(kept.texture).premultiplied()
                .region(0f, 1f, kept.pictureWidth / tw, 1f - kept.pictureHeight / th).draw();
    }

    /**
     * {@code kept}'s picture as {@link #picture} draws it, filtered down to the rect's device size first: each pixel the
     * average of the texels it covers, where one bilinear tap of a picture several times larger flickers as it moves.
     *
     * <pre>{@code
     * canvas.thumbnail(WINDOW, x, y, 160, 100);
     * if (WINDOW.thumbnailStale()) repaintSoon();   // it trails the picture
     * }</pre>
     *
     * <ul>
     *   <li>The filtered copy is kept and filtered again about every 100 ms while the picture changes.</li>
     *   <li>A picture under one and a half times the size asked, or smaller on either axis, draws as {@link #picture}.</li>
     * </ul>
     */
    public CgCanvas thumbnail(CgKeptLayer kept, float x, float y, float w, float h) {
        if (!kept.hasPicture()) return this;
        int dw = Math.round(w * (float) Math.hypot(a, b)), dh = Math.round(h * (float) Math.hypot(c, d));
        int pw = kept.pictureWidth, ph = kept.pictureHeight;
        if (dw < 1 || dh < 1 || pw < dw || ph < dh || pw < dw * 1.5f && ph < dh * 1.5f) return picture(kept, x, y, w, h);
        if (kept.thumbnail == null || kept.thumbnailWidth != dw || kept.thumbnailHeight != dh) {
            if (kept.thumbnail != null) recording.release(kept.thumbnail);
            kept.thumbnail = CgGraphTexture.requested("canvas_kept_thumbnail", CgTextureDesc.of(dw, dh, CgFormat.RGBA16_FLOAT));
            kept.thumbnailWidth = dw;
            kept.thumbnailHeight = dh;
            kept.thumbnailDrawn = -1L;
        }
        kept.thumbnailStale = kept.thumbnailDrawn != kept.drawCount;
        long now = System.nanoTime();
        if (kept.thumbnailStale && (kept.thumbnailDrawn == -1L || now - kept.thumbnailAt >= THUMBNAIL_REFRESH_NANOS)) {
            recorder.end();
            CgComputePass pass = recording.compute("canvas.kept.thumbnail");
            CgGpuOps.shrink(pass, kept.texture, 0f, kept.texture.getHeight() - ph, pw, ph, kept.thumbnail, 0, 0, dw, dh);
            pass.end();
            kept.thumbnailDrawn = kept.drawCount;
            kept.thumbnailAt = now;
            kept.thumbnailStale = false;
        }
        return box(x, y, w, h).fill(kept.thumbnail).premultiplied().region(0f, 1f, 1f, 0f).draw();
    }

    /** Whether a kept layer is being walked here: glass inside draws flat. */
    private boolean insideKept() {
        for (int i = 0; i < layers; i++) if (layerStack[i].kept != null) return true;
        return false;
    }

    /** Frees the kept layers nobody asked for in {@link #KEPT_IDLE_FRAMES} frames; every 16th frame. */
    private void sweepKept() {
        long now = CgFrameRing.frame();
        if (keptLayers.isEmpty() || (now & 15L) != 0L) return;
        for (int i = keptLayers.size() - 1; i >= 0; i--) {
            CgKeptLayer kept = keptLayers.get(i);
            if (now - kept.lastFrame < KEPT_IDLE_FRAMES || kept.keep) continue;
            if (kept.texture != null) recording.release(kept.texture);
            if (kept.thumbnail != null) recording.release(kept.thumbnail);
            kept.texture = kept.thumbnail = null;
            kept.thumbnailDrawn = -1L;
            kept.invalidate();
            kept.sweeper = null;
            keptLayers.remove(i);
        }
    }

    /** What the owner promised, rounded as a texture is; 0 for no promise. */
    private static int reserved(int size) {
        return size <= 0 ? 0 : bucket(Math.min(KEPT_MAX_SIDE, size));
    }

    /** Rounded up to 64, so a picture resizing by a pixel a frame does not remake its texture every frame. */
    private static int bucket(int size) {
        return Math.max(64, (Math.max(1, size) + 63) & ~63);
    }

    // ── Paint chunks ───────────────────────────────────────────────────

    /** Starts taking what is drawn from now as a paint chunk, ended by {@link #endChunk}. Chunks do not nest. */
    public CgCanvas beginChunk() {
        if (chunking) throw new IllegalStateException("beginChunk() inside a chunk");
        pass();
        recorder.tape();
        recorder.openPass();
        chunking = true;
        chunkPasses = recorder.passes();
        chunkClips = recording.clips().count();
        chunkNodes = recording.spatial().count();
        chunkClip = clip;
        chunkNode = spatial();
        chunkX0 = chunkY0 = Float.MAX_VALUE;
        chunkX1 = chunkY1 = -Float.MAX_VALUE;
        chunkTextDraws = textDraws;
        chunkPathDraws = pathDraws;
        chunkDegraded = degradedDraws();
        return this;
    }

    /**
     * Ends the chunk {@link #beginChunk} began and keeps what it drew in {@code into}, answering
     * {@link CgPaintChunk.Refusal#NONE}, or why it kept nothing.
     */
    public CgPaintChunk.Refusal endChunk(CgPaintChunk into) {
        if (!chunking) throw new IllegalStateException("endChunk() without beginChunk()");
        chunking = false;
        int count = recorder.untape();
        return into.capture(this, count, recording, chunkClips, chunkNodes, chunkClip, chunkNode,
                recorder.passes() == chunkPasses, degradedDraws() != chunkDegraded,
                textDraws != chunkTextDraws ? textGamma : null, pathDraws != chunkPathDraws, chunkX0, chunkY0, chunkX1,
                chunkY1);
    }

    /**
     * Adds what {@code chunk} kept, drawing nothing: at the state it was taken in, as the caller's key promises. False,
     * with nothing added, when nothing is kept or its clips would nest too deep: then draw it.
     */
    public boolean replay(CgPaintChunk chunk) {
        if (recording == null) throw new IllegalStateException("replaying on a canvas that records nowhere");
        pass();
        return chunk.replay(this, recording, clip, spatial(), textGamma);
    }

    CgDrawChunk taped(int i) {
        return recorder.taped(i);
    }

    void addChunk(CgDrawChunk sealed) {
        recorder.add(sealed);
    }

    // ── Items ──────────────────────────────────────────────────────────

    /** A rect in one colour. */
    public CgCanvas fill(float x, float y, float w, float h, int argb) {
        return box(x, y, w, h).fill(argb).draw();
    }

    /** A rect in any paint: a colour, a gradient, a grid or a brush. */
    public CgCanvas fill(float x, float y, float w, float h, CgPaint paint) {
        return box(x, y, w, h).fill(paint).draw();
    }

    /** A box to describe and {@link CgCanvasBox#draw draw}: its fill, corners, border, shadow and material. */
    public CgCanvasBox box(float x, float y, float w, float h) {
        return box.reset(x, y, w, h);
    }

    /** An image stretched over a rect. */
    public CgCanvas image(CgTexture texture, float x, float y, float w, float h) {
        return box(x, y, w, h).fill(texture).draw();
    }

    /** A brush over a rect: its region, nine-slice, tiling, sampler and tint; an SVG brush's document fitted to it. */
    public CgCanvas image(CgBrush brush, float x, float y, float w, float h) {
        return box(x, y, w, h).fill(brush).draw();
    }

    /**
     * {@code view}'s picture over a rect: its scene drawn now through its camera, at the rect's size in device pixels
     * ({@link CgView#renderPicture}), then placed as an image. An editor's viewport, a preview, a minimap.
     *
     * <pre>{@code
     * CgView lab = CgView.of(labScene);                  // once, kept: it holds the view's histories
     * labScene.tick(dt);
     * lab.camera().set(x, y, z, rotation, projection);
     * canvas.view(lab, 20, 40, 320, 200);
     * canvas.box(20, 40, 320, 200).radius(8).border(1, 0x40FFFFFF).draw();   // a frame over it
     * }</pre>
     *
     * <ul>
     *   <li>Its picture is kept on the view and remade only on a new size: a rect that animates its size remakes it.</li>
     *   <li>Square corners; for rounded ones, draw it in a layer masked by a path.</li>
     * </ul>
     */
    public CgCanvas view(CgView view, float x, float y, float w, float h) {
        int pw = Math.max(1, Math.round(w * (float) Math.hypot(a, b)));
        int ph = Math.max(1, Math.round(h * (float) Math.hypot(c, d)));
        return box(x, y, w, h).fill(view.renderPicture(pw, ph, 0xFF000000)).region(0f, 1f, 1f, 0f).draw();
    }

    /**
     * A photograph of what this canvas's target holds so far, read back without waiting ({@link CgPhotograph}). False
     * when three are already on their way.
     *
     * <pre>{@code
     * canvas.photograph(320, (w, h, rgb) -> thumbnail = rgb);   // the sink runs frames later, on the render thread
     * }</pre>
     */
    public boolean photograph(int maxWidth, CgPhotograph.Sink sink) {
        if (target == null) throw new IllegalStateException("photograph() outside a turn");
        recorder.end();
        if (frame != null) frame.canvas().endPass();
        return CgPhotograph.take(recording, target, Math.max(1, Math.round(camera.width())),
                Math.max(1, Math.round(camera.height())), maxWidth, sink);
    }

    // ── Text ───────────────────────────────────────────────────────────

    /** {@code text} with its layout's origin at ({@code x}, {@code y}): as its style paints it, white by default. */
    public CgCanvas text(CgText text, float x, float y) {
        return text(text, x, y, 0xFFFFFFFF);
    }

    /**
     * {@code text} at ({@code x}, {@code y}), every layer of its style tinted by {@code argb}. Its glyphs rasterise at
     * their size on the target, under the transform and scale now; while one is still generating it draws a lower
     * tier and counts in {@link #degradedDraws}.
     */
    public CgCanvas text(CgText text, float x, float y, int argb) {
        if (recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        if (opacity <= 0f) return this;
        pass();
        int tint = opacity >= 1f ? argb : (argb & 0x00FFFFFF) | Math.round((argb >>> 24) * opacity) << 24;
        textPose.set(a, b, 0f, 0f, c, d, 0f, 0f, 0f, 0f, 1f, 0f, tx, ty, 0f, 1f);
        textBatch().draw(text, x, y, tint, textPose, clip, base, textGamma);
        textDraws++;
        return this;
    }

    /** The coverage correction later text is drawn with; {@link CgTextGamma#initial()} at first. Not saved. */
    public CgCanvas textGamma(CgTextGamma gamma) {
        this.textGamma = Objects.requireNonNull(gamma, "gamma");
        return this;
    }

    /**
     * Text items that drew a glyph or shadow below the tier they asked for, still generating. Rises only: a caller
     * keeping what it drew (a surface, a chunk) paints again while it moves.
     */
    public long degradedDraws() {
        return text == null ? 0 : text.degradedDraws();
    }

    private CgTextBatch textBatch() {
        CgTextBatch t = text;
        if (t == null) text = t = new CgTextBatch(textTarget);
        return t;
    }

    /** The canvas as its text batch sees it; glyph quads land as the canvas's own records do. */
    private final class TextTarget implements CgTextBatch.Target {

        @Override
        public CgCamera camera() {
            return camera;
        }

        @Override
        public boolean visible(@Nullable Matrix4fc at, float x0, float y0, float x1, float y1) {
            // Asked at the base node alone: the rect's device bounds against the clip and the target
            if (at == null || base != 0) return true;
            float ax = at.m00(), bx = at.m01(), cx = at.m10(), dx = at.m11(), ox = at.m30(), oy = at.m31();
            float minX = Math.min(Math.min(ax * x0 + cx * y0, ax * x1 + cx * y0), Math.min(ax * x0 + cx * y1, ax * x1 + cx * y1)) + ox;
            float maxX = Math.max(Math.max(ax * x0 + cx * y0, ax * x1 + cx * y0), Math.max(ax * x0 + cx * y1, ax * x1 + cx * y1)) + ox;
            float minY = Math.min(Math.min(bx * x0 + dx * y0, bx * x1 + dx * y0), Math.min(bx * x0 + dx * y1, bx * x1 + dx * y1)) + oy;
            float maxY = Math.max(Math.max(bx * x0 + dx * y0, bx * x1 + dx * y0), Math.max(bx * x0 + dx * y1, bx * x1 + dx * y1)) + oy;
            float left = clipNode == base ? Math.max(0f, clipX0) : 0f, top = clipNode == base ? Math.max(0f, clipY0) : 0f;
            float right = Math.min(camera.width(), clipNode == base ? clipX1 : Float.MAX_VALUE);
            float bottom = Math.min(camera.height(), clipNode == base ? clipY1 : Float.MAX_VALUE);
            return maxX > left && maxY > top && minX < right && minY < bottom;
        }

        @Nullable
        @Override
        public CgGraphTexture target() {
            return target;
        }

        @Override
        public void textQuads(CgMaterialInterface material, @Nullable CgRenderState state, @Nullable CgTexture texture,
                              float[] records, int from, int count, int spatial) {
            if (count <= 0) return;
            int stride = CgQuadRecord.FLOATS;
            float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY;
            float x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
            for (int o = from * stride, end = (from + count) * stride; o < end; o += stride) {
                float ox = records[o + CgQuadRecord.ORIGIN], oy = records[o + CgQuadRecord.ORIGIN + 1];
                float rx = records[o + CgQuadRecord.RIGHT], ry = records[o + CgQuadRecord.RIGHT + 1];
                float ux = records[o + CgQuadRecord.UP], uy = records[o + CgQuadRecord.UP + 1];
                // A pixel past the corners, as the quad primitive's coverage reaches
                x0 = Math.min(x0, ox + Math.min(0f, rx) + Math.min(0f, ux) - 1f);
                y0 = Math.min(y0, oy + Math.min(0f, ry) + Math.min(0f, uy) - 1f);
                x1 = Math.max(x1, ox + Math.max(0f, rx) + Math.max(0f, ux) + 1f);
                y1 = Math.max(y1, oy + Math.max(0f, ry) + Math.max(0f, uy) + 1f);
            }
            recorder.draw(CgInstanceKind.QUAD, material, state, texture, null, spatial, records, from * stride, count,
                    x0, y0, x1, y1);
            grow(spatial, x0, y0, x1, y1);
        }
    }

    // ── Paths ──────────────────────────────────────────────────────────

    /** {@code path} filled in one colour, by its fill rule. */
    public CgCanvas fill(CgVectorPath path, int argb) {
        return fill(path, CgPaint.color(argb));
    }

    /**
     * {@code path} filled by its fill rule in {@code paint}: a colour or a gradient, the gradient laid over the path's
     * own bounds. Exact-area coverage, at any transform.
     */
    public CgCanvas fill(CgVectorPath path, CgPaint paint) {
        path(path, null, paint);
        return this;
    }

    /** {@code path} outlined by {@code stroke} in one colour. */
    public CgCanvas stroke(CgVectorPath path, CgStroke stroke, int argb) {
        return stroke(path, stroke, CgPaint.color(argb));
    }

    /**
     * {@code path} outlined by {@code stroke} in {@code paint}. Stroked in the path's units, so a non-uniform scale
     * shapes the pen; thinner than a device pixel, it draws a pixel wide and fades instead.
     */
    public CgCanvas stroke(CgVectorPath path, CgStroke stroke, CgPaint paint) {
        path(path, Objects.requireNonNull(stroke, "stroke"), paint);
        return this;
    }

    /**
     * A path's draw: its coverage rasterised (into the shared atlas when it is small, axis-aligned and on the pixel
     * grid, else into a texture for this frame), then one element painting through it.
     */
    private void path(CgVectorPath path, @Nullable CgStroke stroke, CgPaint paint) {
        if (recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        if (!(paint instanceof CgPaint.Solid) && !(paint instanceof CgGradient)) {
            paintThrough(path, stroke, paint);
            return;
        }
        if (path.isEmpty() || opacity <= 0f) return;
        float scale = (float) Math.sqrt(Math.max(a * a + b * b, c * c + d * d));
        if (!(scale > 0f)) return;
        float fade = 1f;
        if (stroke != null && stroke.width() * scale < 1f) {
            // A hairline: a device pixel wide, faded by how much thinner it is
            fade = stroke.width() * scale;
            stroke = stroke.width(1f / scale);
        }
        // The ink's local bounds: a stroke reaches past the path by its half-width, a miter or a square cap further
        float reach = stroke == null ? 0f : stroke.width() * 0.5f
                * Math.max(1.4142135f, stroke.join() == CgStrokeJoin.MITER ? stroke.miterLimit() : 1f);
        float lx0 = path.minX() - reach, ly0 = path.minY() - reach, lx1 = path.maxX() + reach, ly1 = path.maxY() + reach;
        boolean cached = b == 0f && c == 0f && a > 0f && d > 0f && snap
                && (lx1 - lx0) * a <= CgPathCache.MAX_PX && (ly1 - ly0) * d <= CgPathCache.MAX_PX;
        // A cached path sits on the pixel grid: its translation snapped, its raster built without it
        float rtx = cached ? 0f : tx, rty = cached ? 0f : ty;
        float placeX = cached ? Math.round(tx) : 0f, placeY = cached ? Math.round(ty) : 0f;
        deviceBounds(lx0, ly0, lx1, ly1, rtx, rty);
        int cornerX = (int) Math.floor(bounds[0]) - CgPathCache.PAD, cornerY = (int) Math.floor(bounds[1]) - CgPathCache.PAD;
        int w = (int) Math.ceil(bounds[2]) + CgPathCache.PAD - cornerX, h = (int) Math.ceil(bounds[3]) + CgPathCache.PAD - cornerY;
        float ex0 = placeX + cornerX, ey0 = placeY + cornerY, ex1 = ex0 + w, ey1 = ey0 + h;
        if (clipNode == base && (ex1 <= clipX0 || ey1 <= clipY0 || ex0 >= clipX1 || ey0 >= clipY1)) return;

        CgGraphTexture texture;
        float u0, v0, u1, v1;
        CgPathCache.Entry cell = cached ? cell(path, stroke, w, h) : null;
        if (cell != null) {
            texture = cell.texture();
            if (cell.takeBuild()) build(cell, path, stroke, cornerX, cornerY);
            u0 = cell.x() / (float) CgPathCache.SIZE;
            u1 = (cell.x() + w) / (float) CgPathCache.SIZE;
            // A render target's row 0 is its bottom
            v0 = 1f - cell.y() / (float) CgPathCache.SIZE;
            v1 = 1f - (cell.y() + h) / (float) CgPathCache.SIZE;
            pathDraws++;
        } else {
            texture = CgGraphTexture.transientTexture("canvas_path", CgTextureDesc.of(w, h, CgPathCache.FORMAT));
            raster(texture, true, rasterCamera.screenLens(w, h), path, stroke, a, b, c, d, rtx - cornerX, rty - cornerY,
                    0f, 0f, w, h);
            u0 = 0f;
            u1 = 1f;
            v0 = 1f;
            v1 = 0f;
        }

        pass();
        clear();
        int tint = (Math.round(255f * fade) << 24) | 0x00FFFFFF;
        float gain = 1f;
        if (paint instanceof CgPaint.Solid solid) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, mul(solid.argb(), tint), 0f);
            gain = solid.intensity();
        } else {
            CgUiElements.fill(record, 0, CgUiElements.FILL_GRADIENT, tint, recording.gradients().add((CgGradient) paint));
        }
        CgUiElements.uv(record, 0, u0, v0, u1, v1);
        paintFrame(path, ex0, ey0, cached);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        finish(base, gain);
        element(pathMaterial != null ? pathMaterial : elementMaterial(), texture, null, base, record, 0, 1, ex0, ey0, ex1,
                ey1);
    }

    /** A brush or grid on a path: the paint over the path's bounds, in a layer masked by its fill or its stroke. */
    private void paintThrough(CgVectorPath path, @Nullable CgStroke stroke, CgPaint paint) {
        float reach = stroke == null ? 0f : stroke.width() * 0.5f
                * Math.max(1.4142135f, stroke.join() == CgStrokeJoin.MITER ? stroke.miterLimit() : 1f);
        float x = path.minX() - reach, y = path.minY() - reach;
        float w = path.maxX() - path.minX() + 2f * reach, h = path.maxY() - path.minY() + 2f * reach;
        try (CgCanvasLayer l = layer(x, y, w, h).mask(path)) {
            l.maskStroke = stroke;
            box(x, y, w, h).fill(paint).draw();
        }
    }

    /** The cell for a cached path: claimed, built or pending; null when this canvas rasterises it for its frame. */
    @Nullable
    private CgPathCache.Entry cell(CgVectorPath path, @Nullable CgStroke stroke, int w, int h) {
        CgPathCache.Key key = new CgPathCache.Key(path, stroke, a, d);
        CgPathCache.Entry cell = CgPathCache.SHARED.find(key);
        if (cell == null) cell = CgPathCache.SHARED.claim(key, w, h, this);
        // Another canvas's cell, not built yet: drawing it would read a build that may not have executed
        if (cell == null || !cell.ready() && cell.owner() != this) return null;
        return cell;
    }

    /** A claimed cell's raster, recorded into this recording, opened to every canvas once it has executed. */
    private void build(CgPathCache.Entry cell, CgVectorPath path, @Nullable CgStroke stroke, int cornerX, int cornerY) {
        CgGraphTexture atlas = atlasFor(cell);
        cell.origin(-cornerX, -cornerY);
        raster(atlas, false, atlasCamera, path, stroke, a, 0f, 0f, d, cell.x() - cornerX, cell.y() - cornerY,
                cell.x(), cell.y(), cell.x() + cell.width(), cell.y() + cell.height());
        recording.callback("cg.path.ready", null, () -> CgPathCache.SHARED.ready(cell), atlas);
    }

    /**
     * A claimed cell's atlas, its generation's release and clear recorded first when this canvas owes them: the clear
     * opens the generation to every canvas once it has executed.
     */
    private CgGraphTexture atlasFor(CgPathCache.Entry cell) {
        CgGraphTexture atlas = cell.texture();
        CgGraphTexture released = cell.takeRelease();
        if (released != null) recording.release(released);
        if (cell.takeClear()) {
            recorder.end();
            recording.raster("path-atlas-clear").color(atlas, CgLoad.CLEAR).camera(atlasCamera).end();
            CgPathCache.Generation generation = cell.generation();
            recording.callback("cg.path.atlas-ready", null, () -> CgPathCache.SHARED.ready(generation), atlas);
        }
        return atlas;
    }

    /**
     * Records {@code path}'s exact-area coverage into {@code texture} under the affine, landing the canvas's pass first:
     * the raster executes before the element that reads it, and the canvas's earlier items before both.
     */
    private void raster(CgGraphTexture texture, boolean clear, CgCamera lens, CgVectorPath path, @Nullable CgStroke stroke,
                        float ra, float rb, float rc, float rd, float rtx, float rty, float x0, float y0, float x1, float y1) {
        recorder.end();
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(path, ra, rb, rc, rd, rtx, rty);
        boolean evenOdd = false;
        if (stroke != null) {
            float scale = (float) Math.sqrt(Math.max(ra * ra + rb * rb, rc * rc + rd * rd));
            local = CgPathStroker.outline(local, stroke, CgPathFlattener.TOLERANCE / scale);
        } else {
            evenOdd = path.fillRule() == CgFillRule.EVEN_ODD;
        }
        List<CgPathFlattener.Contour> device = CgPathFlattener.transform(local, ra, rb, rc, rd, rtx, rty);
        int n = cells.fill(device, evenOdd, 0f, 0f);
        coverage.begin(recording, texture, clear ? CgLoad.CLEAR : CgLoad.load(), lens);
        if (n > 0) {
            coverage.draw(CgInstanceKind.CURVE, CgCanvasPrimitives.coverage(), null, null, null, 0, cells.records(), 0, n,
                    x0, y0, x1, y1);
        } else if (clear) {
            coverage.openPass();
        }
        coverage.end();
    }

    /** The device bounds of a local rect under the transform's linear part and the translation given, into {@link #bounds}. */
    private void deviceBounds(float x0, float y0, float x1, float y1, float ttx, float tty) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1;
            float dx = a * x + c * y + ttx, dy = b * x + d * y + tty;
            minX = Math.min(minX, dx);
            minY = Math.min(minY, dy);
            maxX = Math.max(maxX, dx);
            maxY = Math.max(maxY, dy);
        }
        bounds[0] = minX;
        bounds[1] = minY;
        bounds[2] = maxX;
        bounds[3] = maxY;
    }

    /**
     * The element's paint frame: from a pixel's offset from its corner {@code (ex0, ey0)} to the path's own box, 0 to
     * 1, through the transform's inverse. A cached path's translation is its snapped place.
     */
    private void paintFrame(CgVectorPath path, float ex0, float ey0, boolean cached) {
        float det = a * d - b * c;
        float ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        float ttx = cached ? Math.round(tx) : tx, tty = cached ? Math.round(ty) : ty;
        float bw = Math.max(path.maxX() - path.minX(), 1e-6f), bh = Math.max(path.maxY() - path.minY(), 1e-6f);
        // local = inverse * (corner + e - t); unit = (local - min) / size
        float ox = ex0 - ttx, oy = ey0 - tty;
        float lx = ia * ox + ic * oy, ly = ib * ox + id * oy;
        CgUiElements.paintFrame(record, 0, ia / bw, ib / bh, ic / bw, id / bh, (lx - path.minX()) / bw,
                (ly - path.minY()) / bh, bw, bh);
    }

    // ── SVG ────────────────────────────────────────────────────────────

    /** {@code svg} fitted into the rect and centred, in its own colours: {@code image(CgBrush.svg(svg), x, y, w, h)}. */
    public CgCanvas svg(CgSvg svg, float x, float y, float w, float h) {
        return image(CgBrush.svg(svg), x, y, w, h);
    }

    /** Below this many raster pixels across, an icon's raster is built at this and drawn minified: two texels are no icon. */
    private static final float MIN_RASTER_PX = 8f;
    /** Raster scales per doubling, off the pixel grid: a raster is drawn at most a sixteenth of a doubling smaller. */
    private static final float LADDER_STEPS = 16f;

    /** Documents whose drop shadow was skipped, logged once each. Threads: any (concurrent). */
    private static final Set<CgSvg> SHADOWS_LOGGED = ConcurrentHashMap.newKeySet();

    /** An SVG op's raster identity in the path cache: the tier's op, coloured or white, a stroke's raster half-width. */
    private record SvgOp(CgSvg tier, int op, boolean coloured, float halfWidth) {
    }

    /**
     * An SVG brush over a rect: each op's exact-area coverage, from the path cache's atlas when the icon is at most
     * {@link CgPathCache#MAX_PX} device pixels and axis-aligned, else rasterised for this frame; then an element per op
     * in document order, tinted. Snapped, an icon is drawn 1:1 on the pixel grid; unsnapped (animated) between pixels,
     * from its exact raster or the ladder's next rung, filtered.
     */
    private void svg(CgBrush brush, float x, float y, float w, float h, int tint) {
        CgSvg doc = brush.svg();
        float dw = doc.width(), dh = doc.height();
        if (doc.isEmpty() || !(dw > 0f && dh > 0f && w > 0f && h > 0f) || opacity <= 0f) return;
        float sx = w / dw, sy = h / dh, ox = x, oy = y;
        if (brush.fit() == CgFit.CONTAIN) {
            sx = sy = Math.min(sx, sy);
            ox = x + (w - dw * sx) * 0.5f;
            oy = y + (h - dh * sy) * 0.5f;
        }
        // The document's units to device pixels
        float da = a * sx, db = b * sx, dc = c * sy, dd = d * sy;
        float dtx = a * ox + c * oy + tx, dty = b * ox + d * oy + ty;
        float devicePx = Math.max(dw * (float) Math.hypot(da, db), dh * (float) Math.hypot(dc, dd));
        boolean cached = db == 0f && dc == 0f && da > 0f && dd > 0f && devicePx <= CgPathCache.MAX_PX;
        if (cached && snap) {
            dtx = Math.round(dtx);
            dty = Math.round(dty);
        }
        // A flat brush's stroke width is the canvas's units: device pixels by the transform's scale
        float override = brush.flat() && brush.strokeWidth() > 0f ? brush.strokeWidth() * 0.5f * linearScale() : 0f;
        float reach = override * CgSvgOps.Op.CAP_REACH;
        affineBounds(doc.minX(), doc.minY(), doc.maxX(), doc.maxY(), da, db, dc, dd, dtx, dty);
        if (clipNode == base && (bounds[2] + reach <= clipX0 || bounds[3] + reach <= clipY0 || bounds[0] - reach >= clipX1
                || bounds[1] - reach >= clipY1)) {
            return;
        }
        CgSvg tier = doc.tier(devicePx);
        boolean shadows = !brush.flat() && tier.hasShadow();
        boolean layered = shadows && layerable();
        if (shadows && !layered && SHADOWS_LOGGED.add(doc)) {
            LOGGER.info(doc + " has a drop shadow, not drawn inside a turned or scaled movable node (a layer's picture"
                    + " cannot turn with it)");
        }
        List<CgSvgOps.Op> ops = tier.ops();
        for (int i = 0; i < ops.size(); ) {
            // A filtered element's ops share one shadow instance: the shadow is of their union, under them
            CgSvgScene.DropShadow shadow = layered ? ops.get(i).shadow() : null;
            int end = i + 1;
            while (end < ops.size() && (layered ? ops.get(end).shadow() : null) == shadow) end++;
            if (shadow == null) {
                for (; i < end; i++) svgOp(tier, i, ops.get(i), brush, tint, cached, override, da, db, dc, dd, dtx, dty);
                continue;
            }
            try (CgCanvasLayer l = layer(ox + doc.minX() * sx, oy + doc.minY() * sy, (doc.maxX() - doc.minX()) * sx,
                    (doc.maxY() - doc.minY()) * sy).filter(svgShadow(shadow, sx, sy))) {
                for (; i < end; i++) svgOp(tier, i, ops.get(i), brush, tint, cached, override, da, db, dc, dd, dtx, dty);
            }
        }
    }

    /** The last SVG shadow's filter, kept: an icon with one draws it every frame. */
    @Nullable private CgSvgScene.DropShadow lastShadow;
    private float lastShadowSx, lastShadowSy;
    @Nullable private CgFilter lastShadowFilter;

    /** {@code shadow}, in document units, as a filter in the canvas's: the document's scale applied. */
    private CgFilter svgShadow(CgSvgScene.DropShadow shadow, float sx, float sy) {
        if (shadow != lastShadow || sx != lastShadowSx || sy != lastShadowSy) {
            lastShadow = shadow;
            lastShadowSx = sx;
            lastShadowSy = sy;
            lastShadowFilter = CgFilter.dropShadow(shadow.dx() * sx, shadow.dy() * sy, shadow.sigmaX() * sx,
                    shadow.sigmaY() * sy, shadow.argb());
        }
        return lastShadowFilter;
    }

    /** Whether a layer may open here: no turned or scaled movable node above the current one ({@link #owner}). */
    private boolean layerable() {
        for (int n = base; n != 0; n = recording.spatial().parent(n)) {
            if (recording.spatial().local(n, 0) != 1f || recording.spatial().local(n, 1) != 0f
                    || recording.spatial().local(n, 2) != 0f || recording.spatial().local(n, 3) != 1f) {
                return false;
            }
        }
        return true;
    }

    /** One op: its coverage, cached or this frame's, then the element painting through it. */
    private void svgOp(CgSvg tier, int index, CgSvgOps.Op op, CgBrush brush, int tint, boolean cached, float override,
                       float da, float db, float dc, float dd, float dtx, float dty) {
        boolean coloured = op.coloured() && !brush.flat();
        float halfWidth = op.fill() ? 0f : override;
        float[] ob = op.bounds();
        CgGraphTexture texture = null;
        float u0 = 0f, v0 = 1f, u1 = 1f, v1 = 0f, ex0 = 0f, ey0 = 0f, ex1 = 0f, ey1 = 0f;
        if (cached) {
            // The raster's scale: the draw's, floored to MIN_RASTER_PX across, off the grid the ladder's rung at or above
            float rx = da, ry = dd;
            float span = Math.max(tier.width() * rx, tier.height() * ry);
            if (span < MIN_RASTER_PX) {
                rx *= MIN_RASTER_PX / span;
                ry *= MIN_RASTER_PX / span;
            }
            boolean onPixel = dtx == Math.round(dtx) && dty == Math.round(dty) && rx == da && ry == dd;
            CgPathCache.Entry cell = null;
            int cornerX = 0, cornerY = 0, w = 0, h = 0;
            for (int attempt = 0; attempt < 2 && cell == null; attempt++) {
                if (attempt == 1) {
                    if (onPixel) break;
                    float k = rung(Math.max(rx, ry)) / Math.max(rx, ry);
                    rx *= k;
                    ry *= k;
                }
                float rasterHalf = halfWidth * rx / da;
                int grow = (int) Math.ceil(rasterHalf * CgSvgOps.Op.CAP_REACH);
                cornerX = (int) Math.floor(ob[0] * rx) - CgPathCache.PAD - grow;
                cornerY = (int) Math.floor(ob[1] * ry) - CgPathCache.PAD - grow;
                w = (int) Math.ceil(ob[2] * rx) + CgPathCache.PAD + grow - cornerX;
                h = (int) Math.ceil(ob[3] * ry) + CgPathCache.PAD + grow - cornerY;
                CgPathCache.Key key = new CgPathCache.Key(new SvgOp(tier, index, coloured, rasterHalf), null, rx, ry);
                cell = CgPathCache.SHARED.find(key);
                // The exact raster is built only on the grid; between pixels a missing one is the rung's
                if (cell == null && (onPixel || attempt == 1)) cell = CgPathCache.SHARED.claim(key, w, h, this);
                if (cell != null && !cell.ready() && cell.owner() != this) cell = null;
                if (cell != null && cell.takeBuild()) {
                    CgGraphTexture atlas = atlasFor(cell);
                    cell.origin(-cornerX, -cornerY);
                    rasterSvg(atlas, false, atlasCamera, op, coloured, rasterHalf, rx, 0f, 0f, ry,
                            cell.x() - cornerX, cell.y() - cornerY, cell.x(), cell.y(), cell.x() + cell.width(),
                            cell.y() + cell.height());
                    CgPathCache.Entry built = cell;
                    recording.callback("cg.path.ready", null, () -> CgPathCache.SHARED.ready(built), atlas);
                }
            }
            if (cell != null) {
                texture = cell.texture();
                float kx = da / rx, ky = dd / ry;
                ex0 = dtx + cornerX * kx;
                ey0 = dty + cornerY * ky;
                ex1 = ex0 + w * kx;
                ey1 = ey0 + h * ky;
                u0 = cell.x() / (float) CgPathCache.SIZE;
                u1 = (cell.x() + w) / (float) CgPathCache.SIZE;
                // A render target's row 0 is its bottom
                v0 = 1f - cell.y() / (float) CgPathCache.SIZE;
                v1 = 1f - (cell.y() + h) / (float) CgPathCache.SIZE;
                pathDraws++;
            }
        }
        if (texture == null) {
            // This frame's raster, in device pixels: larger, turned, or another canvas's cell not built yet
            affineBounds(ob[0], ob[1], ob[2], ob[3], da, db, dc, dd, dtx, dty);
            int grow = (int) Math.ceil(halfWidth * CgSvgOps.Op.CAP_REACH);
            int cornerX = (int) Math.floor(bounds[0]) - CgPathCache.PAD - grow;
            int cornerY = (int) Math.floor(bounds[1]) - CgPathCache.PAD - grow;
            int w = (int) Math.ceil(bounds[2]) + CgPathCache.PAD + grow - cornerX;
            int h = (int) Math.ceil(bounds[3]) + CgPathCache.PAD + grow - cornerY;
            ex0 = cornerX;
            ey0 = cornerY;
            ex1 = cornerX + w;
            ey1 = cornerY + h;
            if (clipNode == base && (ex1 <= clipX0 || ey1 <= clipY0 || ex0 >= clipX1 || ey0 >= clipY1)) return;
            texture = CgGraphTexture.transientTexture("canvas_svg", CgTextureDesc.of(w, h, CgPathCache.FORMAT));
            rasterSvg(texture, true, rasterCamera.screenLens(w, h), op, coloured, halfWidth, da, db, dc, dd,
                    dtx - cornerX, dty - cornerY, 0f, 0f, w, h);
            u0 = 0f;
            u1 = 1f;
            v0 = 1f;
            v1 = 0f;
        } else if (clipNode == base && (ex1 <= clipX0 || ey1 <= clipY0 || ex0 >= clipX1 || ey0 >= clipY1)) {
            return;
        }

        pass();
        clear();
        if (coloured) {
            // Its own colours, premultiplied in the raster: the tint multiplies them
            CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, tint, 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        } else {
            int argb = brush.flat() || op.currentColor() ? brush.color() : op.argb();
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, mul(argb, tint), 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_COVERAGE, true);
        }
        CgUiElements.uv(record, 0, u0, v0, u1, v1);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        finish(base, 1f);
        element(elementMaterial(), texture, null, base, record, 0, 1, ex0, ey0, ex1, ey1);
    }

    /** Records op {@code op}'s coverage into {@code texture} under the affine, landing the canvas's pass first. */
    private void rasterSvg(CgGraphTexture texture, boolean clear, CgCamera lens, CgSvgOps.Op op, boolean coloured,
                           float halfWidth, float ra, float rb, float rc, float rd, float rtx, float rty,
                           float x0, float y0, float x1, float y1) {
        recorder.end();
        int n = svgCells.op(op, coloured, halfWidth, ra, rb, rc, rd, rtx, rty);
        coverage.begin(recording, texture, clear ? CgLoad.CLEAR : CgLoad.load(), lens);
        if (n > 0) {
            coverage.draw(CgInstanceKind.CURVE, coloured ? CgCanvasPrimitives.colourCoverage() : CgCanvasPrimitives.coverage(),
                    null, null, null, 0, svgCells.records(), 0, n, x0, y0, x1, y1);
        } else if (clear) {
            coverage.openPass();
        }
        coverage.end();
    }

    /** The rung of the raster ladder at or above {@code exact}: an icon growing through a hundred scales builds a dozen rasters. */
    private static float rung(float exact) {
        return (float) Math.pow(2.0, Math.ceil(Math.log(exact) / Math.log(2.0) * LADDER_STEPS) / LADDER_STEPS);
    }

    /** The device bounds of a local rect under the affine given, into {@link #bounds}. */
    private void affineBounds(float x0, float y0, float x1, float y1, float ra, float rb, float rc, float rd,
                              float rtx, float rty) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1;
            float px = ra * x + rc * y + rtx, py = rb * x + rd * y + rty;
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        bounds[0] = minX;
        bounds[1] = minY;
        bounds[2] = maxX;
        bounds[3] = maxY;
    }

    // ── Backdrop ───────────────────────────────────────────────────────

    /** The canvas's panes this turn, reused turn to turn: one each, since each holds its backdrop's values. */
    private final List<CgCanvasGlass> panes = new ArrayList<>();
    private int panesUsed;
    @Nullable private CgBackdrop backdrop;
    private final float[] quadRecord = new float[CgQuadRecord.FLOATS];
    /** The quad record's fade, 1 - opacity, as the old canvas's quads write it. */
    private static final int QUAD_FADE = CgInstanceKind.QUAD.format().getField("fade").getFloatOffset();

    /**
     * {@code box}'s pane: what is drawn under it captured (the target, then each open layer over it), blurred, and drawn
     * through the glass material as one quad, its corners the box's. The old canvas's pane, value for value.
     */
    private void pane(CgCanvasBox box) {
        CgBackdropFilter f = box.backdrop;
        CgCanvasGlass glass = pane();
        CgCornerRadii fit = box.radii == null ? null : box.radii.fitted(box.w, box.h);
        glass.look(f).size(box.w, box.h);
        if (fit == null) {
            glass.radii(0f);
        } else {
            glass.radii(fit.topLeftX(), fit.topLeftY(), fit.topRightX(), fit.topRightY(), fit.bottomRightX(),
                    fit.bottomRightY(), fit.bottomLeftX(), fit.bottomLeftY());
        }
        // The quad in the root's device pixels, bottom-left as the capture counts them
        float ox = a * box.x + c * box.y + tx, oy = b * box.x + d * box.y + ty;
        float rx = a * box.w, ry = b * box.w, ux = c * box.h, uy = d * box.h;
        // A layer's node is its outer base's, untransformed: the base's recorded place is the root's offset either way
        float shiftX = 0f, shiftY = 0f;
        if (base != 0) {
            float[] o = recordedOrigin(base);
            shiftX = o[0];
            shiftY = o[1];
        }
        float px0 = ox + Math.min(0f, rx) + Math.min(0f, ux) + shiftX, px1 = ox + Math.max(0f, rx) + Math.max(0f, ux) + shiftX;
        float py0 = oy + Math.min(0f, ry) + Math.min(0f, uy) + shiftY, py1 = oy + Math.max(0f, ry) + Math.max(0f, uy) + shiftY;
        int w = Math.max(1, Math.round(camera.width())), h = Math.max(1, Math.round(camera.height()));
        float local = Math.max(1e-6f, Math.max(box.w, box.h));
        float pixels = Math.max(px1 - px0, py1 - py0);
        float pad = f.blur() + glass.reach() * pixels / local + 4f;
        int cx0 = Math.max(0, (int) Math.floor(px0 - pad)), cx1 = Math.min(w, (int) Math.ceil(px1 + pad));
        int cy0 = Math.max(0, (int) Math.floor(h - py1 - pad)), cy1 = Math.min(h, (int) Math.ceil(h - py0 + pad));
        if (cx1 <= cx0 || cy1 <= cy0) return;
        recorder.end();
        if (frame != null) frame.canvas().endPass();
        if (backdrop == null) backdrop = new CgBackdrop("canvas.backdrop");
        CgGraphTexture capture = backdrop.capture(recording, target, w, h, CgBackdrop.formatOf(target), cx0, cy0, cx1, cy1);
        if (layers > 0) layersOver(capture, w, h);
        backdrop.blur(recording, f.blur());
        // Into the innermost layer: where its picture's (0, 0) is in the capture's bottom-left texels
        float offsetX = 0f, offsetY = 0f;
        if (layers > 0) {
            CgCanvasLayer top = layerStack[layers - 1];
            if (!top.opened) place(top);
            offsetX = top.ownerX + top.x0;
            offsetY = h - (top.ownerY + top.y0 + top.h);
        }
        glass.backdrop(backdrop, offsetX, offsetY, backdrop.x0(), backdrop.y0(), backdrop.x1(), backdrop.y1()).apply();
        pass();
        Arrays.fill(quadRecord, 0f);
        quadRecord[CgQuadRecord.ORIGIN] = ox;
        quadRecord[CgQuadRecord.ORIGIN + 1] = oy;
        quadRecord[CgQuadRecord.RIGHT] = rx;
        quadRecord[CgQuadRecord.RIGHT + 1] = ry;
        quadRecord[CgQuadRecord.UP] = ux;
        quadRecord[CgQuadRecord.UP + 1] = uy;
        quadRecord[CgQuadRecord.UV1] = 1f;
        quadRecord[CgQuadRecord.UV1 + 1] = 1f;
        for (int k = 0; k < 4; k++) quadRecord[CgQuadRecord.COLOR + k] = 1f;
        quadRecord[QUAD_FADE] = 1f - opacity;
        quadRecord[CgQuadRecord.CLIP] = clip;
        quadRecord[CgQuadRecord.NODE] = CgPalette.pack(base, 0);
        float bx0 = ox + Math.min(0f, rx) + Math.min(0f, ux) - 1f, bx1 = ox + Math.max(0f, rx) + Math.max(0f, ux) + 1f;
        float by0 = oy + Math.min(0f, ry) + Math.min(0f, uy) - 1f, by1 = oy + Math.max(0f, ry) + Math.max(0f, uy) + 1f;
        recorder.draw(CgInstanceKind.QUAD, glass.material(), null, null, null, base, quadRecord, 0, 1, bx0, by0, bx1, by1);
        grow(base, bx0, by0, bx1, by1);
    }

    /** Each open layer's picture so far over the capture, outermost first: what a pane in the innermost sees behind it. */
    private void layersOver(CgGraphTexture capture, int w, int h) {
        filters.begin(recording, capture, CgLoad.load(), filterLens.screenLens(capture.getWidth(), capture.getHeight()));
        for (int i = 0; i < layers; i++) {
            CgCanvasLayer l = layerStack[i];
            if (!l.opened) continue;
            float x = l.ownerX + l.x0, y = capture.getHeight() - h + l.ownerY + l.y0;
            filterElement(CgUiElements.FILL_TEXTURE, 0xFFFFFFFF, true, x, y, l.w, l.h);
            filters.draw(CgInstanceKind.ELEMENT, elementMaterial(), null, l.texture, null, 0, filterRecord, 0, 1,
                    x, y, x + l.w, y + l.h);
        }
        filters.end();
    }

    /** A pane's glass for this turn. */
    private CgCanvasGlass pane() {
        if (panesUsed == panes.size()) panes.add(new CgCanvasGlass(CgPlacement.SCREEN));
        return panes.get(panesUsed++);
    }

    /** A node's recorded translation in the root. */
    private float[] recordedOrigin(int n) {
        float ox = 0f, oy = 0f;
        for (; n != 0; n = recording.spatial().parent(n)) {
            ox += recording.spatial().local(n, 4);
            oy += recording.spatial().local(n, 5);
        }
        originScratch[0] = ox;
        originScratch[1] = oy;
        return originScratch;
    }

    private final float[] originScratch = new float[2];

    // ── Drawing a box ──────────────────────────────────────────────────

    void draw(CgCanvasBox box) {
        if (recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        float w = box.w, h = box.h;
        if (!(w > 0f && h > 0f) || opacity <= 0f) return;
        if (box.backdrop != null) {
            if (insideKept()) {
                // A kept picture of glass would be stale: flat, its fallback under the box's own paint
                flatBox.reset(box.x, box.y, box.w, box.h).fill(box.backdrop.fallback());
                flatBox.radii = box.radii;
                draw(flatBox);
            } else {
                pane(box);
            }
            // Over the glass, the box's own paint only where it was given one
            if (!box.filled && box.shadow == null
                    && box.borderTop + box.borderRight + box.borderBottom + box.borderLeft <= 0f) {
                return;
            }
        }
        pass();
        CgMaterialInterface material = box.material != null ? box.material : elementMaterial();
        if (box.shadow != null && !box.shadow.inside()) shadow(box, material);
        CgPaint paint = box.paint;
        if (paint instanceof CgBrush brush && brush.svg() != null) {
            svg(brush, box.x, box.y, w, h, mul(brush.tint(), box.argb));
            borderOver(box, material);
        } else if (paint instanceof CgBrush brush && (brush.nineSlice() != null || brush.tiling() != CgTiling.STRETCH)) {
            brushTiles(box, brush, material);
        } else {
            element(box, material, box.x, box.y, w, h);
        }
        if (box.shadow != null && box.shadow.inside()) shadow(box, material);
    }

    /** One element of {@code box}'s paint over a rect of it, its corners and border the box's where they touch. */
    private void element(CgCanvasBox box, CgMaterialInterface material, float x, float y, float w, float h) {
        clear();
        CgTexture texture = null;
        CgSampler sampler = null;
        int argb = box.argb;
        CgPaint paint = box.paint;
        if (paint instanceof CgPaint.Solid solid) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, solid.argb(), 0f);
            emit(box, material, null, null, x, y, w, h, solid.intensity(), true);
            return;
        }
        if (paint instanceof CgGradient gradient) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_GRADIENT, argb, recording.gradients().add(gradient));
        } else if (paint instanceof CgGrid grid) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_GRID, grid.lineColor(), Float.intBitsToFloat(grid.secondColor()));
            CgUiElements.uv(record, 0, grid.cellWidth(), grid.cellHeight(), grid.lineWidth(), grid.lineHeight());
            CgUiElements.flag(record, 0, CgUiElements.FLAG_CHECKER, grid.checker());
        } else if (paint instanceof CgBrush brush) {
            texture = brush.texture();
            sampler = brush.sampler();
            CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, mul(brush.tint(), argb), 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, brush.premultiplied());
            float tw = texture.getWidth(), th = texture.getHeight();
            if (brush.hasRegion() && tw > 0 && th > 0) {
                CgUiElements.uv(record, 0, brush.regionX() / tw, brush.regionY() / th,
                        (brush.regionX() + brush.regionWidth()) / tw, (brush.regionY() + brush.regionHeight()) / th);
            }
        } else if (box.texture != null) {
            texture = box.texture;
            CgUiElements.fill(record, 0, box.mask ? CgUiElements.FILL_MASK : CgUiElements.FILL_TEXTURE, argb, 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, box.premultiplied);
            CgUiElements.uv(record, 0, box.u0, box.v0, box.u1, box.v1);
        } else {
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, box.filled ? argb : 0, 0f);
        }
        emit(box, material, texture, sampler, x, y, w, h, 1f, !(paint instanceof CgGrid));
    }

    /** A brush's nine-slice or tiles, as one element a piece. */
    private void brushTiles(CgCanvasBox box, CgBrush brush, CgMaterialInterface material) {
        tiles.brush = brush;
        tiles.argb = mul(brush.tint(), box.argb);
        tiles.material = material;
        CgCanvasTiles.of(brush, box.x, box.y, box.w, box.h, MAX_TILES, tiles);
        tiles.brush = null;
        tiles.material = null;
        borderOver(box, material);
    }

    /** A box's border over what its brush drew: one more element, transparent but its border. */
    private void borderOver(CgCanvasBox box, CgMaterialInterface material) {
        if (box.borderTop + box.borderRight + box.borderBottom + box.borderLeft > 0f) {
            clear();
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, 0, 0f);
            emit(box, material, null, null, box.x, box.y, box.w, box.h, 1f, true);
        }
    }

    /** Where a brush's pieces go: one element each. Reused, so laying tiles allocates nothing. */
    private final class Tiles implements CgCanvasTiles.Sink {
        @Nullable CgBrush brush;
        @Nullable CgMaterialInterface material;
        int argb;

        @Override
        public void piece(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
            clear();
            CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, argb, 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, brush.premultiplied());
            CgUiElements.uv(record, 0, u0, v0, u1, v1);
            int at = spatial();
            float[] r = rect(at, x, y, w, h, true);
            if (r == null) return;
            finish(at, 1f);
            element(material, brush.texture(), brush.sampler(), at, record, 0, 1, r[0], r[1], r[2], r[3]);
        }
    }

    private final Tiles tiles = new Tiles();

    private void shadow(CgCanvasBox box, CgMaterialInterface material) {
        CgShadow s = box.shadow;
        clear();
        boolean inside = s.inside();
        int at = spatial();
        // A blur is in the record's units: device pixels where the transform is baked
        float sigma = s.sigma() * (at == base ? linearScale() : 1f);
        float x = box.x, y = box.y, w = box.w, h = box.h;
        if (inside) {
            // The hole is the box less its border and spread, offset; in the box's units from its corner
            float hx0 = box.borderLeft + s.spread() + s.dx(), hy0 = box.borderTop + s.spread() + s.dy();
            float hx1 = w - box.borderRight - s.spread() + s.dx(), hy1 = h - box.borderBottom - s.spread() + s.dy();
            CgUiElements.fill(record, 0, CgUiElements.FILL_INSET_SHADOW, s.argb(), sigma);
            CgUiElements.uv(record, 0, hx0 * kx(at), hy0 * ky(at), hx1 * kx(at), hy1 * ky(at));
            emitShadow(box, material, at, x, y, w, h, 0f, 0f);
        } else {
            CgUiElements.fill(record, 0, CgUiElements.FILL_SHADOW, s.argb(), sigma);
            emitShadow(box, material, at, x + s.dx() - s.spread(), y + s.dy() - s.spread(), w + 2 * s.spread(),
                    h + 2 * s.spread(), s.spread(), 3f * sigma);
        }
    }

    /** A shadow's element; its ink reaches {@code reach} past its rect, in the record's units. */
    private void emitShadow(CgCanvasBox box, CgMaterialInterface material, int at, float x, float y, float w,
                            float h, float grow, float reach) {
        float[] r = rect(at, x, y, w, h, false, reach);
        if (r == null) return;
        radii(box, at, grow);
        finish(at, 1f);
        element(material, null, null, at, record, 0, 1, r[0] - reach, r[1] - reach, r[2] + reach, r[3] + reach);
    }

    private void emit(CgCanvasBox box, CgMaterialInterface material, @Nullable CgTexture texture,
                      @Nullable CgSampler sampler, float x, float y, float w, float h, float gain, boolean snapIt) {
        int at = spatial();
        float[] r = rect(at, x, y, w, h, snapIt);
        if (r == null) return;
        radii(box, at, 0f);
        if (box.borderTop + box.borderRight + box.borderBottom + box.borderLeft > 0f) {
            float kx = kx(at), ky = ky(at);
            CgUiElements.border(record, 0, box.borderTop * ky, box.borderRight * kx, box.borderBottom * ky,
                    box.borderLeft * kx, box.borderTopColor, box.borderRightColor, box.borderBottomColor,
                    box.borderLeftColor);
        }
        finish(at, gain);
        element(material, texture, sampler, at, record, 0, 1, r[0], r[1], r[2], r[3]);
    }

    private void element(CgMaterialInterface material, @Nullable CgTexture texture, @Nullable CgSampler sampler, int at,
                         float[] records, int from, int count, float x0, float y0, float x1, float y1) {
        recorder.draw(CgInstanceKind.ELEMENT, material, null, texture, sampler, at, records, from, count, x0, y0, x1, y1);
        grow(at, x0, y0, x1, y1);
    }

    /** A chunk being taken grows by what is drawn in its node. */
    private void grow(int at, float x0, float y0, float x1, float y1) {
        if (chunking && at == chunkNode) {
            chunkX0 = Math.min(chunkX0, x0);
            chunkY0 = Math.min(chunkY0, y0);
            chunkX1 = Math.max(chunkX1, x1);
            chunkY1 = Math.max(chunkY1, y1);
        }
    }

    private final float[] rect = new float[4];

    /**
     * The rect in node {@code at}'s space into the record: device pixels at node 0, snapped when asked; null when it is
     * outside the clip.
     */
    @Nullable
    private float[] rect(int at, float x, float y, float w, float h, boolean snapIt) {
        return rect(at, x, y, w, h, snapIt, 0f);
    }

    /** As {@link #rect(int, float, float, float, float, boolean)}, its ink reaching {@code reach} past it. */
    @Nullable
    private float[] rect(int at, float x, float y, float w, float h, boolean snapIt, float reach) {
        float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        if (at == base) {
            x0 = deviceX(x);
            y0 = deviceY(y);
            x1 = deviceX(x + w);
            y1 = deviceY(y + h);
            if (snapIt && snap) {
                x0 = Math.round(x0);
                y0 = Math.round(y0);
                x1 = Math.round(x1);
                y1 = Math.round(y1);
                if (x1 <= x0 || y1 <= y0) return null;
            }
            if (clipNode == base && (x1 + reach <= clipX0 || y1 + reach <= clipY0 || x0 - reach >= clipX1
                    || y0 - reach >= clipY1)) {
                return null;
            }
        }
        CgUiElements.rect(record, 0, x0, y0, x1, y1);
        rect[0] = x0;
        rect[1] = y0;
        rect[2] = x1;
        rect[3] = y1;
        return rect;
    }

    /** The box's corners into the record, grown by a shadow's spread. */
    private void radii(CgCanvasBox box, int at, float grow) {
        CgCornerRadii radii = box.radii;
        if (radii == null || radii.isNone()) return;
        float kx = kx(at), ky = ky(at);
        CgCornerRadii fit = radii.fitted(box.w, box.h);
        CgUiElements.radii(record, 0,
                grown(fit.topLeftX(), grow) * kx, grown(fit.topLeftY(), grow) * ky,
                grown(fit.topRightX(), grow) * kx, grown(fit.topRightY(), grow) * ky,
                grown(fit.bottomRightX(), grow) * kx, grown(fit.bottomRightY(), grow) * ky,
                grown(fit.bottomLeftX(), grow) * kx, grown(fit.bottomLeftY(), grow) * ky);
    }

    /** A radius grown by a shadow's spread, as CSS grows it: a square corner stays square. */
    private static float grown(float r, float spread) {
        return r > 0f ? Math.max(0f, r + spread) : 0f;
    }

    private void finish(int at, float gain) {
        CgUiElements.nodes(record, 0, CgPalette.pack(at, 0), clip);
        CgUiElements.opacity(record, 0, opacity, intensity * gain);
    }

    /** A fresh record: zero but its uv, the whole texture. */
    private void clear() {
        Arrays.fill(record, 0f);
        CgUiElements.uv(record, 0, 0f, 0f, 1f, 1f);
    }

    // ── The transform ──────────────────────────────────────────────────

    /** Whether the transform maps axes to axes with positive scale: what bakes into the record at node 0. */
    private boolean axisAligned() {
        return b == 0f && c == 0f && a > 0f && d > 0f;
    }

    /**
     * The spatial node items are positioned in: the base node when the transform is axis-aligned (baked into their
     * rects), else a node under it with the transform, made once per change.
     */
    private int spatial() {
        if (axisAligned()) return base;
        if (nodeStale || node == 0) {
            node = recording.spatial().add(base, a, b, c, d, tx, ty, false);
            nodeStale = false;
        }
        return node;
    }

    private float deviceX(float x) {
        return a * x + tx;
    }

    private float deviceY(float y) {
        return d * y + ty;
    }

    /** Logical to record units along x, y: the scale where baked, 1 under a node. */
    private float kx(int at) {
        return at == base ? a : 1f;
    }

    private float ky(int at) {
        return at == base ? d : 1f;
    }

    /** The transform's mean linear scale: what a blur's sigma scales by. */
    private float linearScale() {
        return (float) Math.sqrt(Math.abs(a * d - b * c));
    }

    private void resetClip() {
        clip = 0;
        clipBase = 0;
        base = 0;
        clipIsRect = true;
        clipNode = 0;
        clipX0 = clipY0 = -Float.MAX_VALUE;
        clipX1 = clipY1 = Float.MAX_VALUE;
    }

    /** Two {@code 0xAARRGGBB} colours multiplied channel by channel. */
    static int mul(int p, int q) {
        if (q == 0xFFFFFFFF) return p;
        if (p == 0xFFFFFFFF) return q;
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = (p >>> shift) & 255, y = (q >>> shift) & 255;
            out |= ((x * y + 127) / 255) << shift;
        }
        return out;
    }
}
