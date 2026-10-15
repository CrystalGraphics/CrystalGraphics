// CgVfxModule.Feed: each particle pours its m.x of density and m.y of heat a second into its instance's gas, where it is.
// m: (density, heat, 0, 0).
#pragma once

void fx_gas_feed(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGas gas) {
    step_feed(gas, p.position, m.x, m.y, s.dt);
}
