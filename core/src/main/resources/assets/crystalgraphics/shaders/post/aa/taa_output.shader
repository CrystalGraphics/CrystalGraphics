// Temporal AA's output (CgTemporalAA): the resolved colour out of the history, with the picture's own alpha in place of
// the history's age, sharpened by AMD FidelityFX RCAS (robust contrast adaptive sharpening, FSR's): the cross's lobe is
// solved for the most sharpening that keeps every channel inside its range, limited, and turned down where the texel
// stands out of its cross like noise rather than an edge (FSR_RCAS_DENOISE), so a dither's grain is not sharpened. RCAS
// works on 0 to 1: the HDR colour goes through c / (1 + c) and back, so a bright light sharpens as its tonemapped self.
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
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/lib/velocity.glsl"
Material { SceneColorMargin 0.001  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _History ("This frame's resolve", sampler2D) = "black"
    _Sharpen ("x RCAS's sharpness, 2^-stops (1 its most); 0 copies unsharpened. y 1 where every engine solid wrote velocity", vec4) = (0, 0, 0, 0)
    _Velocity ("What the velocity pass wrote, 0 where it wrote nothing", sampler2D) = "black"
    _Responsive ("1 where a blended draw covered (Responsive AA)", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

// FSR_RCAS_LIMIT: the lobe's most, short of where the cross would ring; times _Sharpen.x, which may pass 1 up to 4/3
const float RCAS_LIMIT = 0.25 - 1.0 / 16.0;

vec3 rcas_load(ivec2 q, ivec2 last) {
    vec3 c = max(texelFetch(_History, clamp(q, ivec2(0), last), 0).rgb, vec3(0.0));
    return c / (1.0 + c);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    float alpha = texelFetch(cg_SceneColor, at, 0).a;
    // Unsharpened where the engine drew nothing: the resolve passed it through, as temporal AA off shows it
    if (_Sharpen.x == 0.0 || _Sharpen.y > 0.5 && texelFetch(_Responsive, at, 0).r < 0.5 && !cg_EngineDrew(_Velocity, at)) {
        fragColor = vec4(texelFetch(_History, at, 0).rgb, alpha);
        return;
    }
    ivec2 last = textureSize(_History, 0) - 1;
    //   b
    // d e f
    //   h
    vec3 b = rcas_load(at + ivec2(0, -1), last), d = rcas_load(at + ivec2(-1, 0), last), e = rcas_load(at, last);
    vec3 f = rcas_load(at + ivec2(1, 0), last), h = rcas_load(at + ivec2(0, 1), last);
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
    float lobe = max(-RCAS_LIMIT, min(max(max(lobes.r, lobes.g), lobes.b), 0.0)) * _Sharpen.x * nz;
    vec3 sharp = clamp((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 0.0, 0.9999);
    fragColor = vec4(sharp / (1.0 - sharp), alpha);
}
