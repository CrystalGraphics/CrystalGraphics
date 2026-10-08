// The post stack's debug view: one texture bloom works from (the emission, or a level of the chain) over the whole
// frame, as it is, clamped to the target; with HEAT, the overdraw count through a heat ramp. The distortion view is
// debug_distortion.shader. CgPostDebug draws it (-Dcrystalgraphics.post.debug); nothing else should.
#type none

#pragma cg_feature HEAT

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
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
#ifdef HEAT
        // Black for none, then blue, cyan, green, yellow, red at 1, 2, 4, 8, 16 fragments, white from 32.
        float t = clamp(log2(textureLod(_Source, i.uv, 0.0).r + 1.0) / 5.0, 0.0, 1.0) * 6.0;
        vec3 ramp[7] = vec3[7](vec3(0.0), vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 1.0), vec3(0.0, 1.0, 0.0),
                               vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(1.0));
        int k = min(int(t), 5);
        fragColor = vec4(mix(ramp[k], ramp[k + 1], t - float(k)), 1.0);
#else
        fragColor = vec4(textureLod(_Source, i.uv, 0.0).rgb, 1.0);
#endif
    }
}
