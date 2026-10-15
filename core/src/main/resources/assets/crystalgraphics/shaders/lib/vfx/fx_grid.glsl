// Reading a grid (CgVfxGrid3D, CgVfxGridAtlas): its channels at a point, filtered, and a channel's slope, through its
// page table (fx_sparse.glsl). A point is in the grid's cells from its low corner, its middle at FX_SPARSE_SPAN / 2.
// What it holds fades out toward a missing tile already, so a reader never sees a tile's edge. Every value is an
// argument; the volumes are passed in.
//
//     vec3 c = (p - gridMiddle) / cell + float(FX_SPARSE_SPAN / 2);
//     vec4 v = fx_grid_read(_GridView, _GridPages, grid, c);                       // 0 where it has no tile
//     vec3 slope = fx_grid_slope(_GridView, _GridPages, grid, c, vec4(1, 0, 0, 0));   // channel x a cell a cell
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_sparse.glsl"

// Grid b's channels at c, filtered; 0 where it has no tile.
vec4 fx_grid_read(sampler3D view, sampler3D pages, int b, vec3 c) {
    return fx_sparse_read(view, pages, b, c);
}

// The channels mask picks, summed, at c.
float fx_grid_channel(sampler3D view, sampler3D pages, int b, vec3 c, vec4 mask) {
    return dot(fx_grid_read(view, pages, b, c), mask);
}

// The slope of the channels mask picks at c, a unit a cell, by central differences a cell apart.
vec3 fx_grid_slope(sampler3D view, sampler3D pages, int b, vec3 c, vec4 mask) {
    return 0.5 * vec3(
            fx_grid_channel(view, pages, b, c + vec3(1.0, 0.0, 0.0), mask) - fx_grid_channel(view, pages, b, c - vec3(1.0, 0.0, 0.0), mask),
            fx_grid_channel(view, pages, b, c + vec3(0.0, 1.0, 0.0), mask) - fx_grid_channel(view, pages, b, c - vec3(0.0, 1.0, 0.0), mask),
            fx_grid_channel(view, pages, b, c + vec3(0.0, 0.0, 1.0), mask) - fx_grid_channel(view, pages, b, c - vec3(0.0, 0.0, 1.0), mask));
}
