// CgVfxModule.Flow: carried by the gas its instance is in, its velocity drawn toward the gas's at m.x a second.
#pragma once

void fx_flow(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGas gas) {
    vec4 u = fx_gas_flow(gas, p.position);
    f.accel += (u.xyz - p.velocity) * m.x * u.w;
}
