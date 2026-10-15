// The gas a particle's instance is carried by (CgVfxWorldInput.GAS, CgVfxEmitterInstance.inGas): its velocity where a
// particle is, as the gas's last step left it. The Step kernel includes this when its shape takes GAS, after the
// atlas's properties (_GasVel, _GasCells, _GasBricks).
//
//   void fx_flow(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGas gas) {
//       vec4 u = fx_gas_flow(gas, p.position);           // xyz blocks a second; w 1 inside its box, else 0
//       f.accel += (u.xyz - p.velocity) * m.x * u.w;
//   }
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_gas.glsl"

// offset: the instance's origin from the gas's middle, in blocks; brick: its brick in the atlas; cell: a cell's width
// in blocks; live: false with no gas, which every reader answers with nothing.
struct FxGas {
    vec3 offset;
    float brick;
    float cell;
    bool live;
};

// The gas's velocity at p (from the instance's origin), and 1, inside its box; 0 outside it and with no gas.
vec4 fx_gas_flow(FxGas g, vec3 p) {
    if (!g.live) return vec4(0.0);
    vec3 local = (p + g.offset) / (g.cell * float(_GasCells)) + 0.5;
    if (any(lessThan(local, vec3(0.0))) || any(greaterThan(local, vec3(1.0)))) return vec4(0.0);
    vec3 uvw = fx_gas_uvw(local, g.brick, float(_GasCells), float(_GasBricks));
    return vec4(textureLod(_GasVel, uvw, 0.0).xyz, 1.0);
}
