package com.crystalgraphics.api.canvas;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;

/**
 * A group drawn into a picture of its own, then composited as one: faded as a whole, filtered, masked. CSS's
 * isolation ({@code opacity} below 1, {@code filter}, {@code mask}); Slate's retainer without the keeping. A scope:
 * set it up, draw, close it.
 *
 * <pre>{@code
 * try (CgCanvasLayer layer = canvas.layer().opacity(0.5f)) {          // a card fading as one picture
 *     drawCard(canvas);                                                // overlaps inside do not show through
 * }
 * try (CgCanvasLayer layer = canvas.layer(0, 0, 200, 120).filter(CgFilter.blur(6f))) {
 *     drawPanel(canvas);                                               // blurred as one, its reach included
 * }
 * try (CgCanvasLayer layer = canvas.layer().mask(CgVectorPath.circle(32, 32, 32))) {
 *     canvas.image(avatar, 0, 0, 64, 64);                              // cut to the circle, antialiased
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Set it up before drawing in it: its picture is placed at the first draw, sized by its bounds (or the clip) and
 *       its filter's reach. A setting after that throws.</li>
 *   <li>Close layers in the reverse of their opening; a {@code try} does. What the layer saved and transformed inside
 *       is restored at its close.</li>
 *   <li>Its picture is premultiplied RGBA16F and lives for the frame: nothing is kept (kept layers are another thing).</li>
 *   <li>Drawn nothing, it composites nothing.</li>
 * </ul>
 */
public final class CgCanvasLayer implements AutoCloseable {

    final CgCanvas canvas;

    float opacity;
    CgFilter filter;
    CgBlendMode blend;
    @Nullable CgVectorPath maskPath;
    @Nullable CgPaint maskPaint;
    /** A stroked path's mask: the outline, not the fill. */
    @Nullable CgStroke maskStroke;
    /** Opened by {@code clip(path)}: the {@code restore()} of the level it was made at closes it. */
    boolean clips;

    /** The bounds asked for, device pixels in the outer base's space; NaN when none: the clip's. */
    float boundX0, boundY0, boundX1, boundY1;
    /** The clip outside it, device pixels in the same space: what bounds a layer with none, and culls. */
    float clipX0, clipY0, clipX1, clipY1;
    /** The transform's mean scale at its opening: what a filter's lengths are multiplied by. */
    float scale;
    /** The canvas's save depth after its opening. */
    int saveDepth;
    /** The node its items draw under, and the outer base its picture composites at. */
    int node, outerBase;
    /** The outer base's recorded translation in the root: where the region sits there. */
    float ownerX, ownerY;

    boolean opened, live;
    /** Its picture's region in the outer base's space, whole pixels. */
    int x0, y0, w, h;
    @Nullable CgGraphTexture texture;
    CgLoad load = CgLoad.CLEAR;
    final CgCamera lens = new CgCamera();

    CgCanvasLayer(CgCanvas canvas) {
        this.canvas = canvas;
    }

    void reset() {
        opacity = 1f;
        filter = CgFilter.NONE;
        blend = CgBlendMode.PREMULTIPLIED;
        maskPath = null;
        maskPaint = null;
        maskStroke = null;
        clips = false;
        boundX0 = boundY0 = boundX1 = boundY1 = Float.NaN;
        opened = false;
        live = true;
        texture = null;
        load = CgLoad.CLEAR;
    }

    /** Fades the picture as a whole by {@code f}, 0..1: what CSS's {@code opacity} on a group is. */
    public CgCanvasLayer opacity(float f) {
        settable();
        opacity = Math.max(0f, Math.min(1f, f));
        return this;
    }

    /** Runs the picture through {@code filter} before it is composited; the region grows by its reach. */
    public CgCanvasLayer filter(CgFilter filter) {
        settable();
        this.filter = Objects.requireNonNull(filter, "filter");
        return this;
    }

    /**
     * Keeps the picture only inside {@code path} (in the canvas's units as they are now), antialiased: an avatar's
     * circle, a clip by any shape.
     */
    public CgCanvasLayer mask(CgVectorPath path) {
        settable();
        maskPath = Objects.requireNonNull(path, "path");
        maskPaint = null;
        return this;
    }

    /**
     * Keeps the picture by {@code paint}'s alpha over the layer's bounds: a gradient fading it out, an image's
     * silhouette. CSS's {@code mask-image}.
     */
    public CgCanvasLayer mask(CgPaint paint) {
        settable();
        maskPaint = Objects.requireNonNull(paint, "paint");
        maskPath = null;
        return this;
    }

    /** How the picture lands on what is under it: {@link CgBlendMode#PREMULTIPLIED} (over) by default, ADDITIVE for a glow. */
    public CgCanvasLayer blend(CgBlendMode blend) {
        settable();
        if (blend != CgBlendMode.PREMULTIPLIED && blend != CgBlendMode.ADDITIVE) {
            throw new IllegalArgumentException("a layer composites PREMULTIPLIED or ADDITIVE, not " + blend);
        }
        this.blend = blend;
        return this;
    }

    /** Filters, masks and composites what was drawn in it, and restores the state its opening saved. */
    @Override
    public void close() {
        canvas.closeLayer(this);
    }

    private void settable() {
        if (!live) throw new IllegalStateException("a closed layer");
        if (opened) throw new IllegalStateException("a layer is set up before anything is drawn in it");
    }
}
