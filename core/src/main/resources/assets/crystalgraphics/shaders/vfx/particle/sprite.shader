// A textured sprite: a frame of a flipbook sheet on a quad facing as its renderer says (lib/vfx/particle.glsl's FX_FACE_* and
// FX_ALIGN_*), lit by the world where it is and blended over what is behind: smoke, dust, leaves. A CgVfxQuads quad
// placed from its particle record (CgVfxFrame.particles). _Color tints the sheet, its alpha a strength.
//
//     CgVfxRenderers.sprite("puffs").material(PUFF).bind("_Color", TINT)      // PUFF: this shader, _Sheet a texture
//             .subImage(Map.of("size", List.of(8, 8))).alignment("custom").build()
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/particle.glsl"
Material { BlendMode AlphaComposite  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color ("Tints the sheet; its alpha a strength", color) = (1, 1, 1, 1)
    _Sheet ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.5
}

// Its two flipbook frames
struct v2f { vec4 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = vec4(v.uv0, v.uv1);
}

void surface(in v2f i, inout CgSurface s) {
    vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), cg_Particle.subUVLerp);
    float alpha = texel.a * cg_Particle.opacity * _Color.a * cg_DepthFade(_SoftDistance);
    s.baseColor = texel.rgb * _Color.rgb * cg_Particle.color.rgb * alpha;   // premultiplied
    s.opacity = alpha;
}
