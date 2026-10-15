// The gas a particle's instance is carried by (CgVfxWorldInput.GAS, CgVfxEmitterInstance.inGas): its velocity where a
// particle is, as the gas's last frame left it. The Step kernel includes this when its shape takes GAS, after the
// atlas's properties (_GasVel, _GasPages, _GasLive).
//
//   void fx_flow(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGas gas) {
//       vec4 u = fx_gas_flow(gas, p.position);           // xyz blocks a second; w 1 where the gas holds a tile, else 0
//       f.accel += (u.xyz - p.velocity) * m.x * u.w;
//   }
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_gas.glsl"

// offset: the instance's origin from the gas's grid's low corner, in blocks; gas: its place in the atlas; cell: a cell's
// width in blocks; live: false with no gas, which every reader answers with nothing.
struct FxGas {
    vec3 offset;
    int gas;
    float cell;
    bool live;
};

// The gas's velocity at p (from the instance's origin), and 1, where it holds a tile; 0 elsewhere and with no gas.
vec4 fx_gas_flow(FxGas g, vec3 p) {
    if (!g.live) return vec4(0.0);
    vec3 c = (p + g.offset) / g.cell;
    int slot = fx_gas_slot(_GasPages, g.gas, c);
    if (slot < 0) return vec4(0.0);
    return vec4(textureLod(_GasVel, fx_gas_pool_uvw(_GasVel, slot, c), 0.0).xyz, 1.0);
}
