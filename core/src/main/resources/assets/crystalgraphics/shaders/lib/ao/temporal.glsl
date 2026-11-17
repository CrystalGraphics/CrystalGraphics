// The ambient occlusion temporal filter GTAO and SSAO share (world/ao/gtao.compute, ssao.compute): last frame's
// occlusion reprojected, held near this frame's, blended in.
//
// The including file declares, before its include:
//   _Filtered, _History, _Velocity   sampler2D: this frame's spatially filtered occlusion, last frame's resolved one, and
//                                    the velocity pass's target (0 where nothing wrote)
//   _AoSize, _Size                   vec4: the occlusion's size and the render size, each with its inverse
#include "crystalgraphics:shaders/lib/velocity.glsl"

// The scene's window depth under uv, at the render texel there.
float ao_window_depth(vec2 uv) {
    ivec2 p = clamp(ivec2(floor(uv * _Size.xy)), ivec2(0), ivec2(_Size.xy) - 1);
    return texelFetch(cg_DepthBuffer, p, 0).r;
}

// Where the surface at uv, of window depth window, was last frame: its written velocity, else the camera's motion over it.
vec2 previous_uv(vec2 uv, float window) {
    vec2 ndc = uv * 2.0 - 1.0;
    ivec2 size = textureSize(_Velocity, 0);
    vec2 written = texelFetch(_Velocity, clamp(ivec2(floor(uv * vec2(size))), ivec2(0), size - 1), 0).xy;
    if (written.x > 0.0) return (ndc - cg_DecodeVelocity(written)) * 0.5 + 0.5;
    float eye = cg_LinearEyeDepth(window);
    vec3 v = vec3((ndc.x + cg_ProjMatrix[2][0]) * eye / cg_ProjMatrix[0][0], (ndc.y + cg_ProjMatrix[2][1]) * eye / cg_ProjMatrix[1][1], -eye);
    vec4 previous = cg_PreviousClip(transpose(mat3(cg_ViewMatrix)) * (v - cg_ViewMatrix[3].xyz));
    return previous.xy / previous.w * 0.5 + 0.5;
}

// This frame's filtered occlusion at uv, bilinear, clamped to its edge.
float filtered_bilinear(vec2 uv) {
    vec2 p = uv * _AoSize.xy - 0.5, f = fract(p);
    ivec2 i = ivec2(floor(p)), last = ivec2(_AoSize.xy) - 1;
    float a = texelFetch(_Filtered, clamp(i, ivec2(0), last), 0).r;
    float b = texelFetch(_Filtered, clamp(i + ivec2(1, 0), ivec2(0), last), 0).r;
    float c = texelFetch(_Filtered, clamp(i + ivec2(0, 1), ivec2(0), last), 0).r;
    float e = texelFetch(_Filtered, clamp(i + ivec2(1, 1), ivec2(0), last), 0).r;
    return mix(mix(a, b, f.x), mix(c, e, f.x), f.y);
}

// The range of this frame's occlusion about the pixel: it and four taps a texel and a half out on the diagonals. What a
// still surface uncovered this frame may take of last frame's: the occlusion of what moved off it falls outside.
vec2 neighbourhood(vec2 uv, float now) {
    vec2 d = _AoSize.zw * 1.5;
    float a = filtered_bilinear(uv + vec2(-d.x, -d.y)), b = filtered_bilinear(uv + vec2(-d.x, d.y));
    float c = filtered_bilinear(uv + vec2(d.x, -d.y)), e = filtered_bilinear(uv + vec2(d.x, d.y));
    return vec2(min(now, min(min(a, b), min(c, e))), max(now, max(max(a, b), max(c, e))));
}

// Last frame's occlusion at a texel: 1 off the screen.
float history_at(ivec2 p) {
    return any(lessThan(p, ivec2(0))) || any(greaterThan(p, ivec2(_AoSize.xy) - 1)) ? 1.0 : texelFetch(_History, p, 0).r;
}

// Last frame's at uv: its four texels each held within [lo, hi], then blended bilinearly.
float history_clamped(vec2 uv, float lo, float hi) {
    vec2 p = uv * _AoSize.xy - 0.5, f = fract(p);
    ivec2 i = ivec2(floor(p));
    float h00 = clamp(history_at(i), lo, hi), h10 = clamp(history_at(i + ivec2(1, 0)), lo, hi);
    float h01 = clamp(history_at(i + ivec2(0, 1)), lo, hi), h11 = clamp(history_at(i + ivec2(1, 1)), lo, hi);
    return mix(mix(h00, h10, f.x), mix(h01, h11, f.x), f.y);
}

// This frame's resolved occlusion at texel px (uv its centre), from its filtered value now over a surface of window
// depth window: last frame's, reprojected and held within 0.1 of now while still (none while moving), and within the
// neighbourhood's range, then blended toward now by blend. Without a valid history, last frame's is taken as open.
float ao_temporal(ivec2 px, vec2 uv, float now, float window, bool valid, float blend) {
    vec2 prevUv = previous_uv(uv, window);
    vec2 motion = uv - prevUv;
    float speed = clamp(length(motion) * 100.0, 0.0, 1.0);
    float range = mix(0.1, 0.0, speed);
    vec2 around = neighbourhood(uv, now);
    float lo = max(clamp(now - range, 0.0, 1.0), around.x), hi = min(clamp(now + range, 0.0, 1.0), around.y);

    float history;
    if (!valid) {
        history = hi;
    } else {
        // Whether the surface found where this one was moves as this one does: where it does not, this pixel was
        // uncovered this frame, and last frame's value at its own place stands in
        vec2 there = clamp(prevUv, vec2(0.0), vec2(1.0));
        vec2 thereMotion = there - previous_uv(there, ao_window_depth(there));
        vec2 d = motion - thereMotion;
        float same = 1.0 - clamp(abs(d.x + d.y) * 100.0, 0.0, 1.0);
        history = mix(clamp(history_at(px), lo, hi), history_clamped(prevUv, lo, hi), same);
    }
    return mix(history, now, blend);
}
