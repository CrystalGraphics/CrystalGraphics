// Kakarot's cel-toned character (CellShade_Tone, shading model 13), forward: the material's base pass and the cel
// resolve CC2's renderer runs after it in one program. Ports `Cpl001p1c01s1_body_Mt_inst`'s permutation, read from
// the capture (Goku's body, draw 4712; the resolve, draw 6287's model-13 branch), and its static switches from the
// permutations beside it (the obi 4749, the boots 4697, an item with neither 4600) and from the shader maps of
// instances a switch apart (ue421_shadermap.py): each one off reads a constant.
//
// The base pass makes the colours: a light side and a shadow side, each the diffuse texture shaded between
// AmbientDFctor and AmbientDFctor2 by a painted shadow and a hard rim, with ambient occlusion, a highlight and the
// inside lines. The resolve picks between them by the light, through the engine's tone ramp (TONE, the game's
// /Engine/EngineMaterials/Tone, its row TonePattern), and lights them by the stage's directional light.
//
// Not carried yet: contact shadows (the sun's shadow is the engine's apply; the game's characters take no screen-space AO: its
// resolve binds a 1x1 white where Unreal binds the AO), the sky's SH irradiance, blood and damage (they read uv2; the custom data's colour is the shadow side without them), DBuffer
// decals, the outline ID's in-line and fresnel switches, and the petrifaction, mob-colour and rim-cell switches. Without SWITCH_USEDIFFUSETEXTURE (an NPC's eye, its own shader map) the light
// side is the flat Diffuse and the shadow side the flat AmbientShadow, read with the other switches off.
//
// CELL_DRAGON is the Dragon Fist's dragon (M_skill_cpl001_ryk_dragon_01_00), the same graph but for two things, read
// from its instances' shader maps against a CellShade_Tone with the same switches: its emissive is a glow on uv3 in
// place of the fresnel (NoiseA, panned by Speed_2, bends where NoiseB, panned by Speed, is read; the doubled mask
// blends EmissiveColor_1 to _2, times the move's DR_EmissiveMulti), and its cast-shadow weight is CastShadowPower.
#domain surface
#include "crystalgraphics:shaders/lib/material/panner.glsl"
#include "kk:shaders/lib/kakarot.glsl"
#include "kk:shaders/lib/cell.glsl"

Material { BlendMode Opaque  ShadingModel Unlit  ColorSpace Linear  VertexFactory Mesh, MeshParticles }

Keywords {
    CELL_TONE ("Cell tone")
    CELL_SHADE ("Cell shade")
    CELL_TONE_GROUND ("Cell tone ground")
    SWITCH_USEDIFFUSETEXTURE ("Switch usediffusetexture")
    CELL_DRAGON ("Cell dragon")
    SWITCH_USEAOMAP ("Switch useaomap")
    SWITCH_USEINSIDELINETEX ("Switch useinsidelinetex")
    SWITCH_USELIGHTMAP ("Switch uselightmap")
    SWITCH_USESPECULARTEXTURE ("Switch usespeculartexture")
    SWITCH_USERIMLIGHT ("Switch userimlight")
    SWITCH_USERIMPOWERTEX ("Switch userimpowertex")
    SWITCH_USENORMALTEXTURE ("Switch usenormaltexture")
    SWITCH_SETTEXUV2 ("Switch settexuv2")
    SWITCH_USEUNIQUETEXTURE ("Switch useuniquetexture")
    SWITCH_USEFRESNELPOWERTEX ("Switch usefresnelpowertex")
    SWITCH_USELIGHTCAMVECTOR ("Switch uselightcamvector")
    SWITCH_USEOUTLINETEXTURE ("Switch useoutlinetexture")
}

Properties {
    diffuse_texture ("Its colour; alpha biases the painted shadow", sampler2D) = "white"
    AmbientOcclusion_Texture ("Ambient occlusion, in red", sampler2D) = "white"
    InsideLine_Texture ("The inside lines, on uv1", sampler2D) = "white"
    second_texture ("The second colour's texture: a texture sample the root compiled in, not a parameter", sampler2D) = "white"
    Specular ("The second colour, without its texture", color) = (1, 1, 1, 1)
    RimMaskTexture ("Masks the rim, in red", sampler2D) = "white"
    Normal_Texture ("A tangent-space normal map, xy", sampler2D) = "bump"
    NomalPower ("How far the normal map bends the normal", float) = 1
    Unique_texture ("A colour over the diffuse on uv1, by its alpha", sampler2D) = "black"
    UniqueAmbient ("That colour's shade", float) = 1
    FresnelMaskTexture ("Masks the fresnel, in red", sampler2D) = "white"
    RimLightRotate ("Turns the camera light about (0, 1, 1), in turns", float) = 0
    TONE ("The renderer's tone ramp: 64 rows, red hard, green soft", sampler2D) = "white"
    LightDirection_Vertical ("The material's own light axis, x", float) = 0
    LightDirection_Side ("The material's own light axis, z", float) = 1
    LightDirectionPower ("Turns the normal about that axis, in turns", float) = 0
    FresnelColor ("The fresnel's colour", color) = (1.5, 1.5, 1.5, 1)
    FresnelPower ("The fresnel's exponent", float) = 2.74817
    FresnelInfluence ("The fresnel's strength", float) = 0.5
    Diffuse_Power ("Scales the diffuse texture", float) = 1
    Diffuse ("The flat colour, without the diffuse texture", color) = (0.625, 0.478249, 0.054878, 1)
    AmbientShadow ("The flat shadow side, without the diffuse texture", color) = (0.275, 0.130313, 0.030111, 1)
    NoiseA ("Bends the glow's mask, xy (CELL_DRAGON)", sampler2D) = "black"
    NoiseB ("The glow's mask, red (CELL_DRAGON)", sampler2D) = "black"
    EmissiveColor_1 ("The glow where the mask is 0 (CELL_DRAGON)", color) = (0, 0, 0, 1)
    EmissiveColor_2 ("The glow where it is 1 (CELL_DRAGON)", color) = (1, 1, 1, 1)
    EFF_Scale_U ("The mask's tiling on uv3, u (CELL_DRAGON)", float) = 1
    EFF_Scale_V ("Its tiling, v (CELL_DRAGON)", float) = 1
    EFF_Scale_U_2 ("The bend's tiling on uv3, u (CELL_DRAGON)", float) = 1
    EFF_Scale_V_2 ("Its tiling, v (CELL_DRAGON)", float) = 1
    Speed ("The mask's scroll along v, a second (CELL_DRAGON)", float) = 0
    Speed_2 ("The bend's scroll along v, a second (CELL_DRAGON)", float) = 0
    TexDist ("How far the bend moves the mask (CELL_DRAGON)", float) = 0
    ShadowPower ("Moves the light side, four times this", float) = 0
    Shadow2Power ("Biases the painted shadow", float) = 0
    CastShadowPower ("The cast shadow's weight", float) = 0.6
    CastShadowParamPower ("The cast shadow's response", float) = 1
    RegionOffset ("Moves the light side", float) = 0.22
    RegionOffset_Middle ("Where the rim's middle band sits", float) = 0.8
    MiddleGradPower ("The middle band's sharpness", float) = 1
    RimVector ("Tilts the rim's view vector", color) = (0, 0, 0, 1)
    LightVector_Add ("Added to the light direction, Unreal axes", color) = (0, 0, 0, 1)
    RimLightPow_bright ("The rim's exponent, one side", float) = 3
    RimLightPow_bright_Middle ("The rim's exponent in the middle band", float) = 20
    RimLightPow_dark ("The rim's exponent, other side", float) = 3
    RimLightInfl_bright ("The rim's strength, one side", float) = 1
    RimLightInfl_dark ("The rim's strength, other side", float) = 1
    RimPower_Light ("The rim's push on the light side", float) = 0
    RimPower_Shadow ("The rim's push on the shadow side", float) = 1
    AmbientDFctor ("The colour's shade, unshadowed", color) = (0.355, 0.1207, 0.124604, 1)
    AmbientDFctor2 ("The colour's shade, shadowed", color) = (0.375, 0.180375, 0.238762, 1)
    HightLightColor ("The highlight: rgb added, a its weight", color) = (0.3, 0.3, 0.3, 1)
    AOMult ("Ambient occlusion's strength", float) = 0.6
    AOPower ("Ambient occlusion's exponent", float) = 1
    MipLevel0 ("The inside lines' first mip distance, cm", float) = 0
    MipLevel1 ("Their second, cm", float) = 320
    MipLevel2 ("Their third, cm", float) = 800
    MipLevel3 ("Their fourth, cm", float) = 900
    LineTex_Power ("Hardens the inside lines; 0 leaves them", float) = 0
    TonePattern ("The tone ramp's row, 0 to 63", float) = 41
    AffectedLight ("How much the stage light colours it, 0..1", float) = 0.5
    StageShadowTint ("The renderer's shadow-side tint, as the capture's", color) = (1.16708, 2.31032, 4, 1)
    outline_texture ("Where the outline draws, in red, on uv3 (SWITCH_USEOUTLINETEXTURE)", sampler2D) = "white"
    OutlineID ("The outline ID without the texture", float) = 0.5
    OutlineMask ("Above 0 lets the outline draw", float) = 1
    Opacity_Threshold ("Below 1 masks the outline out", float) = 1
    OutlineColorType ("The outline pass's colour slot, 0 to 3", float) = 0
}

struct v2f { vec2 uv0; vec2 uv1; vec2 uv3; vec4 color; vec3 normal; vec3 position; };


void vertex(inout CgVertex v, out v2f o) {
    o.uv0 = v.uv0;
    o.uv1 = v.uv1;
    o.uv3 = v.uv3;
    o.color = v.color;
    o.normal = v.normal;
    o.position = v.world;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 n = normalize(i.normal);
    vec3 view = normalize(CG_CAMERA_POSITION - i.position);

    // The normal turned about the material's own light axis, then bent halfway to that turned normal: from the
    // normal, or from the normal map's tangent-space normal, NomalPower of the way from (0, 0, 1).
    vec3 turned = kk_cell_turned(n, mat3(CG_OBJECT_TO_WORLD), LightDirection_Vertical, LightDirection_Side, LightDirectionPower);
#ifdef SWITCH_USENORMALTEXTURE
  #ifdef SWITCH_SETTEXUV2
    vec2 normalUv = i.uv1;
  #else
    vec2 normalUv = i.uv0;
  #endif
    vec2 nxy = texture(Normal_Texture, normalUv).xy * 2.0 - 1.0;
    vec3 mapped = vec3(nxy, sqrt(max(0.0, 1.0 - dot(nxy, nxy))));
    vec3 from = kk_tangent_frame(n, i.position, normalUv) * mix(vec3(0.0, 0.0, 1.0), mapped, NomalPower);
#else
    vec3 from = n;
#endif
    vec3 bent = normalize(from + turned);

    float fresnel = FresnelInfluence * min(1.0, pow(max(0.0, 1.0 - dot(bent, view)), FresnelPower));
#ifdef SWITCH_USEFRESNELPOWERTEX
    fresnel *= texture(FresnelMaskTexture, i.uv0).r;
#endif

    // The light map is the vertex colour's red and green; without it both read 0.5.
#ifdef SWITCH_USELIGHTMAP
    vec2 lightMap = i.color.rg;
#else
    vec2 lightMap = vec2(0.5);
#endif
#ifdef SWITCH_USEDIFFUSETEXTURE
    vec4 diffuse = texture(diffuse_texture, i.uv0);
    vec3 plain = Diffuse_Power * diffuse.rgb;
#else
    vec4 diffuse = vec4(Diffuse.rgb, 1.0);
    vec3 plain = Diffuse.rgb;
#endif
#ifdef SWITCH_USEUNIQUETEXTURE
    vec4 unique = texture(Unique_texture, i.uv1);
    vec3 base = mix(plain, unique.rgb, unique.a);
#else
    vec3 base = plain;
#endif
    float painted = min(0.99, max(0.0, 1.0 + floor(max(Shadow2Power + 0.0001 + lightMap.r, lightMap.g + 0.0001)) - diffuse.a));

    // The material's light runs from the light (Unreal's light vector, the deferred pass's negated).
#ifdef SWITCH_USELIGHTCAMVECTOR
    // From the camera: the view vector turned about (0, 1, 1) by RimLightRotate (RotateAboutAxis's offset, the
    // axis as the game gives it), in Unreal's axes.
    vec3 eye = kk_unreal_axes(view);
    vec3 axisU = vec3(0.0, 1.0, 1.0);
    vec3 onAxis = axisU * dot(axisU, eye), across = eye - onAxis;
    float turn = 6.2832 * RimLightRotate;
    vec3 offset = onAxis + across * cos(turn) + cross(axisU, across) * sin(turn) - eye;
    vec3 light = normalize(kk_unreal_axes(offset + LightVector_Add.rgb));
#else
    vec3 light = normalize(-CG_SUN_DIRECTION + kk_unreal_axes(LightVector_Add.rgb));
#endif
    float side = dot(bent, light) + 4.0 * ShadowPower + RegionOffset;
    float away = ceil(clamp(side, 0.0, 1.0));
    float powA = mix(RimLightPow_bright, RimLightPow_bright_Middle, clamp(MiddleGradPower * pow(max(0.0, side + RegionOffset_Middle), 2.0), 0.0, 1.0));
    float powB = mix(RimLightPow_dark, RimLightPow_bright_Middle, clamp(MiddleGradPower * pow(max(0.0, RegionOffset_Middle - side), 2.0), 0.0, 1.0));
    float facing = max(0.0, 0.9 * (1.0 - dot(bent, view + kk_unreal_axes(RimVector.rgb))));
#ifdef SWITCH_USERIMLIGHT
  #ifdef SWITCH_USERIMPOWERTEX
    float rimMask = texture(RimMaskTexture, i.uv0).r;
  #else
    float rimMask = 1.0;
  #endif
    float rim = min(1.0, floor(20.0 * pow(facing, mix(powA, powB, away)) * rimMask)) * mix(RimLightInfl_bright, RimLightInfl_dark, away);
#else
    float rim = 0.0;
#endif
    float shade = min(1.0, max(painted, rim));

#ifdef SWITCH_USEDIFFUSETEXTURE
    vec3 shaded = mix(AmbientDFctor.rgb, AmbientDFctor2.rgb, shade) * plain;
#else
    vec3 shaded = AmbientShadow.rgb;
#endif
#ifdef SWITCH_USEUNIQUETEXTURE
    shaded = mix(shaded, unique.rgb * UniqueAmbient, unique.a);
#endif
#ifdef SWITCH_USEAOMAP
    float ao = 1.0 - clamp(pow(max(0.0, 5.0 * AOMult * (1.0 - texture(AmbientOcclusion_Texture, i.uv0).r)), AOPower), 0.001, 0.999);
    vec3 soft = mix(0.5 * (base + shaded), vec3(1.0), ao);
#else
    vec3 soft = vec3(1.0);
#endif
    vec3 lifted = soft * base;
    vec3 highlight = vec3(1.0) - (vec3(1.0) - clamp(HightLightColor.a * lifted + HightLightColor.rgb, 0.0, 1.0) * rim)
            * (vec3(1.0) - HightLightColor.a * lifted);
    vec3 lightSide = mix(shaded * soft + highlight, base * soft, 1.0 - rim * (1.0 - away));
    vec3 shadowSide = shaded * soft;

    // The inside lines, their mip chosen by view depth in the game's centimetres (Unreal's SV_Position.w).
#ifdef SWITCH_USEINSIDELINETEX
    float cm = -(cg_ViewMatrix * vec4(i.position, 1.0)).z / KK_CM;
    float lod = clamp((cm - MipLevel1) / (MipLevel1 - MipLevel0), 0.0, 1.0)
              + clamp((cm - MipLevel2) / (MipLevel2 - MipLevel1), 0.0, 1.0)
              + clamp((cm - MipLevel3) / (MipLevel3 - MipLevel2), 0.0, 1.0);
    vec3 line = textureLod(InsideLine_Texture, i.uv1, lod).rgb;
    line = mix(line, clamp(floor(LineTex_Power + line), 0.0, 1.0), ceil(LineTex_Power));
#else
    vec3 line = vec3(1.0);
#endif
    // What the base pass writes, clamped as its UNORM targets store it.
    vec3 lit = clamp(lightSide * line, 0.0, 1.0);
    vec3 dark = clamp(shadowSide * line, 0.0, 1.0);
#ifdef SWITCH_USESPECULARTEXTURE
    vec3 second = clamp(texture(second_texture, i.uv0).rgb * line, 0.0, 1.0);
#else
    vec3 second = clamp(Specular.rgb * line, 0.0, 1.0);
#endif
#ifdef CELL_DRAGON
    float castWeight = clamp(CastShadowPower, 0.0, 1.0);
#else
    float castWeight = clamp(CastShadowParamPower * (0.7 * CastShadowPower - CastShadowPower) + CastShadowPower, 0.0, 1.0);
#endif
    float rimLit = rim * away;
    float shadowBias = clamp(ShadowPower + lightMap.r + rim * mix(RimPower_Light, RimPower_Shadow, away), 0.0, 1.0);

    KkCellSurface surface = KkCellSurface(lit, dark, second, painted, rimLit, shadowBias, castWeight);
    vec3 c = kk_cell_resolve(surface, bent, CG_SUN_DIRECTION, TONE, TonePattern, AffectedLight, CG_GLOBAL_directional_light_colour.rgb, StageShadowTint.rgb);
    // TEST ONLY: the screen's ambient occlusion on the sky's share of the world light, never the sun's, as Unreal's
    // IndirectOcclusion (the game binds none here)
    vec3 sunPart = CG_DIRECTIONAL_LIGHT_COLOR, skyPart = CG_SKY_LIGHT_COLOR;
    c *= (sunPart + skyPart * cg_ScreenAmbientOcclusion()) / max(sunPart + skyPart, vec3(1.0e-3));
#ifdef CELL_DRAGON
    vec2 maskUv = cg_panner(i.uv3 * vec2(EFF_Scale_U, EFF_Scale_V), CG_TIME, vec2(0.0, Speed), false);
    vec2 bendUv = cg_panner(i.uv3 * vec2(EFF_Scale_U_2, EFF_Scale_V_2), CG_TIME, vec2(0.0, Speed_2), false);
    vec2 bendBy = texture(NoiseA, bendUv).xy * 2.0 - 1.0;
    float glowMask = clamp(2.0 * texture(NoiseB, bendBy * TexDist + maskUv).r, 0.0, 1.0);
    vec3 emissive = mix(EmissiveColor_1.rgb, EmissiveColor_2.rgb, glowMask)
            * CG_GLOBAL_kakarot_mpc_skill_cpl001_ryk_dragon_01_00_DR_EmissiveMulti;
#else
    vec3 emissive = fresnel * FresnelColor.rgb;
#endif
    s.emissive = max(c, vec3(0.0)) + max(emissive, vec3(0.0));

    // What the outline pass reads: the shadow side and the outline ID (an ID of 1 masks the outline out), the
    // shading model and the outline's colour slot.
#ifdef SWITCH_USEOUTLINETEXTURE
    float outlineId = min(0.98, texture(outline_texture, i.uv3).r * ceil(OutlineMask));
#else
    float outlineId = min(0.98, OutlineID * ceil(OutlineMask));
#endif
    CG_CUSTOM_DATA_OUT(vec4(dark, max(ceil(1.0 - Opacity_Threshold), outlineId)));
#if defined(CELL_SHADE)
    CG_SHADING_MODEL_OUT(10u, uint(OutlineColorType));
#elif defined(CELL_TONE_GROUND)
    CG_SHADING_MODEL_OUT(12u, uint(OutlineColorType));
#else
    CG_SHADING_MODEL_OUT(13u, uint(OutlineColorType));
#endif
}
