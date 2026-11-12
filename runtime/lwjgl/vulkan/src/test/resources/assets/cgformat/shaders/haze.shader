// A heat haze (12.6): it writes only refraction, so it draws in the distortion pass alone
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Faces Both  DepthTest Off  Fog Off }
Properties {
    _Strength ("Bend at full intensity, share of the screen", float) = 0.04
    _Noise ("Noise", sampler2D) = "white"
}
struct v2f { vec3 world; };
void surface(in v2f i, inout CgSurface s) {
    vec2 n = texture(_Noise, i.world.xz * 0.3 + vec2(0.0, CG_TIME * 0.5)).rg - 0.5;
    s.refraction = n * _Strength;
    s.refractionSplit = 0.3;
}
