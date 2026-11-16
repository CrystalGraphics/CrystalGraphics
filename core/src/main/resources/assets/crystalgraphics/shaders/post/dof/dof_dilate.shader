// Depth of field's tiles dilated (CgDepthOfField): each tile takes a neighbour's radius where that neighbour's blur
// reaches past the tiles between them, so a near blur spreads over the sharp scene beside it and a far one gathers in
// from round its edge. Up to four tiles out: the largest radius, 32 half-size texels.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
Material { Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Tiles  ("Each tile's largest nearer (r) and farther (g) radius", sampler2D) = "black"
    _Reach  ("x how many tiles out a blur can reach, 1 to 4", vec4) = (1, 0, 0, 0)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 last = textureSize(_Tiles, 0) - 1;
    ivec2 at = ivec2(gl_FragCoord.xy);
    int reach = int(_Reach.x);
    vec2 most = texelFetch(_Tiles, at, 0).rg;
    for (int k = 0; k < 81; k++) {   // LOOP-BOUND: nine by nine tiles, four out
        ivec2 o = ivec2(k % 9, k / 9) - 4;
        int ring = max(abs(o.x), abs(o.y));
        if (ring == 0 || ring > reach) continue;
        vec2 t = texelFetch(_Tiles, clamp(at + o, ivec2(0), last), 0).rg;
        float gap = float(ring - 1) * 8.0;   // texels between the two tiles' nearest edges
        most = max(most, mix(vec2(0.0), t, greaterThan(t, vec2(gap))));
    }
    fragColor = vec4(most, 0.0, 1.0);
}
