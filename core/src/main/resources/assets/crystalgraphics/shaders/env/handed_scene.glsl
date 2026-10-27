#pragma once
// The scene textures Unreal's post materials sample, handed to one naming them (SceneTextures): CustomStencil, our opaque
// surfaces' world normals, their custom data and shading model. Included by the compiler wherever a program's source
// names CG_CUSTOM_STENCIL(, CG_SCENE_WORLD_NORMAL(, CG_CUSTOM_DATA( or CG_SHADING_MODEL_ID(; CgPostMaterial binds
// _cg_CustomStencil, _cg_WorldNormal and _cg_CustomData from the firing's keys. Where a firing published none, each reads
// its fallback: stencil 0, no normal, no custom data, model 0.
//
//     if (CG_CUSTOM_STENCIL(uv) != 1) discard;               // only the character stencilled 1
//     vec3 n = CG_SCENE_WORLD_NORMAL(uv);                    // world space; 0 where no draw of ours wrote one
//     vec4 data = CG_CUSTOM_DATA(uv);                        // rgb and a, as CG_CUSTOM_DATA_OUT wrote them
//     uvec2 model = CG_SHADING_MODEL_ID(uv);                 // the shading model and the type CG_SHADING_MODEL_OUT wrote

// The texel of s under uv, a screen position: every one is the screen's size, so nothing is filtered.
ivec2 cg_handed_texel(sampler2D s, vec2 uv) {
    ivec2 size = textureSize(s, 0);
    return clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
}

#define CG_CUSTOM_STENCIL(uv) int(round(texelFetch(_cg_CustomStencil, cg_handed_texel(_cg_CustomStencil, (uv)), 0).r * 255.0))

vec3 cg_handed_world_normal(vec2 uv) {
    vec4 n = texelFetch(_cg_WorldNormal, cg_handed_texel(_cg_WorldNormal, uv), 0);
    return n.a > 0.0 ? normalize(n.xyz * 2.0 - 1.0) : vec3(0.0);
}
#define CG_SCENE_WORLD_NORMAL(uv) cg_handed_world_normal(uv)

// What CG_CUSTOM_DATA_TARGET packed: x the ID byte + 256 * (model + 32 * type), y the colour's three bytes.
vec2 cg_handed_packed(vec2 uv) {
    return texelFetch(_cg_CustomData, cg_handed_texel(_cg_CustomData, uv), 0).xy;
}

vec4 cg_handed_custom_data(vec2 uv) {
    vec2 p = cg_handed_packed(uv);
    float rgb = p.y;
    float r = floor(rgb / 65536.0), g = floor((rgb - r * 65536.0) / 256.0), b = rgb - r * 65536.0 - g * 256.0;
    return vec4(r, g, b, mod(p.x, 256.0)) / 255.0;
}
#define CG_CUSTOM_DATA(uv) cg_handed_custom_data(uv)

uvec2 cg_handed_shading_model(vec2 uv) {
    uint above = uint(cg_handed_packed(uv).x) >> 8u;
    return uvec2(above & 31u, above >> 5u);
}
#define CG_SHADING_MODEL_ID(uv) cg_handed_shading_model(uv)
