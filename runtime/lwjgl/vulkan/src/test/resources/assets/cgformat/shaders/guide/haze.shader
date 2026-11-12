// docs/MATERIAL_FORMAT.md § 6: a heat haze, one distortion draw
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit }
Properties { _Ripple ("Ripple", sampler2D) = "black" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    s.refraction = (texture(_Ripple, i.uv0 + CG_TIME * 0.1).rg - 0.5) * 0.02;
}
