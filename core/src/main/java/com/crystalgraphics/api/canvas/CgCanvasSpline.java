package com.crystalgraphics.api.canvas;

import java.util.Objects;

import com.crystalgraphics.api.vector.CgStrokeCap;

/**
 * A line or a Bezier curve stroked on a canvas, drawn by its distance to the curve: no raster, no cache, cheap to draw
 * again every frame. What a graph's wires, a chart's lines and a cursor's marks are; Slate's spline element.
 *
 * <pre>{@code
 * canvas.spline().line(x0, y0, x1, y1).width(2f).color(0xFFFFFFFF).draw();
 * canvas.spline().cubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1).width(3f).colors(from, to).draw();     // a wire
 * canvas.spline().from(x0, y0).via(cx, cy).to(x1, y1).width(4f, 1f).cap(CgStrokeCap.ROUND).draw();  // tapered
 * canvas.spline().line(x0, y0, x1, y1).width(2f).cap(CgStrokeCap.ROUND, CgStrokeCap.BUTT).arrowAtEnd().draw();
 * }</pre>
 *
 * <ul>
 *   <li>Build and draw in one expression: the canvas hands out one builder, reset by the next {@code spline()}.</li>
 *   <li>Widths are full widths in canvas units, as {@code CgStroke}'s; a colour ramp runs along the curve, start to
 *       end.</li>
 *   <li>One open curve. A shape (a fill, a closed outline, an icon) is a {@code CgVectorPath}, whose raster is
 *       cached.</li>
 *   <li>Faded by the canvas's opacity; not boosted by its intensity.</li>
 * </ul>
 */
public final class CgCanvasSpline {

    private final CgCanvas canvas;

    float x0, y0, cx, cy, x1, y1, c1x, c1y, c2x, c2y;
    boolean cubic, set;
    float widthStart, widthEnd;
    int argbStart, argbEnd;
    float feather;
    CgStrokeCap capStart, capEnd;
    boolean arrow;

    CgCanvasSpline(CgCanvas canvas) {
        this.canvas = canvas;
        reset();
    }

    CgCanvasSpline reset() {
        cubic = set = arrow = false;
        widthStart = widthEnd = 1f;
        argbStart = argbEnd = 0xFFFFFFFF;
        feather = -1f;
        capStart = capEnd = CgStrokeCap.BUTT;
        return this;
    }

    /** A straight segment. */
    public CgCanvasSpline line(float x0, float y0, float x1, float y1) {
        return from(x0, y0).via((x0 + x1) * 0.5f, (y0 + y1) * 0.5f).to(x1, y1);
    }

    /** The start point of a quadratic. */
    public CgCanvasSpline from(float x, float y) {
        x0 = x;
        y0 = y;
        return this;
    }

    /** A quadratic's control point. */
    public CgCanvasSpline via(float x, float y) {
        cx = x;
        cy = y;
        return this;
    }

    /** The end point; completes a quadratic. */
    public CgCanvasSpline to(float x, float y) {
        x1 = x;
        y1 = y;
        cubic = false;
        set = true;
        return this;
    }

    /** A cubic Bezier, drawn as quadratics: more of them the larger it is drawn. */
    public CgCanvasSpline cubic(float x0, float y0, float c1x, float c1y, float c2x, float c2y, float x1, float y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.c1x = c1x;
        this.c1y = c1y;
        this.c2x = c2x;
        this.c2y = c2y;
        this.x1 = x1;
        this.y1 = y1;
        cubic = set = true;
        return this;
    }

    /** One width along the whole curve, 1 by default. */
    public CgCanvasSpline width(float width) {
        return width(width, width);
    }

    /** A width tapering from the start to the end. */
    public CgCanvasSpline width(float start, float end) {
        widthStart = Math.max(0f, start);
        widthEnd = Math.max(0f, end);
        return this;
    }

    /** One colour, opaque white by default. */
    public CgCanvasSpline color(int argb) {
        return colors(argb, argb);
    }

    /** A colour ramp from the start to the end. */
    public CgCanvasSpline colors(int start, int end) {
        argbStart = start;
        argbEnd = end;
        return this;
    }

    /** The edge's softness in canvas units; a pixel's antialiasing by default. */
    public CgCanvasSpline feather(float feather) {
        this.feather = Math.max(0f, feather);
        return this;
    }

    /** The cap at both ends, butt by default. */
    public CgCanvasSpline cap(CgStrokeCap cap) {
        return cap(cap, cap);
    }

    /** A cap each end. */
    public CgCanvasSpline cap(CgStrokeCap start, CgStrokeCap end) {
        capStart = Objects.requireNonNull(start, "start");
        capEnd = Objects.requireNonNull(end, "end");
        return this;
    }

    /** An arrowhead at the end in place of its cap: a wire pointing at its target. */
    public CgCanvasSpline arrowAtEnd() {
        arrow = true;
        return this;
    }

    /**
     * Draws it, answering the canvas.
     *
     * @throws IllegalStateException without geometry: {@link #line}, {@link #to} or {@link #cubic} first
     */
    public CgCanvas draw() {
        canvas.draw(this);
        return canvas;
    }
}
