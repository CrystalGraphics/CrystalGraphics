// Reading a grid (CgVfxGrid3D, CgVfxGridAtlas): where a point of a grid is read in the atlas's volumes, its channels
// there, and a channel's slope. Every value is an argument; the volumes are passed in.
//
//     vec3 c = (p - gridLo) / cell;                                  // the grid's cells, 0 to cells
//     vec4 v = fx_grid_read(_GridView, brick, cells, c);             // its four channels, filtered; 0 outside it
//     vec3 slope = fx_grid_slope(_GridView, brick, cells, c, vec4(1, 0, 0, 0));   // channel x a cell a cell
#pragma once

// Cells a brick side: grid b's cell (i, j, k) is texel (i, j, k + FX_GRID_BRICK * b) of each atlas volume.
#define FX_GRID_BRICK 48

// Where grid b's point c is read, filtered: held half a cell inside its cells, so a read never takes a neighbour
// brick's texels.
vec3 fx_grid_uvw(sampler3D atlas, int b, float cells, vec3 c) {
    vec3 t = clamp(c, vec3(0.5), vec3(cells - 0.5));
    t.z += float(FX_GRID_BRICK * b);
    return t / vec3(textureSize(atlas, 0));
}

// Grid b's channels at c, filtered; 0 outside its cells.
vec4 fx_grid_read(sampler3D atlas, int b, float cells, vec3 c) {
    if (any(lessThan(c, vec3(0.0))) || any(greaterThan(c, vec3(cells)))) return vec4(0.0);
    return textureLod(atlas, fx_grid_uvw(atlas, b, cells, c), 0.0);
}

// The channels mask picks, summed, at c.
float fx_grid_channel(sampler3D atlas, int b, float cells, vec3 c, vec4 mask) {
    return dot(fx_grid_read(atlas, b, cells, c), mask);
}

// The slope of the channels mask picks at c, a unit a cell, by central differences a cell apart.
vec3 fx_grid_slope(sampler3D atlas, int b, float cells, vec3 c, vec4 mask) {
    return 0.5 * vec3(
            fx_grid_channel(atlas, b, cells, c + vec3(1.0, 0.0, 0.0), mask) - fx_grid_channel(atlas, b, cells, c - vec3(1.0, 0.0, 0.0), mask),
            fx_grid_channel(atlas, b, cells, c + vec3(0.0, 1.0, 0.0), mask) - fx_grid_channel(atlas, b, cells, c - vec3(0.0, 1.0, 0.0), mask),
            fx_grid_channel(atlas, b, cells, c + vec3(0.0, 0.0, 1.0), mask) - fx_grid_channel(atlas, b, cells, c - vec3(0.0, 0.0, 1.0), mask));
}
