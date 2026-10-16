// A fluid's layer maths (CgVfxFluid, vfx/fluid/fluid.shader). Needs fx_wavelet.glsl.
//
//     float n = fx_fluid_detail(_Wavelet, c - rest, cell, _DetailSize);   // about -1.5..1.5, mean 0
#pragma once

// Flow's stepSizeScale: three quarters of a cell a step.
#define FX_FLUID_STEP_CELLS 0.75

// Two octaves of wavelet noise at rest position r (cells) of a cell `cell` blocks wide, sized `size` blocks.
float fx_fluid_detail(sampler3D wavelet, vec3 r, float cell, float size) {
    vec3 q = r * cell / max(size, 0.02);
    return fx_wavelet(wavelet, q) + 0.5 * fx_wavelet(wavelet, q * 2.0 + vec3(31.7, 17.3, 53.1));
}
