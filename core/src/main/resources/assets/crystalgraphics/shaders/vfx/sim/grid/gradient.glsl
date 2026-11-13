// CgVfxModule.Gradient: pushed down the grid's slope of a channel, m.y blocks a second squared a unit a block: out of
// where particles crowd, by a channel they splat; a negative m.y draws them up it. m: (channel, strength, 0, 0).
#pragma once

void fx_grid_gradient(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGrid grid) {
    f.accel -= fx_grid_gradient_at(grid, p.position, fx_grid_mask(m.x)) * m.y;
}
