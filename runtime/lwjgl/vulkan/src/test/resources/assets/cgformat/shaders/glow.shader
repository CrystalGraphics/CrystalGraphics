// Glow only (12.5): it writes only emissive, so it draws no colour
#domain surface
Material { BlendMode Additive  ShadingModel Unlit }
Properties {
    _Glow ("Glow", color, hdr) = (1, 1, 1, 1)
    _Strength ("Strength", float) = 1
}
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) { s.emissive = _Glow.rgb * _Strength; }
