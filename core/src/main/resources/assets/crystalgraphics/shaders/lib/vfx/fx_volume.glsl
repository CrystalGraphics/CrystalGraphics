// Analytic volumes around a path, evaluated per pixel against the view ray: a hard-edged core round the rings of one
// CgVfxTube chunk, traced through each segment between them, so a bent chunk is right from any side. Drawn on the
// chunk's closed hull (CgVfxLayer.closed, Cull FRONT), which every ray through the core leaves by.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"

// How many segments past each end of its own a chunk traces, so two chunks agree on a pixel near where they meet.
#define FX_CORE_MARGIN 8

// Where the view ray passes nearest the path's segments from ring first to ring end, each ring's radius times scale.
// near: x how far along the ray, y how near as a share of the radius there, z the arc length there, w the rings'
// intensity there; segment, the segment it is on (ring segment to segment + 1). Answers that radius.
float fx_core_nearest(sampler2D path, int row, int first, int end, vec3 origin, vec3 eye, vec3 ray, float scale,
                      out vec4 near, out int segment) {
    near = vec4(0.0, 1.0e30, 0.0, 1.0);
    segment = first;
    float radius = 1.0;
    FxRing a = fx_ring(path, row, first);
    for (int k = first; k < end; k++) {
        FxRing b = fx_ring(path, row, k + 1);
        vec3 pa = origin + a.position, ab = origin + b.position - pa;
        float len = length(ab);
        vec3 t = len > 1.0e-5 ? ab / len : a.tangent;
        float axial = clamp(fx_ray_axis(eye, ray, pa, t).z, 0.0, len);
        vec3 p = pa + t * axial;
        float along = dot(p - eye, ray);
        float f = len > 1.0e-5 ? axial / len : 0.0;
        float r = max(mix(a.radius, b.radius, f) * scale, 1.0e-4);
        float share = length(eye + ray * along - p) / r;
        if (share < near.y) {
            near = vec4(along, share, mix(a.arc, b.arc, f), mix(a.intensity, b.intensity, f));
            segment = k;
            radius = r;
        }
        a = b;
    }
    return radius;
}

// span cut to where g0 + g1 * s >= 0, s along the ray; empty when y <= x.
vec2 fx_span_cut(vec2 span, float g0, float g1) {
    if (abs(g1) < 1.0e-6) return g0 >= 0.0 ? span : vec2(1.0, 0.0);
    float s = -g0 / g1;
    return g1 > 0.0 ? vec2(max(span.x, s), span.y) : vec2(span.x, min(span.y, s));
}

vec2 fx_sphere_span(vec3 eye, vec3 ray, vec3 c, float r) {
    vec3 oc = eye - c;
    float b = dot(oc, ray), h = b * b - dot(oc, oc) + r * r;
    if (h < 0.0) return vec2(1.0, 0.0);
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

// The ray's span inside the cone from pa (radius ra) to pb (radius rb), between the planes of its two rings (normals
// ta and tb). Each plane is its neighbour's too, so a path's segments share no part of space and leave none between
// them; and each radius is its neighbour's, so the surface steps nowhere at a ring.
vec2 fx_segment_span(vec3 eye, vec3 ray, vec3 pa, vec3 ta, vec3 pb, vec3 tb, float ra, float rb) {
    vec3 ab = pb - pa;
    float len = length(ab);
    vec3 t = len > 1.0e-5 ? ab / len : ta;
    vec3 o = eye - pa;
    float rt = dot(ray, t), ot = dot(o, t);
    // The radius where the ray is, r0 + r1 * s; squared distance from the axis, k2 * s^2 + 2 * k1 * s + k0.
    float g = len > 1.0e-5 ? (rb - ra) / len : 0.0;
    float r0 = ra + g * ot, r1 = g * rt;
    float k2 = 1.0 - rt * rt, k1 = dot(o, ray) - ot * rt, k0 = dot(o, o) - ot * ot;
    float qa = k2 - r1 * r1, qb = k1 - r0 * r1, qc = k0 - r0 * r0;
    vec2 span;
    if (qa < 1.0e-6) {
        // Down the axis, steeper than the cone's slope, where its far nappe would answer: a cylinder of the average
        // radius, which seen end-on steps nowhere visible.
        float r = 0.5 * (ra + rb);
        if (k2 < 1.0e-6) {
            if (k0 > r * r) return vec2(1.0, 0.0);
            span = vec2(-1.0e30, 1.0e30);
        } else {
            float h = k1 * k1 - k2 * (k0 - r * r);
            if (h < 0.0) return vec2(1.0, 0.0);
            h = sqrt(h);
            span = vec2((-k1 - h) / k2, (-k1 + h) / k2);
        }
    } else {
        float h = qb * qb - qa * qc;
        if (h < 0.0) return vec2(1.0, 0.0);
        h = sqrt(h);
        span = vec2((-qb - h) / qa, (-qb + h) / qa);
    }
    span = fx_span_cut(span, dot(o, ta), dot(ray, ta));
    return fx_span_cut(span, -dot(eye - pb, tb), -dot(ray, tb));
}

void fx_span_add(vec2 span, inout vec2 into) {
    span.x = max(span.x, 0.0);
    if (span.y > span.x) into = vec2(into.x + span.y - span.x, min(into.y, span.x));
}

// How far the ray travels inside the core round the path's segments from ring first to ring end (count rings in all),
// each ring's radius times scale, from the eye on: x that length, y where it first enters (1e30 if never). The path's
// own ends are rounded by half spheres.
vec2 fx_core_span(sampler2D path, int row, int first, int end, int count, vec3 origin, vec3 eye, vec3 ray,
                  float scale) {
    vec2 span = vec2(0.0, 1.0e30);
    FxRing a = fx_ring(path, row, first);
    for (int k = first; k < end; k++) {
        FxRing b = fx_ring(path, row, k + 1);
        vec3 pa = origin + a.position, pb = origin + b.position;
        fx_span_add(fx_segment_span(eye, ray, pa, a.tangent, pb, b.tangent, a.radius * scale, b.radius * scale), span);
        if (k == 0) {
            vec2 cap = fx_sphere_span(eye, ray, pa, a.radius * scale);
            fx_span_add(fx_span_cut(cap, -dot(eye - pa, a.tangent), -dot(ray, a.tangent)), span);
        }
        if (k + 1 == count - 1) {
            vec2 cap = fx_sphere_span(eye, ray, pb, b.radius * scale);
            fx_span_add(fx_span_cut(cap, dot(eye - pb, b.tangent), dot(ray, b.tangent)), span);
        }
        a = b;
    }
    return span;
}
