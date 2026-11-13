#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"

// Coverage for CgCurveBatch's records: a stroked quadratic, a filled triangle or a cell (curve_instance_coverage picks).
// One copy for every curve material, the canvas's coverage passes included: the cap logic must not fork.

// The antialiasing filter, in pixels. Keep equal to CG_QUAD_EDGE_FILTER (quad.glsl) and CgCurveBatch.AA_FILTER_PX.
#define CG_STROKE_AA_FILTER 1.5

// Caps, CgCurveBatch.CAP_*: two bits an end, start in bits 0-1, end in 2-3.
#define CG_STROKE_CAP_BUTT   0
#define CG_STROKE_CAP_ROUND  1
#define CG_STROKE_CAP_SQUARE 2
#define CG_STROKE_CAP_ARROW  3

// An arrowhead's base half-width and length, in half-widths of the stroke at that end. curve.glsl pads by the length.
#define CG_STROKE_ARROW_SPREAD 3.0
#define CG_STROKE_ARROW_LENGTH 6.0

// Bits 13-14: this end is a joint inside a split cubic (CgCurveBatch.FLAG_JOINT_*), cut hard rather than capped.
#define CG_STROKE_FLAG_JOINT_START 8192
#define CG_STROKE_FLAG_JOINT_END   16384

// Bit 4: a filled triangle (CgCurveBatch.packFill).
#define CG_STROKE_FLAG_FILL 16

// Bit 5, with FILL: the fill runs color0 -> color1 along `gradient` (CgCurveBatch.FLAG_GRADIENT).
#define CG_STROKE_FLAG_GRADIENT 32

// Bits 6-7: the one edge of a filled triangle on the shape's outline, the only one antialiased; the others are seams.
#define CG_STROKE_FILL_EDGE_SHIFT 6
#define CG_STROKE_FILL_EDGE_NONE  0
#define CG_STROKE_FILL_EDGE_P1_P2 1
#define CG_STROKE_FILL_EDGE_P2_P0 2

// Bit 8: a cell, p0, p1, p2 and `widths` its four corners in order; bits 9-12 its edges on the outline
// (p0p1, p1p2, p2p3, p3p0). CgCurveBatch.Cell.
#define CG_STROKE_FLAG_CELL 256
#define CG_STROKE_CELL_EDGE_SHIFT 9

// Signed distance to the line through a->b, positive outside a counter-clockwise triangle. A line, not a segment: the
// outline runs on into the neighbouring triangle, and clamping would notch every seam.
float _fill_edge_dist(vec2 p, vec2 a, vec2 b, float winding) {
    vec2 e = b - a;
    float len = length(e);
    if (len < 1.0e-12) return -3.4e38;
    return winding * (e.x * (p.y - a.y) - e.y * (p.x - a.x)) / len;
}

// One end's cap distance, at local (u along the tangent, positive past the end; v unsigned from the centre line).
// A sentinel where the end adds nothing: the stroke side, or a round cap, which the clamped SDF already is.
float _stroke_cap_dist(float u, float v, float halfWidth, int capStyle) {
    if (u <= 0.0 || capStyle == CG_STROKE_CAP_ROUND) return -3.4e38;

    if (capStyle == CG_STROKE_CAP_ARROW) {
        // A wedge past the end, wider than the shaft at its base, to a point: the max of its two edges' half-planes,
        // exact at the tip.
        float aw = CG_STROKE_ARROW_SPREAD * halfWidth;
        float hl = CG_STROKE_ARROW_LENGTH * halfWidth;
        float invL = 1.0 / length(vec2(hl, aw));
        float top = (aw * u + hl * (v - aw)) * invL;
        float bot = (aw * u - hl * (v + aw)) * invL;
        return max(top, bot);
    }

    // Butt and square: a box, flush at the end or half a width past it. A half-plane cut of the round end's disc at
    // its own tangent would remove nothing.
    float extend = (capStyle == CG_STROKE_CAP_SQUARE) ? halfWidth : 0.0;
    return max(v - halfWidth, u - extend);
}

// A quadratic Bezier stroke's coverage at `p`, in [0,1]. `widths` is (start, end) half-width along the curve; `feather`
// the edge ramp's full width; `t` the closest point's parameter, for taper and gradient. No derivatives, so legal in
// any stage.
float stroke_coverage(vec2 p, vec2 a, vec2 b, vec2 c,
                      vec2 widths, float feather, int cap,
                      out float t) {
    float dist = sdf_bezier(p, a, b, c, t);
    float halfWidth = mix(widths.x, widths.y, t);
    float ramp = max(feather, 1.0e-4);

    // Under half the filter a hairline's clipped peak swings with its sub-pixel phase (0.83 to 0.50), beading as it
    // drifts. Widen the ramp as it thins, at most 2x, and scale coverage so its ink stays 2 * halfWidth (Skia's thin
    // stroke). Only on the default filter: a wider feather is a glow.
    float ink = 1.0;
    if (ramp <= CG_STROKE_AA_FILTER && halfWidth < CG_STROKE_AA_FILTER * 0.5) {
        float shaped = max(halfWidth, CG_STROKE_AA_FILTER * 0.25);
        ramp *= (CG_STROKE_AA_FILTER * 0.5) / shaped;
        float drawn = shaped < ramp * 0.5
                ? (shaped + ramp * 0.5) * (shaped + ramp * 0.5) / ramp
                : 2.0 * shaped;
        ink = 2.0 * max(halfWidth, 0.0) / drawn;
        halfWidth = shaped;
    }

    // Signed before the caps: cut against the unsigned distance, every cap renders round.
    float signedDist = dist - halfWidth;

    // Each end its own cap: a cubic's segments butt their inner ends, since two round caps at a joint composite twice.
    int capStart = cap & 3;
    int capEnd = (cap >> 2) & 3;

    if (capStart != CG_STROKE_CAP_ROUND || capEnd != CG_STROKE_CAP_ROUND) {
        // A quadratic's end tangents are exact: 2(B-A) at t=0, 2(C-B) at t=1.
        vec2 tangentStart = b - a;
        vec2 tangentEnd   = c - b;
        float lenStart = length(tangentStart);
        float lenEnd   = length(tangentEnd);
        tangentStart = (lenStart > 1.0e-6) ? tangentStart / lenStart : vec2(1.0, 0.0);
        tangentEnd   = (lenEnd   > 1.0e-6) ? tangentEnd   / lenEnd   : vec2(1.0, 0.0);

        vec2 dStart = p - a;
        vec2 dEnd   = p - c;
        float uStart = -dot(dStart, tangentStart);
        float uEnd   =  dot(dEnd, tangentEnd);
        float vStart = abs(dot(dStart, vec2(-tangentStart.y, tangentStart.x)));
        float vEnd   = abs(dot(dEnd, vec2(-tangentEnd.y, tangentEnd.x)));

        // A joint partitions, half-open, with no ramp: two antialiased butt caps over one cut composite brighter than
        // the unsplit stroke. Each side cuts on its own tangent, so a sharp turn at a joint leaves a sliver neither
        // owns; the bisector, passed per joint, would close it.
        bool jointStart = (cap & CG_STROKE_FLAG_JOINT_START) != 0;
        bool jointEnd   = (cap & CG_STROKE_FLAG_JOINT_END) != 0;
        if (jointStart && uStart >  0.0) return 0.0;   // the previous segment's pixel
        if (jointEnd   && uEnd   >= 0.0) return 0.0;   // the next one's: the shared point is claimed once
        float capStartDist = jointStart ? -3.4e38 : _stroke_cap_dist(uStart, vStart, halfWidth, capStart);
        float capEndDist   = jointEnd   ? -3.4e38 : _stroke_cap_dist(uEnd,   vEnd,   halfWidth, capEnd);
        float capped = max(capStartDist, capEndDist);
        if (capped > -3.4e38) signedDist = capped;
    }

    // Linear over the caller's ramp, as a quad's edge, so a stroke and a rect at one angle cover alike. Not
    // sdf_coverage: its fwidth ramp would cost this function its stage freedom and changed nothing measured.
    return ink * clamp(0.5 - signedDist / ramp, 0.0, 1.0);
}

// A filled triangle's coverage at `p`, in [0,1]: the same three points a stroke reads. `cornerRadius` dilates it,
// growing the triangle by the radius rather than keeping its size; `silhouetteEdge` the one edge to antialias.
float fill_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, float cornerRadius, float feather,
                    int silhouetteEdge) {
    // Relative to p0 for precision: sdf_triangle's dot products cancel catastrophically at canvas scale, and a seam's
    // owner is decided by the distance's sign, so neighbours would each drop a sliver of it.
    vec2 q  = p  - p0;
    vec2 e1 = p1 - p0;
    vec2 e2 = p2 - p0;

    // Signed: a negative radius erodes.
    float area = e1.x * e2.y - e1.y * e2.x;

    // Hard unless the caller passes a feather.
    float ramp = max(feather, 1.0e-6);
    if (silhouetteEdge == CG_STROKE_FILL_EDGE_NONE || feather <= 0.0) {
        float d = sdf_triangle(q, vec2(0.0), e1, e2) - cornerRadius;
        return clamp(0.5 - d / ramp, 0.0, 1.0);
    }

    // No area: every edge distance is zero and the test below would pass everywhere at half coverage. A strip emits
    // one at every tip of a shape.
    if (abs(area) < 1.0e-9) return 0.0;

    // The two seam edges as a hard step (their half-planes' max has an exact sign), the outline edge as a ramp.
    float winding = -sign(area);
    float dE0 = _fill_edge_dist(q, vec2(0.0), e1, winding);
    float dE1 = _fill_edge_dist(q, e1, e2, winding);
    float dE2 = _fill_edge_dist(q, e2, vec2(0.0), winding);

    float inside = (silhouetteEdge == CG_STROKE_FILL_EDGE_P1_P2) ? max(dE0, dE2) : max(dE0, dE1);
    if (inside - cornerRadius > 0.0) return 0.0;

    float edge = (silhouetteEdge == CG_STROKE_FILL_EDGE_P1_P2) ? dE1 : dE2;
    // Linear: over a one-pixel feather, the exact area a straight edge covers.
    return clamp(0.5 - edge / feather, 0.0, 1.0);
}

/** A lone triangle: every edge its own outline. */
float fill_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, float cornerRadius, float feather) {
    return fill_coverage(p, p0, p1, p2, cornerRadius, feather, CG_STROKE_FILL_EDGE_NONE);
}

// ---- Cells: a tessellated fill's convex quads ----------------------------------------------------------------------
// A soft edge contributes the exact area of the unit pixel inside it: opposite edges by the sum rule, the two pairs
// multiplied. The pixel is one unit of p's space, so cells are for screen curves. A hard edge is a seam, decided at the
// pixel centre half-open: the distance is measured from the edge's lexicographically smaller end, so both cells get
// the same number, and top and left take `<= 0`, right and bottom `< 0`; each seam pixel is claimed once.

// The area of the unit pixel at the origin inside a line at signed distance `d` (positive outside), unit normal `n`.
float _cell_edge_area(float d, vec2 n) {
    float a = max(abs(n.x), abs(n.y));
    float b = min(abs(n.x), abs(n.y));
    float w = (a + b) * 0.5;
    if (d >= w) return 0.0;
    if (d <= -w) return 1.0;
    float lo = (a - b) * 0.5;
    if (abs(d) <= lo) return 0.5 - d / a;
    // Only reachable with b > 0, since lo == w when b == 0.
    float corner = (w - abs(d)) * (w - abs(d)) / (2.0 * a * b);
    return d > 0.0 ? corner : 1.0 - corner;
}

// One edge a->b: its exact area if soft, a half-open step if hard (`ownsLine`: it claims the points on it).
float _cell_edge(vec2 p, vec2 a, vec2 b, float winding, bool soft, bool ownsLine) {
    vec2 lo = a, hi = b;
    float flip = 1.0;
    if (a.x > b.x || (a.x == b.x && a.y > b.y)) { lo = b; hi = a; flip = -1.0; }
    vec2 e = hi - lo;
    float len = length(e);
    if (len < 1.0e-12) return 1.0;     // a collapsed edge, a shape's tip, constrains nothing
    float d = flip * winding * (e.x * (p.y - lo.y) - e.y * (p.x - lo.x)) / len;
    if (soft) return _cell_edge_area(d, vec2(-e.y, e.x) / len);
    return (ownsLine ? d <= 0.0 : d < 0.0) ? 1.0 : 0.0;
}

// The exact area of the pixel at `p` inside the cell (Sutherland-Hodgman, then the shoelace formula), for a cell every
// edge of which is soft: accumulated cells must sum exactly with their neighbours.
float _cell_exact_area(vec2 p, vec2 p0, vec2 p1, vec2 p2, vec2 p3, float winding) {
    vec2 poly[8];
    vec2 clipped[8];
    int n = 4;
    poly[0] = p + vec2(-0.5, -0.5);
    poly[1] = p + vec2( 0.5, -0.5);
    poly[2] = p + vec2( 0.5,  0.5);
    poly[3] = p + vec2(-0.5,  0.5);
    vec2 corners[5] = vec2[5](p0, p1, p2, p3, p0);
    for (int e = 0; e < 4; e++) {
        vec2 a = corners[e];
        vec2 ed = corners[e + 1] - a;
        if (dot(ed, ed) < 1.0e-12) continue;      // a collapsed edge: the tip of a shape
        int m = 0;
        for (int i = 0; i < n; i++) {   // LOOP-BOUND: a clipped quad: 8 corners at most
            vec2 cur = poly[i];
            vec2 prev = poly[(i + n - 1) % n];
            float dc = winding * (ed.x * (cur.y - a.y) - ed.y * (cur.x - a.x));
            float dp = winding * (ed.x * (prev.y - a.y) - ed.y * (prev.x - a.x));
            if (dc <= 0.0) {
                if (dp > 0.0) clipped[m++] = mix(prev, cur, dp / (dp - dc));
                clipped[m++] = cur;
            } else if (dp <= 0.0) {
                clipped[m++] = mix(prev, cur, dp / (dp - dc));
            }
        }
        if (m == 0) return 0.0;
        n = m;
        for (int i = 0; i < n; i++) poly[i] = clipped[i];   // LOOP-BOUND: 8 at most
    }
    float area = 0.0;
    for (int i = 0; i < n; i++) {   // LOOP-BOUND: a clipped quad: 8 corners at most
        vec2 c = poly[i];
        vec2 d = poly[(i + 1) % n];
        area += c.x * d.y - d.x * c.y;
    }
    return abs(area) * 0.5;
}

float cell_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, vec2 p3, int flags) {
    // Absolute coordinates, unlike fill_coverage: two cells must compute a seam's distance bit for bit, and a cross
    // product of cell-sized vectors does not cancel.
    vec2 e1 = p1 - p0;
    vec2 e2 = p2 - p0;
    vec2 e3 = p3 - p0;
    float area = (e1.x * e2.y - e1.y * e2.x) + (e2.x * e3.y - e2.y * e3.x);
    if (abs(area) < 1.0e-9) return 0.0;
    float winding = -sign(area);
    int soft = flags >> CG_STROKE_CELL_EDGE_SHIFT;
    if ((soft & 15) == 15) return _cell_exact_area(p, p0, p1, p2, p3, winding);

    float top    = _cell_edge(p, p0, p1, winding, (soft & 1) != 0, true);
    float right  = _cell_edge(p, p1, p2, winding, (soft & 2) != 0, false);
    float bottom = _cell_edge(p, p2, p3, winding, (soft & 4) != 0, false);
    float left   = _cell_edge(p, p3, p0, winding, (soft & 8) != 0, true);

    return clamp(left + right - 1.0, 0.0, 1.0) * clamp(top + bottom - 1.0, 0.0, 1.0);
}

// A record's coverage, by its flags: cell, fill or stroke. A fill's or cell's `t` is its gradient's, else 0, so the
// caller's mix(color0, color1, t) gives color0; `widths.x` is a fill's corner radius, `widths` a cell's fourth corner.
// `gradient` is (origin.xy, dir.xy) with the reciprocal length folded into dir; clamped, so an overhang holds the end.
float curve_instance_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2,
                              vec2 widths, float feather, int flags, vec4 gradient, out float t) {
    if ((flags & CG_STROKE_FLAG_CELL) != 0) {
        t = ((flags & CG_STROKE_FLAG_GRADIENT) != 0)
                ? clamp(dot(p - gradient.xy, gradient.zw), 0.0, 1.0)
                : 0.0;
        return cell_coverage(p, p0, p1, p2, widths, flags);
    }
    if ((flags & CG_STROKE_FLAG_FILL) != 0) {
        t = ((flags & CG_STROKE_FLAG_GRADIENT) != 0)
                ? clamp(dot(p - gradient.xy, gradient.zw), 0.0, 1.0)
                : 0.0;
        return fill_coverage(p, p0, p1, p2, widths.x, feather,
                (flags >> CG_STROKE_FILL_EDGE_SHIFT) & 3);
    }
    return stroke_coverage(p, p0, p1, p2, widths, feather, flags, t);
}
