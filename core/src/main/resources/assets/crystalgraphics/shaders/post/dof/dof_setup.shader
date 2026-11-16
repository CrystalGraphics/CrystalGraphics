// Depth of field's setup (CgDepthOfField): the scene at half size, its alpha the signed circle of confusion of the
// four texels under each. Where one of them is nearer than the focus, the nearest's radius and the colour of those
// within a texel of it: the nearer layer blurs the near thing alone. Otherwise the farthest's radius and the colour of
// those within a texel of it, so a sharp edge's colour never spreads into the blur behind it.
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/post/dof/dof.glsl"
Material { SceneColorMargin 0.001  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Coc ("x the radius at infinity, y the focus distance, z and w the nearer and farther layers' largest", vec4) = (0, 1, 0, 0)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 full = textureSize(cg_SceneColor, 0) - 1;
    ivec2 at = ivec2(gl_FragCoord.xy) * 2;
    vec3 colours[4];
    float cocs[4];
    float nearest = 1.0e9, farthest = -1.0e9;
    for (int k = 0; k < 4; k++) {   // LOOP-BOUND: the four texels under one
        ivec2 q = min(at + ivec2(k & 1, k >> 1), full);
        colours[k] = texelFetch(cg_SceneColor, q, 0).rgb;
        cocs[k] = dof_coc(cg_LinearEyeDepth(texelFetch(cg_DepthBuffer, q, 0).r), _Coc);
        nearest = min(nearest, cocs[k]);
        farthest = max(farthest, cocs[k]);
    }
    float coc = nearest < -0.5 ? nearest : farthest;
    vec3 colour = vec3(0.0);
    float n = 0.0;
    for (int k = 0; k < 4; k++) {   // LOOP-BOUND: the four again
        if (abs(cocs[k] - coc) > 1.0) continue;
        colour += colours[k];
        n += 1.0;
    }
    fragColor = vec4(colour / n, coc);
}
