// An unlit sprite: a frame of a flipbook sheet, or spark.shader's round glowing disc, on a quad its renderer places and
// faces, drawn as it is painted whatever the light: a hit spark, a swirl, a painted streak. The particle's colour tints
// it by TINT and its alpha is a strength; _Color tints it too, its alpha a strength. It covers what is behind by the
// sheet's alpha, or with _Additive 1 adds its light. It glows by the particle's dynamic parameter 0's x (Niagara's
// Dynamic Material Parameter), which is what blooms.
//
//     CgVfxRenderers.sprite("swirls").material(SWIRL).build()    // SWIRL: this shader, _Sheet a texture, TINT_SOURCE
//
//   TINT (none): the sheet times the particle's colour. SCREEN: the two screened, so the sheet's whites stay white.
//   SOURCE: the sheet's own colour. SHAPE: the sheet's alpha alone, in the particle's colour.
//   DISC: the disc, its middle _Hot, in place of the sheet; with _Additive 1, a spark.
//   _Aside sits it a random share of its size aside on each axis: a shadow copy, drawn black (_Color) under another.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Sheet    ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _Color    ("Times what it draws; its alpha a strength", color) = (1, 1, 1, 1)
    _Hot      ("DISC: the colour at its middle", color) = (1, 1, 1, 1)
    _Additive ("1 adds its light over what is behind, 0 covers it", float) = 0
    _Aside    ("How far aside it sits, a share of its size, a random way on each axis", float) = 0
}

Keywords {
    TINT ("How the particle's colour tints the sheet") = SCREEN | SOURCE | SHAPE
    DISC ("The spark's disc in place of the sheet")
}

// Its two flipbook frames; where on the quad, -1 to 1
struct v2f { vec4 uv; vec2 corner; };

void vertex(inout CgVertex v, out v2f o) {
    if (_Aside > 0.0) {
        float r = cg_Particle.random;
        vec2 aside = (vec2(r, fract(r * 7.31)) * 2.0 - 1.0) * _Aside * cg_Particle.size;
        v.world += cg_SpriteAxes[0] * aside.x + cg_SpriteAxes[1] * aside.y;
    }
    o.uv = vec4(v.uv0, v.uv1);
    o.corner = v.uv2 * 2.0 - 1.0;   // unflipped, the whole sprite's
}

void surface(in v2f i, inout CgSurface s) {
    vec3 tint = cg_Particle.color.rgb * _Color.rgb;
    vec3 colour;
    float cover;
#ifdef DISC
    // spark.shader's: a round disc, hot in the middle, in a wide soft bloom.
    float r = length(i.corner);
    float aa = fwidth(r) + 1.0e-3;
    float disc = 1.0 - smoothstep(0.26 - aa, 0.26 + aa, r);
    float bloom = exp(-r * r * 9.0) * (1.0 - smoothstep(0.7, 1.0, r));
    colour = mix(tint, _Hot.rgb, exp(-r * r * 60.0)) * disc * 1.8 + tint * bloom * 0.9;
    cover = 0.0;
#else
    vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), cg_Particle.subUVLerp);
#if defined(TINT_SHAPE)
    vec3 tinted = tint;
#elif defined(TINT_SOURCE)
    vec3 tinted = texel.rgb * _Color.rgb;
#elif defined(TINT_SCREEN)
    vec3 tinted = (tint + texel.rgb * (1.0 - tint));
#else
    vec3 tinted = texel.rgb * tint;
#endif
    colour = tinted * texel.a;
    cover = _Additive > 0.5 ? 0.0 : texel.a;
#endif
    float strength = cg_Particle.color.a * _Color.a;
    s.baseColor = colour * strength;   // premultiplied
    s.opacity = cover * strength;
    s.emissive = s.baseColor * cg_dynamic_parameter(cg_Particle, 0, vec4(0.0)).x;
}
