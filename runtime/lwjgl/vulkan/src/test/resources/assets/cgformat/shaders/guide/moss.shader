// docs/MATERIAL_FORMAT.md § 2, with § 4.3's grass bend
#domain surface
#include "crystalgraphics:shaders/lib/noise.glsl"
Material { BlendMode Masked  Faces Both }
Keywords { MOSS ("Mossy") }
Properties {
    _Albedo ("Albedo", sampler2D) = "white"
    _Moss   ("Moss colour", color) = (0.3, 0.5, 0.2, 1)
}
struct v2f { vec2 uv0; };
float moss(vec2 uv) { return value_noise(uv * 8.0); }
void vertex(inout CgVertex v, out v2f o) {
    v.world.x += sin(CG_TIME * 2.0 + v.position.x) * 0.1 * v.uv0.y;
    o.uv0 = v.uv0;
}
void surface(in v2f i, inout CgSurface s) {
    vec4 albedo = texture(_Albedo, i.uv0);
    s.baseColor = albedo.rgb;
    s.opacityMask = albedo.a;
#ifdef MOSS
    s.baseColor = mix(s.baseColor, _Moss.rgb, moss(i.uv0));
#endif
}
