// A side input of the scene (bloom's emission, an effect's mask) bent by the firing's distortion, as the scene was:
// each texel takes _Source from where the offsets at it point, summed over the _FieldCount layers of _Fields in use.
// CgPostDistortion draws it.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Source  ("What to bend", sampler2D) = "black"
    _Fields  ("Offsets, a layer a slot", sampler2DArray) = "black"
    _FieldCount ("Layers in use", int) = 1
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

    vec2 cg_mirror(vec2 uv) {
        return 1.0 - abs(1.0 - abs(uv));
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 offset = vec2(0.0);
        for (int k = 0; k < _FieldCount; k++) offset += texture(_Fields, vec3(i.uv, float(k))).xy;
        fragColor = texture(_Source, cg_mirror(i.uv + offset));
    }
}
