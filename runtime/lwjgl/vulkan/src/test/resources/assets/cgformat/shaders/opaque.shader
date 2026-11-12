// An opaque mesh, lit: shader-format.md 12.1
#domain surface
Properties { _Albedo ("Albedo", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) { s.baseColor = texture(_Albedo, i.uv0).rgb; }
