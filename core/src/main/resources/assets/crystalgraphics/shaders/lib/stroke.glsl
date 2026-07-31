#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"

// Stroke coverage for a quadratic Bézier — the shared body of every CgCurveRenderer consumer.
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

// Cap styles — must match CgCurveRenderer.CAP_*.
#define CG_STROKE_CAP_BUTT   0
#define CG_STROKE_CAP_ROUND  1
#define CG_STROKE_CAP_SQUARE 2

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

        // THE CAP REGION NEEDS A BOX METRIC, NOT A CLIPPED DISC. Past an endpoint the region is a
        // disc of radius halfWidth; cutting it with a half-plane at u = halfWidth cuts it exactly at
        // its own tangent point, removing nothing — which makes CAP_SQUARE necessarily identical to
        // CAP_ROUND, not approximately so. Replacing the radial term with (v across, u along) makes
        // the cap a rectangle, which is what both non-round caps actually are.
        // Each end is capped by its OWN style. A round end keeps the radial result already in
        // signedDist; only butt and square replace it with the box metric.
        float extendStart = (capStart == CG_STROKE_CAP_SQUARE) ? halfWidth : 0.0;
        float extendEnd   = (capEnd   == CG_STROKE_CAP_SQUARE) ? halfWidth : 0.0;

        bool cutStart = (uStart > 0.0) && (capStart != CG_STROKE_CAP_ROUND);
        bool cutEnd   = (uEnd   > 0.0) && (capEnd   != CG_STROKE_CAP_ROUND);

        if (cutStart || cutEnd) {
            float capped = -3.4e38;
            if (cutStart) capped = max(capped, max(vStart - halfWidth, uStart - extendStart));
            if (cutEnd)   capped = max(capped, max(vEnd   - halfWidth, uEnd   - extendEnd));
            signedDist = capped;
        }
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
