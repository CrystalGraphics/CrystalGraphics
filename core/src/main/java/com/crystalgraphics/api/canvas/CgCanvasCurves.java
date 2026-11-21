package com.crystalgraphics.api.canvas;

import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.ui.CgCanvasPrimitives;
import com.crystalgraphics.render.ui.path.CgCurveRecords;
import com.crystalgraphics.render.ui.property.CgPalette;

/**
 * A canvas's splines and triangles, drawn by their distance fields as curve records: no raster, no cache. Baked into
 * device pixels at the base node, as an element's rect is; local in a node of their own under any other transform.
 */
final class CgCanvasCurves {

    private final CgCanvas canvas;
    final CgCanvasSpline spline;
    final CgCanvasTriangle triangle;
    private final float[] record = new float[CgCurveRecords.FLOATS];
    private final float[] pieces = new float[CgCurveRecords.MAX_PIECES * 9];

    CgCanvasCurves(CgCanvas canvas) {
        this.canvas = canvas;
        this.spline = new CgCanvasSpline(canvas);
        this.triangle = new CgCanvasTriangle(canvas);
    }

    void draw(CgCanvasSpline s) {
        CgCanvas cv = canvas;
        if (cv.recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        if (!s.set) throw new IllegalStateException("a spline with no geometry: line(), to() or cubic() first");
        if (cv.opacity <= 0f) return;
        cv.pass();
        int at = cv.spatial();
        float k = at == cv.base ? cv.linearScale() : 1f;
        float feather = s.feather < 0f ? CgCurveRecords.ANTIALIAS : s.feather * k;
        int capStart = s.capStart.ordinal(), capEnd = s.arrow ? CgCurveRecords.CAP_ARROW : s.capEnd.ordinal();
        float node = CgPalette.pack(at, cv.effect), fade = 1f - cv.opacity;
        float hs = s.widthStart * 0.5f * k, he = s.widthEnd * 0.5f * k;
        if (!s.cubic) {
            piece(at, s.x0, s.y0, s.cx, s.cy, s.x1, s.y1, hs, he, s.argbStart, s.argbEnd, feather,
                    CgCurveRecords.flags(capStart, capEnd, false, false), node, fade);
            return;
        }
        int n = CgCurveRecords.splitCubic(s.x0, s.y0, s.c1x, s.c1y, s.c2x, s.c2y, s.x1, s.y1, pieces, k);
        for (int i = 0; i < n; i++) {
            int o = i * 9;
            float t0 = i / (float) n, t1 = (i + 1) / (float) n;
            // The caller's caps at the cubic's own ends; joints between pieces cut hard
            int flags = CgCurveRecords.flags(i == 0 ? capStart : CgCurveRecords.CAP_BUTT,
                    i == n - 1 ? capEnd : CgCurveRecords.CAP_BUTT, i > 0, i < n - 1);
            piece(at, pieces[o], pieces[o + 1], pieces[o + 3], pieces[o + 4], pieces[o + 6], pieces[o + 7],
                    hs + (he - hs) * t0, hs + (he - hs) * t1,
                    CgCurveRecords.lerp(s.argbStart, s.argbEnd, t0), CgCurveRecords.lerp(s.argbStart, s.argbEnd, t1),
                    feather, flags, node, fade);
        }
    }

    void draw(CgCanvasTriangle t) {
        CgCanvas cv = canvas;
        if (cv.recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        if (cv.opacity <= 0f) return;
        cv.pass();
        int at = cv.spatial();
        boolean baked = at == cv.base;
        float k = baked ? cv.linearScale() : 1f;
        float feather = t.feather < 0f ? CgCurveRecords.ANTIALIAS : t.feather * k;
        float ax = t.x0, ay = t.y0, bx = t.x1, by = t.y1, cx = t.x2, cy = t.y2;
        // The ramp as t = dot(p - origin, direction), 0 at its start and 1 at its end
        float ox = t.rampX0, oy = t.rampY0, dx = t.rampX1 - t.rampX0, dy = t.rampY1 - t.rampY0;
        float len2 = dx * dx + dy * dy;
        if (len2 > 0f) {
            dx /= len2;
            dy /= len2;
        }
        if (baked) {
            float x = ax, y = ay;
            ax = cv.mapX(x, y);
            ay = cv.mapY(x, y);
            x = bx;
            y = by;
            bx = cv.mapX(x, y);
            by = cv.mapY(x, y);
            x = cx;
            y = cy;
            cx = cv.mapX(x, y);
            cy = cv.mapY(x, y);
            x = ox;
            y = oy;
            ox = cv.mapX(x, y);
            oy = cv.mapY(x, y);
            // Axis-aligned here: a direction scales by the inverse, so t holds at each moved point
            dx /= cv.a;
            dy /= cv.d;
        }
        int edge = t.edge == CgCanvasTriangle.Edge.P1_P2 ? CgCurveRecords.EDGE_P1_P2
                : t.edge == CgCanvasTriangle.Edge.P2_P0 ? CgCurveRecords.EDGE_P2_P0 : CgCurveRecords.EDGE_ALL;
        float radius = t.cornerRadius * k;
        CgCurveRecords.writeTriangle(record, 0, ax, ay, bx, by, cx, cy, t.argbStart, t.argbEnd, radius, feather,
                CgCurveRecords.triangleFlags(t.ramps, edge), ox, oy, dx, dy, cv.clip, CgPalette.pack(at, cv.effect),
                1f - cv.opacity);
        float reach = Math.max(radius, 0f) + feather + 1f;
        land(at, ax, ay, bx, by, cx, cy, reach);
    }

    private void piece(int at, float ax, float ay, float bx, float by, float cx, float cy, float halfStart,
                       float halfEnd, int argbStart, int argbEnd, float feather, int flags, float node, float fade) {
        CgCanvas cv = canvas;
        if (at == cv.base) {
            float x = ax, y = ay;
            ax = cv.mapX(x, y);
            ay = cv.mapY(x, y);
            x = bx;
            y = by;
            bx = cv.mapX(x, y);
            by = cv.mapY(x, y);
            x = cx;
            y = cy;
            cx = cv.mapX(x, y);
            cy = cv.mapY(x, y);
        }
        CgCurveRecords.write(record, 0, ax, ay, bx, by, cx, cy, halfStart, halfEnd, argbStart, argbEnd, feather,
                flags, cv.clip, node, fade);
        // A square cap reaches a half-width past the end along the tangent and across it
        land(at, ax, ay, bx, by, cx, cy, Math.max(halfStart, halfEnd) * 1.5f + feather + 1f);
    }

    /** The record as one curve draw, over the three points' bounds grown by {@code reach}. */
    private void land(int at, float ax, float ay, float bx, float by, float cx, float cy, float reach) {
        float x0 = Math.min(ax, Math.min(bx, cx)) - reach, y0 = Math.min(ay, Math.min(by, cy)) - reach;
        float x1 = Math.max(ax, Math.max(bx, cx)) + reach, y1 = Math.max(ay, Math.max(by, cy)) + reach;
        canvas.recorder.draw(CgInstanceKind.CURVE, CgCanvasPrimitives.curve(), null, null, null, at, record, 0, 1,
                x0, y0, x1, y1);
        canvas.grow(at, x0, y0, x1, y1);
    }
}
