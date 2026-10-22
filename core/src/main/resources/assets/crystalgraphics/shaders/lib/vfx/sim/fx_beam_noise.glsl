// CgVfxModule.BeamNoise, the Step's half: Cascade's BeamNoise clock (ParticleBeamModules.cpp:1372-1570) and the noise
// steps the beam is drawn with (UParticleModuleTypeDataBeam2::Update, :376-409). Its points are beam.compute's, which
// reads these: a pick each time beamNoise.z counts up.
//
//   m0 frequency, frequency_low, lock_time, smooth     m4 speed's high end xyz, tessellation
//   m1 range min xyz, oscillate                         m5 range scale's ends, tangent strength's ends
//   m2 range max xyz, uniform                           m6 frequency distance, scale, target noise, sheets
//   m3 speed's low end xyz, lock radius
//
// Its frequency rolls once, at birth, down to frequency_low (:1421-1426). Its points are picked again once lock_time
// has passed, every step at 0, never below 0 (:1514-1544). Locked to its target, frequency_distance sets the steps by
// the beam's length, at most its frequency, with scale as the distance scale (:383-409; Cascade's NoiseScale curve at
// count / frequency, here its value). beamNoise.w is range_scale times that scale, the offsets' one factor: Cascade
// reads range_scale at its emitter's time each frame (ParticleBeam2EmitterInstance.cpp:1403-1410), a roll an emitter a
// step between its ends.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_rand.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_beam.glsl"

void fx_beam_noise(inout FxParticle p, FxStep s, vec4 m0, vec4 m1, vec4 m2, vec4 m3, vec4 m4, vec4 m5, vec4 m6,
                   FxBeamFrame b) {
    vec4 n = p.beamNoise;
    if (p.age <= 0.0) {
        float frequency = m0.x;
        if (m0.y > 0.0) frequency = floor(fx_rand(floatBitsToUint(p.seed), p.id, 0xBEA0u) * (m0.x - m0.y) + m0.y);
        n = vec4(frequency, 0.0, 0.0, 1.0);
    } else if (m0.z > 1e-4) {
        n.y += s.dt;
        if (n.y > m0.z) {
            n.y = 0.0;
            n.z += 1.0;
        }
    } else if (m0.z >= 0.0) {
        n.z += 1.0;
    }
    float rangeScale = mix(m5.x, m5.y, fx_rand(p.slot, floatBitsToUint(b.loop), 0xBEA1u));
    vec4 target = p.beamTarget;
    if (m6.x > 0.0 && p.beamSource.w > 0.5) {
        target.w = min(floor(length(target.xyz - p.beamSource.xyz) / m6.x), n.x);
        n.w = m6.y * rangeScale;
    } else {
        target.w = n.x;
        n.w = rangeScale;
    }
    p.beamTarget = target;
    p.beamNoise = n;
    p.beamTurn = b.turn;
}
