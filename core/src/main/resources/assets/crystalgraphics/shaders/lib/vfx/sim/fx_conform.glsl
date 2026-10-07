// CgVfxModule.Conform: bevy_hanabi's ConformToSphereModifier (MIT/Apache-2.0, (c) 2021 Jerome Humbert).
// m0: radius, influence (blocks past the surface), attraction (blocks a second squared), most speed toward the
// surface; m1: the shell's half thickness, the sticky factor. centre: the sphere's centre.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_conform(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, vec4 centre) {
    if (p.resting) return;
    vec3 r = centre.xyz - p.position;
    float distance = length(r);
    if (distance <= 0.0) return;
    vec3 dir = r / distance;
    float surface = distance - m0.x;
    if (surface > m0.y) return;
    float radial = dot(p.velocity, dir);
    float shell = smoothstep(0.0, m1.x, abs(surface));
    float delta = sign(surface) * shell * m0.w - radial;
    float accel = mix(m0.z * m1.y, m0.z, shell);
    p.velocity += dir * (sign(delta) * min(abs(delta), s.dt * accel));
}
