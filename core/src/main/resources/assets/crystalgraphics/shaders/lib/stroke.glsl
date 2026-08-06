#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"

// Stroke AND fill coverage for CgVectorRenderer's shared p0/p1/p2 instance schema — the whole body
// of every consumer of that schema, whether the instance is a stroked quadratic or a filled
// triangle (see curve_instance_coverage, the one dispatch point between the two).
//
// WHY THIS IS A LIB RATHER THAN COPIED INTO EACH SHADER. There are two curve materials and there
// will likely be more: crystalgraphics:shaders/curve.shader (3D, DepthTest LEQUAL) and
// crystalgui:shaders/gui_curve.shader (UI, DepthTest ALWAYS + a _LayerOpacity property). Those
// differ ONLY in render state and in what they do with the final alpha — a Pass's RenderState is
// fixed at author time and cannot vary per keyword variant, so they genuinely have to be separate
// materials. What they must NOT have is separate copies of the maths below.
//
// That is not hypothetical tidiness. The cap handling here was wrong three times in a row, each fix
// surviving the previous one, and every one of them rendered something confident and plausible
// rather than failing. Two copies means the fourth fix lands in one file and the other keeps the bug.

// Cap styles — must match CgVectorRenderer.CAP_*. Packed 2 bits per end (start in bits 0-1, end in
// bits 2-3 — see CgVectorRenderer.packCaps), so 4 values is exactly the room available; ARROW is
// the last one that fits without widening the packing.
#define CG_STROKE_CAP_BUTT   0
#define CG_STROKE_CAP_ROUND  1
#define CG_STROKE_CAP_SQUARE 2
#define CG_STROKE_CAP_ARROW  3

// Arrowhead proportions, in units of the LOCAL half-width at that end — so the head scales with
// whatever width the caller gave the stroke, the same way every other cap style already does.
// Base is deliberately WIDER than the shaft (spread > 1): a flare-then-point silhouette is what
// "arrowhead" means, and it is what every reference icon (Feather, Material) actually draws.
// Cosmetic constants, not load-bearing — safe to retune.
#define CG_STROKE_ARROW_SPREAD 3.0
#define CG_STROKE_ARROW_LENGTH 6.0

// Bit 4 of the packed flags — set for a FILLED triangle instance, unset for a stroke. See
// CgVectorRenderer.packFill(). Lives above the cap constants because both stroke_coverage's cap
// dispatch and curve_instance_coverage's fill/stroke dispatch read from the same packed int.
#define CG_STROKE_FLAG_FILL 16

// Signed distance contribution from ONE capped end, given this fragment's local (u, v): u along
// the tangent (positive = past the endpoint, into cap territory), v = perpendicular distance from
// the centerline (already abs()'d by the caller — see the note on that below).
//
// Returns a sentinel far below any real distance when this end contributes nothing: either the
// fragment is still on the stroke side (u <= 0), or the cap is ROUND, whose radial distance is
// already sitting correctly in the caller's signedDist and needs no override. The caller composes
// both ends with max(), so a sentinel from one end never wins over a real contribution from the
// other — same pattern the old inline `cutStart`/`cutEnd` booleans expressed less generally.
float _stroke_cap_dist(float u, float v, float halfWidth, int capStyle) {
    if (u <= 0.0 || capStyle == CG_STROKE_CAP_ROUND) return -3.4e38;

    if (capStyle == CG_STROKE_CAP_ARROW) {
        // A filled wedge appended past the endpoint: half-width CG_STROKE_ARROW_SPREAD*halfWidth
        // at u=0 (deliberately wider than the shaft — the visible "step" at the base is the point),
        // tapering linearly to an exact point at u=CG_STROKE_ARROW_LENGTH*halfWidth.
        //
        // Distance to the wedge is the max of its two slanted edges' half-plane distances — the
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
    // extend distance differs — see the class doc on why this needs a box, not a clipped disc.
    float extend = (capStyle == CG_STROKE_CAP_SQUARE) ? halfWidth : 0.0;
    return max(v - halfWidth, u - extend);
}

// Antialiased coverage of a quadratic Bézier stroke at point `p`, in [0,1].
//
// `widths` is (start, end) HALF-width, interpolated along the curve; `feather` is the full width of
// the edge ramp, in the same units. `t` returns the curve parameter of the closest point, which is
// what callers drive taper and gradient from — an out-parameter rather than something recomputed.
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
    // quantities on different scales, so the cut only bites more than halfWidth past the endpoint —
    // where the radial term has already hidden the pixel. CAP_BUTT becomes an exact no-op and all
    // three styles render as round.
    float signedDist = dist - halfWidth;

    // Start and end caps are INDEPENDENT, packed two bits each. A cubic is drawn as abutting
    // quadratics, and if every segment round-caps both its own ends then each interior joint stacks
    // two identical discs at one point. Alpha compositing blends the antialiased rim twice —
    // 1-(1-a)^2 rather than a — and the joint hardens into a visible disc outline on the stroke.
    // Butting the interior ends flush is the only way to avoid it; the caller's cap survives on the
    // curve's two real ends.
    int capStart = cap & 3;
    int capEnd = (cap >> 2) & 3;

    if (capStart != CG_STROKE_CAP_ROUND || capEnd != CG_STROKE_CAP_ROUND) {
        // A ROUND cap is what the clamped SDF already produces: past an endpoint, clamping t to
        // [0,1] makes the distance radial to that endpoint, which IS a half-disc. So round is the
        // zero-work case and it is butt/square that need doing — the opposite of what the enum
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
        // u = halfWidth cuts it exactly at its own tangent point, removing nothing — which makes
        // CAP_SQUARE necessarily identical to CAP_ROUND, not approximately so. Each end is capped by
        // its OWN style — see _stroke_cap_dist, which is also why an asymmetric curve (e.g. a
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
// SAME three points a quadratic stroke would use as control points — a filled triangle is not a
// different kind of data, only a different thing to do with three points. That is also why this
// lives in stroke.glsl rather than a separate file: both coverage functions are consumed by
// exactly the same two materials (curve.shader / gui_curve.shader), so splitting them would only
// add an #include line everywhere for no benefit.
//
// CORNER ROUNDING IS A DILATION, NOT AN ERODE-THEN-DILATE. sdf_rounded_box shrinks the box's own
// half-size by radius before measuring, then adds radius back — net result, same overall size,
// corners pulled in. Doing that for an arbitrary triangle means offsetting each edge inward along
// its own normal before measuring, which is real extra work for a purely cosmetic feature. This
// instead just subtracts radius from the sharp triangle's own SDF, which is the standard "grow a
// shape outward by radius, rounding whatever was sharp" SDF operator — the triangle ends up
// `cornerRadius` LARGER than its input points, not the same size with pulled-in corners. Fine at
// the small radii a UI icon actually uses (a couple of pixels on a 12-16px glyph); a caller wanting
// the box's same-size behaviour can shrink its own input points by cornerRadius to compensate.
//
// Pure maths: legal in a vertex shader, no CG_VERTEX_STAGE guard, same as sdf_bezier.
float fill_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2, float cornerRadius, float feather) {
    // SIGNED, deliberately: a negative radius ERODES. The clamp that used to be here read as defensive
    // and cost a real capability -- a tessellated fill needs to nudge the two sides of every shared edge
    // in OPPOSITE directions so exactly one of them claims a pixel centre sitting on it. Without erosion
    // the only available choices are "both claim it" (a double blend, wrong for any translucent fill) and
    // "both half-claim it" (a 25%% coverage dip). See CrystalGUI SvgDocument.FILL_OFFSET.
    float d = sdf_triangle(p, p0, p1, p2) - cornerRadius;
    float ramp = max(feather, 1.0e-4);
    return 1.0 - smoothstep(-ramp * 0.5, ramp * 0.5, d);
}

// The one place stroke-vs-fill is decided, so curve.shader and gui_curve.shader can never disagree
// about it — same reasoning as everything else in this file. Dispatches on CG_STROKE_FLAG_FILL in
// `flags` (see CgVectorRenderer.packFill/packCaps).
//
// `t` is meaningless for a fill (there is no "closest point along a path" for a filled region), so
// it is set to 0 — which, fed into the caller's existing `mix(color0, color1, t)`, resolves to
// exactly `color0`. That is why a fill instance's colour is always `color0`: not a special case in
// the fragment shader, just what mix() does at t=0. `widths.x` doubles as cornerRadius in fill mode
// (see CgVectorRenderer.Triangle) — `widths` has no other meaning once a triangle has no taper.
float curve_instance_coverage(vec2 p, vec2 p0, vec2 p1, vec2 p2,
                              vec2 widths, float feather, int flags, out float t) {
    if ((flags & CG_STROKE_FLAG_FILL) != 0) {
        t = 0.0;
        return fill_coverage(p, p0, p1, p2, widths.x, feather);
    }
    return stroke_coverage(p, p0, p1, p2, widths, feather, flags, t);
}
