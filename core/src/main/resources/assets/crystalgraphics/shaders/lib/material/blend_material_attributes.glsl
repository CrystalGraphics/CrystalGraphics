// Unreal's BlendMaterialAttributes (UMaterialExpressionBlendMaterialAttributes, MaterialExpressions.cpp:5055-5095):
// two whole surfaces mixed by alpha, every output lerped; the shading model, which 4.21 does not blend, is a's. A
// material layer's blend is this, by a mask.
//
//     #include "crystalgraphics:shaders/lib/material/blend_material_attributes.glsl"
//     CgSurface rock = s, moss = s;
//     rock.baseColor = texture(_Rock, i.uv0).rgb;   rock.roughness = 0.8;
//     moss.baseColor = texture(_Moss, i.uv0).rgb;   moss.roughness = 0.6;
//     s = cg_blend_material_attributes(rock, moss, smoothstep(0.6, 0.8, i.normal.y));
#pragma once

CgSurface cg_blend_material_attributes(CgSurface a, CgSurface b, float alpha) {
    CgSurface s = a;
    s.baseColor = mix(a.baseColor, b.baseColor, alpha);
    s.metallic = mix(a.metallic, b.metallic, alpha);
    s.specular = mix(a.specular, b.specular, alpha);
    s.roughness = mix(a.roughness, b.roughness, alpha);
    s.ambientOcclusion = mix(a.ambientOcclusion, b.ambientOcclusion, alpha);
    s.subsurfaceColor = mix(a.subsurfaceColor, b.subsurfaceColor, alpha);
    s.opacity = mix(a.opacity, b.opacity, alpha);
    s.opacityMask = mix(a.opacityMask, b.opacityMask, alpha);
    s.emissive = mix(a.emissive, b.emissive, alpha);
    s.normal = mix(a.normal, b.normal, alpha);
    s.refraction = mix(a.refraction, b.refraction, alpha);
    s.refractionSplit = mix(a.refractionSplit, b.refractionSplit, alpha);
    s.refractionDepth = mix(a.refractionDepth, b.refractionDepth, alpha);
    s.pixelDepthOffset = mix(a.pixelDepthOffset, b.pixelDepthOffset, alpha);
    s.customData = mix(a.customData, b.customData, alpha);
    s.light = mix(a.light, b.light, alpha);
    s.volumeDepth = mix(a.volumeDepth, b.volumeDepth, alpha);
    s.depth = mix(a.depth, b.depth, alpha);
    return s;
}
