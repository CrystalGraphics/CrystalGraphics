// The canvas's curve: CgCurveBatch's strokes, triangles and cells, one instance each, its unit quad grown to the
// control hull's bounds. Drawn through CgCanvasMaterials, whose state gives the blend and the depth test.
// SCREEN: a 2D camera, each fragment's point from gl_FragCoord (below). _LayerOpacity fades a whole draw.

#type none
#pragma cg_use curve
#pragma cg_use clip
#pragma cg_feature SCREEN

#include "crystalgraphics:shaders/lib/stroke.glsl"

Tags {
    "RenderType" = "Transparent"
}

// At or past 2500: no generated depth pass, which an alpha-blended SDF has no use for.
Queue = "Transparent"

Properties {
    _LayerOpacity ("Layer Opacity", float) = 1.0
}

struct v2f {
    // In the control points' space: after the pose, before the projection.
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
        DepthTest LEQUAL
        DepthWrite OFF
    }

    void vertex(out v2f o) {
        vec3 worldPos = CG_CURVE_WORLD_POS;
        o.posXy = worldPos.xy;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(worldPos, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Before any discard: it takes derivatives.
        float boxClip = CG_CLIP_CURVE_COVERAGE;
#ifdef SCREEN
        // From gl_FragCoord, not the varying: each instance interpolates over its own hull, and a tessellated fill
        // partitions its pixels only if every cell evaluates the same point. Exact for a 2D camera, points on z = 0.
        mat4 camera = cg_ProjMatrix * cg_ViewMatrix;
        vec2 ndc = gl_FragCoord.xy / cg_Resolution * 2.0 - 1.0;
        vec2 p = inverse(mat2(camera[0].xy, camera[1].xy)) * (ndc - camera[3].xy);
#else
        vec2 p = i.posXy;
#endif
        // curve_instance_coverage decides stroke or fill; t drives what varies along the stroke.
        float t;
        float alpha = curve_instance_coverage(p,
                                              CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy,
                                              CG_CURVE_WIDTHS, CG_CURVE_FEATHER,
                                              int(CG_CURVE_FLAGS + 0.5), CG_CURVE_GRADIENT, t);

        vec4 color = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
        alpha *= color.a * _LayerOpacity;

        if (alpha <= (1.0 / 255.0)) discard;

        fragColor = vec4(color.rgb, alpha * boxClip);
    }
}
