// cg_Particle read by surface(): only the colour and the age reach the fragment.
#domain surface
Material { BlendMode Translucent  VertexFactory Sprites }
Properties { _Puff ("Puff", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    s.baseColor = cg_Particle.color.rgb * texture(_Puff, i.uv0).rgb;
    s.opacity = cg_Particle.color.a * (1.0 - cg_Particle.relativeTime);
}
