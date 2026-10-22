// Cascade's beam (UParticleModuleTypeDataBeam2), shared by its Step kinds (sim/fx_beam_*.glsl), the noise kernel
// (env/compute/vfx/beam.compute) and the strip a draw pulls from them. A beam is one particle; its record's BEAM tail
// holds what Cascade's beam payload keeps between updates, in the particle's frame (from its instance's origin):
//
//   beamSource         the source, and 1 once the far end has arrived (Cascade's "locked")
//   beamSourceTangent  the source's tangent, unnormalized, and its strength
//   beamTarget         the target, and the noise steps it is drawn with (BeamData->Steps)
//   beamTargetTangent  the target's tangent, and its strength
//   beamNoise          its noise frequency (rolled down to frequency_low), seconds since its points were picked, how many
//                      picks so far, and its range scale times its distance scale
//   beamTurn           its effect's orientation, which turns its noise as Cascade's LocalToWorld does
//
// The record's own position is where its far end has travelled to. Each step beam.compute copies the tail into the
// beam's run behind a tag, and its noise points after: what the strip reads, at FX_BEAM_* vec4s of the run.
#pragma once

#define FX_BEAM_TAG 0
#define FX_BEAM_SOURCE 1
#define FX_BEAM_SOURCE_TANGENT 2
#define FX_BEAM_TARGET 3
#define FX_BEAM_TARGET_TANGENT 4
#define FX_BEAM_NOISE 5
#define FX_BEAM_TURN 6
#define FX_BEAM_HEADER 7

// What a beam kind reads of its instance: its orientation, its origin in the world, and its loop's seconds.
struct FxBeamFrame {
    vec4 turn;
    vec3 origin;
    float loop;
};

// v turned by the unit quaternion q.
vec3 fx_beam_turn(vec4 q, vec3 v) {
    vec3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

// FMath::CubicInterp: Hermite from p0 (tangent t0) to p1 (tangent t1) at a in 0..1.
vec3 fx_beam_cubic(vec3 p0, vec3 t0, vec3 p1, vec3 t1, float a) {
    float a2 = a * a, a3 = a2 * a;
    return (2.0 * a3 - 3.0 * a2 + 1.0) * p0 + (a3 - 2.0 * a2 + a) * t0 + (a3 - a2) * t1 + (-2.0 * a3 + 3.0 * a2) * p1;
}

// v normalized, or fallback where it has no length (FVector::Normalize leaves a zero vector zero, and Cascade then
// takes the emitter's X axis for a tangent).
vec3 fx_beam_unit(vec3 v, vec3 fallback) {
    float l = length(v);
    return l > 1e-8 ? v / l : fallback;
}

// One end resolved, as BeamSource and BeamTarget do (ParticleBeamModules.cpp:1886-2112): at the beam's birth, and each
// step after where its lock is off. m0 the point and absolute, m1 the tangent and its method (0 the effect's forward,
// 1 the tangent turned with it unless absolute, 2 the tangent as written), m2 the strength and the point's, the
// tangent's and the strength's locks. A point is from the effect's origin and turned with it, or absolute in the world.
void fx_beam_resolve(inout vec4 point, inout vec4 tangent, bool born, vec4 m0, vec4 m1, vec4 m2, FxBeamFrame b) {
    if (born || m2.y < 0.5) point.xyz = m0.w > 0.5 ? m0.xyz - b.origin : fx_beam_turn(b.turn, m0.xyz);
    if (born || m2.z < 0.5) {
        if (m1.w < 0.5) tangent.xyz = fx_beam_turn(b.turn, vec3(1.0, 0.0, 0.0));
        else if (m1.w < 1.5 && m0.w < 0.5) tangent.xyz = fx_beam_turn(b.turn, m1.xyz);
        else tangent.xyz = m1.xyz;
    }
    if (born || m2.w < 0.5) tangent.w = m2.x;
}
