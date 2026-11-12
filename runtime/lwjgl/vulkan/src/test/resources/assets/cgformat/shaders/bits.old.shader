#type spatial
#pragma cg_use particle
#pragma cg_use rows
Tags { "RenderType" = "Opaque" }
Queue = "Geometry"
Properties { _Tint ("Tint", color) = (1, 1, 1, 1) }
struct v2f { vec2 uv; vec4 tint; };
Pass {
    Tags { "LightMode" = "Forward" }
    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.uv = v.texCoords[0];
        o.tint = p.color;
        gl_Position = cg_material_project(v);
    }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Tint.rgb * i.tint.rgb * (0.5 + 0.5 * i.uv.x), 1.0); }
}
