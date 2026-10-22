// CgVfxModule.BeamTarget: Cascade's BeamTarget (ParticleBeamModules.cpp:2324-2560), a beam's far end resolved as its
// source is, then the beam's travel there (UParticleModuleTypeDataBeam2::Update, :296-326): at speed 0 it pops to its
// target; else the particle flies at m3.y blocks a second from where it is, arriving within m3.x of the target on every
// axis. Once arrived it holds the target. m0..m2 as fx_beam_resolve's; m3 the lock radius and the speed.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_beam.glsl"

void fx_beam_target(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, FxBeamFrame b) {
    bool born = p.age <= 0.0;
    vec4 point = p.beamTarget, tangent = p.beamTargetTangent, source = p.beamSource;
    fx_beam_resolve(point, tangent, born, m0, m1, m2, b);
    if (m3.y <= 0.0 || source.w > 0.5) {
        p.position = point.xyz;
        source.w = 1.0;
    } else {
        vec3 next = p.position + fx_beam_unit(point.xyz - p.position, vec3(0.0)) * m3.y * s.dt;
        if (all(lessThan(abs(next - point.xyz), vec3(m3.x)))) {
            p.position = point.xyz;
            source.w = 1.0;
        } else {
            p.position = next;
        }
    }
    p.velocity = vec3(0.0);
    p.beamTarget = point;
    p.beamTargetTangent = tangent;
    p.beamSource = source;
}
