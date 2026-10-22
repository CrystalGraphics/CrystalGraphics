// CgVfxModule.BeamModifier: Cascade's FBeamParticleModifierPayloadData (ParticleHelper.h), applied each step to the end
// it names after that end resolves (ParticleBeamModules.cpp:150-171, :265-284), so on a locked end it adds up as
// Cascade's does. Each part added, or multiplied where scaled; a tangent turned from +X onto the end's own unless
// absolute (FQuat::FindBetweenNormals).
//
//   m0 position xyz, 1 for the target    m2 strength, modify position, scale position, modify tangent
//   m1 tangent xyz, absolute tangent     m3 scale tangent, modify strength, scale strength
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_beam.glsl"

// The rotation taking unit a onto unit b, as FQuat::FindBetweenNormals.
vec4 fx_beam_between(vec3 a, vec3 b) {
    float w = 1.0 + dot(a, b);
    vec4 q = w < 1e-6 ? (abs(a.x) > abs(a.z) ? vec4(-a.y, a.x, 0.0, 0.0) : vec4(0.0, -a.z, a.y, 0.0)) : vec4(cross(a, b), w);
    return q / length(q);
}

void fx_beam_modifier(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3) {
    bool target = m0.w > 0.5;
    vec4 point = target ? p.beamTarget : p.beamSource;
    vec4 tangent = target ? p.beamTargetTangent : p.beamSourceTangent;
    if (m2.y > 0.5) point.xyz = m2.z > 0.5 ? point.xyz * m0.xyz : point.xyz + m0.xyz;
    if (m2.w > 0.5) {
        vec3 by = m1.w > 0.5 ? m1.xyz : fx_beam_turn(fx_beam_between(vec3(1.0, 0.0, 0.0), fx_beam_unit(tangent.xyz, vec3(1.0, 0.0, 0.0))), m1.xyz);
        tangent.xyz = m3.x > 0.5 ? tangent.xyz * by : tangent.xyz + by;
    }
    if (m3.y > 0.5) tangent.w = m3.z > 0.5 ? tangent.w * m2.x : tangent.w + m2.x;
    if (target) {
        p.beamTarget = point;
        p.beamTargetTangent = tangent;
    } else {
        p.beamSource = point;
        p.beamSourceTangent = tangent;
    }
}
