// Unreal's Default Lit shading model (ShadingModels.ush's DefaultLitBxDF) for a program carrying its own lighting:
// Lambert diffuse and the GGX specular lobe (BRDF.ush's D_GGX, Vis_SmithJointApprox, F_Schlick) under the sun, the
// four point lights nearest and the sky, whose specular is EnvBRDFApprox. Drawn as a solid in the transparent firing,
// where the deferred apply has run and the sun's shadow map is bound:
//
//     Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "ColorSpace" = "Linear" }
//     Queue = "Transparent"
//     Pass { RenderState { Blend OFF DepthWrite ON } ...
//         vec3 lit;
//         CG_DEFAULT_LIT(lit, base, metallic, 0.5, roughness, occlusion, normalWS, i.position);
//         fragColor = vec4(CG_DEFAULT_LIT_OUTPUT(lit), 1.0);
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

// Linear light to the encoding of the target it is written into, past white unchanged: cg_SceneDecode's inverse.
vec3 cg_default_lit_encode(vec3 c) {
    vec3 s = clamp(c, 0.0, 1.0);
    vec3 enc = mix(s * 12.92, 1.055 * pow(s, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), s));
    return enc + max(c - 1.0, 0.0);
}

// In a fragment stage, into radiance: base colour, metallic, specular (Unreal's, 0.5 its default), roughness and
// occlusion (the sky's alone), world normal n and camera-relative position p. Macros, so they expand where they are
// called: the lights they read are declared after a material's includes. Roughness is kept above 0.02, where D_GGX's
// a2 would reach 0.
#define CG_DEFAULT_LIT(radiance, baseColor, metallic, specular, roughness, occlusion, n, p) { \
    vec3 dlBase = (baseColor); \
    float dlMetal = (metallic), dlRough = max((roughness), 0.02); \
    vec3 dlDiffuse = dlBase * (1.0 - dlMetal), dlF0 = mix(vec3(0.08 * (specular)), dlBase, dlMetal); \
    vec3 dlN = normalize(n), dlP = (p), dlV = normalize(-dlP); \
    float dlNoV = clamp(abs(dot(dlN, dlV)) + 1.0e-5, 0.0, 1.0); \
    vec3 dlSky = cg_SceneDecode(CG_SKY_LIGHT_COLOR); \
    radiance = (dlDiffuse + cg_default_lit_env(dlF0, dlRough, dlNoV)) * dlSky * (occlusion); \
    radiance += cg_default_lit_light(dlDiffuse, dlF0, dlRough, dlN, dlV, CG_DIRECTIONAL_LIGHT_DIRECTION, \
            cg_SceneDecode(CG_DIRECTIONAL_LIGHT_COLOR)) * CG_DIRECTIONAL_SHADOW(dlP); \
    int dlIdx[4]; \
    int dlCount = cg_NearestLights(dlP, dlIdx); \
    for (int dlK = 0; dlK < dlCount; dlK++) { \
        int dlI = dlIdx[dlK]; \
        vec3 dlAt = cg_LightPosition(dlI); \
        float dlFall = cg_light_falloff(dlAt, cg_LightRadius(dlI), cg_LightFalloff(dlI), WORLD_LIGHTS(dlI).spotCos, \
                WORLD_LIGHTS(dlI).segmentCone, dlP); \
        radiance += cg_default_lit_light(dlDiffuse, dlF0, dlRough, dlN, dlV, normalize(dlAt - dlP), cg_LightColor(dlI) * dlFall); \
    } }

// What a "ColorSpace" = "Linear" fragment writes: the radiance as it is into the HDR scene, encoded into any other.
#define CG_DEFAULT_LIT_OUTPUT(radiance) (CG_LINEAR_SCENE ? (radiance) : cg_default_lit_encode(radiance))
