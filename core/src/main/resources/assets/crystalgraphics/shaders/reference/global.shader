// The reference global program (docs/SHADERS.md, docs/MATERIAL_FORMAT.md § 9): raw GLSL for one of the renderer's own
// passes, here a full-screen copy. Write a surface, post or ui material wherever one fits: a global program gets no
// lighting, fog, glow or passes worked out for it, only what it writes.
#domain global
RenderState { Blend OFF  DepthTest ALWAYS  DepthWrite OFF  Cull OFF }

Properties {
    _Source ("What it copies", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = vec2(CG_VERTEX_ID & 1, CG_VERTEX_ID >> 1) * 2.0;   // one triangle covering the screen
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 color) {
    color = texture(_Source, i.uv);
}
