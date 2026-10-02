// Analytic volumes around a path, evaluated per pixel against the view ray: a hard-edged core by its thickness along the
// ray, and a Gaussian glow integrated along it. Drawn on a tube hull's far wall (Cull FRONT), each pixel once, inside
// the volume as well as out. The line form of the showcase's sphere glows.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"

// Where the view ray from eye along ray passes the path near axis point a (direction t, arc length arc), the path
// treated as a capsule of the given length: past either end the distance is to that end.
// x how far along the ray, y how near it comes, z the sine of the angle it crosses at (1 at an end), w the arc length
// it passes nearest.
vec4 fx_capsule(vec3 eye, vec3 ray, vec3 a, vec3 t, float arc, float pathLength) {
    vec4 q = fx_ray_axis(eye, ray, a, t);
    float at = arc + q.z;
    if (at < 0.0 || at > pathLength) {
        float clamped = clamp(at, 0.0, pathLength);
        vec3 end = a + t * (clamped - arc);
        float along = dot(end - eye, ray);
        return vec4(along, length(eye + ray * along - end), 1.0, clamped);
    }
    return vec4(q.x, q.y, q.w, at);
}

// How far the ray travels inside a cylinder of radius r that it passes at distance d, crossing at sine s, over the
// cylinder's diameter: 0 at its edge, 1 straight through its middle, more along it.
float fx_core_thickness(float d, float r, float s) {
    return sqrt(max(1.0 - d * d / (r * r), 0.0)) / max(s, 0.25);
}

// A Gaussian line glow of radius sigma, integrated along the ray and counting only the light between the eye and the
// scene at distance scene. 1 at the axis seen side-on.
float fx_line_glow(float along, float d, float s, float sigma, float scene) {
    float sine = max(s, 0.2);
    float spread = sigma / sine;
    float seen = 0.5 * (fx_erf((scene - along) / spread) + fx_erf(along / spread));
    return exp(-d * d / (sigma * sigma)) * seen / sine;
}

// A Gaussian of light of radius sigma around centre, integrated along the ray and counting only the light between the
// eye and the scene at distance scene. 1 through its middle.
float fx_point_glow(vec3 eye, vec3 ray, vec3 centre, float sigma, float scene) {
    float along = dot(centre - eye, ray);
    float d = length(eye + ray * along - centre);
    float seen = 0.5 * (fx_erf((scene - along) / sigma) + fx_erf(along / sigma));
    return exp(-d * d / (sigma * sigma)) * seen;
}
