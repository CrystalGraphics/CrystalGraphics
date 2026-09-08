#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"

// Stroke AND fill coverage for CgVectorRenderer's shared p0/p1/p2 instance schema -- the whole body
// of every consumer of that schema, whether the instance is a stroked quadratic or a filled
// triangle (see curve_instance_coverage, the one dispatch point between the two).
//
// WHY THIS IS A LIB RATHER THAN COPIED INTO EACH SHADER. There are two curve materials and there
// will likely be more: crystalgraphics:shaders/curve.shader (3D, DepthTest LEQUAL) and
// crystalgui:shaders/gui_curve.shader (UI, DepthTest ALWAYS + a _LayerOpacity property). Those
// differ ONLY in render state and in what they do with the final alpha -- a Pass's RenderState is
// fixed at author time and cannot vary per keyword variant, so they genuinely have to be separate
// materials. What they must NOT have is separate copies of the maths below.
//
// That is not hypothetical tidiness. The cap handling here was wrong three times in a row, each fix
// surviving the previous one, and every one of them rendered something confident and plausible
// rather than failing. Two copies means the fourth fix lands in one file and the other keeps the bug.

// Cap styles -- must match CgVectorRenderer.CAP_*. Packed 2 bits per end (start in bits 0-1, end in
// bits 2-3 -- see CgVectorRenderer.packCaps), so 4 values is exactly the room available; ARROW is
// the last one that fits without widening the packing.
#define CG_STROKE_CAP_BUTT   0
#define CG_STROKE_CAP_ROUND  1
#define CG_STROKE_CAP_SQUARE 2
#define CG_STROKE_CAP_ARROW  3

// Arrowhead proportions, in units of the LOCAL half-width at that end -- so the head scales with
// whatever width the caller gave the stroke, the same way every other cap style already does.
// Base is deliberately WIDER than the shaft (spread > 1): a flare-then-point silhouette is what
// "arrowhead" means, and it is what every reference icon (Feather, Material) actually draws.
// Cosmetic constants, not load-bearing -- safe to retune.
#define CG_STROKE_ARROW_SPREAD 3.0
#define CG_STROKE_ARROW_LENGTH 6.0

// Bit 4 of the packed flags -- set for a FILLED triangle instance, unset for a stroke. See
// CgVectorRenderer.packFill(). Lives above the cap constants because both stroke_coverage's cap
// dispatch and curve_instance_coverage's fill/stroke dispatch read from the same packed int.
#define CG_STROKE_FLAG_FILL 16

// Bit 5 -- set alongside FLAG_FILL when the instance's `gradient` field carries a real axis, so the
// fill interpolates color0 -> color1 across the triangle instead of taking color0 flat. See
// CgVectorRenderer.FLAG_GRADIENT and Triangle.gradient(...).
#define CG_STROKE_FLAG_GRADIENT 32

// Bits 6-7 -- which edge of a filled triangle is on the shape's real outline, and therefore the only one
// that may be antialiased. See CgVectorRenderer.FILL_EDGE_SHIFT for why a tessellated fill cannot just be
// given a feather: most of its edges are seams shared with a neighbour, and softening those fades every
// one of them into a visible line from both sides.
#define CG_STROKE_FILL_EDGE_SHIFT 6
#define CG_STROKE_FILL_EDGE_NONE  0
#define CG_STROKE_FILL_EDGE_P1_P2 1
#define CG_STROKE_FILL_EDGE_P2_P0 2

// Bit 8 -- a CELL: p0, p1, p2 and `widths` are its four corners in order. Bits 9-12 say which of its
// four edges (p0p1, p1p2, p2p3, p3p0) are on the shape's outline. See CgVectorRenderer.Cell and
// cell_coverage below.
#define CG_STROKE_FLAG_CELL 256
#define CG_STROKE_CELL_EDGE_SHIFT 9

// Signed distance to the line through a->b, positive on the outside of a counter-clockwise triangle.
// A LINE, not a segment: the silhouette continues into the neighbouring triangle, so clamping to this
// triangle's own span would cut the antialiased band off at every seam and leave a notch at each one.
float _fill_edge_dist(vec2 p, vec2 a, vec2 b, float winding) {
    vec2 e = b - a;
    float len = length(e);
    if (len < 1.0e-12) return -3.4e38;
    return winding * (e.x * (p.y - a.y) - e.y * (p.x - a.x)) / len;
}

// Signed distance contribution from ONE capped end, given this fragment's local (u, v): u along
// the tangent (positive = past the endpoint, into cap territory), v = perpendicular distance from
// the centerline (already abs()'d by the caller -- see the note on that below).
//
// Returns a sentinel far below any real distance when this end contributes nothing: either the
// fragment is still on the stroke side (u <= 0), or the cap is ROUND, whose radial distance is
// already sitting correctly in the caller's signedDist and needs no override. The caller composes
// both ends with max(), so a sentinel from one end never wins over a real contribution from the
// other -- same pattern the old inline `cutStart`/`cutEnd` booleans expressed less generally.
float _stroke_cap_dist(float u, float v, float halfWidth, int capStyle) {
    if (u <= 0.0 || capStyle == CG_STROKE_CAP_ROUND) return -3.4e38;

    if (capStyle == CG_STROKE_CAP_ARROW) {
        // A filled wedge appended past the endpoint: half-width CG_STROKE_ARROW_SPREAD*halfWidth
        // at u=0 (deliberately wider than the shaft -- the visible "step" at the base is the point),
        // tapering linearly to an exact point at u=CG_STROKE_ARROW_LENGTH*halfWidth.
        //
        // Distance to the wedge is the max of its two slanted edges' half-plane distances -- the
        // same "max of half-planes approximates distance to their intersection" trick the box
        // metric below already relies on, just with slanted rather than axis-aligned planes.
        // Verified algebraically: both edge distances are exactly 0 at the tip and grow positive
        // past it, so the tip renders as a genuine point rather than a chamfer, and passing the
        // already-unsigned `v` here is exact (not an approximation) because both edge-distance
        // formulas are themselves symmetric under a v -> -v, top <-> bottom swap.
        float aw = CG_STROKE_ARROW_SPREAD * halfWidth;
        float hl = CG_STROKE_ARROW_LENGTH * halfWidth;
        float invL = 1.0 / length(vec2(hl, aw));
        float top = (aw * u + hl * (v - aw)) * invL;
        float bot = (aw * u - hl * (v + aw)) * invL;
        return max(top, bot);
    }

    // BUTT (flush at u=0) and SQUARE (flush at u=halfWidth) are both the same box metric, only the
    // extend distance differs -- see the class doc on why this needs a box, not a clipped disc.
    float extend = (capStyle == CG_STROKE_CAP_SQUARE) ? halfWidth : 0.0;
    return max(v - halfWidth, u - extend);
}

// Antialiased coverage of a quadratic Bezier stroke at point `p`, in [0,1].
//
// `widths` is (start, end) HALF-width, interpolated along the curve; `feather` is the full width of
// the edge ramp, in the same units. `t` returns the curve parameter of the closest point, which is
// what callers drive taper and gradient from -- an out-parameter rather than something recomputed.
//
// Pure maths: no derivative builtins, so this is legal in a vertex shader and needs no
// CG_VERTEX_STAGE guard. Only sdf_coverage in sdf.glsl needs one.
float stroke_coverage(vec2 p, vec2 a, vec2 b, vec2 c,
                      vec2 widths, float feather, int cap,
                      out float t) {
    float dist = sdf_bezier(p, a, b, c, t);
    float halfWidth = mix(widths.x, widths.y, t);
    float ramp = max(feather, 1.0e-4);

    // SIGNED FIRST. Intersecting the cap half-plane against the raw unsigned distance compares two
    // quantities on different scales, so the cut only bites more than halfWidth past the endpoint --
    // where the radial term has already hidden the pixel. CAP_BUTT becomes an exact no-op and all
    // three styles render as round.
    float signedDist = dist - halfWidth;

    // Start and end caps are INDEPENDENT, packed two bits each. A cubic is drawn as abutting
    // quadratics, and if every segment round-caps both its own ends then each interior joint stacks
    // two identical discs at one point. Alpha compositing blends the antialiased rim twice --
    // 1-(1-a)^2 rather than a -- and the joint hardens into a visible disc outline on the stroke.
    // Butting the interior ends flush is the only way to avoid it; the caller's cap survives on the
    // curve's two real ends.
    int capStart = cap & 3;
    int capEnd = (cap >> 2) & 3;

    if (capStart != CG_STROKE_CAP_ROUND || capEnd != CG_STROKE_CAP_ROUND) {
        // A ROUND cap is what the clamped SDF already produces: past an endpoint, clamping t to
        // [0,1] makes the distance radial to that endpoint, which IS a half-disc. So round is the
        // zero-work case and it is butt/square that need doing -- the opposite of what the enum
        // order suggests.
        //
        // Endpoint tangents are exact: a quadratic's derivative is 2(B-A) at t=0 and 2(C-B) at t=1.
        vec2 tangentStart = b - a;
        vec2 tangentEnd   = c - b;
        float lenStart = length(tangentStart);
        float lenEnd   = length(tangentEnd);
        tangentStart = (lenStart > 1.0e-6) ? tangentStart / lenStart : vec2(1.0, 0.0);
        tangentEnd   = (lenEnd   > 1.0e-6) ? tangentEnd   / lenEnd   : vec2(1.0, 0.0);

        // Local frame at each end: u along the tangent (positive = outside the curve's own
        // parameter range), v perpendicular.
        vec2 dStart = p - a;
        vec2 dEnd   = p - c;
        float uStart = -dot(dStart, tangentStart);
        float uEnd   =  dot(dEnd, tangentEnd);
        float vStart = abs(dot(dStart, vec2(-tangentStart.y, tangentStart.x)));
        float vEnd   = abs(dot(dEnd, vec2(-tangentEnd.y, tangentEnd.x)));

        // THE CAP REGION NEEDS A BOX (OR, FOR ARROW, A WEDGE) METRIC, NOT A CLIPPED DISC. Past an
        // endpoint the radial region is a disc of radius halfWidth; cutting it with a half-plane at
        // u = halfWidth cuts it exactly at its own tangent point, removing nothing -- which makes
        // CAP_SQUARE necessarily identical to CAP_ROUND, not approximately so. Each end is capped by
        // its OWN style -- see _stroke_cap_dist, which is also why an asymmetric curve (e.g. a
        // node-graph wire: round at the source, ARROW at the destination) is one `.cap(start, end)`
        // call rather than two draws.
        float capStartDist = _stroke_cap_dist(uStart, vStart, halfWidth, capStart);
        float capEndDist   = _stroke_cap_dist(uEnd,   vEnd,   halfWidth, capEnd);
        float capped = max(capStartDist, capEndDist);
        if (capped > -3.4e38) signedDist = capped;   // both sentinel = neither end contributed
    }

    // Deliberately NOT sdf_coverage(): that derives its own ~1px ramp from fwidth, whereas a
    // stroke's softness is an authored per-instance property in the same units as the widths.
    //
    // Flooring this ramp with fwidth(signedDist) was tried and MEASURED TO DO NOTHING: worst local
    // deviation was 73.0/52.6/33.2% at feather 1/3/6 with the floor against 72.6/50.6/34.7% without,
    // i.e. identical. fwidth is already below feather here, so the max() never binds. Do not re-add
    // it on the theory that near-horizontal strokes are antialiasing-limited - they are not, and the
    // measurement is the reason.
    return 1.0 - smoothstep(-ramp * 0.5, ramp * 0.5, signedDist);
}

// Antialiased coverage of a FILLED triangle p0->p1->p2 at point `p`, in [0,1]. `cornerRadius`
// softens the corners; `feather` is the edge ramp, same convention as stroke_coverage's.
//
// The instance schema is shared with strokes (see CgVectorRenderer's class doc), so this reads the
// SAME three points a quadratic stroke would use as control points -- a filled triangle is not a
// different kind of data, only a different thing to do with three points. That is also why this
// lives in stroke.glsl rather than a separate file: both coverage functions are consumed by
// exactly the same two materials (curve.shader / gui_curve.shader), so splitting them would only
// add an #include line everywhere for no benefit.
//
// CORNER ROUNDING IS A DILATION, NOT AN ERODE-THEN-DILATE. sdf_rounded_box shrinks the box's own
// half-size by radius before measuring, then adds radius back -- net result, same overall size,
// corners pulled in. Doing that for an arbitrary triangle means offsetting each edge inward along
// its own normal before measuring, which is real extra work for a purely cosmetic feature. This
// instead just subtracts radius from the sharp triangle's own SDF, which is the standard "grow a
// shape outward by radius, rounding whatever was sharp" SDF operator -- the triangle ends up
// `cornerRadius` LARGER than its input points, not the same size with pulled-in corners. Fine at
// the small radii a UI icon actually uses (a couple of pixels on a 12-16px glyph); a caller wanting
// the box's same-size behaviour can shrink its own input points by cornerRadius to compensate.
//
// Pure maths: legal in a vertex shader, no CG_VERTEX_STAGE guard, same as sdf_bezier.

float fill_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, float cornerRadius, float feather,
                    int silhouetteEdge) {
    // RELATIVE TO p0, AND THAT IS ABOUT PRECISION, NOT TIDINESS.
    //
    // sdf_triangle projects the point onto each edge, which squares the coordinates: at a few hundred
    // pixels the dot products reach ~1e5 and then cancel back down to a distance near zero. That is
    // textbook catastrophic cancellation -- the result keeps the absolute error of the large intermediate
    // and loses most of its significant digits, so a distance that should be 1e-4 carries an error of
    // similar size or worse.
    //
    // It matters because a tessellated fill decides seam ownership on the SIGN of that distance. Two
    // triangles sharing an edge derive it from different vertex triples, so the error moves them
    // independently and a sliver of pixels near the seam is claimed fully by neither. On a translucent
    // fill that reads as sparse dark dashes along the boundary, which at a distance blur into a line --
    // and it scales with the coordinates, so it appears as a viewer zooms in.
    //
    // Subtracting p0 first is exact-or-nearly (Sterbenz: subtracting nearby floats loses nothing), and
    // everything after it runs at the triangle's own scale rather than the canvas's.
    vec2 q  = p  - p0;
    vec2 e1 = p1 - p0;
    vec2 e2 = p2 - p0;

    // SIGNED, deliberately: a negative radius ERODES, so two abutting triangles can be nudged apart
    // when an overlap would composite twice.
    float area = e1.x * e2.y - e1.y * e2.x;

    // A HARD EDGE BY DEFAULT: a fill wants a step, and a caller that wants a soft edge passes a real
    // feather. The floor exists only so the smoothstep never divides by zero. (Tessellated fills no
    // longer come through here at all -- see cell_coverage.)
    float ramp = max(feather, 1.0e-6);
    if (silhouetteEdge == CG_STROKE_FILL_EDGE_NONE || feather <= 0.0) {
        float d = sdf_triangle(q, vec2(0.0), e1, e2) - cornerRadius;
        return 1.0 - smoothstep(-ramp * 0.5, ramp * 0.5, d);
    }

    // A ZERO-AREA TRIANGLE IS OUTSIDE EVERYWHERE, AND THIS BRANCH HAS TO SAY SO ITSELF.
    //
    // sdf_triangle carries exactly this guard and explains why; the trap is that the path below does not
    // go through it. With no area every edge distance collapses to zero and sign() returns zero with
    // them, so the membership test passes at every point in the plane and the ramp lands on its own
    // midpoint -- the instance paints its whole bounding quad at HALF coverage. Solid blocks would at
    // least look like a bug; translucent ones read as a shading artefact in the artwork.
    //
    // Degenerate triangles are not exotic here: a trapezoid strip emits one wherever the shape comes to a
    // point, so every tip in every icon produces one.
    if (abs(area) < 1.0e-9) return 0.0;

    // ONE EDGE SOFT, THE OTHER TWO HARD.
    //
    // The two internal edges are seams shared with a neighbouring triangle, and they must stay a step:
    // fading them would leave each seam half-covered from both sides, which on a translucent fill is a
    // visible line -- the artefact class this whole path exists to avoid.
    //
    // A TRIANGLE IS THE INTERSECTION OF THREE HALF-PLANES, so testing the two internal ones with a max()
    // is not an approximation of membership: outside a corner the value understates the true distance,
    // but its SIGN is exact, and a hard step needs nothing else. That is what lets the silhouette be
    // dropped from the membership test entirely -- and it has to be, or the step would clamp the soft
    // band to the inside half of the edge and produce a 1 -> 0.5 fade ending in a cliff rather than an
    // antialiased edge.
    float winding = -sign(area);
    float dE0 = _fill_edge_dist(q, vec2(0.0), e1, winding);
    float dE1 = _fill_edge_dist(q, e1, e2, winding);
    float dE2 = _fill_edge_dist(q, e2, vec2(0.0), winding);

    float inside = (silhouetteEdge == CG_STROKE_FILL_EDGE_P1_P2) ? max(dE0, dE2) : max(dE0, dE1);
    if (inside - cornerRadius > 0.0) return 0.0;

    float edge = (silhouetteEdge == CG_STROKE_FILL_EDGE_P1_P2) ? dE1 : dE2;
    // LINEAR, not smoothstep: with a one-pixel feather this is the exact area a straight edge covers
    // of the pixel it crosses, i.e. what an area-coverage rasteriser computes. smoothstep under-covers
    // everything within half a pixel of an edge, which on a 16px icon is most of it -- measured against
    // IntelliJ's own raster of the same file, the linear ramp halves the per-pixel error.
    return clamp(0.5 - edge / feather, 0.0, 1.0);
}

/** The common case: a lone triangle, whose every edge is its own outline and none of them a seam. */
float fill_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, float cornerRadius, float feather) {
    return fill_coverage(p, p0, p1, p2, cornerRadius, feather, CG_STROKE_FILL_EDGE_NONE);
}

// ---- Cells -------------------------------------------------------------------------------------
//
// The reading a tessellated fill wants. A scanline decomposition cuts a shape into bands, and every
// band cell is a convex quad whose two walls are contour edges and whose top and bottom are cuts shared with
// the neighbouring bands. Drawn as a pair of triangles, a triangle only ever knew ONE wall: any pixel
// on a cut and within reach of the other wall was claimed at full coverage by the half owning the far
// one, which showed as a bright row across every seam. One instance that knows all four edges has no
// such gap.
//
// COVERAGE IS AN AREA, NOT A RAMP. A soft edge contributes the exact area of the unit pixel on its
// inside -- what an area-coverage rasteriser computes, and the reason the output matches a CPU raster
// of the same file. Opposite edges combine by the sum rule (exact for two parallel walls through one
// pixel), the two pairs multiply (exact for a rectangle, the separable approximation at a corner).
// The pixel is ONE UNIT of p's space, so this reading belongs to a material whose points are window
// pixels: gui_curve.shader.
//
// A HARD EDGE IS A SEAM AND IS DECIDED AT THE PIXEL CENTRE, HALF-OPEN. Two cells sharing a seam must
// claim every pixel on it exactly once, or a translucent fill blends twice and an opaque one drops a
// row. The distance to the line is computed from its lexicographically smaller endpoint whichever way
// the edge runs, so both cells compute the SAME number with opposite signs -- and then the top and
// left edges take `<= 0`, the right and bottom `< 0`. A seam is always one cell's top against another's
// bottom, or a right against a left, so exactly one of them owns the boundary line.

// Area of the unit pixel centred on the origin that lies on the inside of a line at signed distance
// `d` (positive outside) with unit normal `n`. Exact: the line cuts the square into a trapezoid while it
// crosses two opposite sides, and a triangle off a corner otherwise.
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

// Coverage from ONE cell edge a->b: exact area if soft, half-open step at the centre if hard.
// `winding` orients the distance so positive is outside for this quad's winding; `ownsLine` is whether
// a hard edge claims the points exactly on it.
float _cell_edge(vec2 p, vec2 a, vec2 b, float winding, bool soft, bool ownsLine) {
    vec2 lo = a, hi = b;
    float flip = 1.0;
    if (a.x > b.x || (a.x == b.x && a.y > b.y)) { lo = b; hi = a; flip = -1.0; }
    vec2 e = hi - lo;
    float len = length(e);
    if (len < 1.0e-12) return 1.0;     // a collapsed edge constrains nothing: the tip of a shape
    float d = flip * winding * (e.x * (p.y - lo.y) - e.y * (p.x - lo.x)) / len;
    if (soft) return _cell_edge_area(d, vec2(-e.y, e.x) / len);
    return (ownsLine ? d <= 0.0 : d < 0.0) ? 1.0 : 0.0;
}

// The exact area of the unit pixel centred on `p` inside the cell: the pixel clipped against the four
// half-planes (Sutherland-Hodgman), then the shoelace formula. Used when EVERY edge is soft -- a cell
// being accumulated rather than composited, whose neighbours' areas it must sum with exactly. The
// separable combination below is exact for a rectangle and close elsewhere; this is exact everywhere.
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
        for (int i = 0; i < n; i++) {
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
        for (int i = 0; i < n; i++) poly[i] = clipped[i];
    }
    float area = 0.0;
    for (int i = 0; i < n; i++) {
        vec2 c = poly[i];
        vec2 d = poly[(i + 1) % n];
        area += c.x * d.y - d.x * c.y;
    }
    return abs(area) * 0.5;
}

float cell_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, vec2 p3, int flags) {
    // ABSOLUTE COORDINATES, unlike fill_coverage: the half-open seam rule needs both cells to compute
    // the same distance bit for bit, and each cell has a different p0 to be relative to. The
    // cancellation fill_coverage guards against is sdf_triangle's dot products; a cross product of
    // two cell-sized vectors has none of it.
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

// The one place stroke-vs-fill is decided, so curve.shader and gui_curve.shader can never disagree
// about it -- same reasoning as everything else in this file. Dispatches on CG_STROKE_FLAG_FILL in
// `flags` (see CgVectorRenderer.packFill/packCaps).
//
// `t` is meaningless for a fill (there is no "closest point along a path" for a filled region), so
// it is set to 0 -- which, fed into the caller's existing `mix(color0, color1, t)`, resolves to
// exactly `color0`. That is why a fill instance's colour is always `color0`: not a special case in
// the fragment shader, just what mix() does at t=0. `widths.x` doubles as cornerRadius in fill mode
// (see CgVectorRenderer.Triangle) -- `widths` has no other meaning once a triangle has no taper.
float curve_instance_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2,
                              vec2 widths, float feather, int flags, vec4 gradient, out float t) {
    if ((flags & CG_STROKE_FLAG_CELL) != 0) {
        t = ((flags & CG_STROKE_FLAG_GRADIENT) != 0)
                ? clamp(dot(p - gradient.xy, gradient.zw), 0.0, 1.0)
                : 0.0;
        return cell_coverage(p, p0, p1, p2, widths, flags);
    }
    if ((flags & CG_STROKE_FLAG_FILL) != 0) {
        // A GRADIENT FILL IS THE SAME COVERAGE WITH A REAL t. The caller already does
        // mix(color0, color1, t), so a per-pixel ramp costs one dot product and no second material --
        // and it removes the reason a mesh would subdivide for colour at all. Without it the only way to
        // draw a ramp is to cut the shape into cells small enough that each flat one passes for part of
        // one, which is quadratic in quality for a diagonal ramp and still seams wherever the cut lines
        // are not the ramp's own iso-lines.
        //
        // `gradient` is (originX, originY, dirX, dirY) with the reciprocal length folded into dir, so
        // this is a dot product and a clamp rather than a normalise. Clamped, not wrapped: a triangle
        // that overhangs its own colour span is legal and should hold the end colour.
        t = ((flags & CG_STROKE_FLAG_GRADIENT) != 0)
                ? clamp(dot(p - gradient.xy, gradient.zw), 0.0, 1.0)
                : 0.0;
        return fill_coverage(p, p0, p1, p2, widths.x, feather,
                (flags >> CG_STROKE_FILL_EDGE_SHIFT) & 3);
    }
    return stroke_coverage(p, p0, p1, p2, widths, feather, flags, t);
}

