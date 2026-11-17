// Depth of field's setup (CgDepthOfField): the scene at half size, its alpha the signed circle of confusion of the
// four texels under each. Where one of them is nearer than the focus, the nearest's radius and the colour of those
// within a texel of it: the nearer layer blurs the near thing alone. Otherwise the farthest's radius and the colour of
// those within a texel of it, so a sharp edge's colour never spreads into the blur behind it. With _Carry.x 1 it reads
// _Carried in the colour's place and writes it alone (CgDepthOfField.carry): what rides beside the colour, chosen the same.
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
    _Carry ("x 1: _Carried in the colour's place", vec4) = (0, 0, 0, 0)
    _Carried ("What rides beside the colour, at full size (the look weights)", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 full = textureSize(cg_SceneColor, 0) - 1;
    ivec2 at = ivec2(gl_FragCoord.xy) * 2;
    bool carry = _Carry.x > 0.5;
    vec4 values[4];
    float cocs[4];
    float nearest = 1.0e9, farthest = -1.0e9;
    for (int k = 0; k < 4; k++) {   // LOOP-BOUND: the four texels under one
        ivec2 q = min(at + ivec2(k & 1, k >> 1), full);
        values[k] = carry ? texelFetch(_Carried, q, 0) : vec4(texelFetch(cg_SceneColor, q, 0).rgb, 0.0);
        cocs[k] = dof_coc(cg_LinearEyeDepth(texelFetch(cg_DepthBuffer, q, 0).r), _Coc);
        nearest = min(nearest, cocs[k]);
        farthest = max(farthest, cocs[k]);
    }
    float coc = nearest < -0.5 ? nearest : farthest;
    vec4 value = vec4(0.0);
    float n = 0.0;
    for (int k = 0; k < 4; k++) {   // LOOP-BOUND: the four again
        if (abs(cocs[k] - coc) > 1.0) continue;
        value += values[k];
        n += 1.0;
    }
    fragColor = carry ? value / n : vec4(value.rgb / n, coc);
}
