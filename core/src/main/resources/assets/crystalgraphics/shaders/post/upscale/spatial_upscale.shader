// The spatial upscale (CgSpatialUpscale, Unreal's PrimaryUpscale): a view drawn below 100%, its finished picture
// (cg_SceneColor, the size drawn at), into its host's target. Bilinear; CATMULL_ROM the 4x4 cubic (B 0, C 1/2), the
// default; LANCZOS the 6x6 Lanczos 3; EASU AMD's edge-adaptive upscale. With DEPTH it writes the nearest texel's depth
// too, so what draws after (OVERLAY) tests against the world.
//
// EASU is ported from ffx_fsr1.h (FidelityFX FSR 1 v1.20210629, FsrEasuF, FsrEasuSetF, FsrEasuTapF and FsrEasuCon),
// changed: its 12 taps are fetched texel by texel rather than gathered, its reciprocals exact, and no packed half path.
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
Material { SceneColorMargin 0.001  Media On }

Keywords {
    CATMULL_ROM ("CATMULL_ROM", switched)
    LANCZOS ("LANCZOS", switched)
    EASU ("EASU", switched)
    DEPTH ("DEPTH", switched)
}

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Upscale ("xy the size drawn at over the output's, zw the size drawn at", vec4) = (1, 1, 1, 1)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

ivec2 last;

vec4 tap(ivec2 at) {
    return texelFetch(cg_SceneColor, clamp(at, ivec2(0), last), 0);
}

#if defined(CATMULL_ROM)
// The cubic's weights at offsets -1, 0, 1, 2 from the texel below t
vec4 cubic(float t) {
    float t2 = t * t, t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);
}

vec3 resample(vec2 p) {
    ivec2 a = ivec2(floor(p));
    vec2 f = p - floor(p);
    vec4 wx = cubic(f.x), wy = cubic(f.y);
    vec3 c = vec3(0.0);
    for (int y = 0; y < 4; y++) {   // LOOP-BOUND: the cubic's four rows
        ivec2 r = a + ivec2(0, y - 1);
        c += wy[y] * (wx.x * tap(r + ivec2(-1, 0)).rgb + wx.y * tap(r).rgb + wx.z * tap(r + ivec2(1, 0)).rgb
                + wx.w * tap(r + ivec2(2, 0)).rgb);
    }
    return max(c, vec3(0.0));
}
#elif defined(LANCZOS)
float lanczos3(float x) {
    x = abs(x);
    if (x < 1.0e-5) return 1.0;
    if (x >= 3.0) return 0.0;
    float px = 3.14159265 * x;
    return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
}

vec3 resample(vec2 p) {
    ivec2 a = ivec2(floor(p));
    vec2 f = p - floor(p);
    vec3 c = vec3(0.0);
    float sum = 0.0;
    for (int y = 0; y < 6; y++) {   // LOOP-BOUND: the kernel's six rows
        float wy = lanczos3(float(y - 2) - f.y);
        for (int x = 0; x < 6; x++) {   // LOOP-BOUND: and six columns
            float w = wy * lanczos3(float(x - 2) - f.x);
            c += w * tap(a + ivec2(x - 2, y - 2)).rgb;
            sum += w;
        }
    }
    return max(c / sum, vec3(0.0));
}
#elif defined(EASU)
// Direction and length from one of the four bilinear corners' crosses
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC, cb = lC - lB;
    float lenX = clamp(abs(lD - lB) / max(max(abs(dc), abs(cb)), 1.0e-20), 0.0, 1.0);
    dir.x += (lD - lB) * w;
    len += lenX * lenX * w;
    float ec = lE - lC, ca = lC - lA;
    float lenY = clamp(abs(lE - lA) / max(max(abs(ec), abs(ca)), 1.0e-20), 0.0, 1.0);
    dir.y += (lE - lA) * w;
    len += lenY * lenY * w;
}

// One tap of the rotated, stretched Lanczos 2 approximation
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x) * len;
    float d2 = min(dot(v, v), clp);
    float wB = 2.0 / 5.0 * d2 - 1.0, wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

float easuLuma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

vec3 resample(vec2 p) {
    ivec2 a = ivec2(floor(p));
    vec2 pp = p - floor(p);
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = tap(a + ivec2(0, -1)).rgb, c = tap(a + ivec2(1, -1)).rgb;
    vec3 e = tap(a + ivec2(-1, 0)).rgb, f = tap(a).rgb, g = tap(a + ivec2(1, 0)).rgb, h = tap(a + ivec2(2, 0)).rgb;
    vec3 i = tap(a + ivec2(-1, 1)).rgb, j = tap(a + ivec2(0, 1)).rgb, k = tap(a + ivec2(1, 1)).rgb, l = tap(a + ivec2(2, 1)).rgb;
    vec3 n = tap(a + ivec2(0, 2)).rgb, o = tap(a + ivec2(1, 2)).rgb;
    float bL = easuLuma(b), cL = easuLuma(c), eL = easuLuma(e), fL = easuLuma(f), gL = easuLuma(g), hL = easuLuma(h);
    float iL = easuLuma(i), jL = easuLuma(j), kL = easuLuma(k), lL = easuLuma(l), nL = easuLuma(n), oL = easuLuma(o);
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
    float dirR = dot(dir, dir);
    bool zero = dirR < 1.0 / 32768.0;
    dir = zero ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
    len = len * 0.5;
    len *= len;
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;
    vec3 lo = min(min(f, g), min(j, k)), hi = max(max(f, g), max(j, k));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
    return min(hi, max(lo, aC / aW));
}
#else
vec3 resample(vec2 p) {
    ivec2 a = ivec2(floor(p));
    vec2 f = p - floor(p);
    return mix(mix(tap(a).rgb, tap(a + ivec2(1, 0)).rgb, f.x), mix(tap(a + ivec2(0, 1)).rgb, tap(a + ivec2(1, 1)).rgb, f.x), f.y);
}
#endif

void fragment(in v2f i, out vec4 fragColor) {
    last = ivec2(_Upscale.zw) - 1;
    // The output pixel's centre in the picture's texels, whose centres sit at whole numbers
    vec2 p = gl_FragCoord.xy * _Upscale.xy - 0.5;
    ivec2 nearest = clamp(ivec2(gl_FragCoord.xy * _Upscale.xy), ivec2(0), last);
    fragColor = vec4(resample(p), texelFetch(cg_SceneColor, nearest, 0).a);
#ifdef DEPTH
    gl_FragDepth = texelFetch(cg_DepthBuffer, nearest, 0).r;
#endif
}
