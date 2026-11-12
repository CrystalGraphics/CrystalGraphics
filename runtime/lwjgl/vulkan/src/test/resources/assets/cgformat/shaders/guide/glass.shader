// docs/MATERIAL_FORMAT.md § 1 and § 4.1
#domain surface
Material { BlendMode Translucent }
Properties {
    _Tint ("Tint", color) = (0.6, 0.8, 1, 0.3)
    _Cracks ("Cracks", sampler2D) = "black"
    _Ripple ("Ripple", sampler2D) = "black"
    _CrackGlow ("Crack glow", color, hdr) = (1, 0.5, 0.2, 1)
}
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    float crack = texture(_Cracks, i.uv0).r;
    s.baseColor = _Tint.rgb;
    s.opacity = 0.3;
    s.refraction = (texture(_Ripple, i.uv0).rg - 0.5) * 0.01;
    s.emissive = _CrackGlow.rgb * crack * 4.0;
}
