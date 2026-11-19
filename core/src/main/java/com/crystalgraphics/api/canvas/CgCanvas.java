package com.crystalgraphics.api.canvas;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.effect.CgShadow;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgTiling;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.pattern.CgGrid;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.ui.CgUiRecorder;
import com.crystalgraphics.render.ui.element.CgUiElements;

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
    private static final int SAVED = 17;

    private final CgUiRecorder recorder = new CgUiRecorder();
    private final float[] record = new float[CgUiElements.FLOATS];
    private final CgCanvasBox box = new CgCanvasBox(this);
    private final Matrix4f pose = new Matrix4f();
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
    private long chunkPasses;
    private float chunkX0, chunkY0, chunkX1, chunkY1;

    private float[] stack = new float[SAVED * 16];
    private int depth;

    @Nullable private static CgMaterial element;

    /** The material every item draws with unless it names its own: the element primitive's paint, premultiplied. */
    public static synchronized CgMaterial elementMaterial() {
        if (element == null) {
            element = CgMaterial.load(ELEMENT_SHADER,
                    CgMaterialState.builder().blend(CgBlendMode.PREMULTIPLIED).twoSided().disableDepthTest().build());
            element.precache();
        }
        return element;
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
        resetClip();
        node = 0;
        nodeStale = false;
        return this;
    }

    /** Lands everything drawn and records nowhere until the next {@link #begin}. */
    public void end() {
        recorder.end();
        recording = null;
        target = null;
        frame = null;
        depth = 0;
    }

    /** Drops what is drawn and not landed, and records nowhere: its recording is being discarded. */
    public void abandon() {
        recorder.abandon();
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

    /** Pushes the transform, opacity, intensity and clip; {@link #restore} pops them. */
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
        return this;
    }

    /** Pops what the matching {@link #save} pushed. */
    public CgCanvas restore() {
        if (depth == 0) throw new IllegalStateException("restore() without a save()");
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
                recorder.passes() == chunkPasses, chunkX0, chunkY0, chunkX1, chunkY1);
    }

    /**
     * Adds what {@code chunk} kept, drawing nothing: at the state it was taken in, as the caller's key promises. False,
     * with nothing added, when nothing is kept or its clips would nest too deep: then draw it.
     */
    public boolean replay(CgPaintChunk chunk) {
        if (recording == null) throw new IllegalStateException("replaying on a canvas that records nowhere");
        pass();
        return chunk.replay(this, recording, clip, spatial());
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

    /** A brush over a rect: its region, nine-slice, tiling, sampler and tint. */
    public CgCanvas image(CgBrush brush, float x, float y, float w, float h) {
        return box(x, y, w, h).fill(brush).draw();
    }

    // ── Drawing a box ──────────────────────────────────────────────────

    void draw(CgCanvasBox box) {
        if (recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        float w = box.w, h = box.h;
        if (!(w > 0f && h > 0f) || opacity <= 0f) return;
        pass();
        CgMaterialInterface material = box.material != null ? box.material : elementMaterial();
        if (box.shadow != null && !box.shadow.inside()) shadow(box, material);
        CgPaint paint = box.paint;
        if (paint instanceof CgBrush brush && (brush.nineSlice() != null || brush.tiling() != CgTiling.STRETCH)) {
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
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, argb, 0f);
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
        // A border, radii or the box's own colour over the slices: one more element, transparent but its border
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
        recorder.element(material, texture, sampler, at, records, from, count, x0, y0, x1, y1);
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
