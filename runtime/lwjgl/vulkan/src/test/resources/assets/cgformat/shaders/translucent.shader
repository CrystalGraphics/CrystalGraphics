// See-through and unlit, glowing toward one edge
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit }
Properties {
    _Tint ("Tint", color) = (1, 1, 1, 0.5)
    _Glow ("Glow", color, hdr) = (2, 1, 0.5, 1)
}
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    s.emissive = _Tint.rgb + _Glow.rgb * i.uv0.x;
    s.opacity = _Tint.a;
}
