#pragma once

#include "crystalgraphics:shaders/lib/math.glsl"

// Signed distance to the edge of an axis-aligned rounded box, centered at the origin.
// `halfSize` is the box's half-extent (outer radius corners included). `radius` is the
// (uniform) outer corner radius, in the same units as `p`/`halfSize`. Negative inside the
// box, positive outside, zero exactly on the edge — standard SDF convention.
//
// Reference: Inigo Quilez, "2D distance functions" (rounded box).
float sdf_rounded_box(vec2 p, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

// Same as above but with an independent radius per corner. `radii` is
// (top-left, top-right, bottom-right, bottom-left) — CSS border-radius order. Local space is
// Y-down (matches UV: v=0 at top), so "top" is negative-y here — opposite of Inigo Quilez's
// original Y-up reference this is adapted from.
float sdf_rounded_box(vec2 p, vec2 halfSize, vec4 radii) {
    vec2 topBottom = (p.x > 0.0) ? radii.yz : radii.xw; // right: (TR,BR); left: (TL,BL)
    float radius = (p.y < 0.0) ? topBottom.x : topBottom.y; // top half picks first, bottom picks second
    return sdf_rounded_box(p, halfSize, radius);
}

// Elliptical per-corner overload: `radiiX`/`radiiY` are independent horizontal/vertical radii per
// corner, same (TL,TR,BR,BL) order/quadrant-selection as the vec4 overload above. Approximate but
// visually correct (exact only when rx==ry per corner): normalizes the corner-region offset by
// (rx,ry) before the circular distance evaluation, then scales the result back by min(rx,ry) so
// AA/coverage stays in consistent real-distance units.
float sdf_rounded_box(vec2 p, vec2 halfSize, vec4 radiiX, vec4 radiiY) {
    vec2 rxTopBottom = (p.x > 0.0) ? radiiX.yz : radiiX.xw;
    float rx = (p.y < 0.0) ? rxTopBottom.x : rxTopBottom.y;
    vec2 ryTopBottom = (p.x > 0.0) ? radiiY.yz : radiiY.xw;
    float ry = (p.y < 0.0) ? ryTopBottom.x : ryTopBottom.y;

    rx = min(rx, halfSize.x);
    ry = min(ry, halfSize.y);
    vec2 q = abs(p) - halfSize + vec2(rx, ry);
    if (rx <= 0.0 || ry <= 0.0) {
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
    }
    vec2 n = max(q, 0.0) / vec2(rx, ry);
    return (length(n) - 1.0) * min(rx, ry) + min(max(q.x, q.y), 0.0);
}

// ── Quadratic Bézier ─────────────────────────────────────────────────────────────────────────
// Everything below is pure maths and MUST stay above the CG_VERTEX_STAGE guard: CgVectorRenderer's
// vertex stage derives its bounding quad from the control hull and the fragment stage evaluates
// the stroke, so both stages need these. No derivative builtins are used here — that is what makes
// it legal in a vertex shader, and it is not an accident.

float _sdf_dot2(vec2 v) { return dot(v, v); }

// Unsigned distance from `p` to the line SEGMENT a->b, and the closest point's parameter along it.
// Used directly for straight strokes, and as sdf_bezier's degenerate fallback.
float sdf_segment(vec2 p, vec2 a, vec2 b, out float t) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float denom = dot(ba, ba);
    t = (denom > 0.0) ? clamp(dot(pa, ba) / denom, 0.0, 1.0) : 0.0;
    return length(pa - ba * t);
}

float sdf_segment(vec2 p, vec2 a, vec2 b) {
    float t;
    return sdf_segment(p, a, b, t);
}

// Unsigned distance from `p` to the quadratic Bézier A->B->C, plus the parameter `t` in [0,1] of
// the closest point — `t` is what drives per-pixel taper and gradient along the stroke, so it is an
// out-parameter rather than something the caller re-derives.
//
// Exact, via one closed-form cubic solve (Inigo Quilez, "quadratic bezier distance"). This exactness
// is the entire reason CgVectorRenderer's primitive is quadratic and not cubic: a cubic's distance is
// a quintic with no closed form, so a cubic primitive would mean approximating per pixel forever.
//
// DEGENERATE CASE — READ BEFORE EDITING. The solve divides by dot(b,b) where b = A - 2B + C, which
// is exactly zero whenever the control point is the midpoint of the endpoints — i.e. for every
// straight line. CgVectorRenderer.Curve#line() constructs precisely that, so this is the common path,
// not an edge case: without the guard below, every straight stroke divides by zero and renders as
// NaN (which on most drivers means an invisible or full-screen-garbage quad, with nothing in the
// log). The threshold is relative to the curve's own extent so it holds at any scale.
float sdf_bezier(vec2 p, vec2 A, vec2 B, vec2 C, out float t) {
    vec2 b = A - 2.0 * B + C;
    float bb = dot(b, b);

    // Two ways to be "straight enough", and both are load-bearing.
    //
    // (1) RELATIVE — a genuine degenerate, where the control point IS the midpoint of the endpoints.
    //     CgVectorRenderer.Curve#line() constructs exactly that, so this is the COMMON path, not an
    //     edge case, and the solve below divides by bb.
    //
    // (2) ABSOLUTE — a curve whose greatest deviation from its own chord, |b|/4, is under a twentieth
    //     of a pixel. Test (1) cannot see this one: bb is small in absolute terms but not relative to
    //     a short chord, so a barely-curved segment still takes the full solve for a result that is
    //     visually a straight line. Cheaper and exact to route it to the segment distance.
    //
    // Nearly-flat is not a rare shape: splitting a cubic into quadratics produces flat segments BY
    // CONSTRUCTION, so anything drawn with cubic() lives here.
    //
    // Note this is a cheap shortcut, NOT a fix for anything — it was added while chasing a dropout on
    // shallow animated curves that it did not turn out to explain. It earns its place on cost alone.
    float extent = max(_sdf_dot2(C - A), 1.0e-12);
    if (bb < 1.0e-6 * extent || bb < 4.0e-2) {
        return sdf_segment(p, A, C, t);
    }

    // ── SOLVE IN THE CURVE'S OWN FRAME. This is not a micro-optimisation; it is the difference
    // between a correct distance and a wildly wrong one. ────────────────────────────────────────
    //
    // In screen coordinates a long, shallow segment drives the intermediates below far apart in
    // magnitude while the answer stays a few pixels. In float32 the cubic solve then loses most of
    // its significant bits. Measured on a real segment the splitter emits —
    // A=(186.95,173.378) B=(218.96,174.03) C=(250.0,174.0), 63px long, 0.30px of bow — for a point
    // lying ON the curve (true distance 0.011):
    //
    //     as-written in screen space : 3.9910   <- reports the pixel as far outside the stroke
    //     translated + scaled here   : 0.0018
    //
    // A distance of 3.99 against a 2.5 half-width means the fragment is discarded, so the stroke
    // develops a hole. Because the error depends on the segment's exact shape, the hole appears on
    // some wires and not others and travels as the geometry animates, which is why this survived
    // seven wrong diagnoses (SDF branches, NaN guards, bounding quad, cap double-blending, split
    // tolerance, antialiasing bandwidth) before being caught by BisectHoleProbeTest.
    //
    // Splitting a cubic produces exactly these long, nearly-flat segments BY CONSTRUCTION, so every
    // cubic() caller depends on this. `t` is dimensionless; only the distance needs scaling back.
    //
    // NOTE: an earlier attempt at this was reverted after a test showed no benefit — that test used
    // a well-conditioned synthetic quadratic, not the near-flat segments that actually fail. If you
    // are about to remove this, check which geometry your measurement used.
    float scale = max(max(length(B - A), length(C - B)), 1.0e-6);
    float inv = 1.0 / scale;

    vec2 a = (B - A) * inv;
    vec2 bs = (A - 2.0 * B + C) * inv;
    vec2 c = a * 2.0;
    vec2 d = (A - p) * inv;

    float kk = 1.0 / dot(bs, bs);
    float kx = kk * dot(a, bs);
    float ky = kk * (2.0 * dot(a, a) + dot(d, bs)) / 3.0;
    float kz = kk * dot(d, a);

    float res;
    float pp = ky - kx * kx;
    float pp3 = pp * pp * pp;
    float q = kx * (2.0 * kx * kx - 3.0 * ky) + kz;
    float h = q * q + 4.0 * pp3;

    if (h >= 0.0) {
        h = sqrt(h);
        vec2 x = (vec2(h, -h) - q) / 2.0;

        // THE max() IS THE FIX FOR THE TRAVELLING-DROPOUT BUG. Do not simplify it back to
        // pow(abs(x), 1/3).
        //
        // GLSL implements pow(x,y) as exp2(y * log2(x)), so x == 0 evaluates log2(0) = -inf and then
        // y * -inf = -inf. Drivers disagree on what comes back; on the NaN path, sign(0) == 0 turns
        // it into 0 * NaN, which is NaN. That NaN reaches `t`, and from there both the coverage and
        // the caller's gradient mix, so the fragment is dropped rather than misplaced.
        //
        // x is exactly zero where p == 0, which is a CODIMENSION-1 LOCUS — a thin curve through the
        // pixel plane, not a region. So the symptom is a narrow band of missing stroke that SWEEPS
        // along a curve as it animates, rather than a static hole: "one empty tiny quad running
        // through the wire left to right".
        //
        // Whether that locus crosses the painted band depends on the individual curve's shape, which
        // is why it hit two of the gallery's three node wires and not the third. That looked like
        // evidence against a numerical cause and was the opposite — SdfBezierDegenerateRootTest
        // measures it: the two shallow wires drive |x| below 1e-3 inside the band, the steep one
        // stays more than 10x further away.
        //
        // max() rather than a branch because the answer is already correct at zero: sign(0) == 0
        // makes the product 0, which IS the cube root of 0. Only the NaN needs preventing.
        vec2 uv = sign(x) * pow(max(abs(x), 1.0e-30), vec2(1.0 / 3.0));

        t = clamp(uv.x + uv.y - kx, 0.0, 1.0);
        res = _sdf_dot2(d + (c + bs * t) * t);
    } else {
        float z = sqrt(-pp);
        // CLAMP REQUIRED. Analytically this argument is inside [-1,1] whenever h < 0, but floating
        // point lands marginally outside near the branch boundary, and acos() of that is NaN — which
        // reaches `t`, then the coverage AND the caller's gradient, dropping the fragment entirely
        // rather than merely misplacing it. Cheap insurance against a whole class of dropout.
        float v = acos(clamp(q / (pp * z * 2.0), -1.0, 1.0)) / 3.0;
        float m = cos(v);
        float n = sin(v) * 1.732050808;
        vec3 tt = clamp(vec3(m + m, -n - m, n - m) * z - kx, 0.0, 1.0);

        // ALL THREE ROOTS, not the two the reference implementation tests. The cubic genuinely has
        // three critical points here and any of them can be the nearest; Quilez's version drops the
        // third as an optimisation, which silently returns a too-large distance whenever that root
        // wins. On a stroke that reads as a bite taken out of the edge.
        float d1 = _sdf_dot2(d + (c + bs * tt.x) * tt.x);
        float d2 = _sdf_dot2(d + (c + bs * tt.y) * tt.y);
        float d3 = _sdf_dot2(d + (c + bs * tt.z) * tt.z);

        // `t` must track whichever root actually won — returning the minimum distance while
        // reporting a different root's parameter desyncs the gradient from the geometry.
        res = d1;
        t = tt.x;
        if (d2 < res) { res = d2; t = tt.y; }
        if (d3 < res) { res = d3; t = tt.z; }
    }
    return sqrt(res) * scale;
}

float sdf_bezier(vec2 p, vec2 A, vec2 B, vec2 C) {
    float t;
    return sdf_bezier(p, A, B, C, t);
}

// Signed distance to a 2D triangle p0->p1->p2, either winding order. Negative inside, positive
// outside — standard SDF convention, matching sdf_rounded_box above.
//
// Built from sdf_segment rather than re-deriving the per-edge point-segment projection inline
// (Quilez's reference "2D distance functions" triangle SDF does the projection by hand three
// times; reusing sdf_segment says the same thing once). Reference: Inigo Quilez, "2D distance
// functions" (triangle).
//
// Pure maths — legal in a vertex shader, no CG_VERTEX_STAGE guard, same as sdf_bezier.
float sdf_triangle(vec2 p, vec2 p0, vec2 p1, vec2 p2) {
    float d = min(min(sdf_segment(p, p0, p1), sdf_segment(p, p1, p2)), sdf_segment(p, p2, p0));

    // Inside/outside via the signed area (cross product) of each edge against p, tested against
    // the triangle's own overall winding `s` — works for EITHER winding order because the
    // reference area and each per-edge cross product flip sign together.
    float area = (p1.x - p0.x) * (p2.y - p0.y) - (p1.y - p0.y) * (p2.x - p0.x);
    float s = sign(area);
    bool inside =
            s * ((p1.x - p0.x) * (p.y - p0.y) - (p1.y - p0.y) * (p.x - p0.x)) >= 0.0 &&
            s * ((p2.x - p1.x) * (p.y - p1.y) - (p2.y - p1.y) * (p.x - p1.x)) >= 0.0 &&
            s * ((p0.x - p2.x) * (p.y - p2.y) - (p0.y - p2.y) * (p.x - p2.x)) >= 0.0;

    return inside ? -d : d;
}

// 1.0 inside the shape, 0.0 outside, antialiased across ~1px at the edge using screen-space
// derivatives. `dist` is an `sdf_*` distance (negative inside, per the convention above).
//
// FRAGMENT-ONLY. `fwidth` is a derivative builtin and does not exist in the vertex stage. This lib
// gets included at material scope, and the compiler hoists every material-scope `#` line into BOTH
// generated stages — so without this guard the function lands in the vertex shader. NVIDIA compiles
// it anyway; AMD correctly refuses, and the whole material fails with
// "ERROR: 'fwidth' : no matching overloaded function found".
//
// The polarity is deliberate: `#ifndef CG_VERTEX_STAGE`, NOT `#ifdef CG_FRAGMENT_STAGE`. Raw
// .vert/.frag files go through CgShaderPreprocessor with no stage defines at all, so an `#ifdef`
// would silently delete this function from every one of them. `#ifndef` keeps it everywhere except
// the one stage that cannot have it.
//
// Everything above this line is pure maths and stays available to vertex shaders.
#ifndef CG_VERTEX_STAGE
float sdf_coverage(float dist) {
    float aa = fwidth(dist) * 0.5;
    return 1.0 - smoothstep(-aa, aa, dist);
}
#endif
