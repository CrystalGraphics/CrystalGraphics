// The scene's depth as a module kind after the solver reads it (CgVfxWorldInput.DEPTH): Niagara's and Unity's depth
// buffer collision. Every point is relative to the particle's instance origin, as FxParticle's positions are. The
// Step kernel includes this when its shape reads the depth, after the depth's properties.
//
//   void fx_splash(inout FxParticle p, FxStep s, vec4 m, FxDepth depth) {
//       vec3 n;
//       if (!fx_depth_behind(depth, p.position, m.x, n)) return;   // m.x: how thick a surface is
//       fx_hit(p, n);
//       p.position = p.previous;
//       p.velocity = reflect(p.velocity, n) * m.y;
//   }
//
// The depth is what the camera saw this frame before the world renderer drew: blind off screen and behind the first
// surface. Off screen, behind the camera, or with no depth (depth.live false) every reader answers "nothing there".
#pragma once

#define FX_DEPTH_NONE 1e30

// The camera, from the instance's origin.
struct FxDepth {
    vec3 eye;
    bool live;
};

// p's own eye depth, and its pixel on the depth; FX_DEPTH_NONE off screen.
float fx_depth_project(FxDepth d, vec3 p, out ivec2 pixel) {
    pixel = ivec2(0);
    if (!d.live) return FX_DEPTH_NONE;
    vec4 c = vec4(p - d.eye, 1.0);
    float w = dot(_DepthClipW, c);
    if (w <= 1e-4) return FX_DEPTH_NONE;
    vec2 uv = vec2(dot(_DepthClipX, c), dot(_DepthClipY, c)) / w * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return FX_DEPTH_NONE;
    pixel = ivec2(uv * _DepthSize.xy);
    return w;
}

// The scene's eye depth at pixel, held to the screen.
float fx_depth_at(ivec2 pixel) {
    return texelFetch(_DepthPyramid, clamp(pixel, ivec2(0), ivec2(_DepthSize.xy) - 1), 0).r;
}

// The scene's surface at pixel's centre.
vec3 fx_depth_surface(FxDepth d, ivec2 pixel) {
    float depth = fx_depth_at(pixel);
    vec2 ndc = (vec2(pixel) + 0.5) / _DepthSize.xy * 2.0 - 1.0;
    vec3 v = vec3(depth * (ndc.x + _DepthLens.z) / _DepthLens.x, depth * (ndc.y + _DepthLens.w) / _DepthLens.y, -depth);
    return _DepthViewX.xyz * v.x + _DepthViewY.xyz * v.y + _DepthViewZ.xyz * v.z + _DepthViewW.xyz + d.eye;
}

// The scene's normal at pixel, facing the camera: across to the neighbour on each axis whose depth is nearer its own,
// so an edge takes the surface it is on (Wicked Engine's and AMD's reconstruction).
vec3 fx_depth_normal(FxDepth d, ivec2 pixel) {
    float here = fx_depth_at(pixel);
    int sx = abs(fx_depth_at(pixel + ivec2(1, 0)) - here) <= abs(fx_depth_at(pixel - ivec2(1, 0)) - here) ? 1 : -1;
    int sy = abs(fx_depth_at(pixel + ivec2(0, 1)) - here) <= abs(fx_depth_at(pixel - ivec2(0, 1)) - here) ? 1 : -1;
    vec3 at = fx_depth_surface(d, pixel);
    vec3 dx = (fx_depth_surface(d, pixel + ivec2(sx, 0)) - at) * float(sx);
    vec3 dy = (fx_depth_surface(d, pixel + ivec2(0, sy)) - at) * float(sy);
    vec3 n = cross(dx, dy);
    float length2 = dot(n, n);
    if (length2 <= 1e-20) return normalize(d.eye - at);
    n *= inversesqrt(length2);
    return dot(n, d.eye - at) < 0.0 ? -n : n;
}

// Whether p is behind the scene's surface, by at most thickness blocks of eye depth, where the camera sees it: a
// collision. Its normal, facing the camera.
bool fx_depth_behind(FxDepth d, vec3 p, float thickness, out vec3 normal) {
    normal = vec3(0.0, 1.0, 0.0);
    ivec2 pixel;
    float own = fx_depth_project(d, p, pixel);
    if (own == FX_DEPTH_NONE) return false;
    float scene = fx_depth_at(pixel);
    if (own <= scene || own > scene + thickness) return false;
    normal = fx_depth_normal(d, pixel);
    return true;
}
