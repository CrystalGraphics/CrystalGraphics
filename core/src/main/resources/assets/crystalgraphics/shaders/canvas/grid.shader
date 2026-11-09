// A ruled grid over a box in one draw, antialiased at any scale and cell size; CgCanvasGrid sets every property.
//
// Ben Golus's "Pristine Grid" ("The Best Darn Grid Shader (Yet)", 2023). His gist has no licence, so this follows the
// CC0 Godot port and the MIT WebGPU port (toji/pristine-grid-webgpu) of the technique; see THIRD-PARTY.md. Over the
// usual smoothstep(fwidth(fract(uv))), which thickens, moires and cuts out as cells shrink:
//   1. derivatives per axis by length, so a rotated grid measures its own footprint;
//   2. a line never thinner than a pixel, faded by the factor it was widened, so its brightness holds;
//   3. under a pixel a cell, a dissolve to the grid's mean density instead of moire.
// invertLine draws the gaps instead once a line passes half a cell. Premultiplied out.

#type none
#pragma cg_use quad
#pragma cg_use clip
#pragma cg_feature WITH_MASK
#pragma cg_permutation WITH_MASK

#include "crystalgraphics:shaders/lib/sdf.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    // Premultiplied.
    _Color         ("Line color (premultiplied)",  vec4)  = (1.0, 1.0, 1.0, 1.0)
    // Cells and lines in _BoxSize's units.
    _Cell          ("Cell size (px)",               vec2)  = (16.0, 16.0)
    _LineWidth     ("Line width (px)",              vec2)  = (1.0, 1.0)
    _BoxSize       ("Box size (px)",                vec2)  = (0.0, 0.0)
    // WITH_MASK only: CSS order, one vec4 per axis.
    _CornerRadiusX ("Corner radii X (TL,TR,BR,BL)", vec4)  = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner radii Y (TL,TR,BR,BL)", vec4)  = (0.0, 0.0, 0.0, 0.0)
    _LayerOpacity  ("Layer opacity",                float) = 1.0
}

struct v2f {
    // Grown by half a pixel when rotated, so the edge can antialias: CG_QUAD_EDGE_* in env/buffer/quad.glsl.
    vec2 param;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    // No Blend: CgMaterialState gives it, PREMULTIPLIED. A screen instance turns the depth test off.
    RenderState {
        Cull OFF
        DepthTest LEQUAL
        DepthWrite OFF
    }

    // No helper functions here: a Pass's #-lines are hoisted above the stage define, so a guard written here cannot
    // keep dFdx out of the vertex stage. Fragment-only helpers go in an included lib.

    void vertex(out v2f o) {
        o.param = CG_QUAD_EDGE_PARAM;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o.param), 1.0);
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Before any branch: it takes derivatives.
        float boxClip = CG_CLIP_QUAD_COVERAGE;
        vec2 cell = max(_Cell, vec2(0.5));
        // Position and line width in cells.
        vec2 param = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
        vec2 uv = param * _BoxSize / cell;
        vec2 lineWidth = clamp(_LineWidth / cell, vec2(0.0), vec2(1.0));

        vec2 ddxUv = dFdx(uv);
        vec2 ddyUv = dFdy(uv);
        vec2 uvDeriv = vec2(length(vec2(ddxUv.x, ddyUv.x)), length(vec2(ddxUv.y, ddyUv.y)));

        bvec2 invertLine = bvec2(lineWidth.x > 0.5, lineWidth.y > 0.5);
        vec2 targetWidth = vec2(
                invertLine.x ? 1.0 - lineWidth.x : lineWidth.x,
                invertLine.y ? 1.0 - lineWidth.y : lineWidth.y);

        vec2 drawWidth = clamp(targetWidth, uvDeriv, vec2(0.5));
        // 1.5 pixels of ramp either side.
        vec2 lineAA = uvDeriv * 1.5;

        vec2 gridUv = abs(fract(uv) * 2.0 - 1.0);
        gridUv.x = invertLine.x ? gridUv.x : 1.0 - gridUv.x;
        gridUv.y = invertLine.y ? gridUv.y : 1.0 - gridUv.y;

        vec2 grid = smoothstep(drawWidth + lineAA, drawWidth - lineAA, gridUv);
        grid *= clamp(targetWidth / drawWidth, vec2(0.0), vec2(1.0));
        grid = mix(grid, targetWidth, clamp(uvDeriv * 2.0 - 1.0, vec2(0.0), vec2(1.0)));
        grid.x = invertLine.x ? 1.0 - grid.x : grid.x;
        grid.y = invertLine.y ? 1.0 - grid.y : grid.y;

        // The union of both axes, not darker where they cross.
        float coverage = mix(grid.x, 1.0, grid.y);

        vec4 c = _Color;
        // A straight tint over a premultiplied colour.
        c *= vec4(i.color.rgb * i.color.a, i.color.a);

        float shape = coverage;

#ifdef WITH_MASK
        vec2 halfSize = _BoxSize * 0.5;
        vec2 localPos = (param - 0.5) * _BoxSize;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        shape *= sdf_coverage(dist, CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0);
#else
        shape *= CG_QUAD_EDGE_COVERAGE(i.param);
#endif

        fragColor = c * shape * _LayerOpacity * CG_QUAD_OPACITY * boxClip;
    }
}
