// CgVfxModule.Updraft: rising air over the source, dying away. m: strength (blocks a second squared), radius, height
// (blocks), duration (seconds). fadeSource: its fade at the step's start, then the source, from the CPU.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_updraft(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, vec4 fadeSource) {
    if (fadeSource.x <= 0.0) return;
    vec3 d = p.position - fadeSource.yzw;
    float column = exp(-(d.x * d.x + d.z * d.z) * (1.0 / (m.y * m.y))) * (1.0 - smoothstep(0.0, m.z, d.y));
    f.accel.y += m.x * column * fadeSource.x;
}
