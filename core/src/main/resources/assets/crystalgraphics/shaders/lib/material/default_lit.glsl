// Unreal's Default Lit and Subsurface shading models (ShadingModels.ush's DefaultLitBxDF and SubsurfaceBxDF) for a
// forward surface: Lambert diffuse and the GGX specular lobe (BRDF.ush's D_GGX, Vis_SmithJointApprox, F_Schlick) under
// the sun, the four point lights nearest and the sky, whose specular is EnvBRDFApprox. The template calls it after
// surface() for ShadingModel DefaultLit and Subsurface; a material may call it too.
//
//     #include "crystalgraphics:shaders/lib/material/default_lit.glsl"
//     vec3 radiance = cg_default_lit(s, i.world, false);   // s's base colour, metallic, specular, roughness, AO, normal
//
// A light's colour here is what a white surface facing it shows, as the lightmap's and the world lights' are: Lambert
// is the albedo times it, and the specular lobe times pi beside it, as Unreal's lights are irradiance and its Lambert
// is albedo over pi. Colours are linear; the sun and the sky are decoded from the lightmap.
#pragma once

// Unreal's D_GGX (BRDF.ush:377-381).
float cg_default_lit_d(float a2, float noh) {
    float d = (noh * a2 - noh) * noh + 1.0;
    return a2 / (3.14159265 * d * d);
}

// Unreal's Vis_SmithJointApprox (BRDF.ush:439-445).
float cg_default_lit_vis(float a2, float nov, float nol) {
    float a = sqrt(a2);
    float v = nol * (nov * (1.0 - a) + a), l = nov * (nol * (1.0 - a) + a);
    return 0.5 / (v + l);
}

// Unreal's F_Schlick (BRDF.ush:469-476): below 2% reflectance is shadowing, so F90 fades with it.
vec3 cg_default_lit_f(vec3 f0, float voh) {
    float fc = pow(1.0 - voh, 5.0);
    return clamp(50.0 * f0.g, 0.0, 1.0) * fc + (1.0 - fc) * f0;
}

// Unreal's EnvBRDFApprox (BRDF.ush:576-597), Lazarov's fit: the specular a sky gives back.
vec3 cg_default_lit_env(vec3 f0, float roughness, float nov) {
    vec4 r = roughness * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
    float a004 = min(r.x * r.x, exp2(-9.28 * nov)) * r.x + r.y;
    vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
    return f0 * ab.x + clamp(50.0 * f0.g, 0.0, 1.0) * ab.y;
}

// One light's radiance off the surface toward v, from direction l, of colour color.
vec3 cg_default_lit_light(vec3 diffuse, vec3 f0, float roughness, vec3 n, vec3 v, vec3 l, vec3 color) {
    float nol = clamp(dot(n, l), 0.0, 1.0);
    if (nol <= 0.0) return vec3(0.0);
    vec3 h = normalize(v + l);
    float nov = clamp(abs(dot(n, v)) + 1.0e-5, 0.0, 1.0), noh = clamp(dot(n, h), 0.0, 1.0), voh = clamp(dot(v, h), 0.0, 1.0);
    float a2 = pow(roughness, 4.0);
    vec3 specular = cg_default_lit_d(a2, noh) * cg_default_lit_vis(a2, nov, nol) * cg_default_lit_f(f0, voh);
    return (diffuse + 3.14159265 * specular) * color * nol;
}

// Unreal's SubsurfaceBxDF transmission (ShadingModels.ush:793-814): light through the surface, scattered in where the
// eye looks into the light and wrapped round where it does not; opacity 0 ignores the normal, 1 follows it. Unreal's
// is not divided by pi, so ours, its light pi times as bright, is.
vec3 cg_subsurface_transmission(vec3 subsurfaceColor, float opacity, float occlusion, vec3 n, vec3 v, vec3 l, vec3 color) {
    vec3 h = normalize(v + l);
    float inScatter = pow(clamp(dot(l, -v), 0.0, 1.0), 12.0) * mix(3.0, 0.1, opacity);
    float normalContribution = clamp(dot(n, h) * opacity + 1.0 - opacity, 0.0, 1.0);
    float backScatter = occlusion * normalContribution / (2.0 * 3.14159265);
    return color / 3.14159265 * mix(backScatter, 1.0, inScatter) * subsurfaceColor;
}

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// The radiance s gives off toward the eye at camera-relative position p: its base colour, metallic, specular, roughness,
// ambient occlusion (of the sky's light alone) and world normal; with subsurface, its subsurface colour through its
// opacity too. Roughness is kept above 0.02, where D_GGX's a2 would reach 0.
vec3 cg_default_lit(CgSurface s, vec3 p, bool subsurface) {
    float metal = clamp(s.metallic, 0.0, 1.0), rough = max(clamp(s.roughness, 0.0, 1.0), 0.02);
    float occlusion = clamp(s.ambientOcclusion, 0.0, 1.0), opacity = clamp(s.opacity, 0.0, 1.0);
    vec3 diffuse = s.baseColor * (1.0 - metal), f0 = mix(vec3(0.08 * clamp(s.specular, 0.0, 1.0)), s.baseColor, metal);
    vec3 n = normalize(s.normal), v = normalize(-p);
    float nov = clamp(abs(dot(n, v)) + 1.0e-5, 0.0, 1.0);
    vec3 radiance = (diffuse + cg_default_lit_env(f0, rough, nov)) * cg_SceneDecode(CG_SKY_LIGHT_COLOR) * occlusion;

    vec3 sun = cg_SceneDecode(CG_DIRECTIONAL_LIGHT_COLOR) * CG_DIRECTIONAL_SHADOW(p);
    radiance += cg_default_lit_light(diffuse, f0, rough, n, v, CG_DIRECTIONAL_LIGHT_DIRECTION, sun);
    if (subsurface) {
        radiance += cg_subsurface_transmission(s.subsurfaceColor, opacity, occlusion, n, v, CG_DIRECTIONAL_LIGHT_DIRECTION, sun);
    }

    int nearest[4];
    int count = cg_NearestLights(p, nearest);
    for (int k = 0; k < min(count, 4); k++) {
        int i = nearest[k];
        vec3 at = cg_LightPosition(i), l = normalize(at - p);
        vec3 color = cg_LightColor(i) * cg_light_falloff(at, cg_LightRadius(i), cg_LightFalloff(i), WORLD_LIGHTS(i).spotCos,
                WORLD_LIGHTS(i).segmentCone, p);
        radiance += cg_default_lit_light(diffuse, f0, rough, n, v, l, color);
        if (subsurface) radiance += cg_subsurface_transmission(s.subsurfaceColor, opacity, occlusion, n, v, l, color);
    }
    return radiance;
}
#endif
