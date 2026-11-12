// Sparks from a kernel's buffer: procedural vertices (12.9)
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory Procedural }
struct Spark { vec4 positionLife; vec4 velocitySeed; };
Buffers {
    SPARKS ("Sparks", Spark, readonly)
    LIVE ("Live", uint, readonly)
}
struct v2f { float life; };
void vertex(inout CgVertex v, out v2f o) {
    Spark p = SPARKS(LIVE(CG_VERTEX_ID >> 2));
    vec3 local = p.positionLife.xyz + vec3(CG_VERTEX_CORNER - 0.5, 0.0) * 0.1;
    v.world = (CG_OBJECT_TO_WORLD * vec4(local, 1.0)).xyz;
    o.life = p.positionLife.w;
}
void surface(in v2f i, inout CgSurface s) { s.emissive = vec3(1.0, 0.6, 0.2) * clamp(i.life, 0.0, 1.0); }
