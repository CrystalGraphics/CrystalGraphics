// Depth of field's recombine (CgDepthOfField), at full size: the sharp scene where it is in focus, the farther layer
// where its own blur passes a texel, and the nearer layer over both by how much of the texel it covers. With _Carry.x 1
// the carried texture's sharp and blurred values mixed by the same covers, written alone (CgDepthOfField.carry).
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
    _Carry ("x 1: _Carried mixed by the colour's covers", vec4) = (0, 0, 0, 0)
    _Carried ("What rides beside the colour, at full size", sampler2D) = "black"
    _FarCarry ("The farther layer's carried values", sampler2D) = "black"
    _NearCarry ("The nearer layer's carried values", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec2 uv = (vec2(at) + 0.5) / vec2(textureSize(cg_SceneColor, 0));
    bool carry = _Carry.x > 0.5;
    vec4 value = carry ? texelFetch(_Carried, at, 0) : vec4(texelFetch(cg_SceneColor, at, 0).rgb, 0.0);
    float coc = dof_coc(cg_LinearEyeDepth(texelFetch(cg_DepthBuffer, at, 0).r), _Coc);
    vec4 far = textureLod(_Far, uv, 0.0), near = textureLod(_Near, uv, 0.0);
    // A half-size texel's blur shows once it is past a full-size texel
    value = mix(value, carry ? textureLod(_FarCarry, uv, 0.0) : vec4(far.rgb, 0.0), far.a * smoothstep(0.25, 1.0, coc));
    value = mix(value, carry ? textureLod(_NearCarry, uv, 0.0) : vec4(near.rgb, 0.0), near.a);
    fragColor = carry ? value : vec4(value.rgb, 1.0);
}
