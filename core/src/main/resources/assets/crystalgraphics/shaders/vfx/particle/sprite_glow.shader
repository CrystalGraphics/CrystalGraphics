// A glowing textured sprite: sprite.shader's flipbook frame and facing, added and unlit, as bright as the particle is
// hot: fire, magic, energy. A CgVfxQuads quad placed from its particle record (CgVfxFrame.particles, QUADS). _Color
// tints the cool sheet, its alpha a strength; _Hot the hot. Its light blooms, as it adds.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/particle.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color ("Tints the sheet where it is cool; its alpha a strength", color) = (1, 0.6, 0.2, 1)
    _Hot ("Tints the sheet where it is hot", color) = (1, 1, 0.9, 1)
    _Sheet ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.25
}

// Its two flipbook frames
struct v2f { vec4 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = vec4(v.uv0, v.uv1);
}

void surface(in v2f i, inout CgSurface s) {
    vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), cg_Particle.subUVLerp);
    float heat = cg_Particle.heat;
    vec3 tint = mix(_Color.rgb, _Hot.rgb, heat) * cg_Particle.color.rgb;
    float strength = texel.a * cg_Particle.opacity * _Color.a * (0.35 + 0.65 * heat) * cg_DepthFade(_SoftDistance);
    s.baseColor = texel.rgb * tint * strength;
    s.emissive = s.baseColor;
}
