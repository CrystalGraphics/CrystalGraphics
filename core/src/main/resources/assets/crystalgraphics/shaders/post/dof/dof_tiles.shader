// Depth of field's tiles (CgDepthOfField): each 8 by 8 block of the setup's half-size scene, its largest nearer radius
// in r and farther in g; how far the gather looks round each texel of it.
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
    _Half ("The setup's half-size scene, alpha the circle of confusion", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 last = textureSize(_Half, 0) - 1;
    ivec2 at = ivec2(gl_FragCoord.xy) * 8;
    vec2 most = vec2(0.0);
    for (int k = 0; k < 64; k++) {   // LOOP-BOUND: a tile's 64 texels
        float coc = texelFetch(_Half, min(at + ivec2(k & 7, k >> 3), last), 0).a;
        most = max(most, vec2(-coc, coc));
    }
    fragColor = vec4(most, 0.0, 1.0);
}
