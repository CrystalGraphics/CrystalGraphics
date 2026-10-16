// CgVfxModule.Feed: each particle pours its m.x of density and m.y of heat a second into its instance's gas, where it is,
// and couples the gas's velocity there to its own at m.z a second (NVIDIA Flow's point emitter).
// m: (density, heat, velocity couple rate, 0).
#pragma once

void fx_gas_feed(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGas gas) {
    step_feed(gas, p.position, p.velocity, m.x, m.y, m.z, s.dt);
}
