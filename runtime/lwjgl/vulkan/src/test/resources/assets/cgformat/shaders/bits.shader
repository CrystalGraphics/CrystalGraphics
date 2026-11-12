// Opaque mesh particles tinted by their particle.
#domain surface
Material { VertexFactory MeshParticles  PerDrawValues On }
Properties { _Tint ("Tint", color) = (1, 1, 1, 1) }
struct v2f { vec2 uv; vec4 tint; };
void vertex(inout CgVertex v, out v2f o) {
    o.uv = v.uv0;
    o.tint = cg_Particle.color;
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = _Tint.rgb * i.tint.rgb * (0.5 + 0.5 * i.uv.x); }
