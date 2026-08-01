// ═════════════════════════════════════════════════════════════════════════════
// CrystalShader — curve.shader
// Reference material for CgCurveRenderer (gl/render/CgCurveRenderer.java), in the
// same way text.shader is CgQuadRenderer's. Draws an antialiased, optionally
// tapered and gradient-filled quadratic Bézier stroke, one instance per curve.
//
// Everything this file needs per instance comes from the CG_CURVE_* macros in
// cg_env.glsl; the only geometry input is the shared unit quad, which the vertex
// stage reinterprets as the curve's derived control-hull bounding box.
// ═════════════════════════════════════════════════════════════════════════════

#type pos2_uv2_col4ub
#pragma cg_use curve

// The stroke maths itself lives in lib/stroke.glsl and is shared with crystalgui:shaders/
// gui_curve.shader. The two materials differ only in render state and in what they do with the final
// alpha — they must NOT differ in how a stroke is shaped, and the cap logic in particular was wrong
// three times over, so there is exactly one copy of it.
#include "crystalgraphics:shaders/lib/stroke.glsl"

Tags {
    "RenderType" = "Transparent"
}

// Transparent (3000) is >= TRANSPARENT_THRESHOLD (2500), so attemptShadowAutoGen /
// attemptDepthAutoGen bail out on their isOpaque check and a stroke never gets an
// auto-generated ShadowCaster or Depth pass. An alpha-blended SDF has no meaningful
// depth-only representation, so those passes would be wrong, not merely wasteful.
Queue = "Transparent"

struct v2f {
    // Position of this fragment in the same space the control points live in — i.e. AFTER
    // Curve#pose was baked in CPU-side, and before the projection matrix. This is the one
    // genuine varying: it is a true per-vertex quantity (it differs across the bounding quad),
    // unlike the control points themselves, which the fragment stage re-reads per instance.
    vec2 posXy;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Curve"
    }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        Cull OFF

        // A stroke is an alpha-blended overlay primitive: it tests against whatever is already
        // there but must not write depth, or overlapping strokes in one batch would punch holes
        // in each other depending on submission order rather than blending.
        DepthTest LEQUAL
        DepthWrite OFF
    }

    void vertex(out v2f o) {
        vec3 worldPos = CG_CURVE_WORLD_POS;
        o.posXy = worldPos.xy;
        gl_Position = cg_ProjMatrix * vec4(worldPos, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // t is the curve parameter of the closest point — everything that varies ALONG the stroke
        // (taper, gradient) is driven by it, which is why the coverage functions hand it back
        // rather than making every caller re-derive it. curve_instance_coverage is the one place
        // stroke vs. filled-triangle is decided (see lib/stroke.glsl) — never call stroke_coverage
        // directly here, or a filled instance submitted through this material draws as a stroke.
        float t;
        float alpha = curve_instance_coverage(i.posXy,
                                              CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy,
                                              CG_CURVE_WIDTHS, CG_CURVE_FEATHER,
                                              int(CG_CURVE_FLAGS + 0.5), t);

        vec4 color = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
        alpha *= color.a;

        if (alpha <= (1.0 / 255.0)) discard;

        fragColor = vec4(color.rgb, alpha);
    }
}
