// Leaves: masked, both faces (12.2)
#domain surface
Material { BlendMode Masked  Faces Both }
Properties { _Leaves ("Leaves", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    vec4 t = texture(_Leaves, i.uv0);
    s.baseColor = t.rgb;
    s.opacityMask = t.a;
}
