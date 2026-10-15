// The world's froxel grid (CgWorldMedia) as a kernel adding into it sees it: where a froxel is, how deep, and what the
// kernels before it added. Every value is an argument; the grid's spacing is cg_env.glsl's CG_MEDIA_*.
//
//     vec3 p = media_point(CG_TEXEL, MEDIA_OUT_SIZE(), _MediaRay00, _MediaRay10, _MediaRay01, _MediaRay11);
//     MEDIA_OUT_WRITE(vec4(media_before(_MediaIn, _MediaFirst, CG_TEXEL) + sigma(p), 0.0, 0.0, 0.0));
#pragma once

// The eye depth slice boundary s stands at, 0 to CG_MEDIA_SLICES.
float media_eye(float s) {
    return CG_MEDIA_NEAR * pow(CG_MEDIA_FAR / CG_MEDIA_NEAR, s / CG_MEDIA_SLICES);
}

// The camera-relative middle of froxel t of a grid `size` froxels, from the view's corner rays (a unit of eye depth each).
vec3 media_point(ivec3 t, ivec3 size, vec4 r00, vec4 r10, vec4 r01, vec4 r11) {
    vec2 uv = (vec2(t.xy) + 0.5) / vec2(size.xy);
    vec3 ray = mix(mix(r00.xyz, r10.xyz, uv.x), mix(r01.xyz, r11.xyz, uv.x), uv.y);
    return ray * 0.5 * (media_eye(float(t.z)) + media_eye(float(t.z + 1)));
}

// The extinction the kernels before this one added at froxel t: nothing for the first.
float media_before(sampler3D before, int first, ivec3 t) {
    return first != 0 ? 0.0 : texelFetch(before, t, 0).x;
}
