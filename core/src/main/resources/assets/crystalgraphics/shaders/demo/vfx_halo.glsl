// What the showcase's glows share (vfx_glow.shader, vfx_supernova_corona.shader): each is drawn on a larger sphere's
// far wall as a picture of light spread through the volume around the glowing sphere, and the scene in front of a
// fragment, read from cg_DepthBuffer, hides the part of that light behind it. So a glow is never cut by what stands
// inside it, only dimmed by however much of it lies behind. Only these two include this file: reading the depth is
// what makes the world renderer take a snapshot. CgVfxShowcase.
#pragma once

#include "crystalgraphics:shaders/demo/vfx_common.glsl"

// Where a view ray passes a centre: x how far along the ray, y how near it comes.
vec2 vfx_pass_by(vec3 camera, vec3 ray, vec3 centre) {
    float along = dot(centre - camera, ray);
    return vec2(along, length(camera + ray * along - centre));
}

// The share of a glow seen along a ray: its light spread along the ray as a Gaussian of width {@code spread} about
// where the ray passes the centre ({@code along}), counted from the eye to the opaque scene at {@code scene}.
float vfx_seen(float along, float spread, float scene) {
    return 0.5 * (fx_erf((scene - along) / spread) + fx_erf(along / spread));
}

// How much of a glow's picture shows over the glowing sphere's own disc: the limb glows, the face does not, since a
// glow is thin in front of what makes it. 1 outside the disc, so nothing passing in front of it shows an edge.
float vfx_limb(float near, float source) {
    float k = smoothstep(source * 0.55, source, near);
    return k * k;
}

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// How far along a view ray the opaque scene is, from the depth snapshot. Fragment stage only; a macro for the frame
// block, as FX_CAMERA is.
#define VFX_SCENE_DISTANCE(ray) (CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) / max(dot(ray, -vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2])), 1.0e-4))
#endif
