// CgVfxModule.BeamSource: Cascade's BeamSource (ParticleBeamModules.cpp:1874-2130), a beam's start resolved at its birth
// and each step its locks leave open (fx_beam_resolve). A newborn beam starts at its source (:1730).
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_beam.glsl"

void fx_beam_source(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 actor, FxBeamFrame b) {
    bool born = p.age <= 0.0;
    vec4 point = p.beamSource, tangent = p.beamSourceTangent;
    if (born) point.w = 0.0;
    fx_beam_resolve(point, tangent, born, m0, m1, m2, actor, b);
    p.beamSource = point;
    p.beamSourceTangent = tangent;
    if (born) p.position = point.xyz;
}
