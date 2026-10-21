// Unreal's ParticleSubUV (UMaterialExpressionParticleSubUV): a flipbook sheet sampled at the particle's two frames and
// blended by its sub-image lerp; the first frame alone where blend is false.
#pragma once

vec4 cg_particle_sub_uv(sampler2D tex, vec2 coords0, vec2 coords1, float subUVLerp, bool blend) {
    vec4 a = texture(tex, coords0);
    return blend ? mix(a, texture(tex, coords1), subUVLerp) : a;
}

// The particle's own frames, where the factory declares the particle.
#ifdef CG_MATERIAL_PARTICLE
vec4 cg_particle_sub_uv(sampler2D tex, CgMaterialParticle p, bool blend) {
    return cg_particle_sub_uv(tex, p.subUVCoords0, p.subUVCoords1, p.subUVLerp, blend);
}
#endif
