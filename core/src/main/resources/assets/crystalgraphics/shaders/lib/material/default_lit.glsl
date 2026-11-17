// Unreal's Default Lit and Subsurface shading models (ShadingModels.ush's DefaultLitBxDF and SubsurfaceBxDF) for a
// forward surface: Lambert diffuse and the GGX specular lobe (BRDF.ush's D_GGX, Vis_SmithJointApprox, F_Schlick) under
// the sun, every light its grid cell lists (as ForwardLightingCommon.ush walks the cell) and the sky, whose specular is
// EnvBRDFApprox. The template calls it after
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

// Jimenez et al. 2016's multi-bounce: occlusion ao over a surface of albedo, lighter as light bounces off its walls
// (Unreal's AOMultiBounce).
vec3 cg_ao_multi_bounce(vec3 albedo, float ao) {
    vec3 a = 2.0404 * albedo - 0.3324, b = -4.7951 * albedo + 0.6417, c = 2.7552 * albedo + 0.6903;
    return max(vec3(ao), ((ao * a + b) * ao + c) * ao);
}

// Lagarde 2014's specular occlusion from ambient occlusion (Unreal's GetSpecularOcclusion).
float cg_specular_occlusion(float nov, float roughSq, float ao) {
    return clamp(pow(nov + ao, roughSq) - 1.0 + ao, 0.0, 1.0);
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
    // The sky under Unreal's indirect occlusion: the screen's, and the material's through multi-bounce on the diffuse and
    // as specular occlusion on the reflection
    float specularOcclusion = cg_specular_occlusion(nov, rough * rough, occlusion);
    vec3 sky = diffuse * cg_ao_multi_bounce(s.baseColor, occlusion)
            + cg_default_lit_env(f0, rough, nov) * cg_ao_multi_bounce(f0, specularOcclusion);
    vec3 radiance = sky * cg_SceneDecode(CG_SKY_LIGHT_COLOR) * cg_ScreenAmbientOcclusion();

    vec3 sun = cg_SceneDecode(CG_DIRECTIONAL_LIGHT_COLOR) * CG_DIRECTIONAL_SHADOW(p);
    radiance += cg_default_lit_light(diffuse, f0, rough, n, v, CG_DIRECTIONAL_LIGHT_DIRECTION, sun);
    if (subsurface) {
        radiance += cg_subsurface_transmission(s.subsurfaceColor, opacity, occlusion, n, v, CG_DIRECTIONAL_LIGHT_DIRECTION, sun);
    }

    // Every light of the cell, those that leave transparent surfaces unlit aside; past the walk's cap its sample stands
    // for the rest
    ivec2 run = cg_LightRun(p);
    vec3 local = vec3(0.0);
    for (int k = 0, walked = min(run.y, CG_LIGHT_CANDIDATES); k < walked; k++) {
        int i = cg_LightOfRun(run, k);
        CgLight light = WORLD_LIGHTS(i);
        if (CG_LIGHT_OPAQUE_ONLY(light)) continue;
        vec3 at = cg_LightPosition(i);
        float falloff = cg_light_falloff(at, light.positionRadius.w, light.colorExponent.w, light.spotCos, light.segmentCone, p);
        if (falloff <= 0.0) continue;
        vec3 color = light.colorExponent.rgb * falloff, l = normalize(cg_LightNearest(i, p) - p);
        local += cg_default_lit_light(diffuse, f0, rough, n, v, l, color);
        if (subsurface) local += cg_subsurface_transmission(s.subsurfaceColor, opacity, occlusion, n, v, l, color);
    }
    return radiance + local * CG_LIGHT_STRIDE(run.y);
}
#endif
