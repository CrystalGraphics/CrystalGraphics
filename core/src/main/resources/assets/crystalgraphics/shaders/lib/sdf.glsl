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
// Everything below is pure maths and MUST stay above the CG_VERTEX_STAGE guard: CgCurveRenderer's
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
// is the entire reason CgCurveRenderer's primitive is quadratic and not cubic: a cubic's distance is
// a quintic with no closed form, so a cubic primitive would mean approximating per pixel forever.
//
// DEGENERATE CASE — READ BEFORE EDITING. The solve divides by dot(b,b) where b = A - 2B + C, which
// is exactly zero whenever the control point is the midpoint of the endpoints — i.e. for every
// straight line. CgCurveRenderer.Curve#line() constructs precisely that, so this is the common path,
// not an edge case: without the guard below, every straight stroke divides by zero and renders as
// NaN (which on most drivers means an invisible or full-screen-garbage quad, with nothing in the
// log). The threshold is relative to the curve's own extent so it holds at any scale.
float sdf_bezier(vec2 p, vec2 A, vec2 B, vec2 C, out float t) {
    vec2 b = A - 2.0 * B + C;
    float bb = dot(b, b);

    // Two ways to be "straight enough", and both are load-bearing.
    //
    // (1) RELATIVE — a genuine degenerate, where the control point IS the midpoint of the endpoints.
    //     CgCurveRenderer.Curve#line() constructs exactly that, so this is the COMMON path, not an
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

    // A unit-frame normalisation was tried here and REMOVED. The theory was catastrophic
    // cancellation in `h = q*q + 4*p*p*p` (both terms ~3e4, sum ~1e2, in a 24-bit mantissa) flipping
    // the branch selector on shallow curves. It is a real effect and the theory was plausible, but
    // SdfBezierConditioningTest measured it in float precision against a double-sampled oracle and
    // the un-normalised solve was NOT measurably less accurate on the geometry that actually
    // misbehaves. Do not re-add it on the strength of the argument alone — the measurement is in
    // that test, and it says the argument does not apply at these magnitudes.
    vec2 a = B - A;
    vec2 c = a * 2.0;
    vec2 d = A - p;

    float kk = 1.0 / bb;
    float kx = kk * dot(a, b);
    float ky = kk * (2.0 * dot(a, a) + dot(d, b)) / 3.0;
    float kz = kk * dot(d, a);

    float res;
    float pp = ky - kx * kx;
    float pp3 = pp * pp * pp;
    float q = kx * (2.0 * kx * kx - 3.0 * ky) + kz;
    float h = q * q + 4.0 * pp3;

    if (h >= 0.0) {
        h = sqrt(h);
        vec2 x = (vec2(h, -h) - q) / 2.0;
        vec2 uv = sign(x) * pow(abs(x), vec2(1.0 / 3.0));
        t = clamp(uv.x + uv.y - kx, 0.0, 1.0);
        res = _sdf_dot2(d + (c + b * t) * t);
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
        float d1 = _sdf_dot2(d + (c + b * tt.x) * tt.x);
        float d2 = _sdf_dot2(d + (c + b * tt.y) * tt.y);
        float d3 = _sdf_dot2(d + (c + b * tt.z) * tt.z);

        // `t` must track whichever root actually won — returning the minimum distance while
        // reporting a different root's parameter desyncs the gradient from the geometry.
        res = d1;
        t = tt.x;
        if (d2 < res) { res = d2; t = tt.y; }
        if (d3 < res) { res = d3; t = tt.z; }
    }
    return sqrt(res);
}

float sdf_bezier(vec2 p, vec2 A, vec2 B, vec2 C) {
    float t;
    return sdf_bezier(p, A, B, C, t);
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
