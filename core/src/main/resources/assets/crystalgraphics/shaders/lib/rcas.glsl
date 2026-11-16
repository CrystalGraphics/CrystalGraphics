// AMD FidelityFX RCAS (robust contrast adaptive sharpening, FSR 1's), for a temporal step's resolved picture: the
// cross's lobe is solved for the most sharpening that keeps every channel inside its range, limited, and turned down
// where the centre stands out of its cross like noise rather than an edge (FSR_RCAS_DENOISE), so a dither's grain is not
// sharpened. RCAS works on 0 to 1: rcas_encode takes HDR colour through c / (1 + c), rcas_decode back, so a bright light
// sharpens as its tonemapped self. Used by temporal AA's output and TSR's sharpen pass.
//
// Ported from ffx_fsr1.h (FidelityFX FSR 1, FsrRcasF and FsrRcasCon, with FSR_RCAS_DENOISE):
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
#pragma once

// FSR_RCAS_LIMIT: the lobe's most, short of where the cross would ring; times the sharpness, which may pass 1 up to 4/3
const float RCAS_LIMIT = 0.25 - 1.0 / 16.0;

vec3 rcas_encode(vec3 hdr) {
    vec3 c = max(hdr, vec3(0.0));
    return c / (1.0 + c);
}

vec3 rcas_decode(vec3 sharp) {
    return sharp / (1.0 - sharp);
}

// The centre e sharpened against its cross (b above, d left, f right, h below), all rcas_encode'd; sharpness is
// 2^-stops, 1 its most. Answers the encoded result.
vec3 rcas(vec3 b, vec3 d, vec3 e, vec3 f, vec3 h, float sharpness) {
    // Noise: the cross's highpass against its luma range, a texel standing alone halving the sharpening
    float bL = b.b * 0.5 + (b.r * 0.5 + b.g), dL = d.b * 0.5 + (d.r * 0.5 + d.g), eL = e.b * 0.5 + (e.r * 0.5 + e.g);
    float fL = f.b * 0.5 + (f.r * 0.5 + f.g), hL = h.b * 0.5 + (h.r * 0.5 + h.g);
    float range = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL);
    float nz = clamp(abs(0.25 * (bL + dL + fL + hL) - eL) / max(range, 1.0e-5), 0.0, 1.0);
    nz = -0.5 * nz + 1.0;
    // The ring's min and max, and the lobe each channel takes before it clips at 0 or 1
    vec3 mn4 = min(min(b, d), min(f, h)), mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-5));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0e-5));
    vec3 lobes = max(-hitMin, hitMax);
    float lobe = max(-RCAS_LIMIT, min(max(max(lobes.r, lobes.g), lobes.b), 0.0)) * sharpness * nz;
    return clamp((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 0.0, 0.9999);
}
