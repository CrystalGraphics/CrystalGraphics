// Kakarot's stage colouring, a post material before translucency (PE_CharaColor; each arena's instance, e.g.
// Area02_CharaColor). Ports the capture's pixel shader (kamehameha_cut_charge.rdc, draw 4415) step for step.
//
// Characters (the cel models, Unreal's debug colour (0.8, 0.8, 0.3): 10, 11, 13) take scene^Chara_Gamma x
// Chara_Brightness. The rest of the lit scene takes scene^BG_Gamma x BG_Brightness, its shadows lifted by
// Shadow_Brightness, blended toward BG-Depth_Color with depth over DepthMaskDistance, and its sky (a black base
// colour) graded by Sky_*. Unlit pixels keep the scene. Each *_Saturation mixes toward luma (0.3, 0.59, 0.11) by
// 2 x saturation - 1: 0.5 leaves the colour as it is.
//
// Our host draws no base colour (GBuffer C), so a pixel's shadow term is 0 and its sky test is "nothing drawn": the
// environment branch is the stage's only where its terrain is the game's. Kakarot's names with '-' are '_' here.
#domain post
#include "kk:shaders/lib/kakarot.glsl"

Material { Location SURFACES  ColorSpace Linear  SceneColorMargin 0.001 }

Properties {
    DepthMaskDistance ("Where the depth colour is full, cm", float) = 1000000
    DepthMaskContrast ("The depth ramp's power", float) = 1
    Chara_Gamma ("Characters: power", vec4) = (1, 1, 1, 1)
    Chara_Brightness ("Characters: gain", vec4) = (1, 1, 1, 1)
    Chara_Saturation ("Characters: saturation, 0.5 unchanged", vec4) = (0.5, 0.5, 0.5, 1)
    BG_Gamma ("Environment: power", vec4) = (1, 1, 1, 1)
    BG_Brightness ("Environment: gain", vec4) = (1, 1, 1, 1)
    BG_Saturation ("Environment: saturation", vec4) = (0.5, 0.5, 0.5, 1)
    Shadow_Gamma ("Shadows: power over the environment's colour", vec4) = (1, 1, 1, 1)
    Shadow_Brightness ("Shadows: gain", vec4) = (1, 1, 1, 1)
    Shadow_Saturation ("Shadows: saturation", vec4) = (0.5, 0.5, 0.5, 1)
    BG_Depth_Gamma ("Distance: power", vec4) = (1, 1, 1, 1)
    BG_Depth_Saturation ("Distance: saturation", vec4) = (0.5, 0.5, 0.5, 1)
    BG_Depth_Brightness ("Distance: gain", vec4) = (1, 1, 1, 1)
    BG_Depth_Color ("Distance: the colour it fades toward, by its alpha", vec4) = (0, 0, 0, 0)
    Sky_Gamma ("Sky: power", vec4) = (1, 1, 1, 1)
    Sky_Brightness ("Sky: gain", vec4) = (1, 1, 1, 1)
    Sky_Saturation ("Sky: saturation", vec4) = (0.5, 0.5, 0.5, 1)
}

const vec3 LUMA = vec3(0.3, 0.59, 0.11);
// Unreal's shading-model debug colours by id, indexed by Unreal's ids, Kakarot's own after its first twelve.
const vec3 MODEL_COLORS[18] = vec3[18](
        vec3(0.1, 0.1, 0.2), vec3(0.1, 1.0, 0.1), vec3(1.0, 0.1, 0.1), vec3(0.6, 0.4, 0.1), vec3(0.1, 0.4, 0.4),
        vec3(0.2, 0.6, 0.5), vec3(0.2, 0.2, 0.8), vec3(0.6, 0.1, 0.5), vec3(0.7, 1.0, 1.0), vec3(0.3, 1.0, 1.0),
        vec3(0.8, 0.8, 0.3), vec3(0.8, 0.8, 0.3), vec3(0.5, 0.2, 0.8), vec3(0.8, 0.8, 0.3), vec3(0.5, 0.2, 0.8),
        vec3(0.5, 0.2, 0.8), vec3(0.5, 0.2, 0.8), vec3(1.0, 1.0, 1.0));

// A power and a gain, then the mix toward luma: the shape of every branch.
vec3 cc_grade(vec3 c, vec3 power, vec3 gain, vec3 saturation) {
    vec3 g = exp2(log2(max(c, vec3(0.0))) * power) * gain;
    return mix(g, vec3(dot(g, LUMA)), saturation * 2.0 - 1.0);
}

void surface(in CgScreen i, inout CgPostSurface s) {
    vec3 scene = CG_SCENE_COLOR(i.uv).rgb;
    vec3 model = MODEL_COLORS[min(CG_SHADING_MODEL_ID(i.uv).x & 31u, 17u)];
    vec3 d = vec3(0.1, 0.1, 0.2) - model;
    float lit = ceil(min(1.0, -d.x - d.y + abs(d.z)));
    vec3 c = vec3(0.8, 0.8, 0.3) - model;
    float notChar = ceil(min(1.0, abs(c.x) + abs(c.y) + abs(c.z)));
    float env = min(lit, notChar);
    float eye = CG_SCENE_EYE_DEPTH(i.uv);
    float sky = min(env, eye >= cg_LinearEyeDepth(cg_DepthParams.x > 0.5 ? 0.0 : 1.0) * 0.999 ? 1.0 : 0.0);
    float shadow = 0.0;

    vec3 a = cc_grade(scene, BG_Gamma.rgb, BG_Brightness.rgb, BG_Saturation.rgb);
    vec3 b = cc_grade(a, Shadow_Gamma.rgb, Shadow_Brightness.rgb, Shadow_Saturation.rgb);
    vec3 e = mix(a, b, shadow);
    vec3 far = exp2(log2(max(e, vec3(0.0))) * BG_Depth_Gamma.rgb);
    far = mix(far, vec3(dot(far, LUMA)), BG_Depth_Saturation.rgb * 2.0 - 1.0);
    far = mix(far * BG_Depth_Brightness.rgb, BG_Depth_Color.rgb, BG_Depth_Color.a);
    float w = min(1.0, pow(max(eye / KK_CM / DepthMaskDistance, 0.0), DepthMaskContrast));
    e = mix(e, far, w);
    e = mix(e, cc_grade(scene, Sky_Gamma.rgb, Sky_Brightness.rgb, Sky_Saturation.rgb), sky);

    vec3 chara = cc_grade(scene, Chara_Gamma.rgb, Chara_Brightness.rgb, Chara_Saturation.rgb);
    vec3 result = max(mix(mix(chara, scene, notChar), e, max(env, w)), vec3(0.0));
    s.color = mix(scene, result, POST_WEIGHT);
}
