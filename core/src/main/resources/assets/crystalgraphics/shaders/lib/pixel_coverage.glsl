// Pixel coverage: what is thinner than a render pixel drawn at least that wide, its colour or opacity scaled by the
// share of it the thing truly fills. Under temporal anti-aliasing's jitter a sub-pixel sprite or wire is under a sample
// some frames and between them others, and flickers; widened, it covers the same pixels every frame and keeps its light.
// Niagara's sprite pixel coverage (the particle factory's sprites take it from their renderer's pixel_coverage), and
// the same widening for a mesh that opts in.
//
//     // a thin mesh's material: a unit cube's half extents, two pixels at least, blended by what it covers
//     #include "crystalgraphics:shaders/lib/pixel_coverage.glsl"
//     o.coverage = cg_min_pixel_width(v, CG_OBJECT_TO_WORLD, vec3(0.5), 2.0);   // vertex()
//     s.opacity = i.coverage;                                                    // surface()
#pragma once

// The width of one render pixel, in blocks, at camera-relative point p: one pixel of the view's resolution mapped to
// p's depth (Niagara's ViewSizeAndInvSize.z * tan(fov / 2) * depth * 2). Orthographic views have one width everywhere.
float cg_render_pixel_size(vec3 p) {
    float depth = (cg_ProjMatrix * (cg_ViewMatrix * vec4(p, 1.0))).w;
    return 2.0 * depth / (cg_ProjMatrix[0][0] * cg_Resolution.x);
}

// Niagara's sprite pixel coverage: a sprite of full size `size` centred at camera-relative `centre`, drawn at least a
// pixel in half-size. Returns what its size is multiplied by; `coverage` is what its colour is scaled toward, the
// share squared (NiagaraSpriteVertexFactory.ush). As Niagara's, the half-size is what is held to a pixel, so a sprite
// under a pixel draws two pixels across.
float cg_sprite_pixel_coverage(vec3 centre, vec2 size, out float coverage) {
    float halfSize = max(size.x, size.y) * 0.5;
    float pixel = max(cg_render_pixel_size(centre), halfSize);
    coverage = clamp(halfSize * 2.0 / pixel, 0.0, 1.0);
    coverage *= coverage;
    return halfSize > 0.0 ? pixel / halfSize : 1.0;
}

// A mesh kept at least `pixels` render pixels wide on each axis of its object space: an axis whose world width (twice
// halfExtent times its scale) is less is stretched about the object's origin until it is that wide, and v.world placed
// again. Returns the share of the widened width it truly fills, the least over the stretched axes (1 where none is),
// for the material's opacity. objectToWorld is an argument so the velocity pass's last-frame copy widens by last
// frame's model.
//   - The thin axes are centred on the object's origin (a pole's x and z), and halfExtent is the mesh's own: a unit
//     cube's is 0.5.
//   - Drawn translucent, the widened mesh blends by its coverage; ResponsiveAA Off keeps its history as it holds still.
float cg_min_pixel_width(inout CgVertex v, mat4 objectToWorld, vec3 halfExtent, float pixels) {
    float least = pixels * cg_render_pixel_size(v.world);
    float coverage = 1.0;
    mat3 axes = mat3(objectToWorld);
    for (int i = 0; i < 3; i++) {   // LOOP-BOUND: three axes
        float width = 2.0 * halfExtent[i] * length(axes[i]);
        if (width <= 0.0 || width >= least) continue;
        v.position[i] *= least / width;
        coverage = min(coverage, width / least);
    }
    v.world = (objectToWorld * vec4(v.position, 1.0)).xyz;
    return coverage;
}
