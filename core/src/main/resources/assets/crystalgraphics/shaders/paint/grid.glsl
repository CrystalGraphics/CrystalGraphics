// A grid's maths, shared by every shader that paints a CgGrid: Ben Golus's "Pristine Grid" ("The Best Darn Grid
// Shader (Yet)", 2023), following the CC0 Godot port and the MIT WebGPU port (toji/pristine-grid-webgpu), see
// THIRD-PARTY.md; and Inigo Quilez's box-filtered checkerboard ("Filtering procedurals", MIT). Over the usual
// smoothstep(fwidth(fract(uv))), which thickens, moires and cuts out as cells shrink:
//   1. derivatives per axis by length, so a rotated grid measures its own footprint;
//   2. a line never thinner than a pixel, faded by the factor it was widened, so its brightness holds;
//   3. under a pixel a cell, a dissolve to the grid's mean density instead of moire.
// A line past half a cell draws the gaps instead. The caller passes uv's derivatives, so any stage includes it.
#pragma once

// The lines' coverage at uv (in cells) for lines lineWidth wide (in cells, 0 to 1): 1 on a line, 0 in a cell.
float cg_grid_lines(vec2 uv, vec2 lineWidth, vec2 ddxUv, vec2 ddyUv) {
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
    return mix(grid.x, 1.0, grid.y);
}

// The checker's square wave at uv (in cells), box-filtered over the pixel's footprint: 0 on the first cells, 1 on
// the second, their mean as a cell shrinks under a pixel.
float cg_grid_checker(vec2 uv, vec2 ddxUv, vec2 ddyUv) {
    vec2 w = abs(ddxUv) + abs(ddyUv) + 0.001;
    vec2 k = 2.0 * (abs(fract((uv - 0.5 * w) * 0.5) - 0.5) - abs(fract((uv + 0.5 * w) * 0.5) - 0.5)) / w;
    return 0.5 - 0.5 * k.x * k.y;
}
