// The post stack's debug view: one texture bloom works from (the emission, or a level of the chain) over the whole
// frame, as it is, clamped to the target. CgPostDebug draws it (-Dcrystalgraphics.post.debug); nothing else should.
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Source ("The texture shown", sampler2D) = "black"
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

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(textureLod(_Source, i.uv, 0.0).rgb, 1.0);
    }
}
