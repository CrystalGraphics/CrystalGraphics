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

// A drawn beam's points, read from its run (at its first vec4) through FX_BEAM_RUN(at), which the includer defines
// first: the strip factory reads CG_PARTICLE_BEAM. noisePoints is its pool's CgVfxShape.beamPoints, end where its far
// end has travelled to, and c2 its draw's interpolation points, flags (1 noise on the target) and noise frequency (w).
#ifdef FX_BEAM_RUN
// A tangent and its strength, Cascade's: a zero tangent is the effect's forward (ParticleBeamModules.cpp:497-514).
vec3 fx_beam_tangent(vec4 tangent, vec4 turn) {
    return (dot(tangent.xyz, tangent.xyz) > 1e-12 ? tangent.xyz : fx_beam_turn(turn, vec3(1.0, 0.0, 0.0))) * tangent.w;
}

// Noise point j of the run at, in the effect's frame and scaled: Cascade's NoiseRangeScale * LocalToWorld(point).
vec3 fx_beam_offset(int at, int j) {
    return FX_BEAM_RUN(at + FX_BEAM_NOISE).w * fx_beam_turn(FX_BEAM_RUN(at + FX_BEAM_TURN), FX_BEAM_RUN(at + FX_BEAM_HEADER + j).xyz);
}

// The tangents' curve at a, InterpolatedPoints' (ParticleBeamModules.cpp:483-527).
vec3 fx_beam_curve(int at, float a) {
    vec4 turn = FX_BEAM_RUN(at + FX_BEAM_TURN);
    return fx_beam_cubic(FX_BEAM_RUN(at + FX_BEAM_SOURCE).xyz, fx_beam_tangent(FX_BEAM_RUN(at + FX_BEAM_SOURCE_TANGENT), turn),
                         FX_BEAM_RUN(at + FX_BEAM_TARGET).xyz, fx_beam_tangent(FX_BEAM_RUN(at + FX_BEAM_TARGET_TANGENT), turn), a);
}

// Noise draw point j of steps (0 the source, steps + 1 the target): the line's point, or on interpolation points the
// curve's as FillData_InterpolatedNoise indexes it (ParticleSystemRender.cpp:4441-4627), plus its noise.
vec3 fx_beam_draw(int at, int j, int steps, float points, float frequency, bool targetNoise) {
    vec3 s = FX_BEAM_RUN(at + FX_BEAM_SOURCE).xyz, t = FX_BEAM_RUN(at + FX_BEAM_TARGET).xyz;
    if (j <= 0) return s;
    if (j > steps) return t + (targetNoise ? fx_beam_offset(at, int(frequency + 0.5)) : vec3(0.0));
    vec3 line;
    if (points > 0.0) {
        float stepSize = points / float(steps), frac = fract(stepSize);
        int index = int(stepSize), s0 = (j - 1) * index, last = int(points + 0.5) - 1;
        vec3 a = fx_beam_curve(at, float(min(s0, last) + 1) / points);
        vec3 b = j == steps ? t : fx_beam_curve(at, float(min(s0 + 1, last) + 1) / points);
        line = a * (1.0 - frac) + b * frac;
    } else {
        line = s + (t - s) * (float(j) / float(steps + 1));
    }
    return line + fx_beam_offset(at, j - 1);
}

// Point k of the beam's strip, whose segments it is drawn with: ParticleSystemRender.cpp's FillVertexData_NoNoise
// (:2948) and FillData_Noise (:3361), tessellating between noise points by FMath::CubicInterp, each inner tangent along
// its neighbours at the noise's tangent strength (:3640-3650).
vec3 fx_beam_point(int at, int k, int segments, int noisePoints, vec3 end, vec4 c2) {
    vec4 source = FX_BEAM_RUN(at + FX_BEAM_SOURCE);
    k = clamp(k, 0, segments);
    if (source.w < 0.5) return mix(source.xyz, end, float(k) / float(max(segments, 1)));
    if (noisePoints == 0) {
        if (c2.x <= 0.0) return mix(source.xyz, FX_BEAM_RUN(at + FX_BEAM_TARGET).xyz, float(min(k, 1)));
        return fx_beam_curve(at, float(k) / c2.x);
    }
    vec4 tag = FX_BEAM_RUN(at + FX_BEAM_TAG);
    int steps = int(FX_BEAM_RUN(at + FX_BEAM_TARGET).w + 0.5), tess = max(int(tag.z + 0.5), 1);
    bool targetNoise = (int(c2.y + 0.5) & 1) != 0;
    int j = min(k / tess, steps + 1), within = k - j * tess;
    vec3 a = fx_beam_draw(at, j, steps, c2.x, c2.w, targetNoise);
    if (within == 0 || tess <= 1 || j > steps) return a;
    vec3 b = fx_beam_draw(at, j + 1, steps, c2.x, c2.w, targetNoise);
    vec4 turn = FX_BEAM_RUN(at + FX_BEAM_TURN);
    vec3 ta = j == 0 ? fx_beam_tangent(FX_BEAM_RUN(at + FX_BEAM_SOURCE_TANGENT), turn)
            : fx_beam_unit(b - fx_beam_draw(at, j - 1, steps, c2.x, c2.w, targetNoise), vec3(0.0)) * tag.w;
    vec3 tb = j + 1 > steps ? fx_beam_unit(FX_BEAM_RUN(at + FX_BEAM_TARGET_TANGENT).xyz, vec3(0.0)) * FX_BEAM_RUN(at + FX_BEAM_TARGET_TANGENT).w
            : fx_beam_unit(fx_beam_draw(at, j + 2, steps, c2.x, c2.w, targetNoise) - a, vec3(0.0)) * tag.w;
    return fx_beam_cubic(a, ta, b, tb, float(within) / float(tess));
}

// The segments a beam of the draw's segments fills: its noise steps times its tessellation, the rest folding onto its
// last point; all of them for a beam without noise, or before its far end arrives.
int fx_beam_filled(int at, int noisePoints, int segments) {
    int filled = noisePoints > 0 && FX_BEAM_RUN(at + FX_BEAM_SOURCE).w > 0.5
            ? (int(FX_BEAM_RUN(at + FX_BEAM_TARGET).w + 0.5) + 1) * max(int(FX_BEAM_RUN(at + FX_BEAM_TAG).z + 0.5), 1) : segments;
    return min(filled, segments);
}
#endif
