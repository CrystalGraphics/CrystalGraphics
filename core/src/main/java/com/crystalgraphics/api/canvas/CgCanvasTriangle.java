package com.crystalgraphics.api.canvas;

import java.util.Objects;

/**
 * A filled triangle on a canvas, a colour or a linear ramp across it, its edges antialiased. A chart's wedges, an
 * indicator's arrowhead, a mesh fanned from a centre; drawn by its distance, cached nowhere.
 *
 * <pre>{@code
 * canvas.triangle(x0, y0, x1, y1, x2, y2).color(0xFFE0E0E0).draw();
 * canvas.triangle(cx, cy, ax, ay, bx, by).ramp(inner, cx, cy, outer, rimX, rimY).draw();
 * // A fan whose wedges meet on hard seams, only the rim antialiased: no soft crack along each seam
 * canvas.triangle(cx, cy, px, py, qx, qy).color(fill).antialias(CgCanvasTriangle.Edge.P1_P2).draw();
 * }</pre>
 *
 * <ul>
 *   <li>Build and draw in one expression: the canvas hands out one builder, reset by the next {@code triangle()}.</li>
 *   <li>Either winding. Colours are straight {@code 0xAARRGGBB}; a ramp interpolates them straight, so a fade to the
 *       same colour at alpha 0 keeps its hue.</li>
 *   <li>A shape of many sides is a {@code CgVectorPath}, whose raster is cached.</li>
 * </ul>
 */
public final class CgCanvasTriangle {

    /** Which edges fade over a pixel; the rest are hard, so triangles sharing them cover each pixel once. */
    public enum Edge {
        /** Every edge: a triangle on its own. */
        ALL,
        /** {@code (x1, y1)} to {@code (x2, y2)} alone: a fan's rim. */
        P1_P2,
        /** {@code (x2, y2)} to {@code (x0, y0)} alone. */
        P2_P0
    }

    private final CgCanvas canvas;

    float x0, y0, x1, y1, x2, y2;
    int argbStart, argbEnd;
    boolean ramps;
    float rampX0, rampY0, rampX1, rampY1;
    Edge edge;
    float cornerRadius, feather;

    CgCanvasTriangle(CgCanvas canvas) {
        this.canvas = canvas;
    }

    CgCanvasTriangle reset(float x0, float y0, float x1, float y1, float x2, float y2) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        argbStart = argbEnd = 0xFFFFFFFF;
        ramps = false;
        edge = Edge.ALL;
        cornerRadius = 0f;
        feather = -1f;
        return this;
    }

    /** One colour, opaque white by default. */
    public CgCanvasTriangle color(int argb) {
        argbStart = argbEnd = argb;
        ramps = false;
        return this;
    }

    /** A linear ramp: {@code start} at {@code (x0, y0)}, {@code end} at {@code (x1, y1)}, held past either. */
    public CgCanvasTriangle ramp(int start, float x0, float y0, int end, float x1, float y1) {
        if (x0 == x1 && y0 == y1) return color(end);
        argbStart = start;
        argbEnd = end;
        rampX0 = x0;
        rampY0 = y0;
        rampX1 = x1;
        rampY1 = y1;
        ramps = true;
        return this;
    }

    /** Which edges antialias; every edge by default. */
    public CgCanvasTriangle antialias(Edge edge) {
        this.edge = Objects.requireNonNull(edge, "edge");
        return this;
    }

    /** Rounds its corners by growing it {@code radius} outward. */
    public CgCanvasTriangle cornerRadius(float radius) {
        cornerRadius = radius;
        return this;
    }

    /** The edges' softness in canvas units; a pixel's antialiasing by default. */
    public CgCanvasTriangle feather(float feather) {
        this.feather = Math.max(0f, feather);
        return this;
    }

    /** Draws it, answering the canvas. */
    public CgCanvas draw() {
        canvas.draw(this);
        return canvas;
    }
}
