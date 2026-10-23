// CgVfxModule.Attract: Godot's sphere and box attractors. m0: the volume (fx_contact.glsl); m1: strength (blocks a
// second squared, positive pulls in), attenuation, directionality, 1 when it lasts; m2: the axis a directed pull pushes
// along. centre: the volume's centre. A pull that does not last moves the particle for the step alone: Cascade's, over a
// base velocity reset each tick.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_contact.glsl"

void fx_attract(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 centre) {
    vec3 r = p.position - centre.xyz;
    float d = fx_volume_reach(m0, r);
    if (d > 1.0) return;
    float amount = m1.y == 0.0 ? 1.0 : pow(max(0.0, 1.0 - d), m1.y);   // no attenuation: Cascade's, even at its rim
    vec3 dir = fx_safe_normalize(mix(fx_safe_normalize(r), -m2.xyz, m1.z));
    vec3 pull = -dir * (amount * m1.x);
    if (m1.w > 0.5) f.accel += pull;
    else f.motionSet += pull * s.dt;
}
