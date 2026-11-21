package com.crystalgraphics.api.canvas;

import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.render.ui.CgCanvasTables;
import com.crystalgraphics.render.ui.CgUiRecorder;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.property.CgClipTree;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.ui.property.CgSpatialTree;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.text.render.CgTextGamma;

/**
 * A 2D canvas for screens, HUDs and textures: rectangles filled by any paint, rounded, bordered and shadowed; images
 * and nine-slices; under a transform, opacity and clip stack. What it draws lands in a recording as batched element
 * draws, in paint order. Coordinates are logical pixels from the top-left corner, scaled to the target by its
 * {@link #scale}.
 *
 * <pre>{@code
 * CgCanvas canvas = frame.canvas();                                  // a HUD or screen stage's, in window pixels
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
public final class CgCanvas {

    /** A save's fields: the affine, opacity, intensity, clip entry, clip rect and whether it is one, clip node, cull rect. */
    private static final int SAVED = 23;

    private static final Logger LOGGER = Logger.getLogger(CgCanvas.class.getName());

    // Its jobs, each holding the canvas: Skia's device to its SkCanvas
    final CgCanvasLayers layers = new CgCanvasLayers(this);
    final CgCanvasKeptLayers keptLayers = new CgCanvasKeptLayers(this);
    final CgCanvasPaths paths = new CgCanvasPaths(this);
    final CgCanvasSvg svg = new CgCanvasSvg(this);
    final CgCanvasBackdrop backdrop = new CgCanvasBackdrop(this);
    final CgCanvasBoxes boxes = new CgCanvasBoxes(this);
    final CgCanvasCurves curves = new CgCanvasCurves(this);
    final CgCanvasText text = new CgCanvasText(this);

    final CgUiRecorder recorder = new CgUiRecorder();
    /** The element record each item is written into before it lands. */
    final float[] record = new float[CgUiElements.FLOATS];
    private final CgCanvasBox box = new CgCanvasBox(this);
    private final Matrix4f pose = new Matrix4f();
    /** Draws from the path atlas so far: a chunk holding one keeps the atlas's resets it was taken at. */
    long pathDraws;
    private final float[] bounds = new float[4], rect = new float[4], origin = new float[2];
    private final float[] noRadii = new float[4], clipRx = new float[4], clipRy = new float[4];

    @Nullable CgRecording recording;
    @Nullable CgGraphTexture target;
    private CgLoad load;
    CgCamera camera;
    /** What {@link #beginNow} records into, and whether {@link #end} executes it. */
    @Nullable private CgRecording nowRecording;
    private final CgCamera nowCamera = new CgCamera();
    private boolean executeAtEnd;
    private float targetHeight;
    private float scale = 1f;
    boolean snap = true;

    // The transform from logical units to the target's pixels: x' = a x + c y + tx, y' = b x + d y + ty
    float a = 1f, b, c, d = 1f, tx, ty;
    /** The effect node items are drawn in: 0, full opacity, unless a {@link #fader} is open. */
    int effect;
    float opacity = 1f, intensity = 1f;
    // The clip: its entry, and for culling and intersecting, its device rect where it is one at node 0
    int clip;
    private boolean clipIsRect = true;
    float clipX0 = -Float.MAX_VALUE, clipY0 = -Float.MAX_VALUE, clipX1 = Float.MAX_VALUE, clipY1 = Float.MAX_VALUE;
    int clipNode;

    /**
     * What can show, conservatively, in the base node's device units: the clips' bounds, carried into a translation
     * node and cut to a kept picture inside its walk. Apart from the clip rect, which a clip entry made inside a scroll
     * node must not take: the node moves with the viewport's rect left behind. @see #clipBounds
     */
    float cullX0, cullY0, cullX1, cullY1;
    // The spatial node of the transform now, made at the first item that needs one after it changed
    private int node;
    private boolean nodeStale;
    // The movable node items bake into ({@link #movable}), 0 for the target, and the clip it was opened under
    int base;
    private int clipBase;

    // A paint chunk being taken: what the tables held at its start, and the union of what it drew
    private boolean chunking;
    private int chunkClips, chunkNodes, chunkClip, chunkNode, chunkEffects, chunkEffect;
    private long chunkTextDraws, chunkDegraded, chunkPathDraws;
    private long chunkPasses;
    private float chunkX0, chunkY0, chunkX1, chunkY1;

    private float[] stack = new float[SAVED * 16];
    int depth;

    /**
     * What this canvas draws ahead of its first frame: the element under each blend it composites with, glass in every
     * set of its layers, and its layers' blur and colour-matrix kernels. {@code CgCanvasPrimitives.precache} calls it.
     */
    public static void precache(CgPipelineList list) {
        CgUiMaterials.precache(list);
        CgCanvasGlass.precache(list);
        list.kernels(() -> {
            CgGpuOps.precacheBlur(CgFormat.RGBA16_FLOAT);
            CgGpuOps.precacheColorMatrix();
            CgGpuOps.precacheShrink(CgFormat.RGBA16_FLOAT);
        });
    }

    /** Forgets its shared materials at a context's teardown: the material registry frees them. Engine. */
    public static synchronized void releaseAll() {
        CgUiMaterials.releaseAll();
        CgCanvasGlass.release();
    }

    /**
     * Records later items into {@code target} in {@code recording}, seen through {@code camera} ({@code CgCamera.screen}
     * for a screen), at {@code scale} target pixels per logical pixel. The stack starts empty.
     */
    public CgCanvas begin(CgRecording recording, CgGraphTexture target, CgLoad load, CgCamera camera, float scale) {
        end();
        backdrop.beginTurn();
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
        cullX0 = cullY0 = 0f;
        cullX1 = camera.width();
        cullY1 = camera.height();
        node = 0;
        nodeStale = false;
        effect = 0;
        layers.beginTurn();
        keptLayers.sweep();
        return this;
    }

    /**
     * Records later items over the current target ({@code CgExecutor.into}), in its pixels, and draws them at
     * {@link #end}: a canvas outside any stage, as a loading screen or a host's own overlay draws. Render thread, inside
     * a frame and a current target.
     *
     * <pre>{@code
     * CgCanvas canvas = new CgCanvas();                  // held, reused each frame
     * canvas.beginNow(width, height);
     * canvas.fill(0, 0, width, height, 0x80000000);
     * canvas.end();                                      // drawn here
     * }</pre>
     *
     * <ul>
     *   <li>Inside a stage's turn, draw on {@code frame.canvas()} instead: it lands in paint order with the rest of the
     *       frame.</li>
     * </ul>
     */
    public CgCanvas beginNow(int width, int height) {
        end();
        if (nowRecording == null) nowRecording = new CgRecording();
        nowRecording.reset();
        begin(nowRecording, CgGraphTexture.current(), CgLoad.load(), nowCamera.screenLens(width, height), 1f);
        executeAtEnd = true;
        return this;
    }

    /** Lands everything drawn and records nowhere until the next {@link #begin}; after {@link #beginNow}, draws it. */
    public void end() {
        if (layers.count > 0) {
            // Not thrown: a turn ends in a finally, and an exception thrown inside the layer is the one to see
            LOGGER.warning("a canvas ended with " + layers.count + " layer(s) open: their drawing is dropped (close every"
                    + " layer; try-with-resources does)");
            layers.drop();
            recorder.abandon();
        }
        recorder.end();
        CgRecording drawn = executeAtEnd ? recording : null;
        executeAtEnd = false;
        recording = null;
        target = null;
        depth = 0;
        if (drawn != null) CgRenderer.executeNow(drawn);
    }

    /** Drops what is drawn and not landed, and records nowhere: its recording is being discarded. */
    public void abandon() {
        layers.abandon();
        recorder.abandon();
        paths.abandon();
        recording = null;
        target = null;
        chunking = false;
        executeAtEnd = false;
        depth = 0;
    }

    /** Lands what is drawn; a later item opens a new pass after whatever is recorded between. */
    public void endPass() {
        recorder.end();
    }

    /** A pass open on the target, or on the innermost layer's picture. */
    void pass() {
        if (recorder.recording()) return;
        if (layers.count > 0) {
            CgCanvasLayer top = layers.top();
            if (!top.opened) layers.place(top);
            recorder.begin(recording, top.texture, top.load, top.lens);
            recorder.view(top.outerBase, top.ownerX + top.x0, top.ownerY + top.y0);
            recorder.layered(true);
            top.load = CgLoad.load();
            return;
        }
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
        stack[o + 18] = Float.intBitsToFloat(effect);
        stack[o + 19] = cullX0;
        stack[o + 20] = cullY0;
        stack[o + 21] = cullX1;
        stack[o + 22] = cullY1;
        return this;
    }

    /** Pops what the matching {@link #save} pushed. */
    public CgCanvas restore() {
        if (depth == 0) throw new IllegalStateException("restore() without a save()");
        while (layers.count > 0 && layers.top().clips && depth == layers.top().saveDepth && !layers.closing) {
            layers.close(layers.top());
        }
        if (depth == 0) throw new IllegalStateException("restore() without a save()");
        if (layers.count > 0 && depth == layers.top().saveDepth && layers.top().live && !layers.closing) {
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
        effect = Float.floatToRawIntBits(stack[o + 18]);
        cullX0 = stack[o + 19];
        cullY0 = stack[o + 20];
        cullX1 = stack[o + 21];
        cullY1 = stack[o + 22];
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

    /**
     * Replaces the transform with {@code x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12}, in the space of the node
     * drawn in (the canvas's own, or the innermost {@link #movable}'s): Canvas 2D's {@code setTransform}. For a caller
     * that computes each item's whole transform, as a box tree does from its boxes' {@code localToWorld}.
     *
     * <pre>{@code
     * canvas.save().setTransform(base.m00(), base.m01(), base.m10(), base.m11(), base.m30(), base.m31());
     * paintBox(canvas);
     * canvas.restore();
     * }</pre>
     */
    public CgCanvas setTransform(float m00, float m10, float m01, float m11, float m02, float m12) {
        if (a != m00 || b != m10 || c != m01 || d != m11 || tx != m02 || ty != m12) nodeStale = true;
        a = m00;
        b = m10;
        c = m01;
        d = m11;
        tx = m02;
        ty = m12;
        return this;
    }

    /** The opacity later items are drawn at: the product of every {@link #opacity(float)} in force. */
    public float currentOpacity() {
        return opacity;
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

    /**
     * Clips later items to a rounded rect, intersected with the clip now. Clips nested past what a draw carries
     * ({@link CgClipTree#MAX_DEPTH} beyond their square intersections) become a mask layer over the shape, closed by
     * the {@link #restore} that pops the level: inside a {@code save()}, as every clip should be.
     */
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
            cull(clipX0, clipY0, clipX1, clipY1);
            pose.identity();
            int entry = CgCanvasTables.of(recording).clips().add(clipBase, base, pose, targetHeight, clipX0, clipY0, clipX1, clipY1, noRadii,
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
        int entry = CgCanvasTables.of(recording).clips().add(clip, at, pose, targetHeight, x0, y0, x1, y1, rounded ? clipRx : noRadii,
                rounded ? clipRy : noRadii, null);
        if (entry < 0) {
            // Deeper than a draw carries: a mask layer over the shape, which composites under the clips outside
            if (depth == 0) {
                throw new IllegalStateException("clips nest at most " + CgClipTree.MAX_DEPTH
                        + " deep past their square intersections outside a save()");
            }
            CgVectorPath.Builder shape = CgVectorPath.builder();
            return clip((rounded ? shape.addRoundRect(x, y, w, h, radii) : shape.addRect(x, y, w, h)).build());
        }
        clip = entry;
        clipIsRect = false;
        clipNode = at;
        if (at == base) {
            clipX0 = Math.max(clipX0, x0);
            clipY0 = Math.max(clipY0, y0);
            clipX1 = Math.min(clipX1, x1);
            clipY1 = Math.min(clipY1, y1);
            cull(x0, y0, x1, y1);
        }
        return this;
    }

    /** Cuts the cull rect to {@code (x0, y0)-(x1, y1)} in the base node's device units. */
    void cull(float x0, float y0, float x1, float y1) {
        cullX0 = Math.max(cullX0, x0);
        cullY0 = Math.max(cullY0, y0);
        cullX1 = Math.max(cullX0, Math.min(cullX1, x1));
        cullY1 = Math.max(cullY0, Math.min(cullY1, y1));
    }

    /**
     * What can show of what is drawn now, conservatively, into {@code out} as {@code x0, y0, x1, y1}: the clips' and
     * the target's bounds in the device units of the node drawn in (the canvas's own, or the innermost
     * {@link #movable}'s). Skia's {@code getDeviceClipBounds}: what a caller culls a subtree against before drawing it.
     *
     * <pre>{@code
     * float[] can = canvas.clipBounds(new float[4]);
     * if (inkX1 <= can[0] || inkY1 <= can[1] || inkX0 >= can[2] || inkY0 >= can[3]) return;   // nothing would show
     * }</pre>
     *
     * <ul>
     *   <li>Inside a {@link #kept} walk, the walk's picture: a held picture keeps what is off the target.</li>
     *   <li>Under a scroll node, the bounds as recorded: drawing culled by them is missing if the node later moves
     *       without a new recording.</li>
     * </ul>
     */
    public float[] clipBounds(float[] out) {
        out[0] = cullX0;
        out[1] = cullY0;
        out[2] = cullX1;
        out[3] = cullY1;
        return out;
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
        CgCanvasLayer l = layers.open(path.minX(), path.minY(), path.maxX() - path.minX(), path.maxY() - path.minY());
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
        return movable(1f, 0f, 0f, 1f, 0f, 0f);
    }

    /**
     * As {@link #movable()}, the node carrying the transform now followed by the affine
     * {@code x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12}: an animated transform a replay sets again with
     * {@link #moved}, nothing drawn.
     *
     * <pre>{@code
     * canvas.save();
     * int spin = canvas.movable(cos, sin, -sin, cos, 0, 0);    // recorded once, in a chunk
     * paintBadge(canvas);
     * canvas.restore();
     * // a later frame: canvas.replay(kept); canvas.save(); canvas.moved(kept.node(spin), cos2, sin2, -sin2, cos2, 0, 0); ...
     * }</pre>
     *
     * <ul>
     *   <li>An axis-aligned scale now stays baked into what is drawn inside, so text is rasterised at it; the node
     *       carries the rest. A replay must begin under the same scale.</li>
     * </ul>
     */
    public int movable(float m00, float m10, float m01, float m11, float m02, float m12) {
        if (recording == null) throw new IllegalStateException("a movable node on a canvas that records nowhere");
        int n = CgCanvasTables.of(recording).spatial().add(base, 1f, 0f, 0f, 1f, 0f, 0f, true);
        enterNode(n, m00, m10, m01, m11, m02, m12);
        base = n;
        nodeStale = true;
        clipBase = clip;
        clipIsRect = true;
        clipNode = n;
        clipX0 = clipY0 = -Float.MAX_VALUE;
        clipX1 = clipY1 = Float.MAX_VALUE;
        cullInto(n);
        return n;
    }

    /**
     * Draws later items in {@code node} again, a node {@link #movable} opened this frame under the node drawn in now,
     * until the matching {@link #restore}: one scroll's content split by children that hold still, as a table's
     * header between its rows. The transform is left as it is: set it in the node's space.
     *
     * <pre>{@code
     * canvas.save();
     * int content = canvas.translate(0, -scrollY).movable();
     * paintRows(canvas, 0, 20);
     * canvas.restore();
     * paintHeader(canvas);                         // holds still
     * canvas.save();
     * canvas.enter(content);
     * paintRows(canvas, 20, 40);                   // scrolls with the first rows
     * canvas.restore();
     * }</pre>
     *
     * @throws IllegalArgumentException for a node that is not movable, or was opened under another
     */
    public CgCanvas enter(int node) {
        if (recording == null) throw new IllegalStateException("entering a node on a canvas that records nowhere");
        CgSpatialTree tree = CgCanvasTables.of(recording).spatial();
        if (node <= 0 || node >= tree.count() || !tree.movable(node) || tree.parent(node) != base) {
            throw new IllegalArgumentException("spatial node " + node + " is not a movable node opened under node " + base);
        }
        base = node;
        nodeStale = true;
        clipBase = clip;
        clipIsRect = true;
        clipNode = node;
        clipX0 = clipY0 = -Float.MAX_VALUE;
        clipX1 = clipY1 = Float.MAX_VALUE;
        cullInto(node);
        return this;
    }

    /** The cull rect carried into node {@code n}'s space: through a translation, else the whole plane. */
    private void cullInto(int n) {
        CgSpatialTree tree = CgCanvasTables.of(recording).spatial();
        if (tree.local(n, 0) == 1f && tree.local(n, 1) == 0f && tree.local(n, 2) == 0f && tree.local(n, 3) == 1f) {
            float dx = tree.local(n, 4), dy = tree.local(n, 5);
            cullX0 -= dx;
            cullY0 -= dy;
            cullX1 -= dx;
            cullY1 -= dy;
        } else {
            cullX0 = cullY0 = -Float.MAX_VALUE;
            cullX1 = cullY1 = Float.MAX_VALUE;
        }
    }

    // ── Layers ─────────────────────────────────────────────────────────

    /**
     * Opens a layer over the live clip: what is drawn until it closes lands in a picture of its own, composited as one.
     * Set it up (opacity, filter, mask) before drawing in it. @see CgCanvasLayer
     */
    public CgCanvasLayer layer() {
        return layers.open(Float.NaN, 0f, 0f, 0f);
    }

    /** As {@link #layer()}, its picture bounded by a rect in the units now, grown by its filter's reach. */
    public CgCanvasLayer layer(float x, float y, float w, float h) {
        if (!(w >= 0f && h >= 0f)) throw new IllegalArgumentException("a layer of " + w + " x " + h);
        return layers.open(x, y, w, h);
    }

    // ── Kept layers ────────────────────────────────────────────────────

    /**
     * Walks {@code kept} over a rect in the units now, composited when the walk closes: drawn when {@code revision} or
     * its device size changed since the last walk, else held and nothing recorded for it. @see CgKeptLayer
     */
    public CgKeptLayer.Walk kept(CgKeptLayer kept, float x, float y, float w, float h, long revision) {
        return keptLayers.walk(kept, x, y, w, h, revision);
    }

    /**
     * {@code kept}'s picture over a rect in the units now, as last drawn: a preview. Nothing when it holds none.
     *
     * <pre>{@code
     * canvas.picture(WINDOW, x, y, 320, 200);
     * }</pre>
     */
    public CgCanvas picture(CgKeptLayer kept, float x, float y, float w, float h) {
        return picture(kept, 0f, 0f, kept.pictureWidth, kept.pictureHeight, x, y, w, h);
    }

    /**
     * Part of {@code kept}'s picture, {@code (sx, sy, sw, sh)} in its device pixels from its top-left, over a rect in the
     * units now: Canvas 2D's nine-argument {@code drawImage}. A window's border box without the shadow its picture holds.
     *
     * <pre>{@code
     * canvas.picture(WINDOW, 12, 12, 640, 400, x, y, 320, 200);
     * }</pre>
     */
    public CgCanvas picture(CgKeptLayer kept, float sx, float sy, float sw, float sh, float x, float y, float w, float h) {
        keptLayers.picture(kept, sx, sy, sw, sh, x, y, w, h);
        return this;
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
        return thumbnail(kept, 0f, 0f, kept.pictureWidth, kept.pictureHeight, x, y, w, h);
    }

    /** As {@link #thumbnail(CgKeptLayer, float, float, float, float)}, of part of the picture as {@link #picture} takes it. */
    public CgCanvas thumbnail(CgKeptLayer kept, float sx, float sy, float sw, float sh, float x, float y, float w,
                              float h) {
        keptLayers.thumbnail(kept, sx, sy, sw, sh, x, y, w, h);
        return this;
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
        chunkClips = CgCanvasTables.of(recording).clips().count();
        chunkNodes = CgCanvasTables.of(recording).spatial().count();
        chunkEffects = CgCanvasTables.of(recording).effects().count();
        chunkEffect = effect;
        chunkClip = clip;
        chunkNode = spatial();
        chunkX0 = chunkY0 = Float.MAX_VALUE;
        chunkX1 = chunkY1 = -Float.MAX_VALUE;
        chunkTextDraws = text.draws;
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
        return into.capture(this, count, recording, chunkClips, chunkNodes, chunkEffects, chunkClip, chunkNode, chunkEffect,
                recorder.passes() == chunkPasses, degradedDraws() != chunkDegraded,
                text.draws != chunkTextDraws ? text.gamma : null, pathDraws != chunkPathDraws, chunkX0, chunkY0, chunkX1,
                chunkY1);
    }

    /**
     * Adds what {@code chunk} kept, drawing nothing: at the state it was taken in, as the caller's key promises. False,
     * with nothing added, when nothing is kept or its clips would nest too deep: then draw it.
     */
    public boolean replay(CgPaintChunk chunk) {
        if (recording == null) throw new IllegalStateException("replaying on a canvas that records nowhere");
        pass();
        return chunk.replay(this, recording, clip, spatial(), effect, text.gamma);
    }

    /**
     * Opens an effect node, an opacity group drawn at {@code opacity} times the one around it, and answers its id: what
     * later items are drawn in until the matching {@link #restore}. Every item multiplies its own opacity by it, as
     * Slate's render opacity does; a sealed frame or a replayed chunk changes it with nothing drawn again
     * ({@link #nodeOpacity}, {@code frame.values().opacity(node, f)}). The effect twin of {@link #movable}.
     *
     * <pre>{@code
     * canvas.save();
     * int fade = canvas.fader(0.5f);
     * paintCard(canvas);
     * canvas.restore();
     * }</pre>
     *
     * <ul>
     *   <li>Per item, not as a group: overlapping items show through each other. For a group faded as one picture,
     *       {@code layer().opacity(f)}.</li>
     * </ul>
     */
    public int fader(float opacity) {
        if (recording == null) throw new IllegalStateException("an effect node on a canvas that records nowhere");
        effect = CgCanvasTables.of(recording).effects().add(effect, Math.max(0f, Math.min(1f, opacity)));
        return effect;
    }

    /**
     * Sets a replayed chunk's movable node as {@link #movable(float, float, float, float, float, float)} opened it,
     * here: the transform now followed by the affine; later transforms are inside it, as they were. Records nothing: a
     * replay's pose walk, between {@link #save} and {@link #restore}.
     *
     * <pre>{@code
     * if (canvas.replay(kept)) {
     *     canvas.save();
     *     canvas.moved(kept.node(spin), cos, sin, -sin, cos, 0, 0);
     *     canvas.restore();
     * }
     * }</pre>
     */
    public CgCanvas moved(int node, float m00, float m10, float m01, float m11, float m02, float m12) {
        if (recording == null) throw new IllegalStateException("a node value on a canvas that records nowhere");
        if (!CgCanvasTables.of(recording).spatial().movable(node)) {
            throw new IllegalArgumentException("spatial node " + node + " is not movable: open it with movable()");
        }
        enterNode(node, m00, m10, m01, m11, m02, m12);
        return this;
    }

    /**
     * Node {@code n}'s affine: the transform now, then {@code m}; the transform becomes what stays baked inside it. An
     * axis-aligned scale stays, so its items' edges snap and its text rasterises at it; anything else is the node's.
     */
    private void enterNode(int n, float m00, float m10, float m01, float m11, float m02, float m12) {
        float na = a * m00 + c * m10, nb = b * m00 + d * m10;
        float nc = a * m01 + c * m11, nd = b * m01 + d * m11;
        float ntx = a * m02 + c * m12 + tx, nty = b * m02 + d * m12 + ty;
        if (axisAligned()) {
            CgCanvasTables.of(recording).spatial().set(n, na / a, nb / a, nc / d, nd / d, ntx, nty);
            b = c = tx = ty = 0f;
        } else {
            CgCanvasTables.of(recording).spatial().set(n, na, nb, nc, nd, ntx, nty);
            a = d = 1f;
            b = c = tx = ty = 0f;
        }
        nodeStale = true;
    }

    /** The transform now into {@code out}: {@code x' = out[0] x + out[2] y + out[4], y' = out[1] x + out[3] y + out[5]}. */
    public float[] transform(float[] out) {
        out[0] = a;
        out[1] = b;
        out[2] = c;
        out[3] = d;
        out[4] = tx;
        out[5] = ty;
        return out;
    }

    /** Whether a chunk is being taken: {@link #beginChunk} and {@link #endChunk} do not nest. */
    public boolean chunking() {
        return chunking;
    }

    /**
     * Sets movable node {@code node}'s affine in its parent's space for this frame: a node made by {@link #movable}, or
     * one a replayed chunk made ({@link CgPaintChunk#node}).
     *
     * <pre>{@code
     * if (canvas.replay(kept)) canvas.nodeTransform(kept.node(window), 1, 0, 0, 1, x, y);   // moved, nothing drawn
     * }</pre>
     */
    public CgCanvas nodeTransform(int node, float a, float b, float c, float d, float tx, float ty) {
        if (recording == null) throw new IllegalStateException("a node value on a canvas that records nowhere");
        if (!CgCanvasTables.of(recording).spatial().movable(node)) {
            throw new IllegalArgumentException("spatial node " + node + " is not movable: open it with movable()");
        }
        CgCanvasTables.of(recording).spatial().set(node, a, b, c, d, tx, ty);
        return this;
    }

    /** Sets effect node {@code node}'s opacity for this frame: one {@link #fader} made, or a replayed chunk's. */
    public CgCanvas nodeOpacity(int node, float opacity) {
        if (recording == null) throw new IllegalStateException("a node value on a canvas that records nowhere");
        CgCanvasTables.of(recording).effects().set(node, Math.max(0f, Math.min(1f, opacity)));
        return this;
    }

    CgDrawChunk taped(int i) {
        return recorder.taped(i);
    }

    void addChunk(CgDrawChunk sealed) {
        recorder.add(sealed);
        if (backdrop.captured) {
            for (int d = 0; d < sealed.draws(); d++) {
                backdrop.painted(sealed.spatial(), sealed.x0(d), sealed.y0(d), sealed.x1(d), sealed.y1(d));
            }
        }
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
        this.text.draw(text, x, y, argb);
        return this;
    }

    /**
     * Text drawn as a {@link CgTextDraw}, the canvas's one, under the transform, clip and gamma now: every knob of the
     * text renderer (a layer's scope, a pixel size, a paragraph) with nothing made per draw. Build and submit in one
     * expression; {@link #text(CgText, float, float, int)} is the everyday form.
     *
     * <pre>{@code
     * canvas.textDraw().layout(cached).family(ui).at(x, y).color(argb).submit();
     * }</pre>
     *
     * <ul>
     *   <li>Its colour is multiplied by the canvas's opacity when it is handed out, as every item's is.</li>
     * </ul>
     */
    public CgTextDraw textDraw() {
        if (recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        return text.draw();
    }

    /** The coverage correction later text is drawn with; {@link CgTextGamma#initial()} at first. Not saved. */
    public CgCanvas textGamma(CgTextGamma gamma) {
        text.gamma = Objects.requireNonNull(gamma, "gamma");
        return this;
    }

    /**
     * Text items that drew a glyph or shadow below the tier they asked for, still generating. Rises only: a caller
     * keeping what it drew (a surface, a chunk) paints again while it moves.
     */
    public long degradedDraws() {
        return text.degraded();
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
        paths.draw(path, null, paint, CgUiMaterials.element());
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
        paths.draw(path, Objects.requireNonNull(stroke, "stroke"), paint, CgUiMaterials.element());
        return this;
    }

    // ── SVG ────────────────────────────────────────────────────────────

    /** {@code svg} fitted into the rect and centred, in its own colours: {@code image(CgBrush.svg(svg), x, y, w, h)}. */
    public CgCanvas svg(CgSvg svg, float x, float y, float w, float h) {
        return image(CgBrush.svg(svg), x, y, w, h);
    }

    // ── Splines ─────────────────────────────────────────────────────────

    /**
     * A line or Bezier curve to stroke: build it and {@link CgCanvasSpline#draw()} in one expression.
     *
     * <pre>{@code
     * canvas.spline().cubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1).width(3f).colors(from, to).draw();
     * }</pre>
     */
    public CgCanvasSpline spline() {
        return curves.spline.reset();
    }

    /**
     * A filled triangle: build it and {@link CgCanvasTriangle#draw()} in one expression.
     *
     * <pre>{@code
     * canvas.triangle(x0, y0, x1, y1, x2, y2).color(argb).draw();
     * }</pre>
     */
    public CgCanvasTriangle triangle(float x0, float y0, float x1, float y1, float x2, float y2) {
        return curves.triangle.reset(x0, y0, x1, y1, x2, y2);
    }

    // ── The record: what every part writes an item through ─────────────

    /** The record as one element in node {@code at}, over {@code (x0, y0)-(x1, y1)} in its units. */
    void element(CgMaterialInterface material, @Nullable CgTexture texture, @Nullable CgSampler sampler, int at,
                 float x0, float y0, float x1, float y1) {
        recorder.draw(CgInstanceKind.ELEMENT, material, null, texture, sampler, at, record, 0, 1, x0, y0, x1, y1);
        grow(at, x0, y0, x1, y1);
    }

    /** What is drawn grows a chunk being taken in its node, and what was painted over the capture. */
    void grow(int at, float x0, float y0, float x1, float y1) {
        if (backdrop.captured) backdrop.painted(at, x0, y0, x1, y1);
        if (chunking && at == chunkNode) {
            chunkX0 = Math.min(chunkX0, x0);
            chunkY0 = Math.min(chunkY0, y0);
            chunkX1 = Math.max(chunkX1, x1);
            chunkY1 = Math.max(chunkY1, y1);
        }
    }

    /**
     * The rect in node {@code at}'s space into the record: device pixels at the base node, snapped when asked; null when
     * it is outside the clip, its ink reaching {@code reach} past it.
     */
    @Nullable
    float[] rect(int at, float x, float y, float w, float h, boolean snapIt, float reach) {
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

    /** The record's nodes and opacity: drawn in node {@code at}, its colour times {@code gain}. */
    void finish(int at, float gain) {
        CgUiElements.nodes(record, 0, CgPalette.pack(at, effect), clip);
        CgUiElements.opacity(record, 0, opacity, intensity * gain);
    }

    /** The record, fresh: zero but its uv, the whole texture. */
    float[] clear() {
        Arrays.fill(record, 0f);
        CgUiElements.uv(record, 0, 0f, 0f, 1f, 1f);
        return record;
    }

    // ── The transform ──────────────────────────────────────────────────

    /** Whether the transform has no rotation or skew: what {@link #snapX} and {@link #snapY} need. */
    public boolean rectilinear() {
        return Math.abs(b) < 1e-5f && Math.abs(c) < 1e-5f;
    }

    /**
     * How many device pixels one unit covers under the transform now, on its larger axis: what a level-of-detail
     * decision keys on, since one logical size is twice the pixels at a scale of 2.
     */
    public float deviceScale() {
        return (float) Math.max(Math.hypot(a, b), Math.hypot(c, d));
    }

    /**
     * The X nearest {@code x} that lands on a whole device pixel: hinted icon art keeps its edges sharp there. {@code x}
     * itself under rotation or skew.
     */
    public float snapX(float x) {
        if (!rectilinear() || Math.abs(a) < 1e-6f) return x;
        return (Math.round(a * x + tx) - tx) / a;
    }

    /** The Y-axis twin of {@link #snapX}. */
    public float snapY(float y) {
        if (!rectilinear() || Math.abs(d) < 1e-6f) return y;
        return (Math.round(d * y + ty) - ty) / d;
    }

    /**
     * Whether {@code (x0, y0)-(x1, y1)}, in the device pixels of the node drawn in, misses what the clip can show:
     * a cull, conservative as {@link #clipBounds} is. Allocates nothing.
     */
    public boolean outsideClip(float x0, float y0, float x1, float y1) {
        return x1 <= cullX0 || x0 >= cullX1 || y1 <= cullY0 || y0 >= cullY1;
    }

    /** Whether the transform maps axes to axes with positive scale: what bakes into the record at node 0. */
    private boolean axisAligned() {
        return b == 0f && c == 0f && a > 0f && d > 0f;
    }

    /**
     * The spatial node items are positioned in: the base node when the transform is axis-aligned (baked into their
     * rects), else a node under it with the transform, made once per change.
     */
    int spatial() {
        if (axisAligned()) return base;
        if (nodeStale || node == 0) {
            node = CgCanvasTables.of(recording).spatial().add(base, a, b, c, d, tx, ty, false);
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
    float kx(int at) {
        return at == base ? a : 1f;
    }

    float ky(int at) {
        return at == base ? d : 1f;
    }

    /** The transform's mean linear scale: what a blur's sigma scales by. */
    float linearScale() {
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

    /** The state inside a layer just opened: items in {@code node}, unclipped, at full opacity. */
    void enterLayer(int node) {
        base = node;
        nodeStale = true;
        clip = 0;
        clipBase = 0;
        clipIsRect = true;
        clipNode = node;
        opacity = 1f;
        intensity = 1f;
        effect = 0;
    }

    /** Whether a device rect at the base node is wholly outside the clip rect: nothing of it would show. */
    boolean clippedOut(float x0, float y0, float x1, float y1) {
        return clipNode == base && (x1 <= clipX0 || y1 <= clipY0 || x0 >= clipX1 || y0 >= clipY1);
    }

    /** The device bounds of a local rect under the affine given, into a scratch array: x0, y0, x1, y1. */
    float[] affineBounds(float x0, float y0, float x1, float y1, float ra, float rb, float rc, float rd,
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
        return bounds;
    }

    /** A point under the transform now, x and y. */
    float mapX(float x, float y) {
        return a * x + c * y + tx;
    }

    float mapY(float x, float y) {
        return b * x + d * y + ty;
    }

    /** A node's recorded translation in the root, into a scratch array. */
    float[] recordedOrigin(int n) {
        CgSpatialTree tree = CgCanvasTables.of(recording).spatial();
        float ox = 0f, oy = 0f;
        for (; n != 0; n = tree.parent(n)) {
            ox += tree.local(n, 4);
            oy += tree.local(n, 5);
        }
        origin[0] = ox;
        origin[1] = oy;
        return origin;
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
