// A fixed load of arithmetic and texture reads over the screen, timed once at a fresh install to choose the quality
// tier (CgQualityDetect): Unreal's synth benchmark, in one pass. Its cost is the measure: changing it re-scales every
// index CgQualityDetect.REFERENCE_NS was calibrated for.
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Noise ("Noise", sampler2D) = "white"
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
        vec2 uv = i.uv;
        vec4 sum = vec4(0.0);
        for (int k = 0; k < 32; k++) {
            vec4 t = texture(_Noise, uv);
            sum += sin(t * 3.1 + sum.yzwx) * exp2(-abs(sum.zwxy));
            uv = fract(uv * 1.7 + sum.xy * 0.01 + 0.013);
        }
        fragColor = sum;
    }
}
