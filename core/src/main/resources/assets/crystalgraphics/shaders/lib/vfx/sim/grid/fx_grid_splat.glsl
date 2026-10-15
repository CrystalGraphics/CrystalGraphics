// CgVfxModule.Splat: each particle adds m's four amounts a second to its instance's grid where it is, after the
// solver. m: the amounts.
#pragma once

void fx_grid_splat(inout FxParticle p, FxStep s, vec4 m, FxGrid grid) {
    step_splat(grid, p.position, m, s.dt);
}
