// A ruled grid over a box in one draw, antialiased at any scale and cell size; CgCanvasGrid sets every property.
//
// Ben Golus's "Pristine Grid" ("The Best Darn Grid Shader (Yet)", 2023). His gist has no licence, so this follows the
// CC0 Godot port and the MIT WebGPU port (toji/pristine-grid-webgpu) of the technique; see THIRD-PARTY.md. Over the
// usual smoothstep(fwidth(fract(uv))), which thickens, moires and cuts out as cells shrink:
//   1. derivatives per axis by length, so a rotated grid measures its own footprint;
//   2. a line never thinner than a pixel, faded by the factor it was widened, so its brightness holds;
//   3. under a pixel a cell, a dissolve to the grid's mean density instead of moire.
// invertLine draws the gaps instead once a line passes half a cell. Drawn PREMULTIPLIED (CgCanvasMaterials).
#domain ui
#include "crystalgraphics:shaders/lib/sdf.glsl"
Material { Primitive Quad }

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
}

Keywords {
    WITH_MASK ("Clipped to rounded corners", switched)
}

void surface(in CgUiInput i, inout CgUiSurface s) {
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
    shape *= s.coverage;
#endif
    s.color = c;
    s.coverage = shape;
    s.premultiplied = true;
}
