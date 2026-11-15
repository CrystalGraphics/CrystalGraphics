// Kakarot's cel-toned eye (CellShade_Tone_Eye, shading model 13), forward: the eye's base pass and CC2's cel resolve
// in one program. Ports `Cpl001p1c01s1_eye00_Mt_inst`'s permutation, read from the capture (Goku's left eye, draw
// 4644; his right, draw 4647, runs the same bytecode with the right eye's parameters in those slots).
//
// The eye texture is placed by its eye's offset and scale (the game moves them at run time, as the eyes look), its
// near-white texels kept or whitened by WhiteEye, scaled by Diffuse_Power outside the mask and raised to
// 1 + 2(1 - Diffuse_Eye_Power). The shadow side is that colour shaded by AmbientDFctor; the resolve is the body's.
//
// The outline's custom data is the shadow side and an ID from the outline texture on uv3, turned about its centre
// by the eye's slider and shaped by its length; Goku's eyes write shading model 13, as the body does.
//
// Not carried yet: the sky's indirect irradiance, DBuffer decals, and the third and fourth eyes' switches.
#domain surface
#include "kk:shaders/lib/kakarot.glsl"
#include "kk:shaders/lib/cell.glsl"

Material { BlendMode Opaque  ShadingModel Unlit  ColorSpace Linear  VertexFactory Mesh, MeshParticles }

Keywords {
    CELL_TONE ("Cell tone")
    SWITCH_LEFTEYE ("Switch lefteye")
    SWITCH_RIGHTEYE ("Switch righteye")
}

Properties {
    diffuse_texture ("The eye", sampler2D) = "white"
    TONE ("The renderer's tone ramp: 64 rows, red hard, green soft", sampler2D) = "white"
    eye_l_u_offset ("The left eye's texture offset, u", float) = 0
    eye_l_v_offset ("Its offset, v", float) = 0
    eye_l_u_scale ("Its scale, u", float) = 1
    eye_l_v_scale ("Its scale, v", float) = 1
    eye_r_u_offset ("The right eye's texture offset, u", float) = 0
    eye_r_v_offset ("Its offset, v", float) = 0
    eye_r_u_scale ("Its scale, u", float) = 1
    eye_r_v_scale ("Its scale, v", float) = 1
    LightDirection_Vertical ("The material's own light axis, x", float) = 0
    LightDirection_Side ("The material's own light axis, z", float) = 1
    LightDirectionPower ("Turns the normal about that axis, in turns", float) = 0
    Diffuse_Power ("Scales the eye outside the mask", float) = 1
    Diffuse_Eye_Power ("Scales the eye inside the mask, and lowers its exponent", float) = 1
    DiffuseMaskPower ("Texels whose r*g*b reach this are the mask", float) = 0.5
    WhiteScale ("Scales the texture coordinate the white test reads", float) = 1.1
    WhiteEye ("How far the whites are forced white", float) = 0
    AmbientDFctor ("The shadow side's shade", color) = (0.5, 0.5, 0.5, 1)
    AmbientPower ("How much of that shade", float) = 0.8
    ShadowPower ("The shadow bias", float) = 0.5
    CastShadowPower ("The cast shadow's weight", float) = 0.6
    CastShadowParamPower ("The cast shadow's response", float) = 1
    TonePattern ("The tone ramp's row, 0 to 63", float) = 0
    AffectedLight ("How much the stage light colours it, 0..1", float) = 0
    StageShadowTint ("The renderer's shadow-side tint, as the capture's", color) = (1.16708, 2.31032, 4, 1)
    outline_texture ("Where the outline draws, in red, on uv3", sampler2D) = "white"
    Outline_Slider_L ("Turns the left eye's outline texture, in sixths of a radian", float) = 0
    Outline_Slider_R ("Turns the right eye's", float) = 0
    Outline_Lenth_L ("Lengthens the left eye's outline", float) = 0.5
    Outline_Lenth_R ("Lengthens the right eye's", float) = 0.5
    Opacity_Threshold ("Below 1 masks the outline out", float) = 1
    OutlineColorType ("The outline pass's colour slot, 0 to 3", float) = 0
}

struct v2f { vec2 uv0; vec2 uv3; vec3 normal; };


// 1 where a texel is white, to the base pass's 1e-5
float kk_white(vec3 c) { return clamp(100000.0 * (1.0 - length(c - vec3(1.0))), 0.0, 1.0); }

void vertex(inout CgVertex v, out v2f o) {
    o.uv0 = v.uv0;
    o.uv3 = v.uv3;
    o.normal = v.normal;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 n = normalize(i.normal);
    // The eye takes the turned normal itself, not the body's halfway bend.
    vec3 turned = kk_cell_turned(n, mat3(CG_OBJECT_TO_WORLD), LightDirection_Vertical, LightDirection_Side, LightDirectionPower);

#ifdef SWITCH_RIGHTEYE
    vec4 place = vec4(eye_r_u_offset, eye_r_v_offset, eye_r_u_scale, eye_r_v_scale);
#else
    vec4 place = vec4(eye_l_u_offset, eye_l_v_offset, eye_l_u_scale, eye_l_v_scale);
#endif
    vec2 uv = vec2(place.z * (place.x + i.uv0.x), -place.w * (1.0 + place.y - i.uv0.y));
    vec3 eye = texture(diffuse_texture, uv).rgb;
    float product = eye.r * eye.g * eye.b;
    float outside = abs(product - DiffuseMaskPower) > 1e-5 && product < DiffuseMaskPower ? 1.0 : 0.0;
    float power = mix(Diffuse_Power, min(Diffuse_Power, Diffuse_Eye_Power), outside);
    vec3 whitened = max(kk_white(eye) * eye, vec3(1.0 - kk_white(texture(diffuse_texture, WhiteScale * uv).rgb)));
    vec3 colour = mix(eye, whitened, WhiteEye);

    float exponent = 2.0 * (1.0 - Diffuse_Eye_Power) + 1.0;
    vec3 lit = min(vec3(1.0), pow(max(colour * power, vec3(0.0)), vec3(exponent)));
    vec3 dark = clamp((AmbientPower * (AmbientDFctor.rgb - 1.0) + 1.0) * colour, 0.0, 1.0);
    float castWeight = clamp(CastShadowParamPower * (0.7 * CastShadowPower - CastShadowPower) + CastShadowPower, 0.0, 1.0);

    KkCellSurface surface = KkCellSurface(lit, dark, vec3(1.0), 0.0, 0.0, clamp(ShadowPower, 0.0, 1.0), castWeight);
    vec3 c = kk_cell_resolve(surface, turned, CG_SUN_DIRECTION, TONE, TonePattern, AffectedLight, CG_GLOBAL_directional_light_colour.rgb, StageShadowTint.rgb);
    s.emissive = max(c, vec3(0.0));

    // What the outline pass reads: the shadow side, the outline ID (an ID of 1 masks the outline out), the
    // shading model and the outline's colour slot.
#ifdef SWITCH_RIGHTEYE
    float slider = 6.0 * Outline_Slider_R, length_ = Outline_Lenth_R;
#else
    float slider = 6.0 * Outline_Slider_L, length_ = Outline_Lenth_L;
#endif
    vec2 d = i.uv3 - 0.5;
    vec2 turnedUv = vec2(cos(slider) * d.x - sin(slider) * d.y, sin(slider) * d.x + cos(slider) * d.y) + 0.5;
    float edge = pow(max(0.0, texture(outline_texture, turnedUv).r), 1.0 - length_);
    float outlineId = clamp(clamp(3.0 * edge - 1.0, 0.0, 1.0), 0.12, 0.98);
    CG_CUSTOM_DATA_OUT(vec4(dark, max(ceil(1.0 - Opacity_Threshold), outlineId)));
    CG_SHADING_MODEL_OUT(13u, uint(OutlineColorType));
}
