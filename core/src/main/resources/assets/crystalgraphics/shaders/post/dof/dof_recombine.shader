// Depth of field's recombine (CgDepthOfField), at full size: the sharp scene where it is in focus, the farther layer
// where its own blur passes a texel, and the nearer layer over both by how much of the texel it covers.
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
    _Far  ("The farther layer, half size: rgb its blur, a 1 where any reached", sampler2D) = "black"
    _Near ("The nearer layer, half size: rgb its blur, a how much it covers", sampler2D) = "black"
    _Coc  ("x the radius at infinity, y the focus distance, z and w the nearer and farther layers' largest", vec4) = (0, 1, 0, 0)
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec2 uv = (vec2(at) + 0.5) / vec2(textureSize(cg_SceneColor, 0));
    vec3 colour = texelFetch(cg_SceneColor, at, 0).rgb;
    float coc = dof_coc(cg_LinearEyeDepth(texelFetch(cg_DepthBuffer, at, 0).r), _Coc);
    vec4 far = textureLod(_Far, uv, 0.0);
    // A half-size texel's blur shows once it is past a full-size texel
    colour = mix(colour, far.rgb, far.a * smoothstep(0.25, 1.0, coc));
    vec4 near = textureLod(_Near, uv, 0.0);
    fragColor = vec4(mix(colour, near.rgb, near.a), 1.0);
}
