// One direction of a Gaussian bloom layer, Unreal's weighted sample sum (FilterPixelShader.usf): up to 32 bilinear taps
// of the level, each its offset in uv and its weight times the layer's tint. ADDITIVE adds the layer before it, the
// next smaller one, sampled bilinearly. One triangle over the level. CgGaussianBloom draws it.
#type none
#pragma cg_feature ADDITIVE
#pragma cg_permutation ADDITIVE

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Target" }
Queue = "Overlay"

Properties {
    _Source   ("The level blurred", sampler2D) = "black"
    _Additive ("The layer before, added", sampler2D) = "black"
    _Taps     ("Each tap: its offset in uv in xy, its weight in z", vec4[32]) = (0, 0, 0, 0)
    _Tint     ("Each tap's weight times this, rgb", vec4) = (1, 1, 1, 1)
    _Count    ("Taps used", float) = 0
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
        vec3 sum = vec3(0.0);
        int count = int(_Count + 0.5);
        for (int k = 0; k < 32; k++) {
            if (k >= count) break;
            vec4 tap = _Taps(k);
            sum += textureLod(_Source, i.uv + tap.xy, 0.0).rgb * tap.z;
        }
        sum *= _Tint.rgb;
#ifdef ADDITIVE
        sum += textureLod(_Additive, i.uv, 0.0).rgb;
#endif
        fragColor = vec4(sum, 1.0);
    }
}
