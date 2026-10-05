// CgVfxModule.Turbulence: a curl-noise force, the field fixed in the world so neighbours swirl together.
// m: strength (blocks a second squared), frequency (a block), evolve (a second). cell0, frac0, cell1, frac1: where the
// instance's origin samples each octave, split in doubles on the CPU with the drift folded in (fx_curl.glsl).
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_curl.glsl"

void fx_turbulence(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, ivec4 cell0, vec4 frac0, ivec4 cell1,
                   vec4 frac1) {
    if (p.resting) return;
    vec3 at = p.position * m.y;
    f.accel += fx_curl(cell0.xyz, frac0.xyz + at, cell1.xyz, frac1.xyz + at * 2.03) * (m.x * 0.5);
}
