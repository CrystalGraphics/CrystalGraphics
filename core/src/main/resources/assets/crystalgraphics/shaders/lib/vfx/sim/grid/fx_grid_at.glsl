// The grid a particle's instance is in (CgVfxWorldInput.GRID, CgVfxEmitterInstance.inGrid): its channels where a
// particle is, as the last frame left them, and a channel's slope. The Step kernel includes this when its shape takes
// GRID or GRID_SPLAT, after the atlas's properties (_GridView, _GridLive).
//
//   void fx_grid_gradient(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGrid grid) {
//       f.accel -= fx_grid_gradient_at(grid, p.position, fx_grid_mask(m.x)) * m.y;   // down the slope
//   }
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_grid.glsl"

// offset: the instance's origin from the grid's low corner, in blocks; brick: its place in the atlas; cell: a cell's
// width in blocks; cells: cells a side; live: false with no grid, which every reader answers with nothing.
struct FxGrid {
    vec3 offset;
    int brick;
    float cell;
    float cells;
    bool live;
};

// The mask picking channel i (0 to 3).
vec4 fx_grid_mask(float i) {
    int c = int(i + 0.5);
    return vec4(c == 0 ? 1.0 : 0.0, c == 1 ? 1.0 : 0.0, c == 2 ? 1.0 : 0.0, c == 3 ? 1.0 : 0.0);
}

// The grid's channels at p (from the instance's origin); 0 outside it and with no grid.
vec4 fx_grid_value(FxGrid g, vec3 p) {
    if (!g.live) return vec4(0.0);
    return fx_grid_read(_GridView, g.brick, g.cells, (p + g.offset) / g.cell);
}

// The slope of the channels mask picks at p, a unit a block; 0 with no grid.
vec3 fx_grid_gradient_at(FxGrid g, vec3 p, vec4 mask) {
    if (!g.live) return vec3(0.0);
    return fx_grid_slope(_GridView, g.brick, g.cells, (p + g.offset) / g.cell, mask) / g.cell;
}
