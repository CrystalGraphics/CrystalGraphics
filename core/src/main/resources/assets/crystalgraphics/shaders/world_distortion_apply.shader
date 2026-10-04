// The world renderer's distortion apply: each pixel takes the scene's colour from where the Distortion passes' summed
// offset points (_Distortion: xy in UV units, z the chromatic split), once, after the transparent pass. UVs mirror at
// the screen's borders (Quantum Break's answer to clamping's smear); a sample nearer than the pixel is refused, since
// it would pull the foreground into the bent region. CgWorldRenderer draws it; nothing else should.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "SceneColorMargin" = "1" }
Queue = "Overlay"

Properties {
    _Distortion ("Offsets", sampler2D) = "black"
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // How much nearer than the pixel a sample may be before it is foreground: a share of the pixel's eye depth.
    const float CG_DISTORTION_LEAK = 0.05;

    vec2 cg_mirror(vec2 uv) {
        return 1.0 - abs(1.0 - abs(uv));
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 d = texelFetch(_Distortion, ivec2(gl_FragCoord.xy), 0);
        if (d.x == 0.0 && d.y == 0.0) discard;
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        vec2 at = cg_mirror(uv + d.xy);
        float own = CG_SCENE_EYE_DEPTH(uv);
        if (CG_SCENE_EYE_DEPTH(at) < own * (1.0 - CG_DISTORTION_LEAK)) discard;
        vec4 g = CG_SCENE_COLOR(at);
        fragColor = vec4(CG_SCENE_COLOR(cg_mirror(uv + d.xy * (1.0 + d.z))).r, g.g,
                         CG_SCENE_COLOR(cg_mirror(uv + d.xy * (1.0 - d.z))).b, g.a);
    }
}
