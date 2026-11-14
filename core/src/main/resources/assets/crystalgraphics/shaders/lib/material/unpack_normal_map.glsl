// Unreal's normal-map decode, which a texture sample of the Normal sampler type goes through (UnpackNormalMap,
// Common.ush; HLSLMaterialTranslator.h:3555): xy = red and green * 2 - 1, z = sqrt(saturate(1 - dot(xy, xy))), the
// normal tangent-space, w 1.
//
//     #include "crystalgraphics:shaders/lib/material/unpack_normal_map.glsl"
//     vec3 n = cg_unpack_normal_map(texture(_Normal, uv)).xyz;
//
// Reads the red and green channels, as a map without DXT5 packing does.
#pragma once

vec4 cg_unpack_normal_map(vec4 textureSample) {
    vec2 xy = textureSample.rg * 2.0 - 1.0;
    return vec4(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)), 1.0);
}
